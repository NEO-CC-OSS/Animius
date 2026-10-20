package com.lanlinju.animius.data.repository

import android.content.Context
import com.lanlinju.animius.util.SourceMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 一次成功的「元数据标题 → 源站详情」解析结果 */
data class SourceMatch(
    val mode: SourceMode,
    val detailUrl: String,
    val siteTitle: String,
    val savedAt: Instant,
)

/**
 * 解析结果缓存：只缓存「哪个源、哪个详情地址」，不缓存集数（集数会随更新变化）。
 * 7 天内再次进入同一部番时跳过搜索步骤。
 */
interface SourceMatchStore {
    fun get(key: String, now: Instant = Instant.now()): SourceMatch?
    fun put(key: String, match: SourceMatch)
    fun remove(key: String)
}

/**
 * 本地 JSON 实现（`filesDir/source_match.json`）。
 * 不写 Room：数据库已禁用破坏性迁移，为这点缓存加表/迁移不值得。
 */
@Singleton
class FileSourceMatchStore @Inject constructor(
    @ApplicationContext context: Context,
) : SourceMatchStore {

    companion object {
        private const val FILE_NAME = "source_match.json"
        private val TTL: Duration = Duration.ofDays(7)
        private const val MAX_ENTRIES = 300
    }

    @Serializable
    private data class Entry(
        val mode: String,
        val detailUrl: String,
        val siteTitle: String,
        val savedAt: String,
    )

    @Serializable
    private data class StoreDto(val entries: Map<String, Entry> = emptyMap())

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()
    private var entries: MutableMap<String, Entry>? = null

    override fun get(key: String, now: Instant): SourceMatch? = synchronized(lock) {
        val entry = load()[key] ?: return@synchronized null
        val mode = SourceMode.values().firstOrNull { it.name == entry.mode }
        val savedAt = runCatching { Instant.parse(entry.savedAt) }.getOrNull()
        if (mode == null || savedAt == null || Duration.between(savedAt, now) > TTL) {
            load().remove(key)
            persist()
            return@synchronized null
        }
        SourceMatch(mode, entry.detailUrl, entry.siteTitle, savedAt)
    }

    override fun put(key: String, match: SourceMatch) = synchronized(lock) {
        val map = load()
        map[key] = Entry(
            mode = match.mode.name,
            detailUrl = match.detailUrl,
            siteTitle = match.siteTitle,
            savedAt = match.savedAt.toString(),
        )
        if (map.size > MAX_ENTRIES) {
            // 简单清理：按保存时间淘汰最旧的一批
            map.entries
                .sortedBy { it.value.savedAt }
                .take(map.size - MAX_ENTRIES)
                .forEach { map.remove(it.key) }
        }
        persist()
    }

    override fun remove(key: String) = synchronized(lock) {
        if (load().remove(key) != null) persist()
    }

    private fun load(): MutableMap<String, Entry> = entries ?: run {
        val loaded = try {
            if (file.isFile) {
                json.decodeFromString<StoreDto>(file.readText()).entries.toMutableMap()
            } else {
                mutableMapOf()
            }
        } catch (_: Exception) {
            mutableMapOf()
        }
        entries = loaded
        loaded
    }

    private fun persist() {
        val snapshot = entries ?: return
        try {
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(StoreDto(snapshot.toMap())))
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }
        } catch (_: Exception) {
            // 缓存写失败不影响播放；下次重新解析即可
        }
    }
}
