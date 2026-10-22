package com.lanlinju.animius.matching

import com.lanlinju.animius.domain.matching.TitleMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleMatcherScoreTest {

    @Test
    fun `exact title beats exact alias beats substring`() {
        val ranked = TitleMatcher.rankCandidates(
            target = "葬送的芙莉莲",
            candidates = listOf("芙莉莲", "Frieren", "葬送的芙莉莲"),
            aliases = listOf("Frieren", "Sousou no Frieren"),
        )

        assertEquals(listOf(2, 1, 0), ranked.map { it.index })
        // 精确归一化相等 50（精确命中不吃排名加分）
        assertEquals(50, ranked[0].score)
        // 别名精确 35（精确命中不吃排名加分）
        assertEquals(35, ranked[1].score)
        // 目标包含候选 (30-3) + 排名第 1(+5)
        assertEquals(32, ranked[2].score)
        assertTrue(ranked[0].score > ranked[1].score)
        assertTrue(ranked[1].score > ranked[2].score)
    }

    @Test
    fun `episode count match outranks near and far`() {
        val target = "葬送的芙莉莲"

        val exact = TitleMatcher.score(target, target, episodeCount = 28, expectedEpisodes = 28)
        val near = TitleMatcher.score(target, target, episodeCount = 27, expectedEpisodes = 28)
        val far = TitleMatcher.score(target, target, episodeCount = 12, expectedEpisodes = 28)

        assertEquals(65, exact!!)
        assertEquals(58, near!!)
        assertEquals(40, far!!)
        assertTrue(exact > near)
        assertTrue(near > far)
    }

    @Test
    fun `episode context never opens the gate`() {
        assertNull(
            TitleMatcher.score(
                target = "葬送的芙莉莲 第二季",
                candidate = "葬送的芙莉莲",
                episodeCount = 28,
                expectedEpisodes = 28,
            )
        )
    }

    @Test
    fun `exact chinese alias adds the chinese bonus`() {
        val score = TitleMatcher.score("航海王", "海贼王", listOf("海贼王"))

        assertEquals(45, score!!) // 别名精确 35 + 中文别名 10
    }

    @Test
    fun `search rank bonus only applies to non-exact candidates`() {
        val target = "葬送的芙莉莲"

        // 精确标题已是同类上限（50），排名不再加分：否则「中文别名排第 1」会压过「精确标题排第 4」
        assertEquals(50, TitleMatcher.score(target, target, rank = 1)!!)
        assertEquals(50, TitleMatcher.score(target, target, rank = 4)!!)

        // 子串候选仍吃排名加分：(30 − 5) + 排名第 1(+5) = 30；排第 4 则只有 25
        assertEquals(30, TitleMatcher.score("魔法少女小圆的奇幻冒险", "魔法少女小圆", rank = 1)!!)
        assertEquals(25, TitleMatcher.score("魔法少女小圆的奇幻冒险", "魔法少女小圆", rank = 4)!!)
    }

    /** 2026-10-22 独立审查 must-fix：中文别名排第 1 不能压过排第 4 的精确标题 */
    @Test
    fun `exact title outranks chinese alias even when the alias is ranked first`() {
        val ranked = TitleMatcher.rankCandidates(
            target = "航海王",
            candidates = listOf("海贼王", "航海王"),
            aliases = listOf("海贼王"),
        )

        assertEquals(1, ranked.first().index)
        assertEquals(50, ranked.first().score)
        assertTrue(ranked.first().score > ranked[1].score)
    }

    @Test
    fun `ranking agrees with pickBest when the exact title competes with a chinese alias`() {
        val candidates = listOf("海贼王", "航海王")
        val aliases = listOf("海贼王")

        val ranked = TitleMatcher.rankCandidates("航海王", candidates, aliases)

        assertEquals(TitleMatcher.pickBest("航海王", candidates, aliases), ranked.first().index)
    }

    @Test
    fun `equal scores keep the original search order`() {
        val ranked = TitleMatcher.rankCandidates(
            target = "魔法少女小圆的奇幻冒险",
            candidates = listOf("魔法少女小圆", "魔法少女小圆的奇幻"),
            aliases = emptyList(),
        )

        assertEquals(listOf(0, 1), ranked.map { it.index })
        assertEquals(30, ranked[0].score) // 30-5 + 排名第 1(+5)
        assertEquals(30, ranked[1].score) // 30-2 + 排名第 2(+2)
        assertEquals(ranked[0].score, ranked[1].score)
    }

    @Test
    fun `cross season, movie and suffix candidates are still rejected`() {
        val target = "葬送的芙莉莲"

        assertTrue(
            TitleMatcher.rankCandidates(
                target = target,
                candidates = listOf("葬送的芙莉莲 第二季", "葬送的芙莉莲 剧场版", "葬送的芙莉莲S"),
            ).isEmpty()
        )
        assertNull(TitleMatcher.score(target, "葬送的芙莉莲 第二季"))
        assertNull(TitleMatcher.score(target, "葬送的芙莉莲 剧场版"))
        assertNull(TitleMatcher.score(target, "葬送的芙莉莲S"))
        assertNull(TitleMatcher.score(target, "进击的巨人"))
    }

    @Test
    fun `alias of another season is still rejected`() {
        assertTrue(
            TitleMatcher.rankCandidates(
                target = "某番 第二季",
                candidates = listOf("某番"),
                aliases = listOf("某番"),
            ).isEmpty()
        )
    }

    @Test
    fun `untrusted candidates never show up in the ranking`() {
        val ranked = TitleMatcher.rankCandidates(
            target = "葬送的芙莉莲",
            candidates = listOf("鬼灭之刃", "葬送的芙莉莲 第二季"),
        )

        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `pickBest still prefers the exact title over an alias`() {
        // tv.7 行为不变：pickBest 不受打分影响
        assertEquals(
            1,
            TitleMatcher.pickBest("航海王", listOf("海贼王", "航海王"), listOf("海贼王")),
        )
    }
}
