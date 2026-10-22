package com.lanlinju.animius.parse

import com.lanlinju.animius.data.remote.dto.AnimeDetailBean
import com.lanlinju.animius.data.remote.dto.EpisodeBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多线路 → AnimeDetail 的映射（tv.9 验收反馈）：
 *
 * 采集源的线路键不一定从 0 开始（量子「闹鬼酒店 第二季」只有 key=1 的 lzm3u8 线路，
 * key=0 的 liangzi 线路全是分享页被丢掉）；主剧集必须取第一个非空线路，
 * 否则详情页会「有线路、无剧集」。
 */
class AnimeDetailBeanTest {

    @Test
    fun `first non empty channel becomes primary episodes`() {
        val bean = AnimeDetailBean(
            title = "闹鬼酒店 第二季",
            imgUrl = "",
            desc = "",
            relatedAnimes = emptyList(),
            episodes = emptyList(),
            channels = mapOf(
                1 to listOf(
                    EpisodeBean("第01集", "https://x/1.m3u8"),
                    EpisodeBean("第02集", "https://x/2.m3u8"),
                ),
            ),
        )

        val detail = bean.toAnimeDetail()
        assertEquals(2, detail.episodes.size)
        assertEquals(1, detail.channelIndex)
    }

    @Test
    fun `legacy single line episodes still map to channel zero`() {
        val bean = AnimeDetailBean(
            title = "测试番",
            imgUrl = "",
            desc = "",
            relatedAnimes = emptyList(),
            episodes = listOf(EpisodeBean("第01集", "https://x/1.m3u8")),
        )

        val detail = bean.toAnimeDetail()
        assertEquals(1, detail.episodes.size)
        assertEquals(0, detail.channelIndex)
    }

    @Test
    fun `all empty channels yields empty episodes and channel zero`() {
        val bean = AnimeDetailBean(
            title = "测试番",
            imgUrl = "",
            desc = "",
            relatedAnimes = emptyList(),
            episodes = emptyList(),
            channels = emptyMap(),
        )

        val detail = bean.toAnimeDetail()
        assertTrue(detail.episodes.isEmpty())
        assertEquals(0, detail.channelIndex)
    }
}
