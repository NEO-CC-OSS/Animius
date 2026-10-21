package com.lanlinju.animius.data.remote.bangumi

import android.content.Context
import com.lanlinju.animius.util.GITHUB_MIRROR_PREFIXES
import com.lanlinju.animius.util.log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 周表元数据客户端：从 GitHub raw / gh-proxy 镜像读取 calendar.json，并做本地缓存。
 *
 * 通道顺序与检查更新一致：直连优先（短超时），失败后走 [GITHUB_MIRROR_PREFIXES]。
 * 缓存策略：
 * - 24 小时内：直接用缓存，不发请求；
 * - 24 小时..7 天：先尝试网络，失败时仍可用旧缓存，并向上层标记 fromCache/failureReason；
 * - 超过 7 天或解析失败：视为不可用，由调用方回退到源站周表。
 */
@Singleton
class BangumiCalendarClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
) {

    companion object {
        const val METADATA_URL =
            "https://raw.githubusercontent.com/NEO-CC-OSS/Animius/metadata/calendar.json"

        private const val TAG = "BangumiCalendar"
        private const val CACHE_DIR = "metadata"
        private const val CACHE_FILE = "calendar.json"

        /** 直连 GitHub 的等待上限，和更新检查保持一致：连不上就尽快切镜像 */
        private const val DIRECT_CONNECT_TIMEOUT_MS = 6_000L
        private const val DIRECT_SOCKET_TIMEOUT_MS = 10_000L

        /** 整轮网络尝试（直连 + 两个镜像）的总上限；切片 3 日历约 9 MB，30s 在慢网会误判超时 */
        private const val NETWORK_TIMEOUT_MS = 60_000L

        /** 直连优先，其次镜像加速（任一成功即可） */
        internal fun metadataUrlCandidates(): List<String> = buildList {
            add(METADATA_URL)
            GITHUB_MIRROR_PREFIXES.forEach { add(it + METADATA_URL) }
        }
    }

    private data class CachedCalendar(
        val calendar: BangumiCalendar,
        val freshness: CalendarFreshness,
    )

    private sealed interface NetworkResult {
        data class Success(
            val calendar: BangumiCalendar,
            val raw: String,
            val freshness: CalendarFreshness,
        ) : NetworkResult

        data class Failure(val reason: String) : NetworkResult
    }

    private val cacheDir: File
        get() = File(context.filesDir, CACHE_DIR)

    private val cacheFile: File
        get() = File(cacheDir, CACHE_FILE)

    /** 最近一次成功解析的周表：详情页按 id 取简介/标签时避免重复解析 6+ MB JSON */
    @Volatile
    private var lastCalendar: BangumiCalendar? = null

    /** 进程内已加载过就直接取；没加载过返回 null（调用方可用 [loadItem]） */
    fun findItem(id: Long): MetadataItem? = lastCalendar?.items?.get(id)

    /**
     * 只读本地缓存，不发任何网络请求（调用方需在 IO 线程调用）。
     *
     * 用途：轻包 index.json 完全不可用时，用旧的 calendar.json 缓存兜底
     * （用户 2026-10-21 拍板：不回退源站内容，但旧缓存仍可用）。
     */
    fun loadCachedOnly(now: Instant = Instant.now()): CalendarLoadResult {
        val cached = readCache(now)
        return if (cached != null) {
            lastCalendar = cached.calendar
            "META legacy cache ok items=${cached.calendar.itemCount} freshness=${cached.freshness}"
                .log("BehaviorTrace")
            CalendarLoadResult.Success(
                calendar = cached.calendar,
                fromCache = true,
                freshness = cached.freshness,
            )
        } else {
            "META legacy cache missing".log("BehaviorTrace")
            CalendarLoadResult.Unavailable("no legacy cache")
        }
    }

    /** 按 Bangumi id 取元数据详情；必要时先加载（优先本地缓存）周表 */
    suspend fun loadItem(id: Long, forceRefresh: Boolean = false): MetadataItem? {
        if (!forceRefresh) findItem(id)?.let { return it }
        return when (val result = load(forceRefresh)) {
            is CalendarLoadResult.Success -> result.calendar.items[id]
            is CalendarLoadResult.Unavailable -> null
        }
    }

    suspend fun load(
        forceRefresh: Boolean = false,
        now: Instant = Instant.now(),
    ): CalendarLoadResult = withContext(Dispatchers.IO) {
        "META load start force=$forceRefresh".log("BehaviorTrace")

        // 进程内已有一份新鲜周表（周表页先加载过）时直接复用，避免首页再解析一次大 JSON
        if (!forceRefresh) {
            val memory = lastCalendar
            if (memory != null &&
                BangumiCalendarParser.freshness(memory.updatedAt, now) == CalendarFreshness.FRESH
            ) {
                "META load ok origin=memory freshness=FRESH items=${memory.itemCount}"
                    .log("BehaviorTrace")
                return@withContext CalendarLoadResult.Success(
                    calendar = memory,
                    fromCache = true,
                    freshness = CalendarFreshness.FRESH,
                )
            }
        }

        val cached = readCache(now)
        if (!forceRefresh && cached != null && cached.freshness == CalendarFreshness.FRESH) {
            lastCalendar = cached.calendar
            (
                "META load ok origin=cache freshness=FRESH items=${cached.calendar.itemCount} " +
                    "updated_at=${cached.calendar.updatedAt}"
            ).log("BehaviorTrace")
            return@withContext CalendarLoadResult.Success(
                calendar = cached.calendar,
                fromCache = true,
                freshness = CalendarFreshness.FRESH,
            )
        }

        when (val network = fetchFromNetwork(now)) {
            is NetworkResult.Success -> {
                writeCache(network.raw)
                lastCalendar = network.calendar
                (
                    "META load ok origin=network freshness=${network.freshness} " +
                        "items=${network.calendar.itemCount} updated_at=${network.calendar.updatedAt}"
                ).log("BehaviorTrace")
                CalendarLoadResult.Success(
                    calendar = network.calendar,
                    fromCache = false,
                    freshness = network.freshness,
                )
            }

            is NetworkResult.Failure -> {
                if (cached != null) {
                    lastCalendar = cached.calendar
                    (
                        "META load ok origin=cache freshness=${cached.freshness} " +
                            "items=${cached.calendar.itemCount} reason=${network.reason}"
                    ).log("BehaviorTrace")
                    CalendarLoadResult.Success(
                        calendar = cached.calendar,
                        fromCache = true,
                        freshness = cached.freshness,
                        failureReason = network.reason,
                    )
                } else {
                    "META load failed reason=${network.reason}".log("BehaviorTrace")
                    CalendarLoadResult.Unavailable(network.reason)
                }
            }
        }
    }

    private fun readCache(now: Instant): CachedCalendar? {
        val file = cacheFile
        if (!file.isFile) return null
        return try {
            val calendar = BangumiCalendarParser.parse(file.readText(), now)
            val freshness = BangumiCalendarParser.freshness(calendar.updatedAt, now)
            if (freshness == CalendarFreshness.EXPIRED) {
                "META cache expired updated_at=${calendar.updatedAt}".log(TAG)
                null
            } else {
                CachedCalendar(calendar, freshness)
            }
        } catch (e: Exception) {
            "META cache invalid: ${e.message}".log(TAG)
            null
        }
    }

    private fun writeCache(raw: String) {
        try {
            val dir = cacheDir
            if (!dir.isDirectory && !dir.mkdirs()) {
                "META cache mkdir failed: ${dir.absolutePath}".log(TAG)
                return
            }
            val target = cacheFile
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
            "META cache write failed: ${e.message}".log(TAG)
        }
    }

    private suspend fun fetchFromNetwork(now: Instant): NetworkResult {
        val result = withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
            fetchFromNetworkInternal(now)
        }
        return result ?: NetworkResult.Failure("timeout (${NETWORK_TIMEOUT_MS / 1000}s)")
    }

    private suspend fun fetchFromNetworkInternal(now: Instant): NetworkResult {
        var lastError = "unknown"
        for ((index, url) in metadataUrlCandidates().withIndex()) {
            try {
                val response = httpClient.get(url) {
                    if (index == 0) {
                        timeout {
                            connectTimeoutMillis = DIRECT_CONNECT_TIMEOUT_MS
                            socketTimeoutMillis = DIRECT_SOCKET_TIMEOUT_MS
                        }
                    }
                }
                if (!response.status.isSuccess()) {
                    lastError = "HTTP ${response.status.value}"
                    "META http ${response.status.value} url=${url.take(60)}".log(TAG)
                    continue
                }
                val raw = response.bodyAsText()
                val calendar = BangumiCalendarParser.parse(raw, now)
                val freshness = BangumiCalendarParser.freshness(calendar.updatedAt, now)
                if (freshness == CalendarFreshness.EXPIRED) {
                    lastError = "metadata expired (updated_at=${calendar.updatedAt})"
                    "META expired url=${url.take(60)} updated_at=${calendar.updatedAt}".log(TAG)
                    continue
                }
                (
                    "META fetch ok url=${url.take(60)} items=${calendar.itemCount} " +
                        "freshness=$freshness"
                ).log(TAG)
                return NetworkResult.Success(calendar, raw, freshness)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                "META fetch failed url=${url.take(60)}: $lastError".log(TAG)
            }
        }
        return NetworkResult.Failure(lastError)
    }
}
