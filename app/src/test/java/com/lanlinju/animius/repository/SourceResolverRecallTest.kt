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
import com.lanlinju.animius.util.SourceRiskSignal
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 匹配召回（tv.8 切片 3，用户 2026-10-22 拍板收窄后）：关键词顺序与上限、候选验证 fallback、
 * 每源详情验证上限、**源内探测串行**、失败诊断、预算耗尽、源间风控降级不变。
 *
 * 多页与源内并发已按实验结论移除（翻页 76 次零收益、源内并发 2 反而更慢），
 * 所以这里断言「只搜第 1 页」与「在飞请求 ≤1」。
 *
 * 复用既有 `SourceResolverTest` 的 Fake 形态（这里额外记录 page 与在飞并发）。
 */
class SourceResolverRecallTest {

    private val now: Instant = Instant.parse("2026-10-22T00:00:00Z")
    private val deadline: Long = Long.MAX_VALUE

    private val nonSkr = setOf(
        SourceMode.Silisili,
        SourceMode.Agedm,
        SourceMode.Jzacg,
        SourceMode.Girigiri,
        SourceMode.Gugufan,
    )
    private val nonSilisili = setOf(
        SourceMode.Skr,
        SourceMode.Agedm,
        SourceMode.Jzacg,
        SourceMode.Girigiri,
        SourceMode.Gugufan,
    )

    @Test
    fun `at least four queries are tried and chinese names come before the japanese one`() = runBlocking {
        val api = FakeAnimeApi(search = { _, _, _ -> emptyList() })

        val result = SourceResolver(api, FakeStore()).resolve(
            title = "测试番",
            originalTitle = "テストバン",
            aliases = listOf("测试别名", "Test Ban", "测试番 第二季"),
            currentMode = SourceMode.Skr,
            exclude = nonSkr,
            now = now,
            deadlineMillis = deadline,
        )

        assertTrue(result is SourceResolveResult.Failure)
        val queries = api.searchCalls.map { it.query }.distinct()
        assertTrue("只试了 ${queries.size} 个关键词：$queries", queries.size >= 4)
        // 串行按关键词表顺序发：中文标题/中文别名先试，日文原名不会排在前两位
        assertTrue("日文原名不该排在前两位：$queries", queries.take(2).none { "テスト" in it })
        assertTrue("6 条上限内应该也试到日文原名：$queries", queries.contains("テストバン"))
        // 不再翻页：所有探测都只取第 1 页
        assertEquals(listOf(1), api.searchCalls.map { it.page }.distinct())
    }

    @Test
    fun `second ranked candidate is verified when the first detail has no episodes`() = runBlocking {
        val api = FakeAnimeApi(
            search = { _, _, _ ->
                listOf(
                    AnimeBean("测试番", "", "https://x/1"),
                    AnimeBean("测试番", "", "https://x/2"),
                )
            },
            detail = { _, url ->
                when (url) {
                    // 同名不同条目：第一名（分数更高的原始顺序）详情没有剧集
                    "https://x/1" -> detailBean("测试番")
                    "https://x/2" -> detailBean("测试番", "第01集" to "e1")
                    else -> throw IllegalStateException("unexpected detail $url")
                }
            },
        )

        val result = SourceResolver(api, FakeStore()).resolve(
            title = "测试番",
            currentMode = SourceMode.Silisili,
            exclude = nonSilisili,
            now = now,
            deadlineMillis = deadline,
        )

        assertTrue(result is SourceResolveResult.Success)
        result as SourceResolveResult.Success
        assertEquals("https://x/2", result.source.detailUrl)
        assertEquals(listOf("https://x/1", "https://x/2"), api.detailCalls.toList())
        // 第 1 页就验出来了：只发过 1 次搜索，不再往后翻
        assertTrue("命中后不该继续搜：${api.searchCalls}", api.searchCalls.all { it.page == 1 })
        assertEquals(1, api.searchCalls.size)
    }

    @Test
    fun `a source verifies at most two details`() = runBlocking {
        val api = FakeAnimeApi(
            search = { _, _, _ -> (1..3).map { AnimeBean("测试番", "", "https://x/$it") } },
            detail = { _, _ -> detailBean("测试番") },
        )

        val result = SourceResolver(api, FakeStore()).resolve(
            title = "测试番",
            currentMode = SourceMode.Skr,
            exclude = nonSkr,
            now = now,
            deadlineMillis = deadline,
        )

        assertTrue(result is SourceResolveResult.Failure)
        result as SourceResolveResult.Failure
        assertEquals("每源最多 2 次详情验证：${api.detailCalls}", 2, api.detailCalls.size)
        assertEquals("详情无剧集", result.attempts.single().reason)
    }

    @Test
    fun `intra source probing is serial`() = runBlocking {
        val api = FakeAnimeApi(
            search = { _, _, _ ->
                delay(60)
                emptyList()
            },
        )

        val result = SourceResolver(api, FakeStore()).resolve(
            title = "测试番",
            originalTitle = "テストバン",
            aliases = listOf("测试别名", "Test Ban", "别名二", "别名三"),
            currentMode = SourceMode.Skr,
            exclude = nonSkr,
            now = now,
            deadlineMillis = deadline,
        )

        assertTrue(result is SourceResolveResult.Failure)
        assertEquals("源内探测必须串行（在飞请求 ≤1）", 1, api.maxInFlight.get())
        assertEquals(6, api.searchCalls.size)
    }

    @Test
    fun `every failed source carries structured diagnostics`() = runBlocking {
        val api = FakeAnimeApi(
            search = { _, _, _ -> listOf(AnimeBean("完全不同的番", "", "https://x/9")) },
        )

        val result = SourceResolver(api, FakeStore()).resolve(
            title = "测试番",
            currentMode = SourceMode.Skr,
            now = now,
            deadlineMillis = deadline,
        )

        assertTrue(result is SourceResolveResult.Failure)
        result as SourceResolveResult.Failure
        assertEquals(6, result.attempts.size)
        assertTrue("每个源都要有诊断", result.attempts.all { it.diagnostics != null })

        val first = result.attempts.first().diagnostics!!
        assertTrue(first.queriesTried >= 1)
        assertTrue(first.candidateCount >= 1)
        assertEquals("完全不同的番", first.nearestCandidate)
        assertEquals("候选不可信", first.rejectReason)
    }

    @Test
    fun `no second verification when the remaining budget is too small`() = runBlocking {
        val api = FakeAnimeApi(
            search = { _, _, _ ->
                listOf(
                    AnimeBean("测试番", "", "https://x/1"),
                    AnimeBean("测试番", "", "https://x/2"),
                )
            },
            detail = { _, _ -> detailBean("测试番") },
        )

        val result = SourceResolver(api, FakeStore()).resolve(
            title = "测试番",
            currentMode = SourceMode.Skr,
            exclude = nonSkr,
            now = now,
            deadlineMillis = System.currentTimeMillis() + 2_000,
        )

        assertTrue(result is SourceResolveResult.Failure)
        assertEquals("预算不足时不该验第 2 名：${api.detailCalls}", 1, api.detailCalls.size)
    }

    /** 2026-10-22 独立审查 must-fix：搜索请求本身抛异常时也要留诊断（否则 §5-5 无法判定） */
    @Test
    fun `search failure still leaves structured diagnostics`() = runBlocking {
        val api = FakeAnimeApi(
            search = { _, _, _ -> throw IllegalStateException("HTTP 403 Forbidden") },
        )

        val result = SourceResolver(api, FakeStore()).resolve(
            title = "测试番",
            currentMode = SourceMode.Skr,
            now = now,
            deadlineMillis = System.currentTimeMillis() + 5_000,
        )

        assertTrue(result is SourceResolveResult.Failure)
        result as SourceResolveResult.Failure
        val attempt = result.attempts.first()
        assertTrue(SourceRiskSignal.isRiskControl(attempt.reason))
        val diag = attempt.diagnostics
        assertNotNull("搜索失败也要带诊断", diag)
        assertEquals(1, diag!!.queriesTried)
        assertEquals("搜索请求失败", diag.rejectReason)
    }

    @Test
    fun `risk control failures still degrade concurrency`() = runBlocking {
        val api = FakeAnimeApi(
            search = { _, _, _ -> throw IllegalStateException("HTTP 403 Forbidden") },
        )
        val progress = Collections.synchronizedList(mutableListOf<ResolveProgress>())

        val result = SourceResolver(api, FakeStore()).resolveConcurrent(
            title = "测试番",
            currentMode = SourceMode.Skr,
            now = now,
            deadlineMillis = deadline,
            maxConcurrency = 3,
            onProgress = { progress += it },
        )

        assertTrue(result is SourceResolveResult.Failure)
        result as SourceResolveResult.Failure
        assertEquals(6, result.attempts.size)
        // 探测异常照旧整源失败，风控文本没被吞成「搜索无匹配」
        assertTrue(result.attempts.all { SourceRiskSignal.isRiskControl(it.reason) })
        assertEquals(listOf(3, 2, 1), progress.map { it.concurrency }.distinct())
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

    private data class SearchCall(val mode: SourceMode, val query: String, val page: Int)

    private class FakeAnimeApi(
        private val search: suspend (SourceMode, String, Int) -> List<AnimeBean> = { _, _, _ ->
            emptyList()
        },
        private val detail: suspend (SourceMode, String) -> AnimeDetailBean = { _, _ ->
            throw IllegalStateException("no detail")
        },
        private val video: suspend (SourceMode, String) -> VideoBean = { _, _ ->
            throw IllegalStateException("no video")
        },
    ) : AnimeApi {
        val searchCalls = CopyOnWriteArrayList<SearchCall>()
        val detailCalls = CopyOnWriteArrayList<String>()

        private val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)

        override suspend fun getHomeAllData(): List<HomeBean> = emptyList()

        override suspend fun getAnimeDetail(detailUrl: String, mode: SourceMode): AnimeDetailBean {
            detailCalls += detailUrl
            return detail(mode, detailUrl)
        }

        override suspend fun getVideoData(episodeUrl: String, mode: SourceMode): VideoBean =
            video(mode, episodeUrl)

        override suspend fun getSearchData(
            query: String,
            page: Int,
            mode: SourceMode,
        ): List<AnimeBean> {
            searchCalls += SearchCall(mode, query, page)
            val current = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, current) }
            try {
                return search(mode, query, page)
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override suspend fun getWeekDate(): Map<Int, List<AnimeBean>> = emptyMap()
    }
}
