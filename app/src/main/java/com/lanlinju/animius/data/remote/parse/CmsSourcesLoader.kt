package com.lanlinju.animius.data.remote.parse

import com.lanlinju.animius.util.log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 远程域名清单的本地缓存（接口化，便于 JVM 单测用内存实现）。
 *
 * 文件实现见 [FileCmsSourcesCache]：`filesDir/cms_sources/` 下两个小文件，
 * 不塞 SharedPreferences（清单是整段 JSON，写 prefs 会拖慢 apply 且难排查）。
 */
internal interface CmsSourcesCache {
    fun readRaw(): String?

    fun readFetchedAtMs(): Long

    /** 拉到可用清单：存原文 + 拉取时间 */
    fun write(raw: String, fetchedAtMs: Long)

    /** 远端明确回答「文件不存在」（清单还没上线）：只记时间，24 h 内不再试，已有清单不动 */
    fun writeMissing(fetchedAtMs: Long)
}

/**
 * 文件缓存：`sources.json`（原样存清单，解析逻辑只有一份）+ `sources.stamp`（本地拉取时间戳）。
 * 读写失败一律吞掉——清单是纯增益能力，不能影响原有失败路径。
 */
internal class FileCmsSourcesCache(private val dir: File) : CmsSourcesCache {

    override fun readRaw(): String? = try {
        val file = File(dir, CmsSourcesManifest.FILE_NAME)
        if (file.isFile) file.readText() else null
    } catch (e: Exception) {
        null
    }

    override fun readFetchedAtMs(): Long = try {
        val file = File(dir, STAMP_FILE)
        if (file.isFile) file.readText().trim().toLongOrNull() ?: 0L else 0L
    } catch (e: Exception) {
        0L
    }

    override fun write(raw: String, fetchedAtMs: Long) {
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return
            File(dir, CmsSourcesManifest.FILE_NAME).writeText(raw)
            File(dir, STAMP_FILE).writeText(fetchedAtMs.toString())
        } catch (e: Exception) {
            "CMS sources cache write failed: ${e.message}".log(TAG)
        }
    }

    override fun writeMissing(fetchedAtMs: Long) {
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return
            File(dir, STAMP_FILE).writeText(fetchedAtMs.toString())
        } catch (e: Exception) {
            "CMS sources cache write failed: ${e.message}".log(TAG)
        }
    }

    private companion object {
        const val TAG = "BehaviorTrace"
        const val STAMP_FILE = "sources.stamp"
    }
}

/**
 * 按需拉远程域名清单（任务 C②）：**只在「内置候选全失败」时被调用**，常态零请求。
 *
 * - 通道：gh-proxy → gcore.jsdelivr → testingcf.jsdelivr（跳过 api.github.com）；
 * - 单请求 8 s、总预算 20 s、失败不重试多轮；
 * - 24 h 内不重复拉（本地时间戳，不信远端 updated_at，避免清单停更就天天重拉）；
 *   远端明确 404（清单还没上线）也记时间，同样 24 h 内零请求；
 * - 请求路径上最多等 [IN_PATH_GRACE_MS]：拉取在应用级作用域继续，成功后写缓存，下次直接用；
 * - 拉不到 / 解析失败 / 404 → 返回缓存里的旧域名（可能为空），静默回退，绝不抛异常。
 */
internal class CmsSourcesLoader(
    private val cache: CmsSourcesCache,
    private val fetch: suspend (url: String) -> String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { it.log(TAG) },
) {

    companion object {
        const val TAG = "BehaviorTrace"

        /** 三条通道共享的总预算 */
        const val TOTAL_TIMEOUT_MS = 20_000L

        /** 单请求上限 */
        const val REQUEST_TIMEOUT_MS = 8_000L

        /** 24 h 内不重复拉 */
        const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

        /** 请求路径上最多等这么久；超时后后台继续拉，不拖慢搜索 */
        const val IN_PATH_GRACE_MS = 3_000L

        /** 拉取失败后的进程内退避：别让每个失败请求都去试三条通道 */
        const val FAILURE_BACKOFF_MS = 10 * 60 * 1000L
    }

    @Volatile
    private var lastAttemptAtMs = 0L

    /** 进程内已解析的清单：详情页每次请求都会问一次候选域名，别每次都读文件+解析 */
    @Volatile
    private var memoLoaded = false

    @Volatile
    private var memoManifest: CmsSourcesManifest? = null

    /** 本地缓存里的域名（不看新鲜度：三天前能用的域名今天多半还能用） */
    fun cachedDomains(modeTag: String): List<String> =
        manifest()?.domainsOf(modeTag).orEmpty()

    /**
     * 内置候选全挂后才调用。24 h 内直接返回缓存；否则按通道拉一次，
     * 最多等 [graceMs]（默认 3 s），拉到了就用新域名，拉不到就回退缓存（可能为空）。
     *
     * 注意：grace 到点就**立刻**返回（不等 20 s 总预算），调用方随即走「全部失败」路径；
     * 拉取任务在应用级作用域继续跑，成功后会写缓存，下次请求直接用。
     */
    suspend fun load(modeTag: String, graceMs: Long = IN_PATH_GRACE_MS): List<String> {
        val cached = cachedDomains(modeTag)
        if (isFresh()) return cached
        if (now() - lastAttemptAtMs < FAILURE_BACKOFF_MS) return cached
        lastAttemptAtMs = now()
        val job = scope.async { pull(modeTag) }
        val result = withTimeoutOrNull(graceMs) { job.await() }
        return result?.takeIf { it.isNotEmpty() } ?: cached
    }

    private fun manifest(): CmsSourcesManifest? {
        if (memoLoaded) return memoManifest
        val parsed = CmsSourcesManifest.parse(cache.readRaw().orEmpty())
        memoManifest = parsed
        memoLoaded = true
        return parsed
    }

    private fun remember(manifest: CmsSourcesManifest) {
        memoManifest = manifest
        memoLoaded = true
    }

    private fun isFresh(): Boolean {
        val fetchedAt = cache.readFetchedAtMs()
        return fetchedAt > 0 && now() - fetchedAt < CACHE_TTL_MS
    }

    private suspend fun pull(modeTag: String): List<String> {
        val startedAt = now()
        var missingAnswers = 0
        var networkFailures = 0
        val result = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            for (url in CmsSourcesManifest.channelUrls()) {
                val channel = CmsSourcesManifest.channelName(url)
                var error: String? = null
                val body = try {
                    withTimeoutOrNull(REQUEST_TIMEOUT_MS) { fetch(url) }
                        ?: run {
                            error = "timeout"
                            null
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.javaClass.simpleName
                    null
                }
                if (body == null) {
                    networkFailures++
                    log("CMS sources channel fail mode=$modeTag channel=$channel err=${error ?: "empty"}")
                    continue
                }
                val manifest = CmsSourcesManifest.parse(body)
                if (manifest == null) {
                    if (CmsSourcesManifest.looksMissing(body)) {
                        // 清单还没上线：这是「确定的答案」，不是网络故障
                        missingAnswers++
                        log("CMS sources missing mode=$modeTag channel=$channel")
                    } else {
                        log("CMS sources parse fail mode=$modeTag channel=$channel bytes=${body.length}")
                    }
                    continue
                }
                cache.write(body, startedAt)
                remember(manifest)
                val domains = manifest.domainsOf(modeTag)
                log(
                    "CMS sources ok mode=$modeTag channel=$channel domains=${domains.size} " +
                        "updated_at=${manifest.updatedAt}",
                )
                return@withTimeoutOrNull domains
            }
            null
        }
        if (result == null) {
            // 三条通道都明确回答「文件不存在」→ 记时间，24 h 内零请求（清单上线后再恢复拉取）
            if (missingAnswers > 0 && networkFailures == 0) {
                cache.writeMissing(startedAt)
                log("CMS sources missing mode=$modeTag channels=$missingAnswers remember=24h")
            }
            log("CMS sources unavailable mode=$modeTag ms=${now() - startedAt}")
            return emptyList()
        }
        return result
    }
}
