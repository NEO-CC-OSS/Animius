package com.lanlinju.animius.parse

import com.lanlinju.animius.data.remote.dto.EpisodeBean
import com.lanlinju.animius.data.remote.parse.buildAgedmChannels
import com.lanlinju.animius.data.remote.parse.isPreviewEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgedmChannelFilterTest {

    private fun ep(name: String, url: String = "/play/1/1/$name") = EpisodeBean(name, url)

    @Test
    fun `drops pv-only channel and reindexes real channels from zero`() {
        val channels = buildAgedmChannels(
            listOf(
                listOf(ep("pv")),
                (1..10).map { ep("第%02d集".format(it)) },
                (1..10).map { ep("第%02d集".format(it), "/play/1/3/$it") },
            )
        )

        assertEquals(setOf(0, 1), channels.keys)
        assertEquals(10, channels.getValue(0).size)
        assertEquals("第01集", channels.getValue(0).first().name)
    }

    @Test
    fun `all preview channels produce no episodes`() {
        val channels = buildAgedmChannels(
            listOf(
                listOf(ep("pv")),
                listOf(ep("PV2")),
                listOf(ep("预告")),
            )
        )

        assertTrue(channels.isEmpty())
    }

    @Test
    fun `keeps a single full episode that is not a preview`() {
        val channels = buildAgedmChannels(listOf(listOf(ep("全集"))))

        assertEquals(1, channels.size)
        assertEquals("全集", channels.getValue(0).single().name)
    }

    @Test
    fun `drops blank urls and empty channels`() {
        val channels = buildAgedmChannels(
            listOf(
                emptyList(),
                listOf(ep("第01集", url = "")),
                listOf(ep("第01集"), ep("第02集")),
            )
        )

        assertEquals(1, channels.size)
        assertEquals(2, channels.getValue(0).size)
    }

    @Test
    fun `preview detection is conservative`() {
        assertTrue(isPreviewEpisode("pv"))
        assertTrue(isPreviewEpisode(" PV "))
        assertTrue(isPreviewEpisode("pv2"))
        assertTrue(isPreviewEpisode("第1弹预告"))
        assertFalse(isPreviewEpisode("第01集"))
        assertFalse(isPreviewEpisode("全集"))
    }
}
