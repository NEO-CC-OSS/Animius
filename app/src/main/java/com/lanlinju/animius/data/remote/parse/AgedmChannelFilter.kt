package com.lanlinju.animius.data.remote.parse

import com.lanlinju.animius.data.remote.dto.EpisodeBean

/**
 * Age动漫的线路过滤（纯函数，便于单测）。
 *
 * 站点会把 bilibili 的 PV 单独放一个线路（实测 2026-10-20：`playlist-source-bilibili`
 * 只有一条 `pv`，后面三个 m3u8 线路各有完整集数）。`toAnimeDetail()` 默认取 key=0
 * 的线路作为集数列表，如果直接按 DOM 顺序编号，详情页就只剩一个 PV。
 *
 * 规则：丢弃空线路和「所有条目都是 PV/预告」的线路；其余线路按顺序重新编号（0..n）。
 */
internal fun buildAgedmChannels(raw: List<List<EpisodeBean>>): Map<Int, List<EpisodeBean>> {
    val channels = mutableMapOf<Int, List<EpisodeBean>>()
    raw.forEach { episodes ->
        val valid = episodes.filter { it.url.isNotBlank() }
        if (valid.isEmpty()) return@forEach
        if (valid.all { isPreviewEpisode(it.name) }) return@forEach
        channels[channels.size] = valid
    }
    return channels
}

internal fun isPreviewEpisode(name: String): Boolean {
    val normalized = name.trim().lowercase()
    return normalized == "pv" || normalized.startsWith("pv") || normalized.contains("预告")
}
