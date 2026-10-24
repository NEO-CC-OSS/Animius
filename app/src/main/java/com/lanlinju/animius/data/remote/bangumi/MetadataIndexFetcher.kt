package com.lanlinju.animius.data.remote.bangumi

import com.lanlinju.animius.util.log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** index.json 实际来自哪个通道（写进 BehaviorTrace，便于排查新鲜度） */
internal enum class MetadataIndexOrigin(val logName: String) {
    /** GitHub contents API（最新，base64；首选） */
    API("api"),

    /** gh-proxy → raw.githubusercontent.com（最新，慢；次选） */
    GH_PROXY("ghproxy"),

    /** jsDelivr（快，但分支缓存不认 force-push，可能滞后最多 12 h；仅兜底） */
    JSDELIVR("jsdelivr"),
}

/**
 * index.json 的网络加载（纯网络逻辑，无 Android 依赖，便于用 MockEngine 做 JVM 单测）。
 *
 * 通道顺序（2026-10-21 实测）：
 * 1. `api.github.com` contents 接口：最新且快（实测 0.8–1.04 s / 407 KB base64 → 295 KB），
 *    响应是 base64，必须用 [GitHubContentsPayload] 解码；403（未认证限流）时回退；
 * 2. `gh-proxy.com` raw：最新，但 14–104 KB/s 不稳定；
 * 3. jsDelivr 主/备用域名：快，但**分支缓存不认 force-push**，实测会滞后 12 h，只做兜底并记录警告；
 * 4. 本地缓存（由 [MetadataIndexClient] 处理）。
 *
 * 三条网络通道共享同一个 [TOTAL_TIMEOUT_MS] 总预算；单请求 8 s。
 */
internal class MetadataIndexFetcher(private val httpClient: HttpClient) {

    companion object {
        /** 首选：GitHub contents API（返回 base64 包装的 index.json） */
        const val API_URL =
            "https://api.github.com/repos/NEO-CC-OSS/Animius-TV/contents/index.json?ref=metadata"

        /** 次选：gh-proxy 镜像 raw（最新，慢） */
        const val GH_PROXY_URL = MetadataCoverUrls.GH_PROXY_PREFIX + "index.json"

        /** 兜底：jsDelivr 主域名（快，但可能滞后 12 h） */
        const val JSDELIVR_PRIMARY_URL = MetadataCoverUrls.JSDELIVR_PRIMARY_PREFIX + "index.json"

        /** 兜底：jsDelivr 备用域名 */
        const val JSDELIVR_BACKUP_URL = MetadataCoverUrls.JSDELIVR_BACKUP_PREFIX + "index.json"

        private const val TAG = "MetadataIndex"

        /** 三条网络通道共享的总预算（不是每条 20 s） */
        const val TOTAL_TIMEOUT_MS = 20_000L

        /** 单请求上限 */
        const val REQUEST_TIMEOUT_MS = 8_000L

        /** 数据超过这个年龄，就继续尝试后面的通道取较新者 */
        private const val REFRESH_HINT_HOURS = 36L
    }

    data class Outcome(
        val raw: String,
        val calendar: BangumiCalendar,
        val freshness: CalendarFreshness,
        val origin: MetadataIndexOrigin,
    )

    sealed interface Result {
        data class Ok(val outcome: Outcome) : Result

        data class Err(val reason: String) : Result
    }

    /**
     * 按通道顺序取 index.json；三条通道共享一个 20 s 外层 deadline。
     * 拿到数据后若 `updated_at` 超过 36 h，会继续尝试后续通道并取较新者（应对 jsDelivr 滞后/断更）。
     */
    suspend fun fetch(now: Instant = Instant.now()): Result {
        val result = withTimeoutOrNull(TOTAL_TIMEOUT_MS) { fetchWithinBudget(now) }
        return result ?: Result.Err("timeout (${TOTAL_TIMEOUT_MS / 1000}s)")
    }

    private suspend fun fetchWithinBudget(now: Instant): Result {
        var best: Outcome? = null
        var lastError = "unknown"

        val channels: List<suspend () -> Result> = listOf(
            { fetchApi(now) },
            { fetchRaw(GH_PROXY_URL, MetadataIndexOrigin.GH_PROXY, now) },
            { fetchJsdelivr(now) },
        )

        for (channel in channels) {
            when (val result = channel()) {
                is Result.Ok -> {
                    val current = best
                    if (current == null || result.outcome.calendar.updatedAt.isAfter(current.calendar.updatedAt)) {
                        best = result.outcome
                    }
                    val newest = best ?: result.outcome
                    if (ageHours(newest.calendar.updatedAt, now) <= REFRESH_HINT_HOURS) {
                        return Result.Ok(newest)
                    }
                    // 数据偏旧：继续试下一个通道，取较新者（全部试完仍偏旧就返回最新的一份）
                }

                is Result.Err -> {
                    lastError = result.reason
                    "META index channel failed: ${result.reason}".log(TAG)
                }
            }
        }
        val fallback = best ?: return Result.Err(lastError)
        return Result.Ok(fallback)
    }

    /** 首选：GitHub contents API（base64 包装） */
    private suspend fun fetchApi(now: Instant): Result = try {
        val response = httpClient.get(API_URL) {
            header(HttpHeaders.Accept, "application/vnd.github+json")
            timeout {
                connectTimeoutMillis = 6_000
                socketTimeoutMillis = REQUEST_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
            }
        }
        if (!response.status.isSuccess()) {
            // 403/429 = 未认证限流；404 = 分支/文件不在 → 都交给下一通道
            "META index api http ${response.status.value}".log(TAG)
            Result.Err("api HTTP ${response.status.value}")
        } else {
            val body = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { response.bodyAsText() }
                ?: return Result.Err("api timeout body")
            val raw = GitHubContentsPayload.decode(body)
                ?: return Result.Err("api payload not base64")
            toResult(raw, MetadataIndexOrigin.API, now)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.Err(e.message ?: e.javaClass.simpleName)
    }

    /** 次选/兜底：直接取 raw JSON */
    private suspend fun fetchRaw(url: String, origin: MetadataIndexOrigin, now: Instant): Result = try {
        val response = httpClient.get(url) {
            timeout {
                connectTimeoutMillis = 6_000
                socketTimeoutMillis = REQUEST_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
            }
        }
        if (!response.status.isSuccess()) {
            "META index http ${response.status.value} url=${url.take(64)}".log(TAG)
            Result.Err("HTTP ${response.status.value}")
        } else {
            val raw = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { response.bodyAsText() }
                ?: return Result.Err("timeout body")
            toResult(raw, origin, now)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.Err(e.message ?: e.javaClass.simpleName)
    }

    /** jsDelivr 两个域名并发竞速（快，但可能滞后；先成功者胜） */
    private suspend fun fetchJsdelivr(now: Instant): Result = coroutineScope {
        val urls = listOf(JSDELIVR_PRIMARY_URL, JSDELIVR_BACKUP_URL)
        val channel = Channel<Result>(urls.size)
        val jobs = urls.map { url ->
            launch { channel.send(fetchRaw(url, MetadataIndexOrigin.JSDELIVR, now)) }
        }
        var lastError = "unknown"
        val firstOk = withTimeoutOrNull(REQUEST_TIMEOUT_MS + 1_000) {
            repeat(urls.size) {
                when (val result = channel.receive()) {
                    is Result.Ok -> return@withTimeoutOrNull result
                    is Result.Err -> lastError = result.reason
                }
            }
            null
        }
        jobs.forEach { it.cancel() }
        firstOk ?: Result.Err(lastError)
    }

    private fun toResult(raw: String, origin: MetadataIndexOrigin, now: Instant): Result {
        val calendar = try {
            MetadataIndexParser.parse(raw, now)
        } catch (e: CalendarParseException) {
            return Result.Err("${origin.logName} parse: ${e.message}")
        }
        val freshness = BangumiCalendarParser.freshness(calendar.updatedAt, now)
        if (freshness == CalendarFreshness.EXPIRED) {
            return Result.Err("${origin.logName} expired (updated_at=${calendar.updatedAt})")
        }
        if (origin == MetadataIndexOrigin.JSDELIVR) {
            (
                "META index fetch ok origin=jsdelivr (可能过期：jsDelivr 分支缓存不认 force-push) " +
                    "items=${calendar.itemCount} updated_at=${calendar.updatedAt}"
            ).log(TAG)
        } else {
            (
                "META index fetch ok origin=${origin.logName} items=${calendar.itemCount} " +
                    "updated_at=${calendar.updatedAt}"
            ).log(TAG)
        }
        return Result.Ok(Outcome(raw, calendar, freshness, origin))
    }

    private fun ageHours(updatedAt: Instant, now: Instant): Long {
        val age = Duration.between(updatedAt, now)
        return if (age.isNegative) 0 else age.toHours()
    }
}
