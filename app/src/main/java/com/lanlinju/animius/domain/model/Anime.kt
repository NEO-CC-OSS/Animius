package com.lanlinju.animius.domain.model

data class Anime(
    val title: String,
    val img: String,
    val detailUrl: String,
    val episodeName: String,
    /* 元数据条目才有：Bangumi subject id；源站条目为 0 */
    val metadataId: Long = 0,
)
