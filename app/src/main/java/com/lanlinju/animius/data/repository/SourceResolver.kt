package com.lanlinju.animius.data.repository

import com.lanlinju.animius.data.remote.api.AnimeApi
import com.lanlinju.animius.data.remote.dto.AnimeBean
import com.lanlinju.animius.data.remote.dto.AnimeDetailBean
import com.lanlinju.animius.domain.matching.EpisodeNumber
import com.lanlinju.animius.domain.matching.RankedCandidate
import com.lanlinju.animius.domain.matching.SearchQueries
import com.lanlinju.animius.domain.matching.TitleAliases
import com.lanlinju.animius.domain.matching.TitleMatcher
import com.lanlinju.animius.domain.matching.isJapaneseText
import com.lanlinju.animius.domain.model.AnimeDetail
import com.lanlinju.animius.domain.model.Episode
import com.lanlinju.animius.util.SourceMode
import com.lanlinju.animius.util.SourceRiskSignal
import com.lanlinju.animius.util.log
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

/**
 * 一个源「为什么没匹配上」的结构化诊断（tv.8 切片 3）。
 *
 * 只写 `BehaviorTrace` 日志，界面仍是一句话（用户 2026-10-22 拍板），
 * 供 AI 判断到底是「站里没有」还是「门槛太严」。
 */
data class MatchDiagnostics(
    /** 真正发出去的搜索次数（关键词，每源最多 [SearchQueries.DEFAULT_MAX] 次） */
    val queriesTried: Int = 0,
    /** 搜到的候选条数合计（每个关键词的结果条数累加） */
    val candidateCount: Int = 0,
    /** 最接近的候选标题：优先「可信但详情不合格」的那条，其次第一条被门槛拒掉的 */
    val nearestCandidate: String? = null,
    /** [nearestCandidate] 被拒的理由 */
    val rejectReason: String? = null,
)

data class SourceAttemptFailure(
    val mode: SourceMode,
    val reason: String,
    /** 结构化诊断；「总预算耗尽」「超时」这类没真正探测的失败为 null */
    val diagnostics: MatchDiagnostics? = null,
)

data class ResolveProgress(
    val index: Int,
    val total: Int,
    val mode: SourceMode,
    /** 同时在飞的源数量；1 表示串行（风控降级后也会回到 1） */
    val concurrency: Int = 1,
)

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
 *
 * 源内怎么试（tv.8 切片 3，用户 2026-10-22 拍板收窄）：
 * 关键词来自 [SearchQueries]（中文标题 → 中文别名 → 拉丁别名 → 日文原名，上限 [MAX_QUERIES]），
 * **按顺序串行**搜第 1 页；候选按 [TitleMatcher.rankCandidates] 的分数顺序验证详情，
 * 每源最多 [MAX_DETAIL_VERIFICATIONS] 次。源内并发（实测更慢：嘶哩嘶哩 4.96→5.32s、
 * Age动漫 1.84→2.45s）与搜索翻页（76 次零收益、请求 +34%）在只读实验里都证明是负收益，已移除。
 * 源之间的并发仍由 [resolveConcurrent] 负责，两者互不影响。
 */
@Singleton
class SourceResolver @Inject constructor(
    private val animeApi: AnimeApi,
    private val matchStore: SourceMatchStore,
) {

    companion object {
        /** 单个源的搜索 + 详情预算 */
        const val PER_SOURCE_TIMEOUT_MS = 12_000L

        /** 一次解析（最多 5 个源：量子/暴风/魔都/Agedm/Silisili）的总预算 */
        const val OVERALL_TIMEOUT_MS = 45_000L

        /**
         * 换源时单个源的预算：搜索 + 详情 + 视频解析。
         * 实测嘶哩嘶哩解析一集约 13s（搜索 2.8 + 详情 5 + 视频 5），12s 会误判超时。
         */
        const val EPISODE_PER_SOURCE_TIMEOUT_MS = 20_000L

        /** 换源的整体预算：给两个慢源留出余量 */
        const val EPISODE_OVERALL_TIMEOUT_MS = 60_000L

        /**
         * 每个源最多尝试的搜索关键词数（中文标题 + 原名 + 别名）。
         * 上限的唯一出处是 [SearchQueries.DEFAULT_MAX]，这里只做转发，避免两处数字漂移。
         */
        private const val MAX_QUERIES = SearchQueries.DEFAULT_MAX

        /** 单源最多详情验证次数（第 1 名 + 第 2 名候选） */
        private const val MAX_DETAIL_VERIFICATIONS = 2

        /** 第 2 次详情验证前要求的最少剩余预算；不足就放弃，不硬撑 */
        private const val MIN_VERIFY_BUDGET_MS = 4_000L

        /** 并发选源默认上限：当前源 + 2 个兜底同时试（用户 2026-10-21 拍板） */
        const val DEFAULT_CONCURRENCY = 3

        /**
         * 并发路径的总预算（卡 §5「最坏 ≤20s」）。
         *
         * 结构性最坏：5 个候选 / 3 并发 = 2 批 × 每源 [PER_SOURCE_TIMEOUT_MS] 12s = 24s，
         * 会超出验收上限。所以并发路径单独收口到 20s：第 1 批最多 12s，第 2 批只剩 8s，
         * 不足即记「总预算耗尽」。串行 [resolve] 仍用 [OVERALL_TIMEOUT_MS]（45s），行为不变。
         */
        const val CONCURRENT_OVERALL_TIMEOUT_MS = 20_000L

        /** 连续风控（403/验证码）达到这个数就降一档并发：3 → 2 → 串行 */
        const val RISK_DEGRADE_STREAK = 2

        /**
         * 固定兜底顺序（2026-10-22 tv.9 切片 1）：采集源在前、Agedm 补长尾、Silisili 最后兜底。
         * Skr/Girigiri/Gugufan/Jzacg 已失效或库存过小，移出候选（代码保留，域名恢复后可改回）。
         */
        private val FALLBACK_ORDER = listOf(
            SourceMode.Lz,
            SourceMode.Bf,
            SourceMode.Md,
            SourceMode.Agedm,
            SourceMode.Silisili,
        )
    }

    fun cacheKey(metadataId: Long, title: String): String =
        if (metadataId > 0) "id:$metadataId" else "title:${TitleMatcher.normalize(title)}"

    /**
     * 播放取址失败时清掉该番的解析缓存：详情能打开不代表能播（例如 Age动漫
     * 部分条目提示「网页端不提供播放」），不清掉会 7 天内一直命中坏源。
     */
    fun invalidate(metadataId: Long, title: String) {
        matchStore.remove(cacheKey(metadataId, title))
    }

    /** 换源/播放成功后记住可用源，下次进入直接命中 */
    fun remember(
        metadataId: Long,
        title: String,
        mode: SourceMode,
        detailUrl: String,
        siteTitle: String,
    ) {
        matchStore.put(
            cacheKey(metadataId, title),
            SourceMatch(mode, detailUrl, siteTitle, Instant.now()),
        )
    }

    fun candidates(currentMode: SourceMode, exclude: Set<SourceMode> = emptySet()): List<SourceMode> =
        (listOf(currentMode) + FALLBACK_ORDER).distinct().filterNot { it in exclude }

    /**
     * 解析一部番在哪个源可看。
     *
     * @param title 中文标题（元数据 `name_cn`，为空时用原名）
     * @param originalTitle 原名（中文搜索无结果时的兜底查询）
     * @param aliases 同一部番的其他名字（内置别名表 + Bangumi 别名），用于搜索与候选校验
     * @param expectedEpisodes 元数据的 `total_episodes`，用于详情复核时的集数吻合度打分；0 = 不比较
     * @param metadataId Bangumi id，用于缓存键
     * @param currentMode 用户当前选择的源，作为第一候选
     */
    suspend fun resolve(
        title: String,
        originalTitle: String = "",
        aliases: List<String> = emptyList(),
        expectedEpisodes: Int = 0,
        metadataId: Long = 0,
        currentMode: SourceMode,
        exclude: Set<SourceMode> = emptySet(),
        now: Instant = Instant.now(),
        deadlineMillis: Long = System.currentTimeMillis() + OVERALL_TIMEOUT_MS,
        onProgress: (ResolveProgress) -> Unit = {},
    ): SourceResolveResult = withContext(Dispatchers.IO) {
        val allAliases = mergeAliases(title, aliases)
        val queries = searchTitles(title, originalTitle, allAliases)
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

            val budget = minOf(PER_SOURCE_TIMEOUT_MS, remaining)
            val source = try {
                withTimeoutOrNull(budget) {
                    findSource(
                        mode = mode,
                        queries = queries,
                        aliases = allAliases,
                        title = title,
                        expectedEpisodes = expectedEpisodes,
                        deadlineAt = System.currentTimeMillis() + budget,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoMatchingSourceException) {
                attempts += SourceAttemptFailure(mode, e.message ?: "没有匹配条目", e.diagnostics)
                null
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
            // 注意：必须先把整行拼好再 .log()——`"a" + "b".log()` 只会记录最后一段
            val okLog = "SOURCE resolve ok key=$key mode=${source.mode} " +
                "title=${source.siteTitle} episodes=${source.episodes.size}"
            okLog.log("BehaviorTrace")
            return@withContext SourceResolveResult.Success(source, fromCache = false)
        }

        val failLog = "SOURCE resolve failed key=$key attempts=" +
            attempts.joinToString { "${it.mode}:${it.reason}" }
        failLog.log("BehaviorTrace")
        SourceResolveResult.Failure(attempts)
    }

    /**
     * 并发选源：候选源按 [maxConcurrency] 分批同时探测，先成功者胜、其余取消。
     *
     * 与 [resolve] 的区别只在「怎么试」：缓存命中路径仍在最前（串行快速路径，只发一次详情请求）；
     * 并发失败（全部源都不可用）时 attempts 与串行一致，完整列出每个源的原因。
     *
     * 风控降级：一批源**全部失败**且其中风控类（403/验证码）失败达到 [RISK_DEGRADE_STREAK] 个时，
     * 累计连续风控数，达到阈值就把并发上限降一档（3 → 2 → 1 串行）；`NoMatchingSource`
     * （搜索无匹配/详情无剧集）不算风控，冷门番不会因为找不到而变慢。
     * 只要求「批内风控失败 ≥2」而不是「整批全是风控」——否则 2 个 403 混 1 个超时就会被清零，
     * 与卡里「连续出现 403/验证码」的口径不符。
     *
     * @param maxConcurrency 并发上限，默认 [DEFAULT_CONCURRENCY]；1 等价于串行
     * @param deadlineMillis 整轮并发探测的总预算，默认 [CONCURRENT_OVERALL_TIMEOUT_MS]（20s）
     * @param onProgress 每启动一个源回调一次；`index` = 已启动的源数，`concurrency` = 当前并发档位
     */
    suspend fun resolveConcurrent(
        title: String,
        originalTitle: String = "",
        aliases: List<String> = emptyList(),
        expectedEpisodes: Int = 0,
        metadataId: Long = 0,
        currentMode: SourceMode,
        exclude: Set<SourceMode> = emptySet(),
        now: Instant = Instant.now(),
        deadlineMillis: Long = System.currentTimeMillis() + CONCURRENT_OVERALL_TIMEOUT_MS,
        maxConcurrency: Int = DEFAULT_CONCURRENCY,
        onProgress: (ResolveProgress) -> Unit = {},
    ): SourceResolveResult = withContext(Dispatchers.IO) {
        val allAliases = mergeAliases(title, aliases)
        val queries = searchTitles(title, originalTitle, allAliases)
        if (queries.isEmpty()) {
            return@withContext SourceResolveResult.Failure(
                listOf(SourceAttemptFailure(currentMode, "标题为空"))
            )
        }

        val key = cacheKey(metadataId, title)
        val attempts = mutableListOf<SourceAttemptFailure>()

        // 1) 缓存命中仍走串行快速路径，且必须排在并发之前（只发一次详情请求）
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

        // 2) 有界并发：一批最多 concurrency 个源同时试
        val modes = candidates(currentMode, exclude)
        var concurrency = maxConcurrency.coerceAtLeast(1)
        var riskStreak = 0
        var cursor = 0
        val started = AtomicInteger(0)

        while (cursor < modes.size) {
            val remaining = deadlineMillis - System.currentTimeMillis()
            if (remaining <= 0) {
                modes.drop(cursor).forEach { attempts += SourceAttemptFailure(it, "总预算耗尽") }
                break
            }

            val batch = modes.subList(cursor, minOf(cursor + concurrency, modes.size)).toList()
            cursor += batch.size
            val batchConcurrency = concurrency

            val outcome = resolveBatch(
                batch = batch,
                queries = queries,
                aliases = allAliases,
                title = title,
                expectedEpisodes = expectedEpisodes,
                deadlineMillis = deadlineMillis,
                onStart = { mode ->
                    onProgress(
                        ResolveProgress(
                            index = started.incrementAndGet(),
                            total = modes.size,
                            mode = mode,
                            concurrency = batchConcurrency,
                        )
                    )
                },
            )
            attempts += outcome.failures

            val source = outcome.source
            if (source != null) {
                matchStore.put(
                    key,
                    SourceMatch(source.mode, source.detailUrl, source.siteTitle, now),
                )
                val okLog = "SOURCE resolve ok key=$key mode=${source.mode} " +
                    "title=${source.siteTitle} episodes=${source.episodes.size} " +
                    "concurrency=$concurrency"
                okLog.log("BehaviorTrace")
                return@withContext SourceResolveResult.Success(source, fromCache = false)
            }

            // 3) 风控降级：本批无成功（走到这里就说明没有成功源）且风控失败 ≥2 才算「连续风控」；
            //    2 个 403 混 1 个超时同样累计，不再被整批口径清零
            val riskFailures = outcome.failures.count { SourceRiskSignal.isRiskControl(it.reason) }
            if (riskFailures >= RISK_DEGRADE_STREAK) {
                riskStreak += riskFailures
                if (concurrency > 1) {
                    concurrency = maxOf(1, concurrency - 1)
                    "SOURCE concurrent degrade concurrency=$concurrency riskStreak=$riskStreak"
                        .log("BehaviorTrace")
                }
            } else {
                riskStreak = 0
            }
        }

        val failLog = "SOURCE resolve failed key=$key attempts=" +
            attempts.joinToString { "${it.mode}:${it.reason}" }
        failLog.log("BehaviorTrace")
        SourceResolveResult.Failure(attempts)
    }

    /**
     * 取不到播放地址时，按集号在下一个源里找同一集。
     *
     * 串行循环保持原样（ADR-0005 的后续动作另开卡）；关键词与候选排序顺带用上 [SearchQueries]
     * 与 [TitleMatcher.rankCandidates]，与详情选源同一套规则。
     *
     * @param episodeNumber 已归一化的集号（[EpisodeNumber.canonical]）；`movie` 表示剧场版
     * @param aliases 同一部番的其他名字（内置别名表 + Bangumi 别名）
     * @param exclude 已失败的源（通常包含当前源）
     */
    suspend fun resolveEpisode(
        title: String,
        originalTitle: String = "",
        aliases: List<String> = emptyList(),
        episodeNumber: String,
        currentMode: SourceMode,
        exclude: Set<SourceMode> = setOf(currentMode),
        deadlineMillis: Long = System.currentTimeMillis() + EPISODE_OVERALL_TIMEOUT_MS,
        onProgress: (ResolveProgress) -> Unit = {},
    ): EpisodeResolveResult = withContext(Dispatchers.IO) {
        val allAliases = mergeAliases(title, aliases)
        val queries = searchTitles(title, originalTitle, allAliases)
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
                withTimeoutOrNull(minOf(EPISODE_PER_SOURCE_TIMEOUT_MS, remaining)) {
                    findEpisode(mode, queries, target, allAliases, title = title)
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

        val failLog = "SOURCE switch failed from=$currentMode ep=$target attempts=" +
            attempts.joinToString { "${it.mode}:${it.reason}" }
        failLog.log("BehaviorTrace")
        EpisodeResolveResult.Failure(attempts)
    }

    /** 一批并发源的结果：成功源（若本批有）与每个失败源的原因 */
    private class BatchOutcome(
        val source: ResolvedSource?,
        val failures: List<SourceAttemptFailure>,
    )

    /** 单个源的并发探测结果：成功带 [source]，失败带 [reason] 与 [diagnostics] */
    private class SourceAttempt(
        val mode: SourceMode,
        val source: ResolvedSource?,
        val reason: String?,
        val diagnostics: MatchDiagnostics? = null,
    )

    /**
     * 一批源同时探测：谁先成功谁胜，成功后取消本批其余仍在飞的请求。
     * 全部失败时返回每个源的失败原因（attempts 完整，供 UI 展示与「去搜索」判断）。
     */
    private suspend fun resolveBatch(
        batch: List<SourceMode>,
        queries: List<String>,
        aliases: List<String>,
        title: String,
        expectedEpisodes: Int = 0,
        deadlineMillis: Long,
        onStart: (SourceMode) -> Unit,
    ): BatchOutcome = coroutineScope {
        val outcomes = Channel<SourceAttempt>(Channel.UNLIMITED)
        val jobs = batch.associateWith { mode ->
            launch(Dispatchers.IO) {
                var attempt = SourceAttempt(mode, null, "探测异常")
                try {
                    onStart(mode)
                    attempt = attemptSource(
                        mode = mode,
                        queries = queries,
                        aliases = aliases,
                        title = title,
                        expectedEpisodes = expectedEpisodes,
                        remaining = deadlineMillis - System.currentTimeMillis(),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // onStart（UI 进度回调）或探测本身抛异常也要回传结果，
                    // 否则 receive() 会永久挂起（trySend 在 finally 里兜底）
                    attempt = SourceAttempt(mode, null, e.message ?: e.javaClass.simpleName)
                } finally {
                    outcomes.trySend(attempt)
                }
            }
        }

        val failures = mutableListOf<SourceAttemptFailure>()
        var source: ResolvedSource? = null
        var received = 0
        while (received < batch.size) {
            val attempt = outcomes.receive()
            received += 1
            val found = attempt.source
            if (found != null) {
                source = found
                break
            }
            failures += SourceAttemptFailure(
                attempt.mode,
                attempt.reason ?: "未知错误",
                attempt.diagnostics,
            )
        }

        if (source != null) {
            // 只统计真正还在飞的：胜出的那个 job 可能尚未结束（isActive=true），不能算进 remaining
            val cancelled = jobs.count { (mode, job) -> job.isActive && mode != source.mode }
            jobs.values.forEach { it.cancel() }
            if (cancelled > 0) {
                "SOURCE concurrent cancel remaining=$cancelled".log("BehaviorTrace")
            }
        }
        BatchOutcome(source, failures)
    }

    /** 单源探测：预算取「单源预算」与「整体剩余」的较小值，超时/异常都转成失败原因 */
    private suspend fun attemptSource(
        mode: SourceMode,
        queries: List<String>,
        aliases: List<String>,
        title: String,
        remaining: Long,
        expectedEpisodes: Int = 0,
    ): SourceAttempt {
        if (remaining <= 0) return SourceAttempt(mode, null, "总预算耗尽")
        val budget = minOf(PER_SOURCE_TIMEOUT_MS, remaining)
        return try {
            val source = withTimeoutOrNull(budget) {
                findSource(
                    mode = mode,
                    queries = queries,
                    aliases = aliases,
                    title = title,
                    expectedEpisodes = expectedEpisodes,
                    deadlineAt = System.currentTimeMillis() + budget,
                )
            }
            if (source == null) {
                SourceAttempt(mode, null, "超时")
            } else {
                SourceAttempt(mode, source, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoMatchingSourceException) {
            SourceAttempt(mode, null, e.message ?: "没有匹配条目", e.diagnostics)
        } catch (e: Exception) {
            SourceAttempt(mode, null, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 单源找源：按 [SearchQueries] 的关键词顺序**串行**搜第 1 页，
     * 第一个「可信候选 + 详情合格」的条目胜出；失败时带上结构化诊断抛 [NoMatchingSourceException]。
     *
     * 不并发、不翻页（用户 2026-10-22 拍板）：源内并发 2 实测更慢，翻页 76 次零收益还多 34% 请求。
     * 串行下预算靠 [SourceProbeState.canProbeMore] 守住：没预算、验证次数用完、
     * 或第 2 次验证前不足 [MIN_VERIFY_BUDGET_MS] 时立刻停手，不把请求花在来不及确认的候选上。
     */
    private suspend fun findSource(
        mode: SourceMode,
        queries: List<String>,
        aliases: List<String>,
        title: String = "",
        expectedEpisodes: Int = 0,
        deadlineAt: Long = System.currentTimeMillis() + PER_SOURCE_TIMEOUT_MS,
    ): ResolvedSource {
        // 采集源不吃日文原名（实验 0 命中）：只给 Agedm 发，省请求（2026-10-22 tv.9 切片 2）
        val effectiveQueries = queriesFor(mode, queries)
        if (effectiveQueries.isEmpty()) {
            throw NoMatchingSourceException("日文原名仅 Agedm 支持")
        }
        val state = SourceProbeState(
            mode = mode,
            aliases = aliases,
            expectedEpisodes = expectedEpisodes,
            deadlineAt = deadlineAt,
            title = title,
            stem = SearchQueries.mainStem(title),
        )
        for (query in effectiveQueries) {
            if (!state.canProbeMore()) break
            val source = probeQuery(state, query)
            if (source != null) return source
        }

        val reason = state.lastReason ?: "没有匹配条目"
        val failLog = "MATCH fail mode=${mode.name} queries=${state.queriesTried} " +
            "candidates=${state.candidateCount} nearest=${state.nearestCandidate?.take(40) ?: "-"} " +
            "reject=${state.rejectReason ?: "-"}"
        failLog.log("BehaviorTrace")
        throw NoMatchingSourceException(reason, state.diagnostics())
    }

    /**
     * 一个关键词的一次探测（只取第 1 页）：搜索 → [TitleMatcher.rankCandidates] 打分排序 → 按分数顺序验证详情。
     *
     * 探测请求抛异常（403/验证码/网络）时**整源立刻失败**并把异常抛给上层：与改造前一致，
     * 风控信号靠异常文本传给 [SourceRiskSignal] 触发降级，不会被吞成「搜索无匹配」。
     */
    private suspend fun probeQuery(state: SourceProbeState, query: String): ResolvedSource? {
        val results = try {
            animeApi.getSearchData(query, 1, state.mode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 搜索请求本身失败（403/验证码/网络）：也要留诊断，否则卡 §5-5「每个源都有诊断」不成立。
            // 异常文本原样带进 reason，SourceRiskSignal 仍能识别风控并触发降级。
            state.queriesTried += 1
            val reason = e.message ?: e.javaClass.simpleName
            state.setReason(reason)
            state.rejectReason = "搜索请求失败"
            val failLog = "MATCH fail mode=${state.mode.name} queries=${state.queriesTried} " +
                "candidates=${state.candidateCount} nearest=- reject=搜索请求失败"
            failLog.log("BehaviorTrace")
            throw NoMatchingSourceException(reason, state.diagnostics())
        }
        state.queriesTried += 1
        state.candidateCount += results.size

        val ranked = TitleMatcher.rankCandidates(
            // 门槛用原始目标标题：主干名查询（「某番 第二季」→「某番」）不能把第一季候选排进来
            target = state.title.ifBlank { query },
            candidates = results.map { it.title },
            aliases = state.aliases,
        )
        val picked = ranked.firstOrNull()
        // 注意：必须先把整行拼好再 .log()——`"a" + "b".log()` 只会记录最后一段
        val queryLog = "MATCH query=$query via=${state.viaOf(query)} page=1 results=${results.size} " +
            "picked=${picked?.title?.take(40) ?: "-"} score=${picked?.score ?: "-"}"
        queryLog.log("BehaviorTrace")

        if (picked == null) {
            state.noteGenericReason("搜索无匹配")
            // 「最接近」按归一化标题长度差取，避免把搜索结果第一条（可能完全无关）当诊断
            val nearest = results.minByOrNull { bean ->
                kotlin.math.abs(
                    TitleMatcher.normalize(bean.title).length - TitleMatcher.normalize(query).length,
                )
            }
            nearest?.let { state.noteReject(it.title, "候选不可信", trusted = false) }
            return null
        }
        return verifyCandidates(state, results, query, ranked)
    }

    /**
     * 按 [TitleMatcher.rankCandidates] 的分数顺序验证详情：详情标题必须再过一次门槛，
     * 集数参与复核打分。每源最多 [MAX_DETAIL_VERIFICATIONS] 次，第 2 次前要求剩余预算
     * ≥ [MIN_VERIFY_BUDGET_MS]；同一个详情地址不重复验证（同一个条目可能在多个关键词下都搜到）。
     */
    private suspend fun verifyCandidates(
        state: SourceProbeState,
        results: List<AnimeBean>,
        query: String,
        ranked: List<RankedCandidate>,
    ): ResolvedSource? {
        for ((index, candidate) in ranked.withIndex()) {
            if (state.verifications >= MAX_DETAIL_VERIFICATIONS) return null
            val rank = index + 1
            if (state.verifications > 0 && state.budgetLeft() < MIN_VERIFY_BUDGET_MS) return null

            // 门槛与打分都用「原始目标标题」，不用当前查询词：
            // 主干名查询（「某番 第二季」→「某番」）若用查询词做校验，会把第一季候选当成命中。
            val targetTitle = state.title.ifBlank { query }

            // 采集站库存杂（解说/预告/合集/剧场版…）：候选含排除词而目标不含 → 不验详情（tv.9 切片 2）
            if (TitleMatcher.isJunkCandidate(targetTitle, candidate.title)) {
                state.setReason("杂项条目")
                state.noteReject(candidate.title, "杂项条目", trusted = false)
                "MATCH reject junk=${candidate.title.take(40)}".log("BehaviorTrace")
                continue
            }

            val detailUrl = results[candidate.index].url
            if (!state.markVerified(detailUrl)) continue
            if (rank > 1) "MATCH verify fallback rank=$rank".log("BehaviorTrace")
            state.verifications += 1

            val bean = try {
                animeApi.getAnimeDetail(detailUrl, state.mode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.setReason(e.message ?: e.javaClass.simpleName)
                state.noteReject(candidate.title, "详情请求失败", trusted = true)
                "MATCH verify rank=$rank fail reason=详情请求失败".log("BehaviorTrace")
                continue
            }

            val detail = toResolvedDetail(bean)
            // 详情标题为空时退回搜索页标题，避免「标题空 → 归一化空 → 被判不可信」误杀
            val detailTitle = detail.title.ifBlank { candidate.title }
            // 采集源有站点总集数（vod_total）时优先用它打分；番剧站没有该字段，退回实际剧集数
            val episodeCount = if (bean.totalEpisodes > 0) bean.totalEpisodes else detail.episodes.size
            val detailScore = TitleMatcher.score(
                target = targetTitle,
                candidate = detailTitle,
                aliases = state.aliases,
                episodeCount = episodeCount,
                expectedEpisodes = state.expectedEpisodes,
                rank = candidate.index + 1,
            )
            if (detailScore == null) {
                state.setReason("详情标题不符")
                state.noteReject(candidate.title, "详情标题不符", trusted = true)
                "MATCH verify rank=$rank fail reason=详情标题不符".log("BehaviorTrace")
                continue
            }
            if (detail.episodes.isEmpty()) {
                state.setReason("详情无剧集")
                state.noteReject(candidate.title, "详情无剧集", trusted = true)
                "MATCH verify rank=$rank fail reason=详情无剧集".log("BehaviorTrace")
                continue
            }
            "MATCH verify rank=$rank ok title=$detailTitle score=$detailScore"
                .log("BehaviorTrace")
            return ResolvedSource(state.mode, detailUrl, detailTitle, detail.episodes, detail)
        }
        return null
    }

    private suspend fun findEpisode(
        mode: SourceMode,
        queries: List<String>,
        target: String,
        aliases: List<String>,
        title: String = "",
    ): ResolvedEpisode {
        // 与 findSource 同一套口径：日文原名只发给 Agedm
        val effectiveQueries = queriesFor(mode, queries)
        if (effectiveQueries.isEmpty()) {
            throw NoMatchingSourceException("日文原名仅 Agedm 支持")
        }
        var lastReason = "没有匹配条目"
        for (query in effectiveQueries) {
            // 门槛用原始目标标题（与详情选源一致），避免主干名查询把第一季当命中
            val targetTitle = title.ifBlank { query }
            val results = animeApi.getSearchData(query, 1, mode)
            val best = TitleMatcher.rankCandidates(targetTitle, results.map { it.title }, aliases)
                .firstOrNull()
            if (best == null) {
                lastReason = "搜索无匹配"
                continue
            }
            val match = results[best.index]
            if (TitleMatcher.isJunkCandidate(targetTitle, match.title)) {
                lastReason = "杂项条目"
                continue
            }
            val detail = animeApi.getAnimeDetail(match.url, mode)
            val detailTitle = detail.title.ifBlank { match.title }
            if (TitleMatcher.isJunkCandidate(targetTitle, detailTitle)) {
                lastReason = "杂项条目"
                continue
            }
            val episodes = primaryEpisodes(detail).map { it.toEpisode() }
            if (episodes.isEmpty()) {
                lastReason = "详情无剧集"
                continue
            }
            // 详情标题复核：搜索标题通过不代表详情是同一条目（与 verifyCandidates 同口径）
            if (TitleMatcher.score(
                    target = targetTitle,
                    candidate = detailTitle,
                    aliases = aliases,
                    episodeCount = if (detail.totalEpisodes > 0) detail.totalEpisodes else episodes.size,
                ) == null
            ) {
                lastReason = "详情标题不符"
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

    /** 内置别名表 + 元数据别名，去重去空；内置表保证「航海王/海贼王」这类立刻可用 */
    private fun mergeAliases(title: String, aliases: List<String>): List<String> =
        (TitleAliases.forTitle(title) + aliases)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * 搜索关键词：交给 [SearchQueries] 按语言分流排序（中文标题 → 中文别名 → 拉丁别名 → 日文原名 → 其余），
     * 上限 [MAX_QUERIES] 条，避免一个源被大量别名搜索拖垮。
     * 别名同时会传给 [TitleMatcher.rankCandidates] 做候选校验，所以多数情况第一条就能命中。
     */
    private fun searchTitles(
        title: String,
        originalTitle: String,
        aliases: List<String>,
    ): List<String> =
        SearchQueries.build(title, originalTitle, aliases, max = MAX_QUERIES)

    /**
     * 按源区分查询词（2026-10-22 tv.9 切片 2）：采集源不吃日文原名（实验 0 命中），
     * 含假名的查询只发给 Agedm；其余源跳过并记日志，省请求。
     */
    private fun queriesFor(mode: SourceMode, queries: List<String>): List<String> {
        if (mode == SourceMode.Agedm) return queries
        return queries.filterNot { query ->
            val japanese = isJapaneseText(query)
            if (japanese) {
                "MATCH query skip jp mode=$mode query=$query".log("BehaviorTrace")
            }
            japanese
        }
    }

    /** 源内探测的累计状态：诊断计数、验证次数、剩余预算；只在单源协程内使用 */
    private class SourceProbeState(
        val mode: SourceMode,
        val aliases: List<String>,
        val expectedEpisodes: Int,
        private val deadlineAt: Long,
        val title: String = "",
        val stem: String? = null,
    ) {
        var queriesTried = 0
        var candidateCount = 0
        var verifications = 0
        var lastReason: String? = null
        var nearestCandidate: String? = null
        var rejectReason: String? = null

        /** 这个查询词属于哪一路：全名 / 主干名 / 别名 / 日文原名 / 其他（只用于日志） */
        fun viaOf(query: String): String = when {
            query == title -> "full"
            query == stem -> "main"
            query in aliases -> "alias"
            isJapaneseText(query) -> "jp"
            else -> "other"
        }

        private val verifiedUrls = mutableSetOf<String>()
        private var nearestIsTrusted = false

        fun budgetLeft(): Long = deadlineAt - System.currentTimeMillis()

        /**
         * 还能不能继续搜下一个关键词。三种情况再搜都是白花请求：
         * 预算见底、详情验证次数已用完、第 2 次验证前的预算不够。
         */
        fun canProbeMore(): Boolean = when {
            budgetLeft() <= 0 -> false
            verifications >= MAX_DETAIL_VERIFICATIONS -> false
            verifications > 0 && budgetLeft() < MIN_VERIFY_BUDGET_MS -> false
            else -> true
        }

        /** 同一个详情地址只验证一次；返回 false 表示这次跳过 */
        fun markVerified(url: String): Boolean = verifiedUrls.add(url)

        /** 更具体的原因覆盖旧值（详情无剧集 / 详情标题不符 / 异常） */
        fun setReason(reason: String) {
            lastReason = reason
        }

        /** 泛化原因只在还没有更具体的原因时记录 */
        fun noteGenericReason(reason: String) {
            if (lastReason == null) lastReason = reason
        }

        /** 记「最接近的候选」：可信但详情不合格的优先于完全不可信的 */
        fun noteReject(title: String, reason: String, trusted: Boolean) {
            if (nearestCandidate == null || (trusted && !nearestIsTrusted)) {
                nearestCandidate = title
                rejectReason = reason
                nearestIsTrusted = trusted
            }
        }

        fun diagnostics(): MatchDiagnostics = MatchDiagnostics(
            queriesTried = queriesTried,
            candidateCount = candidateCount,
            nearestCandidate = nearestCandidate,
            rejectReason = rejectReason,
        )
    }

    private class NoMatchingSourceException(
        message: String,
        val diagnostics: MatchDiagnostics? = null,
    ) : Exception(message)
}
