package com.lanlinju.animius.bangumi

import com.lanlinju.animius.data.remote.bangumi.BangumiCalendarParser
import com.lanlinju.animius.data.remote.bangumi.CalendarFreshness
import com.lanlinju.animius.data.remote.bangumi.CalendarParseException
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BangumiCalendarParserTest {

    private val now = Instant.parse("2026-10-19T12:00:00Z")

    private fun item(
        id: Long = 1,
        name: String = "Name",
        nameCn: String = "",
        image: String = "data:image/webp;base64,AA==",
        airDate: String = "2026-10-05",
        summary: String = "",
        tags: List<String> = emptyList(),
        totalEpisodes: Int = 0,
        aliases: List<String> = emptyList(),
    ): String {
        val tagsJson = tags.joinToString(",") { "\"$it\"" }
        val aliasesJson = aliases.joinToString(",") { "\"$it\"" }
        return """{"id":$id,"name":"$name","name_cn":"$nameCn","image":"$image",""" +
            """"air_date":"$airDate","air_weekday":1,"rank":0,""" +
            """"summary":"$summary","tags":[$tagsJson],"total_episodes":$totalEpisodes,""" +
            """"aliases":[$aliasesJson]}"""
    }

    private fun days(items: Map<Int, List<String>> = emptyMap()): String =
        (1..7).joinToString(",") { weekday ->
            val list = items[weekday].orEmpty().joinToString(",")
            """{"weekday":$weekday,"weekday_cn":"d$weekday","items":[$list]}"""
        }

    private fun calendarJson(
        updatedAt: String = "2026-10-19T08:00:00Z",
        days: String = days(),
        schema: Int = 1,
    ): String =
        """{"schema":$schema,"updated_at":"$updatedAt","source":"test","days":[$days]}"""

    @Test
    fun `parses 7 days and maps weekday 1 to index 0`() {
        val raw = calendarJson(
            days = days(
                mapOf(
                    1 to listOf(item(id = 1, nameCn = "甲"), item(id = 2, nameCn = "乙")),
                    7 to listOf(item(id = 3, nameCn = "丙")),
                )
            )
        )

        val parsed = BangumiCalendarParser.parse(raw, now)

        assertEquals(Instant.parse("2026-10-19T08:00:00Z"), parsed.updatedAt)
        assertEquals((0..6).toSet(), parsed.days.keys)
        assertEquals(listOf("甲", "乙"), parsed.days.getValue(0).map { it.title })
        assertEquals(listOf("丙"), parsed.days.getValue(6).map { it.title })
        assertEquals(3, parsed.itemCount)
        assertEquals("2026-10-05", parsed.days.getValue(0).first().episodeName)
        assertEquals("data:image/webp;base64,AA==", parsed.days.getValue(0).first().img)
    }

    @Test
    fun `falls back to name when name_cn is blank and drops blank titles`() {
        val raw = calendarJson(
            days = days(
                mapOf(
                    1 to listOf(
                        item(id = 1, name = "Original", nameCn = ""),
                        item(id = 2, name = "", nameCn = "中文"),
                        item(id = 3, name = "", nameCn = ""),
                    )
                )
            )
        )

        val parsed = BangumiCalendarParser.parse(raw, now)

        assertEquals(listOf("Original", "中文"), parsed.days.getValue(0).map { it.title })
        assertEquals(2, parsed.itemCount)
    }

    @Test
    fun `missing optional fields use safe defaults`() {
        val raw = calendarJson(
            days = days(mapOf(1 to listOf("""{"id":9,"name":"OnlyName"}""")))
        )

        val parsed = BangumiCalendarParser.parse(raw, now)
        val anime = parsed.days.getValue(0).single()

        assertEquals("OnlyName", anime.title)
        assertEquals("", anime.img)
        assertEquals("", anime.episodeName)
        assertEquals("", anime.detailUrl)
    }

    @Test
    fun `keeps metadata id on list items and exposes detail fields by id`() {
        val raw = calendarJson(
            days = days(
                mapOf(
                    1 to listOf(
                        item(
                            id = 77,
                            name = "Original",
                            nameCn = "中文名",
                            summary = "简介",
                            tags = listOf("TV", "奇幻"),
                            totalEpisodes = 12,
                        )
                    )
                )
            )
        )

        val parsed = BangumiCalendarParser.parse(raw, now)
        val anime = parsed.days.getValue(0).single()
        val meta = parsed.items.getValue(77)

        assertEquals(77L, anime.metadataId)
        assertEquals("中文名", meta.title)
        assertEquals("Original", meta.originalTitle)
        assertEquals("简介", meta.summary)
        assertEquals(listOf("TV", "奇幻"), meta.tags)
        assertEquals(12, meta.totalEpisodes)
    }

    @Test
    fun `keeps aliases for source matching`() {
        val raw = calendarJson(
            days = days(
                mapOf(
                    1 to listOf(
                        item(
                            id = 975,
                            name = "ONE PIECE",
                            nameCn = "航海王",
                            aliases = listOf("海贼王", "ONE PIECE", " 海贼王 "),
                        )
                    )
                )
            )
        )

        val meta = BangumiCalendarParser.parse(raw, now).items.getValue(975)

        assertEquals(listOf("海贼王", "ONE PIECE"), meta.aliases)
    }

    @Test
    fun `old json without detail fields still parses`() {
        val raw = calendarJson(
            days = days(mapOf(1 to listOf("""{"id":9,"name":"OnlyName"}""")))
        )

        val parsed = BangumiCalendarParser.parse(raw, now)
        val meta = parsed.items.getValue(9)

        assertEquals("OnlyName", meta.title)
        assertEquals("", meta.summary)
        assertEquals(emptyList<String>(), meta.tags)
        assertEquals(0, meta.totalEpisodes)
        assertEquals(emptyList<String>(), meta.aliases)
    }

    @Test
    fun `rejects unsupported schema`() {
        assertThrows(CalendarParseException::class.java) {
            BangumiCalendarParser.parse(calendarJson(schema = 2), now)
        }
    }

    @Test
    fun `rejects non 7 day calendar`() {
        val sixDays = (1..6).joinToString(",") {
            """{"weekday":$it,"weekday_cn":"d$it","items":[]}"""
        }
        assertThrows(CalendarParseException::class.java) {
            BangumiCalendarParser.parse(calendarJson(days = sixDays), now)
        }
    }

    @Test
    fun `rejects invalid or duplicate weekday`() {
        val invalid = days().replace("\"weekday\":7", "\"weekday\":0")
        assertThrows(CalendarParseException::class.java) {
            BangumiCalendarParser.parse(calendarJson(days = invalid), now)
        }

        val duplicate = days().replace("\"weekday\":7", "\"weekday\":1")
        assertThrows(CalendarParseException::class.java) {
            BangumiCalendarParser.parse(calendarJson(days = duplicate), now)
        }
    }

    @Test
    fun `rejects invalid updated_at`() {
        assertThrows(CalendarParseException::class.java) {
            BangumiCalendarParser.parse(calendarJson(updatedAt = "not-a-date"), now)
        }
    }

    @Test
    fun `rejects corrupt json`() {
        assertThrows(CalendarParseException::class.java) {
            BangumiCalendarParser.parse("{not json", now)
        }
    }

    @Test
    fun `freshness boundaries are 24 hours and 7 days`() {
        assertEquals(
            CalendarFreshness.FRESH,
            BangumiCalendarParser.freshness(now.minus(Duration.ofHours(24)), now),
        )
        assertEquals(
            CalendarFreshness.STALE,
            BangumiCalendarParser.freshness(now.minus(Duration.ofHours(24).plusSeconds(1)), now),
        )
        assertEquals(
            CalendarFreshness.STALE,
            BangumiCalendarParser.freshness(now.minus(Duration.ofDays(7)), now),
        )
        assertEquals(
            CalendarFreshness.EXPIRED,
            BangumiCalendarParser.freshness(now.minus(Duration.ofDays(7).plusSeconds(1)), now),
        )
    }

    @Test
    fun `future timestamp is treated as fresh`() {
        assertEquals(
            CalendarFreshness.FRESH,
            BangumiCalendarParser.freshness(now.plus(Duration.ofHours(1)), now),
        )
    }
}
