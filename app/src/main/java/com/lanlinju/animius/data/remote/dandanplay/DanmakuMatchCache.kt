package com.lanlinju.animius.data.remote.dandanplay

import com.lanlinju.animius.application.AnimeApplication
import com.lanlinju.animius.util.log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 弹幕匹配结果缓存：命中即零 API 调用（控制接口调用量的关键）。
 *
 * 存储用独立 JSON 文件而非 Room —— 项目数据库已禁用破坏性迁移，加表必须写 Migration，
 * 写错会导致用户升级崩溃；缓存数据本身可丢，不值得承担该风险。
 *
 * - 正结果缓存 [POSITIVE_TTL_MS]；负结果（已知在弹幕库中不存在）短期缓存 [NEGATIVE_TTL_MS]，
 *   避免失配的番每次播放都重新请求
 * - 容量上限 [MAX_ENTRIES] 条，超出按最久未更新淘汰
 * - 任何读写异常都不向上抛：缓存不可用只影响调用量，不影响弹幕功能本身
 */
@Singleton
class DanmakuMatchCache @Inject constructor() {

    @Serializable
    private data class Entry(
        /** null 表示该「番剧+集数」已知在弹幕库中不存在（负缓存） */
        val episodeId: Long? = null,
        val updatedAt: Long = 0L,
    )

    /** 查询结果：[episodeId] 为 null 表示已确认失配 */
    data class Lookup(val episodeId: Long?)

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var loaded = false
    private val entries = mutableMapOf<String, Entry>()

    private val cacheFile: File
        get() = File(AnimeApplication.getInstance().filesDir, CACHE_FILE_NAME)

    /** 查缓存；未命中返回 null */
    fun get(key: String): Lookup? = synchronized(lock) {
        ensureLoaded()
        val entry = entries[key] ?: return null
        val ttl = if (entry.episodeId == null) NEGATIVE_TTL_MS else POSITIVE_TTL_MS
        if (System.currentTimeMillis() - entry.updatedAt > ttl) {
            entries.remove(key)
            return null
        }
        Lookup(entry.episodeId)
    }

    /** 记录成功匹配 */
    fun putMatched(key: String, episodeId: Long) =
        put(key, Entry(episodeId, System.currentTimeMillis()))

    /** 记录已知失配（短期有效） */
    fun putNotFound(key: String) =
        put(key, Entry(null, System.currentTimeMillis()))

    /** 缓存 key：归一化番剧名 + 规范集数，使同一部番的不同源写法共享结果 */
    fun keyOf(subjectName: String, targetEpisode: String): String =
        DanmakuMatchHeuristics.normalizeTitle(subjectName) +
            "|" + DanmakuMatchHeuristics.canonicalEpisode(targetEpisode)

    private fun put(key: String, entry: Entry) = synchronized(lock) {
        ensureLoaded()
        entries[key] = entry
        evictIfNeeded()
        persist()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val file = cacheFile
            if (file.exists()) {
                entries.putAll(json.decodeFromString<Map<String, Entry>>(file.readText()))
            }
        } catch (e: Exception) {
            "弹幕匹配缓存读取失败: $e".log(TAG)
            entries.clear()
        }
    }

    private fun persist() {
        try {
            cacheFile.writeText(json.encodeToString(entries.toMap()))
        } catch (e: Exception) {
            "弹幕匹配缓存写入失败: $e".log(TAG)
        }
    }

    private fun evictIfNeeded() {
        if (entries.size <= MAX_ENTRIES) return
        entries.entries
            .sortedBy { it.value.updatedAt }
            .take(entries.size - MAX_ENTRIES)
            .map { it.key }
            .forEach { entries.remove(it) }
    }

    companion object {
        private const val TAG = "DanmakuCache"
        private const val CACHE_FILE_NAME = "danmaku_match_cache.json"
        private const val POSITIVE_TTL_MS = 90L * 24 * 60 * 60 * 1000
        private const val NEGATIVE_TTL_MS = 24L * 60 * 60 * 1000
        private const val MAX_ENTRIES = 500
    }
}
