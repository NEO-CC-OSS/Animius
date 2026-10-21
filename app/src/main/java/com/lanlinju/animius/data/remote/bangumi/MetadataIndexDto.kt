package com.lanlinju.animius.data.remote.bangumi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * metadata 分支上的 index.json（schema 2，轻包；由 .github/workflows/metadata.yml 生成）。
 *
 * 与旧 calendar.json 的差别：
 * - 封面不再内嵌 base64，改为独立文件 `covers/{id}.webp`，用 [MetadataIndexItemDto.cover] 相对路径
 *   + [MetadataIndexItemDto.coverHash]（内容哈希，拼 `?v=` 让 Coil 缓存失效）；
 * - 每张封面附 [MetadataIndexItemDto.lqip]（data URI 模糊图），加载中先显示；
 * - 其余字段与旧包一致（days / related_subjects / featured 全部保留）。
 *
 * 字段全部带默认值：缺字段的旧产物按空处理，不因个别字段缺失整包解析失败。
 */
@Serializable
data class MetadataIndexDto(
    val schema: Int = 0,
    /* 首页三行/相关推荐数据可用标记；旧产物没有该字段时为 0 */
    @SerialName("home_rows") val homeRows: Int = 0,
    @SerialName("updated_at") val updatedAt: String = "",
    val source: String = "",
    val days: List<MetadataIndexDayDto> = emptyList(),
    /* 只出现在相关推荐里的条目（周表没有），key 为 Bangumi id 字符串 */
    @SerialName("related_subjects") val relatedSubjects: Map<String, MetadataIndexItemDto> = emptyMap(),
    /* 首页「高分推荐」行（Bangumi 评分排名） */
    val featured: List<MetadataIndexItemDto> = emptyList(),
)

@Serializable
data class MetadataIndexDayDto(
    val weekday: Int = 0,
    @SerialName("weekday_cn") val weekdayCn: String = "",
    val items: List<MetadataIndexItemDto> = emptyList(),
)

@Serializable
data class MetadataIndexItemDto(
    val id: Long = 0,
    val name: String = "",
    @SerialName("name_cn") val nameCn: String = "",
    /* 封面相对路径，形如 covers/390200.webp；为空表示没有封面 */
    val cover: String = "",
    /* 封面内容哈希：拼成 ?v={cover_hash}，内容没变时命中缓存 */
    @SerialName("cover_hash") val coverHash: String = "",
    /* 32×45 WebP q40 的 data URI；加载中先显示，避免卡片空白 */
    val lqip: String = "",
    @SerialName("air_date") val airDate: String = "",
    @SerialName("air_weekday") val airWeekday: Int = 0,
    val rank: Int = 0,
    val summary: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("total_episodes") val totalEpisodes: Int = 0,
    /* Bangumi infobox 里的别名（海贼王这类） */
    val aliases: List<String> = emptyList(),
    /* Bangumi 相关条目 id（type=2，已排除自身） */
    val related: List<Long> = emptyList(),
)
