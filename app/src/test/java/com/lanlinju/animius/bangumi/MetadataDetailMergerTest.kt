package com.lanlinju.animius.bangumi

import com.lanlinju.animius.data.remote.bangumi.MetadataDetailMerger
import com.lanlinju.animius.data.remote.bangumi.MetadataItem
import com.lanlinju.animius.data.remote.bangumi.MetadataRelated
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
        // 元数据没有相关条目时保留源站相关推荐，避免整块空白
        assertEquals(sourceDetail.relatedAnimes, merged.relatedAnimes)
    }

    @Test
    fun `metadata related replaces source related`() {
        val relatedMeta = meta.copy(
            related = listOf(
                MetadataRelated(
                    id = 200,
                    title = "续作",
                    image = "data:image/webp;base64,BB==",
                ),
                MetadataRelated(
                    id = 201,
                    title = "前作",
                    image = "data:image/webp;base64,CC==",
                ),
            )
        )

        val merged = MetadataDetailMerger.merge(sourceDetail, relatedMeta)

        assertEquals(listOf("续作", "前作"), merged.relatedAnimes.map { it.title })
        assertEquals(listOf(200L, 201L), merged.relatedAnimes.map { it.metadataId })
        assertTrue(merged.relatedAnimes.all { it.detailUrl.isEmpty() })
        assertEquals("data:image/webp;base64,BB==", merged.relatedAnimes.first().img)
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
    fun `null metadata keeps source related recommendations`() {
        val merged = MetadataDetailMerger.merge(sourceDetail, null)

        assertEquals(sourceDetail.title, merged.title)
        assertEquals(sourceDetail.desc, merged.desc)
        assertEquals(sourceDetail.tags, merged.tags)
        assertEquals(sourceDetail.relatedAnimes, merged.relatedAnimes)
    }

    @Test
    fun `blank metadata title keeps the source title`() {
        val merged = MetadataDetailMerger.merge(sourceDetail, meta.copy(title = ""))

        assertEquals("源站标题", merged.title)
    }

    @Test
    fun `metadata cover fallbacks and lqip override source detail`() {
        val metaWithCovers = meta.copy(
            image = "https://gcore.example/cover.webp?v=1",
            imageBackup = "https://testingcf.example/cover.webp?v=1",
            imageFallback = "https://gh-proxy.example/cover.webp?v=1",
            lqip = "data:image/webp;base64,AA==",
            related = listOf(
                MetadataRelated(
                    id = 200,
                    title = "续作",
                    image = "https://gcore.example/r.webp?v=1",
                    imageBackup = "https://testingcf.example/r.webp?v=1",
                    imageFallback = "https://gh-proxy.example/r.webp?v=1",
                    lqip = "data:image/webp;base64,BB==",
                ),
            ),
        )

        val merged = MetadataDetailMerger.merge(sourceDetail, metaWithCovers)

        assertEquals("https://gcore.example/cover.webp?v=1", merged.img)
        assertEquals("https://testingcf.example/cover.webp?v=1", merged.imgBackup)
        assertEquals("https://gh-proxy.example/cover.webp?v=1", merged.imgFallback)
        assertEquals("data:image/webp;base64,AA==", merged.imgLqip)

        val related = merged.relatedAnimes.single()
        assertEquals(200L, related.metadataId)
        assertEquals("https://testingcf.example/r.webp?v=1", related.imgBackup)
        assertEquals("https://gh-proxy.example/r.webp?v=1", related.imgFallback)
        assertEquals("data:image/webp;base64,BB==", related.lqip)
    }

    @Test
    fun `missing metadata cover fallbacks keep source detail values`() {
        // 旧缓存/源站详情没有三级封面：不能被空串覆盖
        val sourceWithCovers = sourceDetail.copy(
            imgBackup = "https://source.example/backup.jpg",
            imgFallback = "https://source.example/fallback.jpg",
            imgLqip = "data:image/webp;base64,CC==",
        )

        val merged = MetadataDetailMerger.merge(sourceWithCovers, meta)

        assertEquals("https://source.example/backup.jpg", merged.imgBackup)
        assertEquals("https://source.example/fallback.jpg", merged.imgFallback)
        assertEquals("data:image/webp;base64,CC==", merged.imgLqip)
    }
}
