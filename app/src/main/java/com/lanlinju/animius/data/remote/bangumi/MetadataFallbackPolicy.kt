package com.lanlinju.animius.data.remote.bangumi

/**
 * index.json 网络全失败时的兜底决策（纯函数，便于 JVM 单测）。
 *
 * - 有本地缓存（7 天内）→ 用缓存，并把网络失败原因带在 `failureReason` 里（只用于日志）；
 * - 没有缓存 → 返回 Unavailable，由上层显示空态 + 重试（TV 不回退源站）。
 */
internal object MetadataFallbackPolicy {

    fun onNetworkFailure(
        cached: MetadataIndexCache.Entry?,
        reason: String,
    ): CalendarLoadResult = if (cached != null) {
        CalendarLoadResult.Success(
            calendar = cached.calendar,
            fromCache = true,
            freshness = cached.freshness,
            failureReason = reason,
        )
    } else {
        CalendarLoadResult.Unavailable(reason)
    }
}
