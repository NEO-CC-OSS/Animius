package com.lanlinju.animius.util

import com.lanlinju.animius.application.AnimeApplication

/**
 * 源站风控「软跳过」记忆（2026-10-23 批次 2，用户拍板）。
 *
 * 某个源最近被 403/验证码拦过，就把它在候选顺序里排到最后——**只影响排序、不封杀**：
 * - [SKIP_WINDOW_MS]（2 小时）后自动过期，重新当正常源；
 * - 当前源（用户选的）**不会被后置**，不受记忆影响；
 * - 手动换源/重新选集仍会试到它（只是排在后面，预算足够时才会轮到）。
 *
 * 记录时机：`SourceResolver` 在**单个源失败时即时**记录（不是等整轮失败），
 * 这样「当前源 403、兜底源顶上」这种最常见情况也能被记住。
 * 排序是纯函数 [order]，窗口判断是纯函数 [isWithinWindow]，都便于单测。
 */
object SourceRiskMemory {

    /** 软跳过窗口：覆盖一场番（约 2 小时）的观看时长，用户 2026-10-23 拍板 */
    const val SKIP_WINDOW_MS = 2 * 60 * 60 * 1000L

    private const val KEY_PREFIX = "source_risk_at_"

    /** 记录某个源刚被风控（403/验证码）；写失败不影响主流程 */
    fun mark(mode: SourceMode, now: Long = System.currentTimeMillis()) {
        runCatching {
            AnimeApplication.getInstance().preferences.edit()
                .putLong(KEY_PREFIX + mode.name, now)
                .apply()
        }
    }

    /** 该源是否仍在软跳过窗口内；读失败按「不在窗口」处理 */
    fun isRecent(mode: SourceMode, now: Long = System.currentTimeMillis()): Boolean {
        val at = runCatching {
            AnimeApplication.getInstance().preferences.getLong(KEY_PREFIX + mode.name, 0L)
        }.getOrDefault(0L)
        return isWithinWindow(at, now)
    }

    /**
     * 纯函数：时间戳 [at] 是否落在 [now] 的软跳过窗口内。
     * 边界：`at == 0`（从未记录）与「恰好 2 小时」都算过期；时钟回拨（`now < at`）视为不在窗口。
     */
    fun isWithinWindow(at: Long, now: Long): Boolean =
        at > 0L && now - at in 0L until SKIP_WINDOW_MS

    /**
     * 纯函数：把 [risked] 里的源排到最后，其余保持原顺序。
     *
     * [current] 是用户明确选的当前源：它在列表里时**不受风控记忆影响**（通常是第一位）；
     * 不在列表里时（例如换源时当前源已被 `exclude`）不特殊对待——否则兜底顺序第一个源
     * 哪怕刚被风控过也会被顶回第一。
     *
     * 只排序、不剔除——所有候选最终仍会被试到。
     */
    fun order(
        modes: List<SourceMode>,
        risked: Set<SourceMode>,
        current: SourceMode? = null,
    ): List<SourceMode> {
        if (modes.size <= 1 || risked.isEmpty()) return modes
        val (normal, recent) = modes.partition { it !in risked || it == current }
        return normal + recent
    }
}
