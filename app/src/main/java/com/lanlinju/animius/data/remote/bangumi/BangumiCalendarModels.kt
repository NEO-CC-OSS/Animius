package com.lanlinju.animius.data.remote.bangumi

import com.lanlinju.animius.domain.model.Anime
import java.time.Instant

/** 解析后的周表：key 为 0..6（周一..周日），与现有 TABS 顺序一致 */
data class BangumiCalendar(
    val updatedAt: Instant,
    val days: Map<Int, List<Anime>>,
    val itemCount: Int,
    /* 按 Bangumi subject id 索引的详情字段（切片 2）；旧 JSON 没有摘要时为默认值 */
    val items: Map<Long, MetadataItem> = emptyMap(),
)

/** 元数据条目的详情字段：周表卡片只需要标题/封面，详情页再取简介/标签 */
data class MetadataItem(
    val id: Long,
    val title: String,
    val originalTitle: String,
    val image: String,
    val summary: String,
    val tags: List<String>,
    val totalEpisodes: Int,
)

enum class CalendarFreshness {
    /** 24 小时内生成 */
    FRESH,

    /** 超过 24 小时但不超过 7 天：可用，但提示是旧数据 */
    STALE,

    /** 超过 7 天：按失败处理，回退源站 */
    EXPIRED,
}

class CalendarParseException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

sealed interface CalendarLoadResult {
    /**
     * 拿到了可用数据。
     *
     * @param fromCache 数据来自本地缓存（本次网络请求失败或未发起请求）
     * @param freshness 数据的新鲜度
     * @param failureReason 网络失败但仍用了旧缓存时的原因（仅用于日志/诊断，不直接展示给用户）
     */
    data class Success(
        val calendar: BangumiCalendar,
        val fromCache: Boolean,
        val freshness: CalendarFreshness,
        val failureReason: String? = null,
    ) : CalendarLoadResult

    /** 没有可用数据（无缓存、缓存过期或解析失败），调用方应回退到源站周表 */
    data class Unavailable(val reason: String) : CalendarLoadResult
}
