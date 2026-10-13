package com.lanlinju.animius.dandanplay

import com.lanlinju.animius.data.remote.dandanplay.DanmakuMatchCache
import com.lanlinju.animius.data.remote.dandanplay.DanmakuMatchHeuristics
import com.lanlinju.animius.data.remote.dandanplay.dto.DandanplayMatchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 弹幕匹配启发式规则的单元测试。
 * 候选可信度用例取自 2026-10-13 对 /api/v2/match 的实测样本（不产生任何接口调用）。
 */
class DanmakuMatchHeuristicsTest {

    private fun candidate(anime: String, episode: String, id: Long = 1) =
        DandanplayMatchResult(episodeId = id, animeTitle = anime, episodeTitle = episode)

    // ---------- 集数归一化 ----------

    @Test
    fun testNormalizeEpisodeName_VariousSourceFormats() {
        assertEquals("01", DanmakuMatchHeuristics.normalizeEpisodeName("第01集"))
        assertEquals("01", DanmakuMatchHeuristics.normalizeEpisodeName("第01话"))
        assertEquals("1", DanmakuMatchHeuristics.normalizeEpisodeName("1"))
        assertEquals("01", DanmakuMatchHeuristics.normalizeEpisodeName("01v2"))
        assertEquals("01", DanmakuMatchHeuristics.normalizeEpisodeName("01.5"))
        assertEquals("01", DanmakuMatchHeuristics.normalizeEpisodeName("01-02"))
        assertEquals("movie", DanmakuMatchHeuristics.normalizeEpisodeName("剧场版"))
        assertEquals("movie", DanmakuMatchHeuristics.normalizeEpisodeName("全集"))
        assertNull(DanmakuMatchHeuristics.normalizeEpisodeName("上"))
    }

    @Test
    fun testCanonicalEpisode_LeadingZeros() {
        assertEquals("1", DanmakuMatchHeuristics.canonicalEpisode("01"))
        assertEquals("1", DanmakuMatchHeuristics.canonicalEpisode("1"))
        assertEquals("0", DanmakuMatchHeuristics.canonicalEpisode("0"))
        assertEquals("movie", DanmakuMatchHeuristics.canonicalEpisode("movie"))
    }

    @Test
    fun testBuildMatchFileName_Format() {
        assertEquals("葬送的芙莉莲 第01集", DanmakuMatchHeuristics.buildMatchFileName("葬送的芙莉莲", "01"))
        assertEquals("犬王 剧场版", DanmakuMatchHeuristics.buildMatchFileName("犬王", "movie"))
    }

    // ---------- 候选可信度（实测样本） ----------

    @Test
    fun testPickBestMatch_RescuesMismatchedTitle() {
        // 实测：search 完全失配，match 第一候选正确
        val result = DanmakuMatchHeuristics.pickBestMatch(
            "转学后班上的清纯可爱美少女，竟是小时候玩在一起的哥儿们", "01",
            listOf(candidate("转学后班上的清纯可爱美少女，竟是小时候玩在一起的哥儿们", "第1话 开始的夏日")),
        )
        assertNotNull(result)
    }

    @Test
    fun testPickBestMatch_MovieAlias() {
        // 实测：源站「平家物语：犬王」→ 弹弹play「犬王」
        val result = DanmakuMatchHeuristics.pickBestMatch(
            "平家物语：犬王", "movie", listOf(candidate("犬王", "剧场版")),
        )
        assertNotNull(result)
    }

    @Test
    fun testPickBestMatch_RejectsLongerCandidate() {
        // 实测：「进击的巨人」会匹配到「反击的狼烟」「第三季 前篇」等特定篇章 → 必须拒绝
        val result = DanmakuMatchHeuristics.pickBestMatch(
            "进击的巨人", "01",
            listOf(
                candidate("进击的巨人 反击的狼烟", "第1集 反撃の幕開け"),
                candidate("进击的巨人 第三季 前篇", "第1话 狼煙"),
            ),
        )
        assertNull(result)
    }

    @Test
    fun testPickBestMatch_RejectsUnrelated() {
        // 实测：「脑洞学生会」的候选全是无关番
        val result = DanmakuMatchHeuristics.pickBestMatch(
            "脑洞学生会", "01", listOf(candidate("青之洞门", "第1话 OVA")),
        )
        assertNull(result)
    }

    @Test
    fun testPickBestMatch_PrefersBaseSeason() {
        // 实测：候选 1 是基季、候选 2 是第二季；源站标题无季标时默认取基季
        val result = DanmakuMatchHeuristics.pickBestMatch(
            "葬送的芙莉莲", "01",
            listOf(
                candidate("葬送的芙莉莲", "第1话 冒险结束"),
                candidate("葬送的芙莉莲 第二季", "第1话"),
            ),
        )
        assertEquals("葬送的芙莉莲", result?.animeTitle)
    }

    @Test
    fun testPickBestMatch_LeadingZeroInsensitive() {
        // 源站「第01集」与弹弹play「第1话」是同一集
        val result = DanmakuMatchHeuristics.pickBestMatch(
            "乱马1/2第三季", "01", listOf(candidate("乱马1/2 第三季", "第1话 修行DEディナー")),
        )
        assertNotNull(result)
    }

    @Test
    fun testPickBestMatch_RejectsEpisodeMismatch() {
        val result = DanmakuMatchHeuristics.pickBestMatch(
            "葬送的芙莉莲", "01", listOf(candidate("葬送的芙莉莲", "第5话 别的东西")),
        )
        assertNull(result)
    }

    // ---------- 标题归一化与缓存 key ----------

    @Test
    fun testNormalizeTitle_IgnoresPunctuationAndSeason() {
        assertEquals(
            DanmakuMatchHeuristics.normalizeTitle("乱马1/2 第三季"),
            DanmakuMatchHeuristics.normalizeTitle("乱马1/2第三季"),
        )
        assertEquals("葬送的芙莉莲", DanmakuMatchHeuristics.normalizeTitle("葬送的芙莉莲 第二季"))
    }

    @Test
    fun testCacheKey_SharesAcrossSources() {
        // 同一部番不同源的写法差异被清洗抹平，共享同一条缓存
        val cache = DanmakuMatchCache()
        assertEquals(cache.keyOf("乱马1/2 第三季", "01"), cache.keyOf("乱马1/2第三季", "1"))
    }
}
