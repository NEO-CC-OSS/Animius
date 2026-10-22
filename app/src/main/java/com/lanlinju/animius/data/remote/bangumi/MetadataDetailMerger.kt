package com.lanlinju.animius.data.remote.bangumi

import com.lanlinju.animius.domain.model.Anime
import com.lanlinju.animius.domain.model.AnimeDetail

/**
 * 元数据详情与源站详情的合并规则（纯函数，便于单测）。
 *
 * 标题/封面/简介/标签以元数据为准；元数据缺字段时退回源站值，避免详情页出现空白。
 * 剧集列表与历史进度始终来自源站详情（元数据没有可播放地址）。
 * 相关推荐优先用元数据；元数据没有（旧缓存/抓取失败）时保留源站相关推荐。
 */
object MetadataDetailMerger {

    /**
     * 骨架详情：只用本地元数据构造首帧内容，**不产生任何源站请求**。
     *
     * 剧集列表、线路、历史进度只能来自源站，骨架态一律为空/0（[AnimeDetail.lastPosition] = 0）。
     * 封面三级回退字段（主图/备用/回退/LQIP）原样带上，骨架态也能立刻显示封面。
     *
     * @param fallbackTitle 元数据标题为空时的兜底（路由标题），与 [merge] 的 `ifBlank` 口径一致，
     *   避免极低概率的空白标题让骨架页顶部空一块
     */
    fun skeleton(meta: MetadataItem, fallbackTitle: String = ""): AnimeDetail = AnimeDetail(
        title = meta.title.ifBlank { fallbackTitle },
        img = meta.image,
        desc = meta.summary,
        tags = meta.tags,
        lastPosition = 0,
        episodes = emptyList(),
        relatedAnimes = relatedOf(meta),
        channelIndex = 0,
        channels = emptyMap(),
        imgBackup = meta.imageBackup,
        imgFallback = meta.imageFallback,
        imgLqip = meta.lqip,
    )

    fun merge(detail: AnimeDetail, meta: MetadataItem?): AnimeDetail {
        if (meta == null) return detail
        val related = if (meta.related.isNotEmpty()) {
            relatedOf(meta)
        } else {
            detail.relatedAnimes
        }
        return detail.copy(
            title = meta.title.ifBlank { detail.title },
            img = meta.image.ifBlank { detail.img },
            desc = meta.summary.ifBlank { detail.desc },
            tags = meta.tags.ifEmpty { detail.tags },
            relatedAnimes = related,
            imgBackup = meta.imageBackup.ifBlank { detail.imgBackup },
            imgFallback = meta.imageFallback.ifBlank { detail.imgFallback },
            imgLqip = meta.lqip.ifBlank { detail.imgLqip },
        )
    }

    /** 元数据相关推荐 → 详情页卡片模型（点击按 metadataId 走元数据链路） */
    private fun relatedOf(meta: MetadataItem): List<Anime> = meta.related.map {
        Anime(
            title = it.title,
            img = it.image,
            detailUrl = "",
            episodeName = "",
            metadataId = it.id,
            imgBackup = it.imageBackup,
            imgFallback = it.imageFallback,
            lqip = it.lqip,
        )
    }
}
