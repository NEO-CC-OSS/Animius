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
 * - related / related_subjects / featured 为切片 3 新增的可选字段；旧缓存同样按空处理
 */
@Serializable
data class BangumiCalendarDto(
    val schema: Int = 0,
    /* 切片 3 生成物标记：1 表示首页三行/相关推荐数据可用；旧缓存没有该字段时为 0 */
    @SerialName("home_rows") val homeRows: Int = 0,
    @SerialName("updated_at") val updatedAt: String = "",
    val source: String = "",
    val days: List<BangumiDayDto> = emptyList(),
    /* 只出现在相关推荐里的条目（周表没有），key 为 Bangumi id 字符串 */
    @SerialName("related_subjects") val relatedSubjects: Map<String, BangumiItemDto> = emptyMap(),
    /* 首页「高分推荐」行（Bangumi 评分排名） */
    val featured: List<BangumiItemDto> = emptyList(),
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
    /* Bangumi infobox 里的别名（海贼王这类）；旧缓存没有该字段时为空 */
    val aliases: List<String> = emptyList(),
    /* Bangumi 相关条目 id（type=2，已排除自身）；旧缓存没有该字段时为空 */
    val related: List<Long> = emptyList(),
)
