package com.lanlinju.animius.domain.model

import com.anime.danmaku.api.DanmakuSession

/**
 * 弹幕获取结果：带失败原因的密封结果，替代原先「一切异常吞成 null」的返回方式，
 * 让 UI 能区分「鉴权失败 / 没匹配到 / 网络问题 / 该集不支持」并提示用户。
 */
sealed class DanmakuResult {
    data class Success(val session: DanmakuSession) : DanmakuResult()

    /** AppId/AppSecret 无效或未申请（弹弹play errorCode「应用不存在」等） */
    data class AuthFailed(val message: String) : DanmakuResult()

    /** 番剧/集数未在弹弹play 弹幕库中匹配到，或该集暂无弹幕数据 */
    data class NotFound(val message: String) : DanmakuResult()

    /** 网络不可达、超时或服务器错误 */
    data class NetworkError(val message: String) : DanmakuResult()

    /** 剧集名无法归一化出集数（如 OVA/SP/纯「上」「下」），暂不支持自动匹配 */
    data class UnsupportedEpisode(val message: String) : DanmakuResult()
}
