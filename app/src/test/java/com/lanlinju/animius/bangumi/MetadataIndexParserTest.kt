package com.lanlinju.animius.bangumi

import com.lanlinju.animius.data.remote.bangumi.BangumiCalendarParser
import com.lanlinju.animius.data.remote.bangumi.CalendarParseException
import com.lanlinju.animius.data.remote.bangumi.MetadataCoverUrls
import com.lanlinju.animius.data.remote.bangumi.MetadataIndexParser
import com.lanlinju.animius.presentation.screen.home.MetadataHomeRows
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * index.json（schema 2，轻包）解析测试。
 *
 * 覆盖：7 天映射、封面地址组装（jsDelivr + ?v= / gh-proxy 回退）、
 * days + related_subjects + featured 合并查找表、相关推荐裁剪、
 * 空数据、损坏 JSON、旧 calendar.json（schema 1）兼容边界。
 */
class MetadataIndexParserTest {

    private val now = Instant.parse("2026-10-21T12:00:00Z")

    private fun item(
        id: Long,
        name: String = "Name$id",
        nameCn: String = "中文$id",
        cover: String = "covers/$id.webp",
        coverHash: String = "hash$id",
        lqip: String = "data:image/webp;base64,AA==",
        summary: String = "",
        tags: List<String> = emptyList(),
        related: List<Long> = emptyList(),
    ): String {
        val tagsJson = tags.joinToString(",") { "\"$it\"" }
        val relatedJson = related.joinToString(",")
        return """{"id":$id,"name":"$name","name_cn":"$nameCn","cover":"$cover",""" +
            """"cover_hash":"$coverHash","lqip":"$lqip","air_date":"2026-10-05",""" +
            """"air_weekday":1,"rank":0,"summary":"$summary","tags":[$tagsJson],""" +
            """"total_episodes":12,"aliases":[],"related":[$relatedJson]}"""
    }

    private fun days(items: Map<Int, List<String>> = emptyMap()): String =
        (1..7).joinToString(",") { weekday ->
            val list = items[weekday].orEmpty().joinToString(",")
            """{"weekday":$weekday,"weekday_cn":"d$weekday","items":[$list]}"""
        }

    private fun indexJson(
        updatedAt: String = "2026-10-21T08:00:00Z",
        days: String = days(),
        schema: Int = 2,
        homeRows: Int? = 1,
        relatedSubjects: String? = null,
        featured: String? = null,
    ): String = buildString {
        append("""{"schema":$schema,"updated_at":"$updatedAt","source":"test","days":[$days]""")
        if (homeRows != null) append(""","home_rows":$homeRows""")
        if (relatedSubjects != null) append(""","related_subjects":$relatedSubjects""")
        if (featured != null) append(""","featured":[$featured]""")
        append("}")
    }

    // ---------------------------------------------------------------- 基础解析

    @Test
    fun `parses seven days and keeps metadata id`() {
        val raw = indexJson(
            days = days(
                mapOf(
                    1 to listOf(item(390200, nameCn = "甲")),
                    7 to listOf(item(390201, nameCn = "乙")),
                )
            )
        )

        val parsed = MetadataIndexParser.parse(raw, now)

        assertEquals(Instant.parse("2026-10-21T08:00:00Z"), parsed.updatedAt)
        assertEquals((0..6).toSet(), parsed.days.keys)
        assertEquals(listOf("甲"), parsed.days.getValue(0).map { it.title })
        assertEquals(listOf("乙"), parsed.days.getValue(6).map { it.title })
        assertEquals(390200L, parsed.days.getValue(0).single().metadataId)
        assertEquals("2026-10-05", parsed.days.getValue(0).single().episodeName)
        assertEquals(2, parsed.itemCount)
        assertTrue(parsed.supportsHomeRows)
    }

    @Test
    fun `resolves cover urls with cache buster and mirror fallback`() {
        val raw = indexJson(days = days(mapOf(1 to listOf(item(390200)))))

        val parsed = MetadataIndexParser.parse(raw, now)
        val anime = parsed.days.getValue(0).single()
        val meta = parsed.items.getValue(390200)

        assertEquals(
            "https://gcore.jsdelivr.net/gh/NEO-CC-OSS/Animius@metadata/covers/390200.webp?v=hash390200",
            anime.img,
        )
        // 第二级：jsDelivr 备用域名（MediaSmall 的第一顺位回退）
        assertTrue(anime.imgBackup.startsWith("https://testingcf.jsdelivr.net/"))
        assertTrue(anime.imgBackup.contains("covers/390200.webp?v=hash390200"))
        // 第三级：gh-proxy
        assertTrue(anime.imgFallback.startsWith("https://gh-proxy.com/"))
        assertTrue(anime.imgFallback.contains("covers/390200.webp?v=hash390200"))
        assertEquals("data:image/webp;base64,AA==", anime.lqip)

        // 详情页拿到的封面与 LQIP 与列表一致
        assertEquals(anime.img, meta.image)
        assertEquals(anime.imgBackup, meta.imageBackup)
        assertEquals(anime.imgFallback, meta.imageFallback)
        assertEquals(anime.lqip, meta.lqip)

        // 封面索引也保留，供预取/排查使用；三级地址与 candidates() 完全一致
        val cover = parsed.covers.getValue(390200)
        assertEquals("covers/390200.webp", cover.path)
        assertEquals("hash390200", cover.hash)
        assertEquals(anime.img, cover.primaryUrl)
        assertEquals(anime.imgBackup, cover.backupUrl)
        assertEquals(anime.imgFallback, cover.fallbackUrl)
        assertEquals(
            listOf(anime.img, anime.imgBackup, anime.imgFallback),
            MetadataCoverUrls.candidates("covers/390200.webp", "hash390200"),
        )
    }

    @Test
    fun `blank cover yields empty urls and keeps lqip`() {
        val raw = indexJson(
            days = days(mapOf(1 to listOf(item(7, cover = "", coverHash = "", lqip = "data:image/webp;base64,BB=="))))
        )

        val anime = MetadataIndexParser.parse(raw, now).days.getValue(0).single()

        assertEquals("", anime.img)
        assertEquals("", anime.imgBackup)
        assertEquals("", anime.imgFallback)
        assertEquals("data:image/webp;base64,BB==", anime.lqip)
    }

    // ---------------------------------------------------------------- 合并查找表

    @Test
    fun `merges days related subjects and featured into one lookup table`() {
        val raw = indexJson(
            days = days(mapOf(1 to listOf(item(1, nameCn = "本番", related = listOf(10, 2, 999))))),
            relatedSubjects = """
                {
                  "10": ${item(10, nameCn = "相关A", summary = "简介A", tags = listOf("TV", "奇幻"))},
                  "2": ${item(2, nameCn = "本周番")}
                }
            """.trimIndent(),
            featured = item(20, nameCn = "高分", tags = listOf("剧场版")),
        )

        val parsed = MetadataIndexParser.parse(raw, now)

        // days + related_subjects + featured 都在同一个查找表里（详情页零等待取数）
        assertEquals(setOf(1L, 2L, 10L, 20L), parsed.items.keys)
        assertEquals("简介A", parsed.items.getValue(10).summary)
        assertEquals("高分", parsed.items.getValue(20).title)

        // 合并进 items 的条目字段必须完整（详情页只读这张表，缺字段会直接显示空白）
        val relatedMeta = parsed.items.getValue(10)
        assertEquals("相关A", relatedMeta.title)
        assertEquals(listOf("TV", "奇幻"), relatedMeta.tags)
        assertEquals(12, relatedMeta.totalEpisodes)
        assertEquals("data:image/webp;base64,AA==", relatedMeta.lqip)
        assertTrue(relatedMeta.image.startsWith("https://gcore.jsdelivr.net/"))
        assertTrue(relatedMeta.imageBackup.startsWith("https://testingcf.jsdelivr.net/"))
        assertTrue(relatedMeta.imageFallback.startsWith("https://gh-proxy.com/"))

        val featuredMeta = parsed.items.getValue(20)
        assertEquals("高分", featuredMeta.title)
        assertEquals(listOf("剧场版"), featuredMeta.tags)
        assertEquals(12, featuredMeta.totalEpisodes)
        assertEquals("data:image/webp;base64,AA==", featuredMeta.lqip)

        // 相关推荐按 id 映射到已解析条目，找不到的 id 直接丢弃
        assertEquals(listOf(10L, 2L), parsed.items.getValue(1).related.map { it.id })
        assertEquals("相关A", parsed.items.getValue(1).related.first().title)
        assertTrue(parsed.items.getValue(1).related.first().image.startsWith("https://gcore.jsdelivr.net/"))

        // 高分行只保留周表之外的条目
        assertEquals(listOf(20L), parsed.featured.map { it.metadataId })
    }

    @Test
    fun `related list excludes self and caps at six`() {
        val raw = indexJson(
            days = days(mapOf(1 to listOf(item(1, related = (1L..10L).toList())))),
            relatedSubjects = (2L..10L).joinToString(prefix = "{", postfix = "}") {
                """"$it":${item(it)}"""
            },
        )

        val related = MetadataIndexParser.parse(raw, now).items.getValue(1).related

        assertEquals(listOf(2L, 3L, 4L, 5L, 6L, 7L), related.map { it.id })
    }

    // ---------------------------------------------------------------- 首页三行

    @Test
    fun `builds home rows from parsed index without changing rules`() {
        val raw = indexJson(
            days = days(
                mapOf(
                    1 to listOf(item(1), item(2)),
                    2 to listOf(item(3)),
                )
            ),
            featured = (10L..12L).joinToString(",") { item(it) },
        )

        val parsed = MetadataIndexParser.parse(raw, now)
        val rows = MetadataHomeRows.build(calendar = parsed, todayWeekday = 1)

        assertEquals(
            listOf(MetadataHomeRows.TITLE_TODAY, MetadataHomeRows.TITLE_WEEK, MetadataHomeRows.TITLE_FEATURED),
            rows.map { it.title },
        )
        assertEquals(listOf(1L, 2L), rows[0].animeList.map { it.metadataId })
        assertEquals(listOf(3L), rows[1].animeList.map { it.metadataId })
        assertEquals(listOf(10L, 11L, 12L), rows[2].animeList.map { it.metadataId })
    }

    // ---------------------------------------------------------------- 空数据 / 失败

    @Test
    fun `empty index parses to empty calendar`() {
        val parsed = MetadataIndexParser.parse(indexJson(), now)

        assertEquals(0, parsed.itemCount)
        assertEquals((0..6).toSet(), parsed.days.keys)
        assertTrue(parsed.days.values.all { it.isEmpty() })
        assertTrue(parsed.items.isEmpty())
        assertTrue(parsed.featured.isEmpty())
        assertTrue(parsed.covers.isEmpty())
        assertTrue(MetadataHomeRows.build(parsed, todayWeekday = 1).isEmpty())
    }

    @Test
    fun `old index without home rows marker does not enable home rows`() {
        val parsed = MetadataIndexParser.parse(indexJson(homeRows = null), now)

        assertTrue(!parsed.supportsHomeRows)
    }

    @Test
    fun `rejects corrupt json`() {
        assertThrows(CalendarParseException::class.java) {
            MetadataIndexParser.parse("{not json", now)
        }
    }

    @Test
    fun `rejects legacy calendar schema`() {
        // schema 1 是旧的 calendar.json（内嵌 base64 封面）：不能当 index 解析，
        // 客户端必须走旧缓存兜底（BangumiCalendarParser）
        assertThrows(CalendarParseException::class.java) {
            MetadataIndexParser.parse(indexJson(schema = 1), now)
        }
    }

    @Test
    fun `legacy calendar json still parses and keeps base64 covers`() {
        val legacy = buildString {
            append("""{"schema":1,"home_rows":1,"updated_at":"2026-10-20T08:00:00Z","source":"test","days":[""")
            append(
                (1..7).joinToString(",") { weekday ->
                    val list = if (weekday == 1) {
                        """{"id":9,"name":"OnlyName","name_cn":"旧","image":"data:image/webp;base64,AA=="}"""
                    } else {
                        ""
                    }
                    """{"weekday":$weekday,"weekday_cn":"d$weekday","items":[$list]}"""
                }
            )
            append("]}")
        }

        val parsed = BangumiCalendarParser.parse(legacy, now)

        assertTrue(parsed.supportsHomeRows)
        assertEquals("data:image/webp;base64,AA==", parsed.days.getValue(0).single().img)
        // 旧缓存没有独立封面文件：图片走 base64 分支，LQIP/回退地址为空
        assertEquals("", parsed.days.getValue(0).single().lqip)
        assertEquals("", parsed.days.getValue(0).single().imgFallback)
    }

    @Test
    fun `rejects non seven day index and invalid timestamps`() {
        val sixDays = (1..6).joinToString(",") {
            """{"weekday":$it,"weekday_cn":"d$it","items":[]}"""
        }
        assertThrows(CalendarParseException::class.java) {
            MetadataIndexParser.parse(indexJson(days = sixDays), now)
        }
        assertThrows(CalendarParseException::class.java) {
            MetadataIndexParser.parse(indexJson(updatedAt = "not-a-date"), now)
        }
    }

    // ---------------------------------------------------------------- 封面地址

    @Test
    fun `cover candidates fall back from jsdelivr to gh proxy`() {
        val urls = MetadataCoverUrls.candidates("covers/1.webp", "abc")

        assertEquals(3, urls.size)
        assertEquals(
            "https://gcore.jsdelivr.net/gh/NEO-CC-OSS/Animius@metadata/covers/1.webp?v=abc",
            urls[0],
        )
        assertEquals(
            "https://testingcf.jsdelivr.net/gh/NEO-CC-OSS/Animius@metadata/covers/1.webp?v=abc",
            urls[1],
        )
        assertEquals(
            "https://gh-proxy.com/https://raw.githubusercontent.com/NEO-CC-OSS/Animius/metadata/covers/1.webp?v=abc",
            urls[2],
        )
        assertEquals(urls[0], MetadataCoverUrls.primary("covers/1.webp", "abc"))
        assertEquals(urls[2], MetadataCoverUrls.fallback("covers/1.webp", "abc"))
    }

    @Test
    fun `cover url trims leading slash and skips empty hash`() {
        assertEquals(
            "https://gcore.jsdelivr.net/gh/NEO-CC-OSS/Animius@metadata/covers/1.webp",
            MetadataCoverUrls.primary("/covers/1.webp", ""),
        )
        assertEquals("", MetadataCoverUrls.primary("", "abc"))
        assertTrue(MetadataCoverUrls.candidates("", "abc").isEmpty())
        // 非空返回类型不写 assertNotNull（空断言）；备用域名的拼接同样受 hash 影响
        assertEquals(
            "https://testingcf.jsdelivr.net/gh/NEO-CC-OSS/Animius@metadata/covers/1.webp?v=abc",
            MetadataCoverUrls.backup("covers/1.webp", "abc"),
        )
    }
}
