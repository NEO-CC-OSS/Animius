package com.lanlinju.animius.data.repository

import com.lanlinju.animius.data.remote.api.AnimeApi
import com.lanlinju.animius.data.remote.dto.AnimeDetailBean
import com.lanlinju.animius.domain.matching.EpisodeNumber
import com.lanlinju.animius.domain.matching.TitleMatcher
import com.lanlinju.animius.domain.model.AnimeDetail
import com.lanlinju.animius.domain.model.Episode
import com.lanlinju.animius.util.SourceMode
import com.lanlinju.animius.util.log
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 解析到的源：详情地址、站点标题、完整剧集列表，以及已取到的详情（避免详情页重复请求） */
data class ResolvedSource(
    val mode: SourceMode,
    val detailUrl: String,
    val siteTitle: String,
    val episodes: List<Episode>,
    val detail: AnimeDetail,
)

/** 换源后的一集：新源的剧集列表 + 对应集号 + 已解析出的视频地址 */
data class ResolvedEpisode(
    val mode: SourceMode,
    val detailUrl: String,
    val siteTitle: String,
    val episodes: List<Episode>,
    val episodeIndex: Int,
    val episodeUrl: String,
    val episodeName: String,
    val videoUrl: String,
    val headers: Map<String, String>,
)

data class SourceAttemptFailure(val mode: SourceMode, val reason: String)

data class ResolveProgress(val index: Int, val total: Int, val mode: SourceMode)

sealed interface SourceResolveResult {
    data class Success(val source: ResolvedSource, val fromCache: Boolean) : SourceResolveResult
    data class Failure(val attempts: List<SourceAttemptFailure>) : SourceResolveResult
}

sealed interface EpisodeResolveResult {
    data class Success(val episode: ResolvedEpisode) : EpisodeResolveResult
    data class Failure(val attempts: List<SourceAttemptFailure>) : EpisodeResolveResult
}

/**
 * 「元数据标题 → 可用源」解析。
 *
 * 候选顺序：当前源优先（尊重用户选择），其余按固定顺序兜底；已知长期打不开的源排最后。
 * 每个源有独立时间预算，整体有总预算，避免一个坏源拖住整次点播。
 * 匹配规则见 [TitleMatcher]：宁可找不到源，也不能进错番。
 */
@Singleton
class SourceResolver @Inject constructor(
    private val animeApi: AnimeApi,
    private val matchStore: SourceMatchStore,
) {

    companion object {
        /** 单个源的搜索 + 详情预算 */
        const val PER_SOURCE_TIMEOUT_MS = 12_000L

        /** 一次解析（最多 6 个源）的总预算 */
        const val OVERALL_TIMEOUT_MS = 45_000L

        /** 固定兜底顺序：稳定源在前，已知长期故障的源放最后 */
        private val FALLBACK_ORDER = listOf(
            SourceMode.Skr,
            SourceMode.Silisili,
            SourceMode.Agedm,
            SourceMode.Jzacg,
            SourceMode.Girigiri,
            SourceMode.Gugufan,
        )
    }

    fun cacheKey(metadataId: Long, title: String): String =
        if (metadataId > 0) "id:$metadataId" else "title:${TitleMatcher.normalize(title)}"

    fun candidates(currentMode: SourceMode, exclude: Set<SourceMode> = emptySet()): List<SourceMode> =
        (listOf(currentMode) + FALLBACK_ORDER).distinct().filterNot { it in exclude }

    /**
     * 解析一部番在哪个源可看。
     *
     * @param title 中文标题（元数据 `name_cn`，为空时用原名）
     * @param originalTitle 原名（中文搜索无结果时的兜底查询）
     * @param metadataId Bangumi id，用于缓存键
     * @param currentMode 用户当前选择的源，作为第一候选
     */
    suspend fun resolve(
        title: String,
        originalTitle: String = "",
        metadataId: Long = 0,
        currentMode: SourceMode,
        exclude: Set<SourceMode> = emptySet(),
        now: Instant = Instant.now(),
        deadlineMillis: Long = System.currentTimeMillis() + OVERALL_TIMEOUT_MS,
        onProgress: (ResolveProgress) -> Unit = {},
    ): SourceResolveResult = withContext(Dispatchers.IO) {
        val queries = searchTitles(title, originalTitle)
        if (queries.isEmpty()) {
            return@withContext SourceResolveResult.Failure(
                listOf(SourceAttemptFailure(currentMode, "标题为空"))
            )
        }

        val key = cacheKey(metadataId, title)
        val attempts = mutableListOf<SourceAttemptFailure>()

        // 1) 先试 7 天内的解析缓存：只跳过搜索，详情仍要重新取（集数会更新）
        val cached = matchStore.get(key, now)
        if (cached != null && cached.mode !in exclude) {
            val fromCache = try {
                withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) { loadCached(cached) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (fromCache != null) {
                "SOURCE resolve cache=hit key=$key mode=${cached.mode} title=${cached.siteTitle}"
                    .log("BehaviorTrace")
                return@withContext SourceResolveResult.Success(fromCache, fromCache = true)
            }
            matchStore.remove(key)
            attempts += SourceAttemptFailure(cached.mode, "缓存详情失效")
        }

        // 2) 逐源搜索；第一个可信匹配且能拿到剧集的源胜出
        val modes = candidates(currentMode, exclude)
        for ((index, mode) in modes.withIndex()) {
            onProgress(ResolveProgress(index + 1, modes.size, mode))

            val remaining = deadlineMillis - System.currentTimeMillis()
            if (remaining <= 0) {
                attempts += SourceAttemptFailure(mode, "总预算耗尽")
                break
            }

            val source = try {
                withTimeoutOrNull(minOf(PER_SOURCE_TIMEOUT_MS, remaining)) {
                    findSource(mode, queries)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempts += SourceAttemptFailure(mode, e.message ?: e.javaClass.simpleName)
                null
            }

            if (source == null) {
                if (attempts.none { it.mode == mode }) {
                    attempts += SourceAttemptFailure(mode, "超时")
                }
                continue
            }

            matchStore.put(
                key,
                SourceMatch(source.mode, source.detailUrl, source.siteTitle, now),
            )
            "SOURCE resolve ok key=$key mode=${source.mode} title=${source.siteTitle} " +
                "episodes=${source.episodes.size}".log("BehaviorTrace")
            return@withContext SourceResolveResult.Success(source, fromCache = false)
        }

        "SOURCE resolve failed key=$key attempts=" +
            attempts.joinToString { "${it.mode}:${it.reason}" }.log("BehaviorTrace")
        SourceResolveResult.Failure(attempts)
    }

    /**
     * 取不到播放地址时，按集号在下一个源里找同一集。
     *
     * @param episodeNumber 已归一化的集号（[EpisodeNumber.canonical]）；`movie` 表示剧场版
     * @param exclude 已失败的源（通常包含当前源）
     */
    suspend fun resolveEpisode(
        title: String,
        originalTitle: String = "",
        episodeNumber: String,
        currentMode: SourceMode,
        exclude: Set<SourceMode> = setOf(currentMode),
        deadlineMillis: Long = System.currentTimeMillis() + OVERALL_TIMEOUT_MS,
        onProgress: (ResolveProgress) -> Unit = {},
    ): EpisodeResolveResult = withContext(Dispatchers.IO) {
        val queries = searchTitles(title, originalTitle)
        if (queries.isEmpty()) {
            return@withContext EpisodeResolveResult.Failure(
                listOf(SourceAttemptFailure(currentMode, "标题为空"))
            )
        }

        val target = EpisodeNumber.canonical(episodeNumber)
        val attempts = mutableListOf<SourceAttemptFailure>()
        val modes = candidates(currentMode, exclude)

        for ((index, mode) in modes.withIndex()) {
            onProgress(ResolveProgress(index + 1, modes.size, mode))

            val remaining = deadlineMillis - System.currentTimeMillis()
            if (remaining <= 0) {
                attempts += SourceAttemptFailure(mode, "总预算耗尽")
                break
            }

            val episode = try {
                withTimeoutOrNull(minOf(PER_SOURCE_TIMEOUT_MS, remaining)) {
                    findEpisode(mode, queries, target)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempts += SourceAttemptFailure(mode, e.message ?: e.javaClass.simpleName)
                null
            }

            if (episode == null) {
                if (attempts.none { it.mode == mode }) {
                    attempts += SourceAttemptFailure(mode, "超时")
                }
                continue
            }

            "SOURCE switch from=$currentMode to=${episode.mode} ep=$target".log("BehaviorTrace")
            return@withContext EpisodeResolveResult.Success(episode)
        }

        "SOURCE switch failed from=$currentMode ep=$target attempts=" +
            attempts.joinToString { "${it.mode}:${it.reason}" }.log("BehaviorTrace")
        EpisodeResolveResult.Failure(attempts)
    }

    private suspend fun findSource(mode: SourceMode, queries: List<String>): ResolvedSource {
        var lastReason = "没有匹配条目"
        for (query in queries) {
            val results = animeApi.getSearchData(query, 1, mode)
            val matchIndex = TitleMatcher.pickBest(query, results.map { it.title })
            if (matchIndex == null) {
                lastReason = "搜索无匹配"
                continue
            }
            val match = results[matchIndex]
            val bean = animeApi.getAnimeDetail(match.url, mode)
            val detail = toResolvedDetail(bean)
            if (detail.episodes.isEmpty()) {
                lastReason = "详情无剧集"
                continue
            }
            return ResolvedSource(mode, match.url, detail.title, detail.episodes, detail)
        }
        throw NoMatchingSourceException(lastReason)
    }

    private suspend fun findEpisode(
        mode: SourceMode,
        queries: List<String>,
        target: String,
    ): ResolvedEpisode {
        var lastReason = "没有匹配条目"
        for (query in queries) {
            val results = animeApi.getSearchData(query, 1, mode)
            val matchIndex = TitleMatcher.pickBest(query, results.map { it.title })
            if (matchIndex == null) {
                lastReason = "搜索无匹配"
                continue
            }
            val match = results[matchIndex]
            val detail = animeApi.getAnimeDetail(match.url, mode)
            val episodes = primaryEpisodes(detail).map { it.toEpisode() }
            if (episodes.isEmpty()) {
                lastReason = "详情无剧集"
                continue
            }
            val episodeIndex = episodes.indexOfFirst { episode ->
                EpisodeNumber.normalize(episode.name)
                    ?.let(EpisodeNumber::canonical) == target
            }
            if (episodeIndex < 0) {
                lastReason = "没有第${target}集"
                continue
            }
            val episode = episodes[episodeIndex]
            val video = animeApi.getVideoData(episode.url, mode)
            return ResolvedEpisode(
                mode = mode,
                detailUrl = match.url,
                siteTitle = detail.title,
                episodes = episodes,
                episodeIndex = episodeIndex,
                episodeUrl = episode.url,
                episodeName = episode.name,
                videoUrl = video.videoUrl,
                headers = video.headers,
            )
        }
        throw NoMatchingSourceException(lastReason)
    }

    private suspend fun loadCached(match: SourceMatch): ResolvedSource? {
        val bean = animeApi.getAnimeDetail(match.detailUrl, match.mode)
        val detail = toResolvedDetail(bean)
        if (detail.episodes.isEmpty()) return null
        return ResolvedSource(
            mode = match.mode,
            detailUrl = match.detailUrl,
            siteTitle = detail.title.ifBlank { match.siteTitle },
            episodes = detail.episodes,
            detail = detail,
        )
    }

    /** 多线路源的默认线路：`episodes` 非空时用它，否则取第 0 条线路，再退到第一条非空线路 */
    private fun primaryEpisodes(detail: AnimeDetailBean) =
        detail.episodes.ifEmpty {
            detail.channels[0].orEmpty().ifEmpty {
                detail.channels.values.firstOrNull { it.isNotEmpty() }.orEmpty()
            }
        }

    /**
     * 转成详情模型；若 channel 0 为空但其他线路有剧集，用第一条非空线路兜底，
     * 并把 channelIndex 指到该线路，避免「显示的是第 2 条线路、标题却写线路 1」。
     */
    private fun toResolvedDetail(bean: AnimeDetailBean): AnimeDetail {
        val detail = bean.toAnimeDetail()
        if (detail.episodes.isNotEmpty()) return detail
        val fallbackKey = bean.channels.keys.sorted()
            .firstOrNull { bean.channels[it].orEmpty().isNotEmpty() }
            ?: return detail
        return detail.copy(
            episodes = bean.channels[fallbackKey].orEmpty().map { it.toEpisode() },
            channelIndex = fallbackKey,
        )
    }

    private fun searchTitles(title: String, originalTitle: String): List<String> =
        listOf(title.trim(), originalTitle.trim())
            .filter { it.isNotEmpty() }
            .distinct()

    private class NoMatchingSourceException(message: String) : Exception(message)
}
