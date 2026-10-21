package com.lanlinju.animius.bangumi

import com.lanlinju.animius.data.remote.bangumi.MetadataIndexCache
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** index.json 本地缓存：读写往返、损坏自愈、超过 7 天视为不可用。 */
class MetadataIndexCacheTest {

    private val now = Instant.parse("2026-10-21T09:20:00Z")

    private fun indexJson(updatedAt: String): String {
        val days = (1..7).joinToString(",") { weekday ->
            val items = if (weekday == 1) {
                """{"id":1,"name":"A","name_cn":"甲","cover":"covers/1.webp","cover_hash":"h","lqip":"data:image/webp;base64,AA=="}"""
            } else {
                ""
            }
            """{"weekday":$weekday,"weekday_cn":"d$weekday","items":[$items]}"""
        }
        return """{"schema":2,"home_rows":1,"updated_at":"$updatedAt","source":"test","days":[$days]}"""
    }

    private fun newCache(): Pair<MetadataIndexCache, java.io.File> {
        val dir = Files.createTempDirectory("metadata-index-cache").toFile()
        return MetadataIndexCache(dir) to dir
    }

    @Test
    fun `writes and reads back index cache`() {
        val (cache, dir) = newCache()
        try {
            cache.write(indexJson("2026-10-21T08:53:19Z"))

            val entry = cache.read(now)

            assertNotNull(entry)
            assertEquals(1, entry!!.calendar.itemCount)
            assertEquals("甲", entry.calendar.days.getValue(0).single().title)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `corrupt cache is treated as missing`() {
        val (cache, dir) = newCache()
        try {
            cache.write("{not json")

            assertNull(cache.read(now))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `cache older than seven days is unavailable`() {
        val (cache, dir) = newCache()
        try {
            // 8 天前：EXPIRED，不能当兜底数据
            cache.write(indexJson("2026-10-13T08:53:19Z"))

            assertNull(cache.read(now))
        } finally {
            dir.deleteRecursively()
        }
    }
}
