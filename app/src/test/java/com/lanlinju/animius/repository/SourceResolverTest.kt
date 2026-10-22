package com.lanlinju.animius.repository

import com.lanlinju.animius.data.remote.api.AnimeApi
import com.lanlinju.animius.data.remote.dto.AnimeBean
import com.lanlinju.animius.data.remote.dto.AnimeDetailBean
import com.lanlinju.animius.data.remote.dto.EpisodeBean
import com.lanlinju.animius.data.remote.dto.HomeBean
import com.lanlinju.animius.data.remote.dto.VideoBean
import com.lanlinju.animius.data.repository.EpisodeResolveResult
import com.lanlinju.animius.data.repository.SourceMatch
import com.lanlinju.animius.data.repository.SourceMatchStore
import com.lanlinju.animius.data.repository.SourceResolveResult
import com.lanlinju.animius.data.repository.SourceResolver
import com.lanlinju.animius.util.SourceMode
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceResolverTest {

    private val now: Instant = Instant.parse("2026-10-20T00:00:00Z")
    private val deadline: Long = Long.MAX_VALUE

    @Test
    fun `falls back to the next source when the current source has no match`() = runBlocking {
        val api = FakeAnimeApi(
            search = { mode, _ ->
                if (mode == SourceMode.Bf) {
                    listOf(AnimeBean("测试番", "", "https://silisili/1"))
                } else {
                    emptyList()
                }
            },
            detail = { _, url ->
                if (url == "https://silisili/1") detailBean("测试番", "第01集" to "ep1", "第02集" to "ep2")
                else throw IllegalStateException("no detail")
            },
        )
        val store = FakeStore()
        val resolver = SourceResolver(api, store)

        val result = resolver.resolve(
            title = "测试番",
            currentMode = SourceMode.Lz,
            now = now,
            deadlineMillis = deadline,
        )

        assertTrue(result is SourceResolveResult.Success)
        result as SourceResolveResult.Success
        assertEquals(SourceMode.Bf, result.source.mode)
        assertEquals(2, result.source.episodes.size)
        assertFalse(result.fromCache)
        assertEquals(listOf(SourceMode.Lz, SourceMode.Bf), api.searchCalls.map { it.first }.distinct())
        // 成功后写入缓存
        assertTrue(store.map.containsKey("title:测试番"))
    }

    @Test
    fun `network error in one source does not stop the fallback`() = runBlocking {
        val api = FakeAnimeApi(
            search = { mode, _ ->
                when (mode) {
                    SourceMode.Lz -> throw IllegalStateException("timeout")
                    SourceMode.Bf -> listOf(AnimeBean("测试番", "", "https://silisili/1"))
                    else -> emptyList()
                }
            },
            detail = { _, _ -> detailBean("测试番", "1" to "ep1") },
        )
        val result = SourceResolver(api, FakeStore()).resolve(
            title = "测试番",
            currentMode = SourceMode.Lz,
            now = now,
            deadlineMillis = deadline,
        )

        assertTrue(result is SourceResolveResult.Success)
        result as SourceResolveResult.Success
        assertEquals(SourceMode.Bf, result.source.mode)
    }

    @Test
    fun `returns failure with every attempt when no source matches`() = runBlocking {
        val api = FakeAnimeApi(search = { _, _ -> emptyList() })
        val result = SourceResolver(api, FakeStore()).resolve(
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
    fun `season mismatch is rejected even when the base title matches`() = runBlocking {
        val api = FakeAnimeApi(
            search = { _, _ -> listOf(AnimeBean("测试番", "", "https://x/1")) },
            detail = { _, _ -> detailBean("测试番", "1" to "ep1") },
        )
        val result = SourceResolver(api, FakeStore()).resolve(
            title = "测试番 第二季",
            currentMode = SourceMode.Lz,
            now = now,
            deadlineMillis = deadline,
        )

        assertTrue(result is SourceResolveResult.Failure)
    }

    @Test
    fun `cache hit skips search but still refreshes episodes`() = runBlocking {
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

        val result = SourceResolver(api, store).resolve(
            title = "测试番",
            metadataId = 42,
            currentMode = SourceMode.Lz,
            now = now,
            deadlineMillis = deadline,
        )

        assertTrue(result is SourceResolveResult.Success)
        result as SourceResolveResult.Success
        assertTrue(result.fromCache)
        assertEquals(SourceMode.Agedm, result.source.mode)
        assertEquals(1, result.source.episodes.size)
        assertTrue(api.searchCalls.isEmpty())
    }

    @Test
    fun `expired cache is ignored`() = runBlocking {
        val api = FakeAnimeApi(
            search = { _, _ -> listOf(AnimeBean("测试番", "", "https://fresh/1")) },
            detail = { _, _ -> detailBean("测试番", "1" to "ep1") },
        )
        val store = FakeStore().apply {
            map["id:42"] = SourceMatch(
                mode = SourceMode.Agedm,
                detailUrl = "https://stale/1",
                siteTitle = "测试番",
                savedAt = now.minus(Duration.ofDays(8)),
            )
        }

        val result = SourceResolver(api, store).resolve(
            title = "测试番",
            metadataId = 42,
            currentMode = SourceMode.Lz,
            now = now,
            deadlineMillis = deadline,
        )

        assertTrue(result is SourceResolveResult.Success)
        result as SourceResolveResult.Success
        assertFalse(result.fromCache)
        assertEquals("https://fresh/1", result.source.detailUrl)
    }

    @Test
    fun `episode switch picks the same episode number in another source`() = runBlocking {
        val api = FakeAnimeApi(
            search = { mode, _ ->
                if (mode == SourceMode.Bf) {
                    listOf(AnimeBean("测试番", "", "https://silisili/1"))
                } else {
                    emptyList()
                }
            },
            detail = { _, _ -> detailBean("测试番", "第01集" to "s1", "第02集" to "s2", "第03集" to "s3") },
            video = { _, url -> VideoBean("video:$url") },
        )

        val result = SourceResolver(api, FakeStore()).resolveEpisode(
            title = "测试番",
            episodeNumber = "2",
            currentMode = SourceMode.Lz,
            exclude = setOf(SourceMode.Lz),
            deadlineMillis = deadline,
        )

        assertTrue(result is EpisodeResolveResult.Success)
        result as EpisodeResolveResult.Success
        assertEquals(SourceMode.Bf, result.episode.mode)
        assertEquals(1, result.episode.episodeIndex)
        assertEquals("s2", result.episode.episodeUrl)
        assertEquals("video:s2", result.episode.videoUrl)
    }

    @Test
    fun `episode switch never picks a different episode`() = runBlocking {
        val api = FakeAnimeApi(
            search = { _, _ -> listOf(AnimeBean("测试番", "", "https://x/1")) },
            detail = { _, _ -> detailBean("测试番", "第01集" to "s1", "第03集" to "s3") },
            video = { _, url -> VideoBean("video:$url") },
        )

        val result = SourceResolver(api, FakeStore()).resolveEpisode(
            title = "测试番",
            episodeNumber = "2",
            currentMode = SourceMode.Lz,
            exclude = setOf(SourceMode.Lz),
            deadlineMillis = deadline,
        )

        assertTrue(result is EpisodeResolveResult.Failure)
        result as EpisodeResolveResult.Failure
        assertTrue(result.attempts.any { it.reason == "没有第2集" })
    }

    @Test
    fun `episode number formats are treated as the same episode`() = runBlocking {
        val api = FakeAnimeApi(
            search = { _, _ -> listOf(AnimeBean("测试番", "", "https://x/1")) },
            detail = { _, _ -> detailBean("测试番", "第1话" to "s1", "第2话" to "s2") },
            video = { _, url -> VideoBean("video:$url") },
        )

        val result = SourceResolver(api, FakeStore()).resolveEpisode(
            title = "测试番",
            episodeNumber = "2",
            currentMode = SourceMode.Lz,
            exclude = setOf(SourceMode.Lz),
            deadlineMillis = deadline,
        )

        assertTrue(result is EpisodeResolveResult.Success)
        result as EpisodeResolveResult.Success
        assertEquals("s2", result.episode.episodeUrl)
    }

    @Test
    fun `excluded sources are skipped`() = runBlocking {
        val api = FakeAnimeApi(
            search = { mode, _ ->
                if (mode == SourceMode.Agedm) {
                    listOf(AnimeBean("测试番", "", "https://agedm/1"))
                } else {
                    emptyList()
                }
            },
            detail = { _, _ -> detailBean("测试番", "1" to "ep1") },
        )

        val result = SourceResolver(api, FakeStore()).resolve(
            title = "测试番",
            currentMode = SourceMode.Lz,
            exclude = setOf(SourceMode.Lz, SourceMode.Bf),
            now = now,
            deadlineMillis = deadline,
        )

        assertTrue(result is SourceResolveResult.Success)
        result as SourceResolveResult.Success
        assertEquals(SourceMode.Agedm, result.source.mode)
        assertTrue(api.searchCalls.none { it.first == SourceMode.Lz || it.first == SourceMode.Bf })
    }

    @Test
    fun `exhausted overall budget fails without searching`() = runBlocking {
        val api = FakeAnimeApi(search = { _, _ -> throw AssertionError("should not search") })

        val result = SourceResolver(api, FakeStore()).resolve(
            title = "测试番",
            currentMode = SourceMode.Lz,
            now = now,
            deadlineMillis = 0L,
        )

        assertTrue(result is SourceResolveResult.Failure)
        result as SourceResolveResult.Failure
        assertTrue(result.attempts.any { it.reason == "总预算耗尽" })
        assertTrue(api.searchCalls.isEmpty())
    }

    @Test
    fun `falls back to the first non-empty channel when channel zero is empty`() = runBlocking {
        val api = FakeAnimeApi(
            search = { _, _ -> listOf(AnimeBean("测试番", "", "https://x/1")) },
            detail = { _, _ ->
                AnimeDetailBean(
                    title = "测试番",
                    imgUrl = "",
                    desc = "",
                    relatedAnimes = emptyList(),
                    episodes = emptyList(),
                    channels = mapOf(1 to listOf(EpisodeBean("第01集", "s1"))),
                )
            },
        )

        val result = SourceResolver(api, FakeStore()).resolve(
            title = "测试番",
            currentMode = SourceMode.Lz,
            now = now,
            deadlineMillis = deadline,
        )

        assertTrue(result is SourceResolveResult.Success)
        result as SourceResolveResult.Success
        assertEquals(1, result.source.episodes.size)
        assertEquals(1, result.source.detail.channelIndex)
    }

    @Test
    fun `candidate matching a built-in alias is accepted`() = runBlocking {
        val api = FakeAnimeApi(
            search = { _, query ->
                // 主标题搜索就返回了别名标题：靠别名校验直接命中，不需要再搜一次
                if (query == "航海王") {
                    listOf(AnimeBean("海贼王", "", "https://x/1"))
                } else {
                    emptyList()
                }
            },
            detail = { _, _ -> detailBean("海贼王", "第01集" to "ep1") },
        )

        val result = SourceResolver(api, FakeStore()).resolve(
            title = "航海王",
            currentMode = SourceMode.Lz,
            now = now,
            deadlineMillis = deadline,
        )

        assertTrue(result is SourceResolveResult.Success)
        result as SourceResolveResult.Success
        assertEquals("海贼王", result.source.siteTitle)
        assertEquals(listOf("航海王"), api.searchCalls.map { it.second })
    }

    @Test
    fun `alias query finds the show when the primary title does not`() = runBlocking {
        val api = FakeAnimeApi(
            search = { _, query ->
                if (query == "海贼王") {
                    listOf(AnimeBean("海贼王", "", "https://x/1"))
                } else {
                    emptyList()
                }
            },
            detail = { _, _ -> detailBean("海贼王", "第01集" to "ep1") },
        )

        val result = SourceResolver(api, FakeStore()).resolve(
            title = "航海王",
            currentMode = SourceMode.Lz,
            now = now,
            deadlineMillis = deadline,
        )

        assertTrue(result is SourceResolveResult.Success)
        result as SourceResolveResult.Success
        assertEquals("海贼王", result.source.siteTitle)
    }

    @Test
    fun `invalidate removes the cached match so the next resolve searches again`() = runBlocking {
        val api = FakeAnimeApi(
            search = { _, _ -> listOf(AnimeBean("测试番", "", "https://x/1")) },
            detail = { _, _ -> detailBean("测试番", "1" to "ep1") },
        )
        val store = FakeStore()
        val resolver = SourceResolver(api, store)

        resolver.resolve(
            title = "测试番",
            metadataId = 42,
            currentMode = SourceMode.Lz,
            now = now,
            deadlineMillis = deadline,
        )
        assertTrue(store.map.containsKey("id:42"))

        resolver.invalidate(42, "测试番")

        assertFalse(store.map.containsKey("id:42"))
    }

    @Test
    fun `remember writes the playable source for next time`() {
        val store = FakeStore()
        val resolver = SourceResolver(FakeAnimeApi(), store)

        resolver.remember(
            metadataId = 42,
            title = "测试番",
            mode = SourceMode.Bf,
            detailUrl = "https://x/1",
            siteTitle = "测试番",
        )

        val match = store.get("id:42", now)
        assertEquals(SourceMode.Bf, match?.mode)
        assertEquals("https://x/1", match?.detailUrl)
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
        private val search: (SourceMode, String) -> List<AnimeBean> = { _, _ -> emptyList() },
        private val detail: (SourceMode, String) -> AnimeDetailBean = { _, _ ->
            throw IllegalStateException("no detail")
        },
        private val video: (SourceMode, String) -> VideoBean = { _, _ ->
            throw IllegalStateException("no video")
        },
    ) : AnimeApi {
        val searchCalls = mutableListOf<Pair<SourceMode, String>>()

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
            return search(mode, query)
        }

        override suspend fun getWeekDate(): Map<Int, List<AnimeBean>> = emptyMap()
    }
}
