package com.lanlinju.animius.data.remote.bangumi

import java.io.File
import java.time.Instant
import com.lanlinju.animius.util.log

/**
 * index.json 的本地缓存（纯文件逻辑，便于 JVM 单测）。
 *
 * - 24 h 内视为 FRESH；1–7 天 STALE 仍可用；超过 7 天 EXPIRED 视为不可用；
 * - 解析失败/文件损坏返回 null（自愈：下次网络成功会覆盖）。
 */
internal class MetadataIndexCache(private val dir: File) {

    data class Entry(
        val calendar: BangumiCalendar,
        val freshness: CalendarFreshness,
    )

    private val file: File
        get() = File(dir, CACHE_FILE)

    fun read(now: Instant = Instant.now()): Entry? {
        val target = file
        if (!target.isFile) return null
        return try {
            val calendar = MetadataIndexParser.parse(target.readText(), now)
            val freshness = BangumiCalendarParser.freshness(calendar.updatedAt, now)
            if (freshness == CalendarFreshness.EXPIRED) {
                "META index cache expired updated_at=${calendar.updatedAt}".log(TAG)
                null
            } else {
                Entry(calendar, freshness)
            }
        } catch (e: Exception) {
            "META index cache invalid: ${e.message}".log(TAG)
            null
        }
    }

    fun write(raw: String) {
        try {
            if (!dir.isDirectory && !dir.mkdirs()) {
                "META index cache mkdir failed: ${dir.absolutePath}".log(TAG)
                return
            }
            val target = file
            val tmp = File(dir, "$CACHE_FILE.tmp")
            tmp.writeText(raw)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            "META index cache write failed: ${e.message}".log(TAG)
        }
    }

    private companion object {
        const val TAG = "MetadataIndex"
        const val CACHE_FILE = "index.json"
    }
}
