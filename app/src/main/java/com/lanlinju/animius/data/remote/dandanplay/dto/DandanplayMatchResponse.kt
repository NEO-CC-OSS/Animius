package com.lanlinju.animius.data.remote.dandanplay.dto

import kotlinx.serialization.Serializable

/**
 * 弹弹play 文件识别（/api/v2/match）响应。
 *
 * 流媒体场景没有本地文件、无法计算真实文件哈希，请求以占位哈希 + fileNameOnly 模式发起，
 * 因此 [isMatched] 恒为 false（该字段仅在哈希精确命中时为 true），实际结果取自 [matches] 候选列表。
 */
@Serializable
data class DandanplayMatchResponse(
    val isMatched: Boolean = false,
    /** 服务端在参数错误等场景会返回 null，故为可空 */
    val matches: List<DandanplayMatchResult>? = null,
    val errorCode: Int = 0,
    val success: Boolean = false,
    val errorMessage: String? = null,
)

@Serializable
data class DandanplayMatchResult(
    val episodeId: Long = 0,
    val animeId: Long = 0,
    val animeTitle: String = "",
    val episodeTitle: String = "",
    val type: String? = null,
    val typeDescription: String? = null,
    val shift: Double? = null,
    val imageUrl: String? = null,
)

/**
 * 文件识别请求体。
 *
 * 注意两点（均由实测踩出）：
 * 1. `fileHash` 在官方文档中标注为可选，但实测缺失会返回「参数不符合规则」，故必须以 32 位占位哈希发起；
 *    `matchMode=fileNameOnly` 保证服务端只按文件名模糊匹配，占位哈希不会命中任何弹幕库。
 * 2. 这里**不能给字段设默认值**——kotlinx.serialization 默认 `encodeDefaults=false`，
 *    值等于默认值的字段会被直接省略，导致请求体缺字段被服务端拒绝。参数由调用方显式传入。
 */
@Serializable
data class DandanplayMatchRequest(
    val fileName: String,
    val fileHash: String,
    val matchMode: String,
) {
    companion object {
        const val PLACEHOLDER_FILE_HASH = "00000000000000000000000000000000"
        const val MATCH_MODE_FILE_NAME_ONLY = "fileNameOnly"
    }
}
