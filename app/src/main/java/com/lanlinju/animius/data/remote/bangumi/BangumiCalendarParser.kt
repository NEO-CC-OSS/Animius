package com.lanlinju.animius.data.remote.bangumi

import com.lanlinju.animius.domain.model.Anime
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant

/**
 * calendar.json 的纯解析/校验逻辑。
 *
 * 不依赖 Android，方便 JVM 单测；网络、缓存与线程调度在 [BangumiCalendarClient]。
 */
object BangumiCalendarParser {

    const val SCHEMA = 1

    private const val FRESH_WINDOW_HOURS = 24L
    private const val MAX_USABLE_DAYS = 7L
    private const val MAX_RELATED = 6

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * @throws CalendarParseException JSON 损坏、schema 不匹配、7 天结构不完整或 updated_at 非法
     */
    fun parse(raw: String, now: Instant = Instant.now()): BangumiCalendar {
        val dto = try {
            json.decodeFromString<BangumiCalendarDto>(raw)
        } catch (e: Exception) {
            throw CalendarParseException("JSON 解析失败: ${e.message}", e)
        }

        if (dto.schema != SCHEMA) {
            throw CalendarParseException("schema 不支持: ${dto.schema}")
        }

        val updatedAt = try {
            Instant.parse(dto.updatedAt)
        } catch (e: Exception) {
            throw CalendarParseException("updated_at 非法: ${dto.updatedAt}", e)
        }

        if (dto.days.size != 7) {
            throw CalendarParseException("天数不是 7: ${dto.days.size}")
        }
        val weekdays = dto.days.map { it.weekday }
        if (weekdays.any { it !in 1..7 } || weekdays.toSet().size != 7) {
            throw CalendarParseException("weekday 非法: $weekdays")
        }

        val calendarDtos = dto.days.asSequence()
            .flatMap { it.items.asSequence() }
            .filter { it.id > 0 }
            .associateBy { it.id }

        val calendarItems = calendarDtos.values.mapNotNull { it.toMetadataItem() }.associateBy { it.id }
        val relatedItems = dto.relatedSubjects.values.mapNotNull { it.toMetadataItem() }.associateBy { it.id }
        val featuredItems = dto.featured.mapNotNull { it.toMetadataItem() }.associateBy { it.id }

        // 周表条目优先；相关/高分条目只补缺，保证点进详情时能按 id 取到标题/封面/简介。
        // 相关条目来自单条详情（带 infobox/别名），比高分列表更完整，放在 featured 之后覆盖。
        val allItems = buildMap {
            putAll(featuredItems)
            putAll(relatedItems)
            putAll(calendarItems)
        }

        val items = buildMap {
            putAll(allItems)
            // 周表条目带上相关推荐；找不到数据的 id 直接丢弃，避免出现空白卡片
            calendarItems.forEach { (id, item) ->
                val related = calendarDtos[id]?.related.orEmpty()
                    .asSequence()
                    .filter { it != id }
                    .mapNotNull { relatedId ->
                        allItems[relatedId]?.let { MetadataRelated(it.id, it.title, it.image) }
                    }
                    .distinctBy { it.id }
                    .take(MAX_RELATED)
                    .toList()
                put(id, item.copy(related = related))
            }
        }

        val days = dto.days.associate { day ->
            val index = day.weekday - 1
            index to day.items.mapNotNull { it.toAnime() }
        }

        return BangumiCalendar(
            updatedAt = updatedAt,
            days = days,
            itemCount = days.values.sumOf { it.size },
            items = items,
            featured = dto.featured.mapNotNull { it.toAnime() }.filter { it.metadataId > 0 },
            supportsHomeRows = dto.homeRows >= 1,
        )
    }

    /**
     * 24 小时内为 FRESH；1..7 天为 STALE；超过 7 天为 EXPIRED。
     * 时间戳在未来（设备时钟偏差）时按 FRESH 处理，避免把可用数据误判为过期。
     */
    fun freshness(updatedAt: Instant, now: Instant): CalendarFreshness {
        val age = Duration.between(updatedAt, now)
        return when {
            age.isNegative -> CalendarFreshness.FRESH
            age <= Duration.ofHours(FRESH_WINDOW_HOURS) -> CalendarFreshness.FRESH
            age <= Duration.ofDays(MAX_USABLE_DAYS) -> CalendarFreshness.STALE
            else -> CalendarFreshness.EXPIRED
        }
    }

    private fun BangumiItemDto.toMetadataItem(): MetadataItem? {
        val title = nameCn.ifBlank { name }.trim()
        if (id <= 0 || title.isEmpty()) return null
        return MetadataItem(
            id = id,
            title = title,
            originalTitle = name.trim(),
            image = image,
            summary = summary.trim(),
            tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.take(10),
            totalEpisodes = totalEpisodes,
            aliases = aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(8),
        )
    }

    private fun BangumiItemDto.toAnime(): Anime? {
        val title = nameCn.ifBlank { name }.trim()
        if (title.isEmpty()) return null
        return Anime(
            title = title,
            img = image,
            // 元数据条目没有源站详情地址；点击时按 id 进元数据详情页（切片 2）
            detailUrl = "",
            episodeName = airDate,
            metadataId = id,
        )
    }
}
