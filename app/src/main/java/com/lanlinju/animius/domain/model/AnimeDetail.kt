package com.lanlinju.animius.domain.model


data class AnimeDetail(
    val title: String,
    val img: String,
    val desc: String,
    val tags: List<String>,
    val lastPosition: Int,
    val episodes: List<Episode>,
    val relatedAnimes: List<Anime>,
    val channelIndex: Int = 0,
    val channels: Map<Int, List<Episode>> = emptyMap(),
    /* 元数据轻包（schema 2）：第二级回退（jsDelivr 备用域名）；源站详情为空 */
    val imgBackup: String = "",
    /* 元数据轻包（schema 2）：首选/备用封面都失败时的回退地址（gh-proxy）；源站详情为空 */
    val imgFallback: String = "",
    /* 元数据轻包（schema 2）：加载中的模糊占位图（data URI）；源站详情为空 */
    val imgLqip: String = "",
)
