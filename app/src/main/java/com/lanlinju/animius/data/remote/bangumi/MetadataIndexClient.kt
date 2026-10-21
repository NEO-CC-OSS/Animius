package com.lanlinju.animius.data.remote.bangumi

import android.content.Context
import com.lanlinju.animius.util.log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 轻包元数据客户端：读 metadata 分支的 `index.json`（schema 2，文字 + LQIP + 封面地址）。
 *
 * 网络加载见 [MetadataIndexFetcher]（API → gh-proxy → jsDelivr → 缓存），本地缓存见 [MetadataIndexCache]，
 * 全失败兜底见 [MetadataFallbackPolicy]；本类只负责进程内状态、单飞与旧缓存兼容。
 *
 * 与旧的 [BangumiCalendarClient]（9 MB calendar.json）并存：
 * - 平时只读 index.json（实测 249,818 B ≈ 244 KB），封面改成独立文件按需加载；
 * - 24 小时新鲜度；超过 7 天视为过期；
 * - 网络总预算 20 s（旧包 60 s），单请求 8 s；
 * - index.json 完全不可用时，用旧的 calendar.json 本地缓存兜底（不发源站请求）。
 */
@Singleton
class MetadataIndexClient @Inject constructor(
    @ApplicationContext context: Context,
    httpClient: HttpClient,
    private val legacyClient: BangumiCalendarClient,
) {

    companion object {
        private const val CACHE_DIR = "metadata"
        private const val TAG = "MetadataIndex"

        /** 通道顺序（供日志/诊断核对）：api → gh-proxy → jsDelivr 主 → jsDelivr 备 */
        internal fun channelUrls(): List<String> = listOf(
            MetadataIndexFetcher.API_URL,
            MetadataIndexFetcher.GH_PROXY_URL,
            MetadataIndexFetcher.JSDELIVR_PRIMARY_URL,
            MetadataIndexFetcher.JSDELIVR_BACKUP_URL,
        )
    }

    private val cache = MetadataIndexCache(File(context.filesDir, CACHE_DIR))

    private val fetcher = MetadataIndexFetcher(httpClient)

    /** 最近一次成功解析的轻包：详情页按 id 取简介/标签时零等待，不再解析整包 */
    @Volatile
    private var lastIndex: BangumiCalendar? = null

    /**
     * 应用级作用域：in-flight 加载不绑定某个 ViewModel。
     * 页面销毁不打断已发出的请求（结果仍会写缓存，下次秒开）。
     */
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 共享 in-flight：并发调用者 await 同一份结果（含失败），不会各跑一轮 */
    private val indexLoader = SingleFlight<CalendarLoadResult>(loadScope)

    /** 进程内已加载过就直接取；没加载过返回 null（调用方可用 [loadItem]） */
    fun findItem(id: Long): MetadataItem? = lastIndex?.items?.get(id)

    /**
     * 按 Bangumi id 取元数据详情。
     *
     * 轻包已在内存时直接命中；没加载过才加载一次轻包（约 244 KB，走缓存优先），
     * 不再像旧实现那样为了取一条数据解析 9 MB 的整包。
     */
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
    ): CalendarLoadResult {
        // 快路径：内存里已有新鲜数据（日常打开都走这里），不建 in-flight
        if (!forceRefresh) {
            val memory = lastIndex
            if (memory != null &&
                BangumiCalendarParser.freshness(memory.updatedAt, now) == CalendarFreshness.FRESH
            ) {
                "META index ok origin=memory items=${memory.itemCount}".log("BehaviorTrace")
                return CalendarLoadResult.Success(
                    calendar = memory,
                    fromCache = true,
                    freshness = CalendarFreshness.FRESH,
                )
            }
        }

        // 单飞：时间表（默认首屏）与首页会同时 init 调 load()。
        // 共享同一个 in-flight 结果（含失败）：
        // 不会在第一个 20 s 预算失败后再串行跑第二轮（首页最坏 40 s 的根因），
        // 也不会并发下载两份 index / 并发写同一个 index.json.tmp。
        return indexLoader.load { runLoad(forceRefresh, now) }
    }

    /** 包一层兜底：加载内部异常不能把 ViewModel 协程炸掉 */
    private suspend fun runLoad(forceRefresh: Boolean, now: Instant): CalendarLoadResult = try {
        loadLocked(forceRefresh, now)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "META index load crashed: ${e.message}".log(TAG)
        CalendarLoadResult.Unavailable(e.message ?: e.javaClass.simpleName)
    }

    private suspend fun loadLocked(
        forceRefresh: Boolean,
        now: Instant,
    ): CalendarLoadResult {
        "META index load start force=$forceRefresh".log("BehaviorTrace")

        if (!forceRefresh) {
            val memory = lastIndex
            if (memory != null &&
                BangumiCalendarParser.freshness(memory.updatedAt, now) == CalendarFreshness.FRESH
            ) {
                "META index ok origin=memory items=${memory.itemCount}".log("BehaviorTrace")
                return CalendarLoadResult.Success(
                    calendar = memory,
                    fromCache = true,
                    freshness = CalendarFreshness.FRESH,
                )
            }
        }

        val cached = cache.read(now)
        if (!forceRefresh && cached != null && cached.freshness == CalendarFreshness.FRESH) {
            lastIndex = cached.calendar
            (
                "META index ok origin=cache freshness=FRESH items=${cached.calendar.itemCount} " +
                    "updated_at=${cached.calendar.updatedAt}"
            ).log("BehaviorTrace")
            return CalendarLoadResult.Success(
                calendar = cached.calendar,
                fromCache = true,
                freshness = CalendarFreshness.FRESH,
            )
        }

        return when (val network = fetcher.fetch(now)) {
            is MetadataIndexFetcher.Result.Ok -> {
                val outcome = network.outcome
                cache.write(outcome.raw)
                lastIndex = outcome.calendar
                (
                    "META index ok origin=${outcome.origin.logName} freshness=${outcome.freshness} " +
                        "items=${outcome.calendar.itemCount} updated_at=${outcome.calendar.updatedAt}"
                ).log("BehaviorTrace")
                CalendarLoadResult.Success(
                    calendar = outcome.calendar,
                    fromCache = false,
                    freshness = outcome.freshness,
                )
            }

            is MetadataIndexFetcher.Result.Err -> fallbackToLocal(network.reason, cached, now)
        }
    }

    /** 网络全失败：先用 index 本地缓存；没有再用旧 calendar.json 缓存（只读本地，不发网络） */
    private fun fallbackToLocal(
        reason: String,
        cached: MetadataIndexCache.Entry?,
        now: Instant,
    ): CalendarLoadResult {
        when (val fallback = MetadataFallbackPolicy.onNetworkFailure(cached, reason)) {
            is CalendarLoadResult.Success -> {
                lastIndex = fallback.calendar
                (
                    "META index ok origin=cache freshness=${fallback.freshness} " +
                        "items=${fallback.calendar.itemCount} reason=$reason"
                ).log("BehaviorTrace")
                return fallback
            }

            is CalendarLoadResult.Unavailable -> {
                return when (val legacy = legacyClient.loadCachedOnly(now)) {
                    is CalendarLoadResult.Success -> {
                        lastIndex = legacy.calendar
                        (
                            "META index legacy fallback items=${legacy.calendar.itemCount} " +
                                "reason=$reason"
                        ).log("BehaviorTrace")
                        legacy
                    }

                    is CalendarLoadResult.Unavailable -> {
                        "META index failed reason=$reason".log("BehaviorTrace")
                        CalendarLoadResult.Unavailable(reason)
                    }
                }
            }
        }
    }
}
