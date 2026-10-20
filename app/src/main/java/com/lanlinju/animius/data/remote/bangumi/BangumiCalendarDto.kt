package com.lanlinju.animius.data.remote.bangumi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * metadata 分支上的 calendar.json（由 .github/workflows/metadata.yml 生成）。
 *
 * 字段与真实 API 返回已对齐（2026-10-19 实测）：
 * - days[].weekday 为 Bangumi 的 1..7（周一..周日）
 * - items[].image 可能是 http(s) URL，也可能是内嵌的 data:image/webp;base64
 * - rank 在 /calendar 返回中不存在，生成端统一写 0
 * - summary / tags / total_episodes 为切片 2 新增的可选字段；旧缓存没有这些字段时按空处理
 */
@Serializable
data class BangumiCalendarDto(
    val schema: Int = 0,
    @SerialName("updated_at") val updatedAt: String = "",
    val source: String = "",
    val days: List<BangumiDayDto> = emptyList(),
)

@Serializable
data class BangumiDayDto(
    val weekday: Int = 0,
    @SerialName("weekday_cn") val weekdayCn: String = "",
    val items: List<BangumiItemDto> = emptyList(),
)

@Serializable
data class BangumiItemDto(
    val id: Long = 0,
    val name: String = "",
    @SerialName("name_cn") val nameCn: String = "",
    val image: String = "",
    @SerialName("air_date") val airDate: String = "",
    @SerialName("air_weekday") val airWeekday: Int = 0,
    val rank: Int = 0,
    val summary: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("total_episodes") val totalEpisodes: Int = 0,
)
