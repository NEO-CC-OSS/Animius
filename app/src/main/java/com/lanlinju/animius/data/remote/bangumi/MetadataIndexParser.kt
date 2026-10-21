package com.lanlinju.animius.data.remote.bangumi

import com.lanlinju.animius.domain.model.Anime
import java.time.Instant
import kotlinx.serialization.json.Json

/**
 * index.json（schema 2，轻包）的纯解析逻辑。
 *
 * 不依赖 Android，方便 JVM 单测；网络、缓存与线程调度在 [MetadataIndexClient]。
 *
 * 与 [BangumiCalendarParser] 的关系：
 * - schema 2 只能由本解析器处理（旧解析器会拒绝，避免把轻包当旧包解析出空封面）；
 * - schema 1 的旧 calendar.json 继续由 [BangumiCalendarParser] 解析，作为本地缓存兜底。
 */
object MetadataIndexParser {

    const val SCHEMA = 2

    private const val MAX_RELATED = 6
    private const val MAX_TAGS = 10
    private const val MAX_ALIASES = 8

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * @throws CalendarParseException JSON 损坏、schema 不匹配、7 天结构不完整或 updated_at 非法
     */
    fun parse(raw: String, now: Instant = Instant.now()): BangumiCalendar {
        val dto = try {
            json.decodeFromString<MetadataIndexDto>(raw)
        } catch (e: Exception) {
            throw CalendarParseException("index JSON 解析失败: ${e.message}", e)
        }

        if (dto.schema != SCHEMA) {
            throw CalendarParseException("index schema 不支持: ${dto.schema}")
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

        // 封面索引：周表 + 相关推荐 + 高分推荐里的条目都可能被点进详情/预取
        val covers = buildMap {
            dto.days.forEach { day ->
                day.items.forEach { item -> item.toCover()?.let { put(item.id, it) } }
            }
            dto.relatedSubjects.values.forEach { item -> item.toCover()?.let { put(item.id, it) } }
            dto.featured.forEach { item -> item.toCover()?.let { put(item.id, it) } }
        }

        val calendarDtos = dto.days.asSequence()
            .flatMap { it.items.asSequence() }
            .filter { it.id > 0 }
            .associateBy { it.id }

        val calendarItems = calendarDtos.values
            .mapNotNull { it.toMetadataItem(covers[it.id]) }
            .associateBy { it.id }
        val relatedItems = dto.relatedSubjects.values
            .mapNotNull { it.toMetadataItem(covers[it.id]) }
            .associateBy { it.id }
        val featuredItems = dto.featured
            .mapNotNull { it.toMetadataItem(covers[it.id]) }
            .associateBy { it.id }

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
                        allItems[relatedId]?.let {
                            MetadataRelated(
                                id = it.id,
                                title = it.title,
                                image = it.image,
                                imageBackup = it.imageBackup,
                                imageFallback = it.imageFallback,
                                lqip = it.lqip,
                            )
                        }
                    }
                    .distinctBy { it.id }
                    .take(MAX_RELATED)
                    .toList()
                put(id, item.copy(related = related))
            }
        }

        val days = dto.days.associate { day ->
            val index = day.weekday - 1
            index to day.items.mapNotNull { it.toAnime(covers[it.id]) }
        }

        return BangumiCalendar(
            updatedAt = updatedAt,
            days = days,
            itemCount = days.values.sumOf { it.size },
            items = items,
            featured = dto.featured.mapNotNull { it.toAnime(covers[it.id]) }
                .filter { it.metadataId > 0 },
            supportsHomeRows = dto.homeRows >= 1,
            covers = covers,
        )
    }

    private fun MetadataIndexItemDto.toCover(): MetadataCover? {
        val path = cover.trim()
        if (id <= 0 || path.isEmpty()) return null
        return MetadataCover(path = path, hash = coverHash.trim(), lqip = lqip.trim())
    }

    private fun MetadataIndexItemDto.toMetadataItem(cover: MetadataCover?): MetadataItem? {
        val title = nameCn.ifBlank { name }.trim()
        if (id <= 0 || title.isEmpty()) return null
        return MetadataItem(
            id = id,
            title = title,
            originalTitle = name.trim(),
            image = cover?.primaryUrl.orEmpty(),
            summary = summary.trim(),
            tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_TAGS),
            totalEpisodes = totalEpisodes,
            aliases = aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_ALIASES),
            imageBackup = cover?.backupUrl.orEmpty(),
            imageFallback = cover?.fallbackUrl.orEmpty(),
            lqip = lqip.trim(),
        )
    }

    private fun MetadataIndexItemDto.toAnime(cover: MetadataCover?): Anime? {
        val title = nameCn.ifBlank { name }.trim()
        if (title.isEmpty()) return null
        return Anime(
            title = title,
            img = cover?.primaryUrl.orEmpty(),
            // 元数据条目没有源站详情地址；点击时按 id 进元数据详情页
            detailUrl = "",
            episodeName = airDate,
            metadataId = id,
            imgBackup = cover?.backupUrl.orEmpty(),
            imgFallback = cover?.fallbackUrl.orEmpty(),
            lqip = lqip.trim(),
        )
    }
}
