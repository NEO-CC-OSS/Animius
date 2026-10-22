package com.lanlinju.animius.matching

import com.lanlinju.animius.domain.matching.SearchQueries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueriesTest {

    @Test
    fun `chinese title first, chinese alias next, japanese original then latin alias`() {
        val queries = SearchQueries.build(
            title = "罗梅莉亚战记",
            originalTitle = "ロメリア戦記",
            aliases = listOf("Romelia Senki", "罗梅莉亚战记 第二季"),
        )

        assertEquals(
            listOf("罗梅莉亚战记", "罗梅莉亚战记 第二季", "ロメリア戦記", "Romelia Senki"),
            queries,
        )
    }

    @Test
    fun `japanese original title is never in the second slot`() {
        val queries = SearchQueries.build(
            title = "葬送的芙莉莲",
            originalTitle = "葬送のフリーレン",
            aliases = listOf("Frieren", "芙莉莲"),
        )

        assertEquals(
            listOf("葬送的芙莉莲", "芙莉莲", "葬送のフリーレン", "Frieren"),
            queries,
        )
    }

    @Test
    fun `duplicates and punctuation variants collapse`() {
        val queries = SearchQueries.build(
            title = "航海王",
            originalTitle = "航海王！",
            aliases = listOf("海贼王", "海贼王"),
        )

        assertEquals(listOf("航海王", "海贼王"), queries)
    }

    @Test
    fun `caps the query count at six by default`() {
        val aliases = listOf("别名一", "别名二", "别名三", "别名四", "别名五", "别名六", "别名七")
        val queries = SearchQueries.build(
            title = "主标题",
            originalTitle = "メインタイトル",
            aliases = aliases,
        )

        assertEquals(6, queries.size)
        assertEquals("主标题", queries.first())
        assertEquals("别名五", queries.last())
    }

    @Test
    fun `blank input yields no queries`() {
        assertTrue(SearchQueries.build("", "  ", emptyList()).isEmpty())
        assertTrue(SearchQueries.build("   ", "", listOf("", " ")).isEmpty())
        assertTrue(SearchQueries.build("葬送的芙莉莲", "葬送のフリーレン", emptyList(), max = 0).isEmpty())
    }

    @Test
    fun `japanese original comes before latin aliases`() {
        val queries = SearchQueries.build(
            title = "ロメリア戦記",
            originalTitle = "ロメリア戦記",
            aliases = listOf("Romelia Senki"),
        )

        // 拉丁别名实测 0 增益；6 条上限下宁可先丢拉丁别名，也不要把原名丢掉
        assertEquals(listOf("ロメリア戦記", "Romelia Senki"), queries)
    }

    @Test
    fun `chinese aliases keep their incoming order`() {
        val queries = SearchQueries.build(
            title = "某番",
            originalTitle = "Some Anime",
            aliases = listOf("某番 第二季", "某番外传"),
        )

        assertEquals(listOf("某番", "某番 第二季", "某番外传", "Some Anime"), queries)
    }
}
