package com.lanlinju.animius.data.remote.dto

import com.lanlinju.animius.domain.model.AnimeDetail

data class AnimeDetailBean(
    val title: String,
    val imgUrl: String,
    val desc: String,
    val tags: List<String> = emptyList(),
    val relatedAnimes: List<AnimeBean>,
    val episodes: List<EpisodeBean> = emptyList(),  /* 保持对旧的数据兼容, 如果支持多线路则需要置为空 */
    val channels: Map<Int, List<EpisodeBean>> = emptyMap(), /* 剧集多线路支持 */
    /** 站点声明的总集数（采集源 `vod_total`；番剧站没有该字段时为 0） */
    val totalEpisodes: Int = 0,
) {
    fun toAnimeDetail(): AnimeDetail {
        val tempChannels = if (episodes.isNotEmpty()) { /* 保持对旧的不支持多线路的兼容 */
            mapOf(0 to episodes.map { it.toEpisode() })
        } else {
            channels.mapValues { it.value.map { it.toEpisode() } }
        }
        // 多线路源的线路键不一定从 0 开始（采集源会丢掉「只有分享页」的线路，
        // 例：量子「闹鬼酒店 第二季」liangzi 线路全是 /share/ → 只剩 key=1 的 lzm3u8）。
        // 主剧集取第一个非空线路，channelIndex 也指向它，避免详情页「有线路、却无剧集」。
        val primaryKey = tempChannels.keys.sorted()
            .firstOrNull { tempChannels[it].orEmpty().isNotEmpty() }
        return AnimeDetail(
            title = title,
            img = imgUrl,
            desc = desc,
            tags = tags.map { it.uppercase() },
            lastPosition = 0,
            episodes = primaryKey?.let { tempChannels[it] }.orEmpty(),
            relatedAnimes = relatedAnimes.map { it.toAnime() },
            channelIndex = primaryKey ?: 0,
            channels = tempChannels,
        )
    }
}