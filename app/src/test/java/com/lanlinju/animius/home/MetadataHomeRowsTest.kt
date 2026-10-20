package com.lanlinju.animius.home

import com.lanlinju.animius.data.remote.bangumi.BangumiCalendar
import com.lanlinju.animius.domain.model.Anime
import com.lanlinju.animius.presentation.screen.home.MetadataHomeRows
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataHomeRowsTest {

    private fun anime(id: Long, title: String = "A$id") =
        Anime(title = title, img = "img$id", detailUrl = "", episodeName = "", metadataId = id)

    private fun calendar(
        days: Map<Int, List<Anime>> = emptyMap(),
        featured: List<Anime> = emptyList(),
    ) = BangumiCalendar(
        updatedAt = Instant.EPOCH,
        days = days,
        itemCount = days.values.sumOf { it.size },
        featured = featured,
    )

    @Test
    fun `builds today week and featured rows in order`() {
        val rows = MetadataHomeRows.build(
            calendar = calendar(
                days = mapOf(
                    0 to listOf(anime(1), anime(2)),
                    1 to listOf(anime(3)),
                    2 to listOf(anime(1), anime(4)),
                ),
                featured = listOf(anime(3), anime(5), anime(6)),
            ),
            todayWeekday = 1,
        )

        assertEquals(
            listOf(MetadataHomeRows.TITLE_TODAY, MetadataHomeRows.TITLE_WEEK, MetadataHomeRows.TITLE_FEATURED),
            rows.map { it.title },
        )
        assertEquals(listOf(1L, 2L), rows[0].animeList.map { it.metadataId })
        assertEquals(listOf(3L, 4L), rows[1].animeList.map { it.metadataId })
        assertEquals(listOf(5L, 6L), rows[2].animeList.map { it.metadataId })
    }

    @Test
    fun `empty metadata returns no rows`() {
        assertTrue(MetadataHomeRows.build(calendar(), todayWeekday = 3).isEmpty())
    }

    @Test
    fun `week row caps at 40 and skips today`() {
        val days = (0..5).associateWith { day ->
            (1..10).map { index -> anime(day * 10L + index) }
        }
        val rows = MetadataHomeRows.build(calendar(days = days), todayWeekday = 7)

        assertEquals(listOf(MetadataHomeRows.TITLE_WEEK), rows.map { it.title })
        assertEquals(40, rows.single().animeList.size)
    }

    @Test
    fun `featured row caps at 30 and skips already shown ids`() {
        val featured = (1..40).map { anime(it.toLong()) }
        val rows = MetadataHomeRows.build(
            calendar = calendar(days = mapOf(0 to listOf(anime(1))), featured = featured),
            todayWeekday = 1,
        )

        val featuredRow = rows.single { it.title == MetadataHomeRows.TITLE_FEATURED }
        assertEquals(30, featuredRow.animeList.size)
        assertTrue(featuredRow.animeList.none { it.metadataId == 1L })
    }

    @Test
    fun `drops items without metadata id`() {
        val rows = MetadataHomeRows.build(
            calendar = calendar(days = mapOf(0 to listOf(anime(0), anime(1)))),
            todayWeekday = 1,
        )

        assertEquals(listOf(1L), rows.single().animeList.map { it.metadataId })
    }
}
