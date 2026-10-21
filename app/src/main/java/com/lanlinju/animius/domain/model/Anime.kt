package com.lanlinju.animius.domain.model

data class Anime(
    val title: String,
    val img: String,
    val detailUrl: String,
    val episodeName: String,
    /* 元数据条目才有：Bangumi subject id；源站条目为 0 */
    val metadataId: Long = 0,
    /* 元数据轻包（schema 2）：第二级回退（jsDelivr 备用域名）；源站条目为空 */
    val imgBackup: String = "",
    /* 元数据轻包（schema 2）：首选/备用封面都失败时的回退地址（gh-proxy）；源站条目为空 */
    val imgFallback: String = "",
    /* 元数据轻包（schema 2）：加载中的模糊占位图（data URI）；源站条目为空 */
    val lqip: String = "",
)
