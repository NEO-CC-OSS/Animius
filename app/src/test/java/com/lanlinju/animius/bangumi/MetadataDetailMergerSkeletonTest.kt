package com.lanlinju.animius.bangumi

import com.lanlinju.animius.data.remote.bangumi.MetadataDetailMerger
import com.lanlinju.animius.data.remote.bangumi.MetadataItem
import com.lanlinju.animius.data.remote.bangumi.MetadataRelated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 骨架详情（tv.8 切片 2）：只用本地元数据构造首帧内容，episodes 空、lastPosition 0。
 */
class MetadataDetailMergerSkeletonTest {

    @Test
    fun `skeleton exposes metadata fields and has no episodes`() {
        val skeleton = MetadataDetailMerger.skeleton(
            meta(
                title = "测试番",
                summary = "简介",
                tags = listOf("奇幻", "冒险"),
            )
        )

        assertEquals("测试番", skeleton.title)
        assertEquals("https://img/1.webp", skeleton.img)
        assertEquals("简介", skeleton.desc)
        assertEquals(listOf("奇幻", "冒险"), skeleton.tags)
        assertTrue(skeleton.episodes.isEmpty())
        assertEquals(0, skeleton.lastPosition)
        assertEquals(0, skeleton.channelIndex)
        assertTrue(skeleton.channels.isEmpty())
    }

    @Test
    fun `skeleton keeps the three level cover fallbacks`() {
        val skeleton = MetadataDetailMerger.skeleton(
            meta(
                imageBackup = "https://img/1-backup.webp",
                imageFallback = "https://img/1-fallback.webp",
                lqip = "data:image/webp;base64,AAAA",
            )
        )

        assertEquals("https://img/1-backup.webp", skeleton.imgBackup)
        assertEquals("https://img/1-fallback.webp", skeleton.imgFallback)
        assertEquals("data:image/webp;base64,AAAA", skeleton.imgLqip)
    }

    @Test
    fun `skeleton related comes from metadata and links by id`() {
        val skeleton = MetadataDetailMerger.skeleton(
            meta(
                related = listOf(
                    MetadataRelated(
                        id = 7,
                        title = "相关番",
                        image = "https://img/7.webp",
                        imageBackup = "https://img/7-backup.webp",
                        imageFallback = "https://img/7-fallback.webp",
                        lqip = "data:image/webp;base64,BBBB",
                    )
                )
            )
        )

        assertEquals(1, skeleton.relatedAnimes.size)
        val related = skeleton.relatedAnimes.first()
        assertEquals("相关番", related.title)
        assertEquals(7L, related.metadataId)
        assertEquals("https://img/7.webp", related.img)
        assertEquals("https://img/7-backup.webp", related.imgBackup)
        assertEquals("https://img/7-fallback.webp", related.imgFallback)
        assertEquals("data:image/webp;base64,BBBB", related.lqip)
        // 元数据相关推荐没有源站地址，点击走 metadataId 链路
        assertEquals("", related.detailUrl)
    }

    @Test
    fun `skeleton without related has an empty related list`() {
        val skeleton = MetadataDetailMerger.skeleton(meta())

        assertTrue(skeleton.relatedAnimes.isEmpty())
    }

    /** 与 merge 的 `ifBlank` 口径一致：元数据标题空白时用路由标题兜底，避免骨架页顶部空一块 */
    @Test
    fun `skeleton falls back to the route title when metadata title is blank`() {
        val skeleton = MetadataDetailMerger.skeleton(
            meta(title = ""),
            fallbackTitle = "路由标题",
        )

        assertEquals("路由标题", skeleton.title)
    }

    @Test
    fun `skeleton keeps the metadata title when it is not blank`() {
        val skeleton = MetadataDetailMerger.skeleton(
            meta(title = "元数据标题"),
            fallbackTitle = "路由标题",
        )

        assertEquals("元数据标题", skeleton.title)
    }

    private fun meta(
        title: String = "测试番",
        summary: String = "简介",
        tags: List<String> = emptyList(),
        related: List<MetadataRelated> = emptyList(),
        imageBackup: String = "",
        imageFallback: String = "",
        lqip: String = "",
    ) = MetadataItem(
        id = 42,
        title = title,
        originalTitle = "テスト",
        image = "https://img/1.webp",
        summary = summary,
        tags = tags,
        totalEpisodes = 12,
        related = related,
        imageBackup = imageBackup,
        imageFallback = imageFallback,
        lqip = lqip,
    )
}
