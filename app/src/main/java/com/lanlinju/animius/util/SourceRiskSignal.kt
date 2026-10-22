package com.lanlinju.animius.util

/**
 * 源站「风控」信号判定（纯函数，便于单测）。
 *
 * 只有 403/429/验证码/人机验证这类**站点主动拒绝**才算风控；「搜索无匹配」「详情无剧集」
 * 这类业务性失败（`NoMatchingSource`）不算——否则冷门番会把并发降级成串行，白白变慢。
 *
 * 用途：并发选源时连续遇到风控就自动降级（3 → 2 → 串行），见 `SourceResolver.resolveConcurrent`。
 */
object SourceRiskSignal {

    /** 风控类失败的关键字（小写比较）；命中任一即视为风控 */
    private val PATTERNS = listOf(
        "403",
        "429",
        "forbidden",
        "too many requests",
        "验证码",
        "人机验证",
        "captcha",
        "challenge",
    )

    /** 业务性「没有匹配到条目」的失败原因，永远不算风控 */
    private val BUSINESS_REASONS = listOf(
        "搜索无匹配",
        "详情无剧集",
        "没有匹配条目",
        "超时",
        "总预算耗尽",
        "标题为空",
    )

    fun isRiskControl(reason: String?): Boolean {
        val text = reason?.trim()?.lowercase().orEmpty()
        if (text.isEmpty()) return false
        if (BUSINESS_REASONS.any { it in text }) return false
        return PATTERNS.any { it in text }
    }
}
