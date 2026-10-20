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

    fun merge(detail: AnimeDetail, meta: MetadataItem?): AnimeDetail {
        if (meta == null) return detail
        val related = if (meta.related.isNotEmpty()) {
            meta.related.map {
                Anime(
                    title = it.title,
                    img = it.image,
                    detailUrl = "",
                    episodeName = "",
                    metadataId = it.id,
                )
            }
        } else {
            detail.relatedAnimes
        }
        return detail.copy(
            title = meta.title.ifBlank { detail.title },
            img = meta.image.ifBlank { detail.img },
            desc = meta.summary.ifBlank { detail.desc },
            tags = meta.tags.ifEmpty { detail.tags },
            relatedAnimes = related,
        )
    }
}
