package com.lanlinju.animius.data.remote.dandanplay

import com.anime.danmaku.api.DanmakuSession
import com.anime.danmaku.api.TimeBasedDanmakuSession
import com.lanlinju.animius.data.remote.dandanplay.DandanplayDanmakuProvider.Companion.ID
import com.lanlinju.animius.data.remote.dandanplay.dto.SearchAnimeEpisodes
import com.lanlinju.animius.data.remote.dandanplay.dto.SearchEpisodeDetails
import com.lanlinju.animius.data.remote.dandanplay.dto.toDanmakuOrNull
import com.lanlinju.animius.domain.model.DanmakuResult
import com.lanlinju.animius.util.createHttpClient
import com.lanlinju.animius.util.log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A [DanmakuProvider] provides a stream of danmaku for a specific episode.
 *
 * @see DanmakuProviderFactory
 */
interface DanmakuProvider : AutoCloseable {
    // 弹幕提供者的唯一标识符
    val id: String

    // 挂起函数，用于获取弹幕会话
    suspend fun fetch(subjectName: String, episodeName: String?): DanmakuResult
}

interface DanmakuProviderFactory { // SPI 接口
    /**
     * @see DanmakuProvider.id
     * 获取弹幕提供者的唯一标识符
     */
    val id: String

    // 创建一个新的弹幕提供者实例
    fun create(): DanmakuProvider
}

/**
 * 弹弹play 弹幕提供者。
 *
 * 匹配流程（按调用量从少到多，任一步命中即返回）：
 * 1. 本地缓存（命中则零 API 调用）
 * 2. 关键词搜索 /api/v2/search/episodes（原有路径，保持不变）
 * 3. 搜索无结果时走文件识别 /api/v2/match 兜底（解决源站番剧名与弹弹play 写法对不上的问题）
 *
 * 文件识别结果必须通过 [DanmakuMatchHeuristics.pickBestMatch] 的可信度校验才会采用，
 * 宁可没有弹幕，也不错挂其他剧集的弹幕。
 */
@Singleton
class DandanplayDanmakuProvider @Inject constructor(
    private val client: HttpClient,
    private val matchCache: DanmakuMatchCache,
) : DanmakuProvider {

    companion object {
        const val ID = "弹弹play"
        private const val TAG = "DanmakuProvider"
    }

    override val id: String get() = ID

    private val dandanplayClient = DandanplayClient(client)

    /** 单次匹配的中间结果 */
    private sealed interface Outcome {
        /** 命中 episodeId */
        data class Matched(val episodeId: Long) : Outcome

        /** 弹幕库中没有对应内容（可继续尝试下一层兜底） */
        object NoResult : Outcome

        /** 网络/鉴权类失败（不再兜底，直接呈现原因） */
        data class Failed(val result: DanmakuResult) : Outcome
    }

    override suspend fun fetch(
        subjectName: String, episodeName: String?
    ): DanmakuResult {
        if (episodeName.isNullOrBlank()) {
            return DanmakuResult.UnsupportedEpisode("剧集名缺失，无法匹配弹幕")
        }
        val targetEpisode = DanmakuMatchHeuristics.normalizeEpisodeName(episodeName)
            ?: return DanmakuResult.UnsupportedEpisode("「$episodeName」暂不支持自动匹配弹幕")

        val cacheKey = matchCache.keyOf(subjectName, targetEpisode)

        // 1) 缓存：命中则完全不产生接口调用
        matchCache.get(cacheKey)?.let { hit ->
            return hit.episodeId
                ?.let { createAndCache(cacheKey, it) }
                ?: DanmakuResult.NotFound("弹幕库中未匹配到「$subjectName」第 $targetEpisode 集")
        }

        // 2) 关键词搜索（原有路径）
        when (val searchOutcome = searchAndResolve(subjectName, targetEpisode)) {
            is Outcome.Matched -> return createAndCache(cacheKey, searchOutcome.episodeId)
            is Outcome.Failed -> return searchOutcome.result
            Outcome.NoResult -> Unit
        }

        // 3) 文件识别兜底（仅搜索无结果时；实测可救回「源站番剧名与弹幕库写法不一致」的失配）
        return when (val matchOutcome = matchAndResolve(subjectName, targetEpisode)) {
            is Outcome.Matched -> createAndCache(cacheKey, matchOutcome.episodeId)
            else -> {
                matchCache.putNotFound(cacheKey)
                DanmakuResult.NotFound("弹幕库中未匹配到「$subjectName」第 $targetEpisode 集")
            }
        }
    }

    /** 关键词搜索 + 番剧/集数回找（原有算法，仅将"无结果"与"失败"区分开以便兜底） */
    private suspend fun searchAndResolve(subjectName: String, targetEpisode: String): Outcome {
        val searchResponse = try {
            fetchWithFallback(subjectName, targetEpisode)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "弹幕搜索失败: $e".log(TAG)
            return Outcome.Failed(
                DanmakuResult.NetworkError("弹幕服务连接失败: ${e.message ?: e.javaClass.simpleName}")
            )
        }

        if (!searchResponse.success) {
            return Outcome.Failed(classifyApiError(searchResponse.errorCode, searchResponse.errorMessage))
        }
        if (searchResponse.animes.isEmpty()) {
            return Outcome.NoResult
        }

        // 番剧回找：优先标题精确/包含匹配，不盲取第一条
        val cleanedSubject = DanmakuMatchHeuristics.cleanSubjectName(subjectName)
        val anime: SearchAnimeEpisodes = searchResponse.animes.firstOrNull { it.animeTitle == subjectName }
            ?: searchResponse.animes.firstOrNull {
                cleanedSubject.isNotBlank() && it.animeTitle.contains(cleanedSubject)
            }
            ?: searchResponse.animes.first()

        // 集数回找：按归一化集数在候选列表中定位目标集，避免取到第一集的弹幕
        val episode: SearchEpisodeDetails = findTargetEpisode(anime.episodes, targetEpisode)
            ?: anime.episodes.singleOrNull()
            ?: return Outcome.NoResult

        return Outcome.Matched(episode.episodeId.toLong())
    }

    /** 文件识别兜底：构造虚拟文件名交给官方模糊匹配，候选须通过可信度校验 */
    private suspend fun matchAndResolve(subjectName: String, targetEpisode: String): Outcome {
        return try {
            val fileName = DanmakuMatchHeuristics.buildMatchFileName(subjectName, targetEpisode)
            val response = dandanplayClient.matchEpisode(fileName)
            if (!response.success) {
                "文件识别失败: errorCode=${response.errorCode} ${response.errorMessage}".log(TAG)
                return Outcome.NoResult
            }
            val best = DanmakuMatchHeuristics.pickBestMatch(subjectName, targetEpisode, response.matches.orEmpty())
            if (best != null) {
                "文件识别命中: 「${best.animeTitle}」/「${best.episodeTitle}」 episodeId=${best.episodeId}".log(TAG)
                Outcome.Matched(best.episodeId)
            } else {
                "文件识别无可靠候选（共 ${response.matches?.size ?: 0} 个）".log(TAG)
                Outcome.NoResult
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "文件识别异常: $e".log(TAG)
            Outcome.NoResult
        }
    }

    /** 搜索降级链：原名 -> 去掉「第X季/年份括号」后的主干名 */
    private suspend fun fetchWithFallback(subjectName: String, targetEpisode: String) =
        dandanplayClient.searchEpisode(subjectName, targetEpisode).let { first ->
            val cleaned = DanmakuMatchHeuristics.cleanSubjectName(subjectName)
            if (!first.success || first.animes.isNotEmpty() || cleaned == subjectName || cleaned.isBlank()) {
                first
            } else {
                "「$subjectName」搜索无结果，用「$cleaned」重试".log(TAG)
                dandanplayClient.searchEpisode(cleaned, targetEpisode)
            }
        }

    /**
     * 在候选集列表中定位目标集：
     * 归一化后相等优先（忽略前导零差异，源站「第01集」与弹弹play「第1话」是同一集）；
     * 全列表唯一时直接采用；否则视为未命中（宁可无弹幕也不错挂其他集的时间轴）。
     */
    private fun findTargetEpisode(
        episodes: List<SearchEpisodeDetails>,
        target: String,
    ): SearchEpisodeDetails? {
        val canonical = DanmakuMatchHeuristics.canonicalEpisode(target)
        return episodes.firstOrNull { detail ->
            val normalized = DanmakuMatchHeuristics.normalizeEpisodeName(detail.episodeTitle)
            normalized != null && DanmakuMatchHeuristics.canonicalEpisode(normalized) == canonical
        }
    }

    private fun classifyApiError(errorCode: Int, errorMessage: String?): DanmakuResult {
        val message = errorMessage ?: "未知错误(errorCode=$errorCode)"
        "弹弹play API 错误: errorCode=$errorCode, $message".log(TAG)
        return if (errorMessage?.contains("应用不存在") == true || message.contains("appId", ignoreCase = true)) {
            DanmakuResult.AuthFailed("弹弹play 鉴权失败（AppId 无效或未生效）: $message")
        } else {
            DanmakuResult.NetworkError("弹弹play 服务返回错误: $message")
        }
    }

    /** 拉取弹幕并回写缓存（成功与"该集无弹幕"都会缓存，网络类失败不缓存） */
    private suspend fun createAndCache(cacheKey: String, episodeId: Long): DanmakuResult {
        val result = createSession(episodeId)
        when (result) {
            is DanmakuResult.Success -> matchCache.putMatched(cacheKey, episodeId)
            is DanmakuResult.NotFound -> matchCache.putNotFound(cacheKey)
            else -> Unit
        }
        return result
    }

    private suspend fun createSession(
        episodeId: Long,
    ): DanmakuResult {
        return try {
            val list = dandanplayClient.getDanmakuList(episodeId = episodeId)
            if (list.isEmpty()) {
                DanmakuResult.NotFound("弹幕库中该集暂无弹幕数据")
            } else {
                DanmakuResult.Success(
                    TimeBasedDanmakuSession.create(
                        list.asSequence().mapNotNull { it.toDanmakuOrNull() },
                        coroutineContext = Dispatchers.Default,
                    )
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: DandanplayApiException) {
            classifyApiError(e.errorCode, e.message)
        } catch (e: Exception) {
            "弹幕列表获取失败(episodeId=$episodeId): $e".log(TAG)
            DanmakuResult.NetworkError("弹幕数据获取失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun close() {
        //client.close()
    }
}

class DandanplayDanmakuProviderFactory : DanmakuProviderFactory {
    override val id: String get() = ID

    override fun create(): DandanplayDanmakuProvider {
        return DandanplayDanmakuProvider(createHttpClient(), DanmakuMatchCache())
    }
}
