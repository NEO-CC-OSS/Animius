package com.lanlinju.animius.repository

import com.lanlinju.animius.data.remote.api.AnimeApi
import com.lanlinju.animius.data.remote.dto.AnimeBean
import com.lanlinju.animius.data.remote.dto.AnimeDetailBean
import com.lanlinju.animius.data.remote.dto.HomeBean
import com.lanlinju.animius.data.remote.dto.VideoBean
import com.lanlinju.animius.data.repository.AggregateGroup
import com.lanlinju.animius.data.repository.AggregateSearch
import com.lanlinju.animius.util.SourceMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聚合搜索（tv.9 切片 3）：按源分组、失败源不阻塞其他源、先到先显示、空查询不请求。
 */
class AggregateSearchTest {

    private val sources = listOf(SourceMode.Lz, SourceMode.Bf, SourceMode.Md)

    @Test
    fun `groups results by source in candidate order`() = runBlocking {
        val api = FakeAnimeApi(
            search = { mode, _ ->
                when (mode) {
                    SourceMode.Lz -> listOf(AnimeBean("番A", "", "https://lz/1"))
                    SourceMode.Bf -> listOf(AnimeBean("番B", "", "https://bf/1"))
                    else -> emptyList()
                }
            },
        )

        val groups = AggregateSearch(api).search("测试番", sources)

        assertEquals(sources, groups.map { it.mode })
        assertEquals(listOf("番A"), groups[0].results.map { it.title })
        assertEquals(listOf("番B"), groups[1].results.map { it.title })
        assertTrue(groups[2].results.isEmpty())
        assertTrue(groups.all { it.error == null })
    }

    @Test
    fun `failing source does not block the others`() = runBlocking {
        val api = FakeAnimeApi(
            search = { mode, _ ->
                if (mode == SourceMode.Bf) throw IllegalStateException("HTTP 403 Forbidden")
                listOf(AnimeBean("番${mode.name}", "", "https://${mode.name}/1"))
            },
        )

        val groups = AggregateSearch(api).search("测试番", sources)

        assertEquals(3, groups.size)
        assertEquals("HTTP 403 Forbidden", groups.first { it.mode == SourceMode.Bf }.error)
        assertTrue(groups.filter { it.mode != SourceMode.Bf }.all { it.error == null })
    }

    @Test
    fun `onGroup is called once per source so results can show as they arrive`() = runBlocking {
        val api = FakeAnimeApi(search = { _, _ -> emptyList() })
        val seen = mutableListOf<AggregateGroup>()

        AggregateSearch(api).search("测试番", sources) { seen += it }

        assertEquals(sources.toSet(), seen.map { it.mode }.toSet())
        assertEquals(3, seen.size)
    }

    @Test
    fun `blank query does not request any source`() = runBlocking {
        val api = FakeAnimeApi()
        assertTrue(AggregateSearch(api).search("   ", sources).isEmpty())
        assertTrue(api.searchCalls.isEmpty())
    }

    private class FakeAnimeApi(
        private val search: suspend (SourceMode, String) -> List<AnimeBean> = { _, _ -> emptyList() },
    ) : AnimeApi {
        val searchCalls: MutableList<Pair<SourceMode, String>> =
            java.util.Collections.synchronizedList(mutableListOf())

        override suspend fun getHomeAllData(): List<HomeBean> = emptyList()

        override suspend fun getAnimeDetail(detailUrl: String, mode: SourceMode): AnimeDetailBean =
            throw IllegalStateException("unused")

        override suspend fun getVideoData(episodeUrl: String, mode: SourceMode): VideoBean =
            throw IllegalStateException("unused")

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
