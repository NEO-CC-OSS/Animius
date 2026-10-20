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

        val days = dto.days.associate { day ->
            val index = day.weekday - 1
            index to day.items.mapNotNull { it.toAnime() }
        }

        return BangumiCalendar(
            updatedAt = updatedAt,
            days = days,
            itemCount = days.values.sumOf { it.size },
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

    private fun BangumiItemDto.toAnime(): Anime? {
        val title = nameCn.ifBlank { name }.trim()
        if (title.isEmpty()) return null
        return Anime(
            title = title,
            img = image,
            // 元数据条目没有源站详情地址；切片 1 的点击落点是搜索页（带标题预填）
            detailUrl = "",
            episodeName = airDate,
        )
    }
}
