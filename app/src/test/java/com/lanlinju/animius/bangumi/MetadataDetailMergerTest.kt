package com.lanlinju.animius.bangumi

import com.lanlinju.animius.data.remote.bangumi.MetadataDetailMerger
import com.lanlinju.animius.data.remote.bangumi.MetadataItem
import com.lanlinju.animius.domain.model.Anime
import com.lanlinju.animius.domain.model.AnimeDetail
import com.lanlinju.animius.domain.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataDetailMergerTest {

    private val sourceDetail = AnimeDetail(
        title = "源站标题",
        img = "https://source/cover.jpg",
        desc = "源站简介",
        tags = listOf("源站标签"),
        lastPosition = 2,
        episodes = listOf(Episode("第01集", "https://source/ep1")),
        relatedAnimes = listOf(Anime("相关", "", "https://source/related", "")),
    )

    private val meta = MetadataItem(
        id = 1,
        title = "元数据标题",
        originalTitle = "Original",
        image = "data:image/webp;base64,AA==",
        summary = "元数据简介",
        tags = listOf("TV", "奇幻"),
        totalEpisodes = 12,
    )

    @Test
    fun `metadata overrides title cover summary and tags`() {
        val merged = MetadataDetailMerger.merge(sourceDetail, meta)

        assertEquals("元数据标题", merged.title)
        assertEquals("data:image/webp;base64,AA==", merged.img)
        assertEquals("元数据简介", merged.desc)
        assertEquals(listOf("TV", "奇幻"), merged.tags)
        // 剧集与历史进度仍来自源站
        assertEquals(sourceDetail.episodes, merged.episodes)
        assertEquals(sourceDetail.channels, merged.channels)
        assertEquals(2, merged.lastPosition)
        assertTrue(merged.relatedAnimes.isEmpty())
    }

    @Test
    fun `missing metadata fields fall back to source values`() {
        val blankMeta = meta.copy(image = "", summary = "", tags = emptyList())

        val merged = MetadataDetailMerger.merge(sourceDetail, blankMeta)

        assertEquals("元数据标题", merged.title)
        assertEquals("https://source/cover.jpg", merged.img)
        assertEquals("源站简介", merged.desc)
        assertEquals(listOf("源站标签"), merged.tags)
    }

    @Test
    fun `null metadata only clears related recommendations`() {
        val merged = MetadataDetailMerger.merge(sourceDetail, null)

        assertEquals(sourceDetail.title, merged.title)
        assertEquals(sourceDetail.desc, merged.desc)
        assertEquals(sourceDetail.tags, merged.tags)
        assertTrue(merged.relatedAnimes.isEmpty())
    }

    @Test
    fun `blank metadata title keeps the source title`() {
        val merged = MetadataDetailMerger.merge(sourceDetail, meta.copy(title = ""))

        assertEquals("源站标题", merged.title)
    }
}
