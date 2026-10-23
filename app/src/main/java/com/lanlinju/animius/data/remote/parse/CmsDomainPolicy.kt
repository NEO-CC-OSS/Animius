package com.lanlinju.animius.data.remote.parse

import java.net.URI
import kotlinx.coroutines.CancellationException

/**
 * 采集源域名策略（纯函数，便于 JVM 单测）。
 *
 * 首轮候选（[firstRoundCandidates]）：当前域名（用户自定义或上次成功的）→ 内置 `CANDIDATE_DOMAINS`。
 * **远程清单缓存不进首轮**：它只在首轮全挂后的「二次机会」里出现（见 [shouldPullRemote]），
 * 否则缓存域名会吃光首轮预算、内置兜底永远试不到（2026-10-23 独立审查 must-fix）。
 *
 * 顺延只在「快速失败」时发生：连接重置 / DNS 失败 / 非 200 / 非 JSON 挑战页都算；
 * **超时类失败不换域名**——换域名只会把总耗时翻倍，而超时通常意味着整条网络都不通。
 */
internal object CmsDomainPolicy {

    /** 一次请求最多顺延几个候选（首个域名之外） */
    const val MAX_FALLBACK_CANDIDATES = 2

    /** 首轮尝试上限：首个 + 顺延额度 */
    const val FIRST_ROUND_BUDGET = 1 + MAX_FALLBACK_CANDIDATES

    private const val SCHEME = "https://"
    private const val MIN_URL_LENGTH = 10
    private const val MAX_URL_LENGTH = 200
    private const val MAX_HOST_LENGTH = 253
    private val IPV4 = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
    private val LABEL = Regex("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?")
    private val TLD = Regex("[A-Za-z]{2,}")

    /**
     * 首轮候选：当前域名 → 用户自定义 → 内置；去重、统一无尾斜杠形式。
     *
     * 用户手填的域名只做空白/尾斜杠归一、**不校验**：校验只针对远程清单条目，
     * 免得一个「不合规但能用」的自定义域名被静默丢弃。
     */
    fun firstRoundCandidates(
        current: String,
        userDomain: String?,
        builtIn: List<String>,
    ): List<String> {
        val ordered = orderedDomains(userDomain, emptyList(), builtIn)
        val head = canonical(current)
        return if (head.isEmpty()) ordered else listOf(head) + ordered.filterNot { it == head }
    }

    /**
     * 通用候选排序（用户自定义 → 缓存 → 内置）。
     * 采集源的**首轮**不用缓存（见 [firstRoundCandidates]），缓存只经 [shouldPullRemote] 的二次机会进入。
     */
    fun orderedDomains(
        userDomain: String?,
        cached: List<String>,
        builtIn: List<String>,
    ): List<String> {
        val result = LinkedHashSet<String>()
        fun add(value: String?) {
            val normalized = canonical(value.orEmpty())
            if (normalized.isNotEmpty()) result.add(normalized)
        }
        add(userDomain)
        cached.forEach { add(it) }
        builtIn.forEach { add(it) }
        return result.toList()
    }

    /**
     * 首轮失败后是否去拉远程清单（并进入二次机会）。
     *
     * 两个条件任一成立就拉：
     * - 内置候选**全部**试过（正常的「内置兜底已用尽」）；
     * - 首轮预算已耗尽（用户自定义域名 + 当前域名把 3 次机会吃光，内置根本没轮到）。
     *
     * 后者是 2026-10-23 审查 must-fix：只判「内置试完」会让这类源既不拉清单、
     * 也不刷新缓存，永久卡死。
     */
    fun shouldPullRemote(tried: Set<String>, builtIn: List<String>): Boolean {
        val builtInCanonical = builtIn.map { canonical(it) }
        val builtInExhausted = tried.containsAll(builtInCanonical)
        val budgetExhausted = tried.size >= FIRST_ROUND_BUDGET
        return builtInExhausted || budgetExhausted
    }

    /** 只做空白/尾斜杠归一，不校验 */
    fun canonical(domain: String): String = domain.trim().trimEnd('/')

    /**
     * 校验远程清单里的域名条目（用户自定义域名不走这里）：
     * 必须 `https://`、host 为合法域名或 IPv4、长度合理、不带路径/端口/用户信息。
     * 返回归一化后的地址（无尾斜杠）；不合法返回 null（调用方丢弃该条目）。
     */
    fun normalizeDomain(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.length < MIN_URL_LENGTH || value.length > MAX_URL_LENGTH) return null
        if (!value.startsWith(SCHEME, ignoreCase = true)) return null
        val host = value.substring(SCHEME.length).trimEnd('/')
        if (host.isEmpty() || host.length > MAX_HOST_LENGTH) return null
        if (host.any { it.isWhitespace() }) return null
        if (host.any { it == '/' || it == '?' || it == '#' || it == '@' || it == ':' }) return null
        if (IPV4.matches(host)) {
            val parts = host.split('.').map { it.toIntOrNull() ?: -1 }
            if (parts.size != 4 || parts.any { it !in 0..255 }) return null
            return SCHEME + host
        }
        val labels = host.split('.')
        if (labels.size < 2) return null
        if (labels.any { it.length > 63 || !LABEL.matches(it) }) return null
        if (!TLD.matches(labels.last())) return null
        return SCHEME + host.lowercase()
    }

    /**
     * 超时类失败：换域名没有意义，反而让总耗时翻倍。
     *
     * - `InterruptedIOException` 及子类（含 `SocketTimeoutException`、OkHttp 的超时）算超时；
     * - ktor 的 `ConnectTimeoutException` / `HttpRequestTimeoutException` 算超时；
     * - **`CancellationException`（含 `TimeoutCancellationException`）不算超时**：那是取消，
     *   必须原样重抛，不能被当成失败吞掉（协程取消不是网络故障）。
     */
    fun isTimeoutFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        var guard = 0
        while (current != null && guard < 8) {
            if (current is CancellationException) return false
            if (current is java.io.InterruptedIOException) return true
            when (current.javaClass.simpleName) {
                "ConnectTimeoutException",
                "HttpRequestTimeoutException",
                -> return true
            }
            current = current.cause
            guard++
        }
        return false
    }

    /** 苹果CMS 接口的正常响应一定是 JSON 对象；挑战页/404 页面不是（先去掉 BOM） */
    fun looksLikeJsonObject(body: String): Boolean =
        body.trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("{")

    /**
     * 从绝对地址里取出「路径 + 查询」（如 `/api.php/provide/vod/?ac=detail&ids=1`），
     * 供换域名时复用同一请求；取不出来（非常规地址）返回 null，调用方按原样请求。
     */
    fun requestTarget(url: String): String? = try {
        val uri = URI(url.trim())
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery.orEmpty()
        when {
            path.isEmpty() -> null
            query.isEmpty() -> path
            else -> "$path?$query"
        }
    } catch (e: Exception) {
        null
    }
}
