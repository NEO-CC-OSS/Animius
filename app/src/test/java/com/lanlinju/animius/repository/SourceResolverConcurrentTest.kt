package com.lanlinju.animius.repository

import com.lanlinju.animius.data.remote.api.AnimeApi
import com.lanlinju.animius.data.remote.dto.AnimeBean
import com.lanlinju.animius.data.remote.dto.AnimeDetailBean
import com.lanlinju.animius.data.remote.dto.EpisodeBean
import com.lanlinju.animius.data.remote.dto.HomeBean
import com.lanlinju.animius.data.remote.dto.VideoBean
import com.lanlinju.animius.data.repository.ResolveProgress
import com.lanlinju.animius.data.repository.SourceMatch
import com.lanlinju.animius.data.repository.SourceMatchStore
import com.lanlinju.animius.data.repository.SourceResolveResult
import com.lanlinju.animius.data.repository.SourceResolver
import com.lanlinju.animius.util.SourceMode
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 并发选源（tv.8 切片 2）：先成功后取消其余、attempts 完整、缓存优先、在飞请求 ≤ 上限、
 * 风控降级。串行 [SourceResolver.resolve] 的行为仍由 `SourceResolverTest` 覆盖。
 */
class SourceResolverConcurrentTest {

    private val now: Instant = Instant.parse("2026-10-21T00:00:00Z")
    private val deadline: Long = Long.MAX_VALUE

    @Test
    fun `first success wins and the slower sources are cancelled`() = runBlocking {
        val slowFinished = AtomicBoolean(false)
        val api = FakeAnimeApi(
            search = { mode, _ ->
                when (mode) {
                    SourceMode.Bf -> {
                        delay(30)
                        listOf(AnimeBean("测试番", "", "https://silisili/1"))
                    }

                    else -> {
                        // 慢源：并发赢家出现后应被取消，走不到这一行之后
                        delay(3_000)
                        slowFinished.set(true)
                        emptyList()
                    }
                }
            },
            detail = { _, url ->
                if (url == "https://silisili/1") detailBean("测试番", "第01集" to "ep1")
                else throw IllegalStateException("unexpected detail $url")
            },
        )

        val result = SourceResolver(api, FakeStore()).resolveConcurrent(
            title = "测试番",
            currentMode = SourceMode.Lz,
            now = now,
            deadlineMillis = deadline,
        )

        assertTrue(result is SourceResolveResult.Success)
        result as SourceResolveResult.Success
        assertEquals(SourceMode.Bf, result.source.mode)
        assertFalse(result.fromCache)
        // 慢源被取消：不会跑完
        assertFalse(slowFinished.get())
    }

    @Test
    fun `all sources fail with a complete attempt list`() = runBlocking {
        val api = FakeAnimeApi(search = { _, _ -> emptyList() })

        val result = SourceResolver(api, FakeStore()).resolveConcurrent(
            title = "测试番",
            currentMode = SourceMode.Lz,
            now = now,
            deadlineMillis = deadline,
        )

        assertTrue(result is SourceResolveResult.Failure)
        result as SourceResolveResult.Failure
        assertEquals(5, result.attempts.size)
        assertTrue(result.attempts.all { it.reason == "搜索无匹配" })
    }

    @Test
    fun `cache hit stays on the serial fast path`() = runBlocking {
        val api = FakeAnimeApi(
            detail = { _, url ->
                if (url == "https://cached/1") detailBean("测试番", "第01集" to "ep1")
                else throw IllegalStateException("unexpected url")
            },
        )
        val store = FakeStore().apply {
            map["id:42"] = SourceMatch(
                mode = SourceMode.Agedm,
                detailUrl = "https://cached/1",
                siteTitle = "测试番",
                savedAt = now.minus(Duration.ofHours(2)),
            )
        }
        val progress = Collections.synchronizedList(mutableListOf<ResolveProgress>())

        val result = SourceResolver(api, store).resolveConcurrent(
            title = "测试番",
            metadataId = 42,
            currentMode = SourceMode.Lz,
            now = now,
            deadlineMillis = deadline,
            onProgress = { progress += it },
        )

        assertTrue(result is SourceResolveResult.Success)
        result as SourceResolveResult.Success
        assertTrue(result.fromCache)
        assertEquals(SourceMode.Agedm, result.source.mode)
        // 缓存命中不搜索、也不启动并发
        assertTrue(api.searchCalls.isEmpty())
        assertTrue(progress.isEmpty())
    }

    @Test
    fun `in flight requests never exceed the concurrency limit`() = runBlocking {
        val api = FakeAnimeApi(
            search = { _, _ ->
                delay(200)
                emptyList()
            },
        )

        val result = SourceResolver(api, FakeStore()).resolveConcurrent(
            title = "测试番",
            currentMode = SourceMode.Lz,
            now = now,
            deadlineMillis = deadline,
            maxConcurrency = 3,
        )

        assertTrue(result is SourceResolveResult.Failure)
        // 真的并发了（>1），且没超过上限
        assertEquals(3, api.maxInFlight.get())
    }

    @Test
    fun `risk control failures degrade concurrency step by step`() = runBlocking {
        val api = FakeAnimeApi(
            search = { _, _ -> throw IllegalStateException("HTTP 403 Forbidden") },
        )
        val progress = Collections.synchronizedList(mutableListOf<ResolveProgress>())

        val result = SourceResolver(api, FakeStore()).resolveConcurrent(
            title = "测试番",
            currentMode = SourceMode.Lz,
            now = now,
            deadlineMillis = deadline,
            maxConcurrency = 3,
            onProgress = { progress += it },
        )

        assertTrue(result is SourceResolveResult.Failure)
        result as SourceResolveResult.Failure
        assertEquals(5, result.attempts.size)
        // 3 → 2 → 1（串行）：连续风控后每批都降一档
        assertEquals(listOf(3, 2), progress.map { it.concurrency }.distinct())
    }

    @Test
    fun `no matching source is not a risk signal so concurrency is kept`() = runBlocking {
        val api = FakeAnimeApi(search = { _, _ -> emptyList() })
        val progress = Collections.synchronizedList(mutableListOf<ResolveProgress>())

        SourceResolver(api, FakeStore()).resolveConcurrent(
            title = "测试番",
            currentMode = SourceMode.Lz,
            now = now,
            deadlineMillis = deadline,
            maxConcurrency = 3,
            onProgress = { progress += it },
        )

        // 业务性失败（搜索无匹配）不触发降级
        assertEquals(listOf(3), progress.map { it.concurrency }.distinct())
    }

    /**
     * 降级口径：批内无成功且风控失败 ≥2 就累计，不再要求「整批全是风控」。
     * 2 个 403 混 1 个业务性失败也要降档（旧口径会清零，永远降不下来）。
     */
    @Test
    fun `two risk failures mixed with a business failure still degrade concurrency`() = runBlocking {
        val api = FakeAnimeApi(
            search = { mode, _ ->
                when (mode) {
                    SourceMode.Lz, SourceMode.Bf ->
                        throw IllegalStateException("HTTP 403 Forbidden")

                    else -> emptyList()
                }
            },
        )
        val progress = Collections.synchronizedList(mutableListOf<ResolveProgress>())

        val result = SourceResolver(api, FakeStore()).resolveConcurrent(
            title = "测试番",
            currentMode = SourceMode.Lz,
            now = now,
            deadlineMillis = deadline,
            maxConcurrency = 3,
            onProgress = { progress += it },
        )

        assertTrue(result is SourceResolveResult.Failure)
        result as SourceResolveResult.Failure
        assertEquals(5, result.attempts.size)
        // 第 1 批 2×403 + 1×无匹配 → 降到 2；第 2 批业务性失败不再降
        assertEquals(listOf(3, 2), progress.map { it.concurrency }.distinct())
    }

    /** 卡 §5「最坏 ≤20s」：并发路径的总预算必须收在 20s 内（串行仍用 45s） */
    @Test
    fun `concurrent overall budget stays within the twenty second bound`() {
        assertTrue(SourceResolver.CONCURRENT_OVERALL_TIMEOUT_MS <= 20_000L)
        assertTrue(SourceResolver.CONCURRENT_OVERALL_TIMEOUT_MS >= SourceResolver.PER_SOURCE_TIMEOUT_MS)
        // 串行路径的行为不变
        assertEquals(45_000L, SourceResolver.OVERALL_TIMEOUT_MS)
    }

    /** 总预算耗尽时不再逐个空等：未启动的源直接记「总预算耗尽」，attempts 仍完整 */
    @Test
    fun `deadline bounds the whole concurrent pass and reports untried sources`() = runBlocking {
        val api = FakeAnimeApi(search = { _, _ -> delay(5_000); emptyList() })
        val startedAt = System.currentTimeMillis()

        val result = SourceResolver(api, FakeStore()).resolveConcurrent(
            title = "测试番",
            currentMode = SourceMode.Lz,
            now = now,
            deadlineMillis = System.currentTimeMillis() + 600,
            maxConcurrency = 3,
        )

        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue(result is SourceResolveResult.Failure)
        result as SourceResolveResult.Failure
        assertEquals(5, result.attempts.size)
        assertTrue("耗时 $elapsed ms 超过预算", elapsed < 3_000)
    }

    /** 进度回调抛异常不能让 `receive()` 永久挂起（子任务必须在 finally 里回传结果） */
    @Test
    fun `a throwing progress callback cannot hang the batch`() = runBlocking {
        val api = FakeAnimeApi(search = { _, _ -> emptyList() })

        val result = withTimeoutOrNull(5_000) {
            SourceResolver(api, FakeStore()).resolveConcurrent(
                title = "测试番",
                currentMode = SourceMode.Lz,
                now = now,
                deadlineMillis = deadline,
                maxConcurrency = 3,
                onProgress = { throw IllegalStateException("progress boom") },
            )
        }

        assertNotNull("并发批次挂死（onProgress 抛异常后没有回传结果）", result)
        assertTrue(result is SourceResolveResult.Failure)
        result as SourceResolveResult.Failure
        assertEquals(5, result.attempts.size)
    }

    private fun detailBean(title: String, vararg episodes: Pair<String, String>) = AnimeDetailBean(
        title = title,
        imgUrl = "",
        desc = "",
        relatedAnimes = emptyList(),
        episodes = episodes.map { EpisodeBean(it.first, it.second) },
    )

    private class FakeStore : SourceMatchStore {
        val map = mutableMapOf<String, SourceMatch>()

        override fun get(key: String, now: Instant): SourceMatch? {
            val match = map[key] ?: return null
            return match.takeIf { Duration.between(it.savedAt, now) <= Duration.ofDays(7) }
        }

        override fun put(key: String, match: SourceMatch) {
            map[key] = match
        }

        override fun remove(key: String) {
            map.remove(key)
        }
    }

    private class FakeAnimeApi(
        private val search: suspend (SourceMode, String) -> List<AnimeBean> = { _, _ -> emptyList() },
        private val detail: suspend (SourceMode, String) -> AnimeDetailBean = { _, _ ->
            throw IllegalStateException("no detail")
        },
        private val video: suspend (SourceMode, String) -> VideoBean = { _, _ ->
            throw IllegalStateException("no video")
        },
    ) : AnimeApi {
        val searchCalls = CopyOnWriteArrayList<Pair<SourceMode, String>>()

        /** 当前在飞的搜索请求数，与历史峰值（验证并发上限） */
        private val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)

        override suspend fun getHomeAllData(): List<HomeBean> = emptyList()

        override suspend fun getAnimeDetail(detailUrl: String, mode: SourceMode): AnimeDetailBean =
            detail(mode, detailUrl)

        override suspend fun getVideoData(episodeUrl: String, mode: SourceMode): VideoBean =
            video(mode, episodeUrl)

        override suspend fun getSearchData(
            query: String,
            page: Int,
            mode: SourceMode,
        ): List<AnimeBean> {
            searchCalls += mode to query
            val current = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, current) }
            try {
                return search(mode, query)
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override suspend fun getWeekDate(): Map<Int, List<AnimeBean>> = emptyMap()
    }
}
