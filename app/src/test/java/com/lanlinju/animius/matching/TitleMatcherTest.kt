package com.lanlinju.animius.matching

import com.lanlinju.animius.domain.matching.TitleMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleMatcherTest {

    @Test
    fun `exact title is trustworthy`() {
        assertTrue(TitleMatcher.isTrustworthy("葬送的芙莉莲", "葬送的芙莉莲"))
    }

    @Test
    fun `punctuation and spaces are ignored`() {
        assertTrue(TitleMatcher.isTrustworthy("葬送的芙莉莲", "葬送的芙莉莲！"))
        assertTrue(TitleMatcher.isTrustworthy("SPY×FAMILY", "SPY FAMILY"))
    }

    @Test
    fun `year in brackets is ignored`() {
        assertTrue(TitleMatcher.isTrustworthy("进击的巨人（2013）", "进击的巨人"))
    }

    @Test
    fun `explicit same season matches`() {
        assertTrue(TitleMatcher.isTrustworthy("葬送的芙莉莲 第二季", "葬送的芙莉莲第二季"))
        assertTrue(TitleMatcher.isTrustworthy("葬送的芙莉莲 Season 2", "葬送的芙莉莲 第二季"))
        assertTrue(TitleMatcher.isTrustworthy("葬送的芙莉莲 S2", "葬送的芙莉莲 第二季"))
    }

    @Test
    fun `target season 2 must not match a season-less candidate`() {
        assertFalse(TitleMatcher.isTrustworthy("葬送的芙莉莲 第二季", "葬送的芙莉莲"))
    }

    @Test
    fun `season-less target must not match a later season candidate`() {
        assertFalse(TitleMatcher.isTrustworthy("葬送的芙莉莲", "葬送的芙莉莲 第二季"))
    }

    @Test
    fun `season-less target accepts explicit season one`() {
        assertTrue(TitleMatcher.isTrustworthy("葬送的芙莉莲", "葬送的芙莉莲 第一季"))
    }

    @Test
    fun `roman numeral season is not mistaken for the base season`() {
        assertFalse(TitleMatcher.isTrustworthy("无职转生Ⅱ", "无职转生"))
        assertTrue(TitleMatcher.isTrustworthy("无职转生Ⅱ", "无职转生 第二季"))
    }

    @Test
    fun `final season is not mistaken for the base season`() {
        assertFalse(TitleMatcher.isTrustworthy("进击的巨人 最终季", "进击的巨人"))
        assertFalse(TitleMatcher.isTrustworthy("进击的巨人 最终季", "进击的巨人 第四季"))
        assertTrue(TitleMatcher.isTrustworthy("进击的巨人 最终季", "进击的巨人 最终季"))
    }

    @Test
    fun `single letter season suffix is not mistaken for the base season`() {
        assertFalse(TitleMatcher.isTrustworthy("某科学的超电磁炮S", "某科学的超电磁炮"))
        assertFalse(TitleMatcher.isTrustworthy("某科学的超电磁炮T", "某科学的超电磁炮"))
        assertTrue(TitleMatcher.isTrustworthy("某科学的超电磁炮T", "某科学的超电磁炮 T"))
    }

    @Test
    fun `final chapter is a movie marker and must not match final season`() {
        assertFalse(TitleMatcher.isTrustworthy("某番 最终章", "某番 最终季"))
        assertFalse(TitleMatcher.isTrustworthy("某番 最终章", "某番"))
        assertTrue(TitleMatcher.isTrustworthy("某番 最终章", "某番最终章"))
    }

    @Test
    fun `suffix candidate (series prefix in target) is still accepted`() {
        // 目标带系列前缀、候选是后缀：这是允许的降级匹配
        assertTrue(TitleMatcher.isTrustworthy("平家物语：犬王", "犬王"))
    }

    @Test
    fun `longer candidate is rejected`() {
        // 源站常见「进击的巨人 反击的狼烟」这类篇章名，接受会进错篇章
        assertFalse(TitleMatcher.isTrustworthy("进击的巨人", "进击的巨人 反击的狼烟"))
    }

    @Test
    fun `shorter candidate within threshold is accepted`() {
        assertTrue(TitleMatcher.isTrustworthy("平家物语：犬王", "犬王"))
    }

    @Test
    fun `shorter candidate beyond threshold is rejected`() {
        assertFalse(TitleMatcher.isTrustworthy("这是一个非常非常长的番剧标题", "标题"))
    }

    @Test
    fun `movie marker must match`() {
        assertFalse(TitleMatcher.isTrustworthy("某科学的超电磁炮 剧场版", "某科学的超电磁炮"))
        assertTrue(TitleMatcher.isTrustworthy("某科学的超电磁炮 剧场版", "某科学的超电磁炮剧场版"))
    }

    @Test
    fun `empty titles are never trustworthy`() {
        assertFalse(TitleMatcher.isTrustworthy("", "某番"))
        assertFalse(TitleMatcher.isTrustworthy("某番", ""))
        assertFalse(TitleMatcher.isTrustworthy("  ", "！"))
    }

    @Test
    fun `pickBest prefers exact normalized match`() {
        val candidates = listOf("葬送的芙莉莲 第二季", "葬送的芙莉莲", "芙莉莲")
        assertEquals(1, TitleMatcher.pickBest("葬送的芙莉莲", candidates))
    }

    @Test
    fun `pickBest returns null when nothing is trustworthy`() {
        assertEquals(null, TitleMatcher.pickBest("葬送的芙莉莲", listOf("进击的巨人", "鬼灭之刃")))
    }

    @Test
    fun `pickBest ignores candidates of another season`() {
        val candidates = listOf("葬送的芙莉莲 第二季", "葬送的芙莉莲 第三季")
        assertEquals(null, TitleMatcher.pickBest("葬送的芙莉莲 第四季", candidates))
    }

    @Test
    fun `pickBest handles empty candidate list`() {
        assertEquals(null, TitleMatcher.pickBest("葬送的芙莉莲", emptyList()))
    }
}
