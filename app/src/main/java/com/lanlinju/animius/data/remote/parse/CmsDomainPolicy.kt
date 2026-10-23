package com.lanlinju.animius.data.remote.parse

import java.net.URI

/**
 * 采集源域名策略（纯函数，便于 JVM 单测）。
 *
 * 域名优先级（2026-10-23 任务 B / C②）：
 * 1. 用户自定义域名（设置页 `KEY_SOURCE_DOMAIN`）：永远第一优先，自动顺延不写回设置；
 * 2. 远程清单 `sources.json` 的本地缓存：内置候选全挂后才可能用上；
 * 3. 内置 `CANDIDATE_DOMAINS`。
 *
 * 顺延只在「快速失败」时发生：连接重置 / DNS 失败 / 非 200 / 非 JSON 挑战页都算；
 * **超时类失败不换域名**——换域名只会把总耗时翻倍，而超时通常意味着整条网络都不通。
 */
internal object CmsDomainPolicy {

    /** 一次请求最多顺延几个候选（首个域名之外） */
    const val MAX_FALLBACK_CANDIDATES = 2

    private const val SCHEME = "https://"
    private const val MIN_URL_LENGTH = 10
    private const val MAX_URL_LENGTH = 200
    private const val MAX_HOST_LENGTH = 253
    private val IPV4 = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
    private val LABEL = Regex("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?")
    private val TLD = Regex("[A-Za-z]{2,}")

    /**
     * 组装候选顺序：用户自定义 → 清单缓存 → 内置，去重并统一成无尾斜杠形式
     * （`CmsSource` 拼 URL 时是 `domain + "/api.php/..."`）。
     *
     * 用户手填的域名只做空白/尾斜杠归一、**不校验**：校验只针对远程清单条目，
     * 免得一个「不合规但能用」的自定义域名被静默丢弃。
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
     * 按类名判断，避免 ktor/OkHttp 不同层的超时类型被漏掉。
     */
    fun isTimeoutFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        var guard = 0
        while (current != null && guard < 8) {
            if (current is java.net.SocketTimeoutException) return true
            when (current.javaClass.simpleName) {
                "SocketTimeoutException",
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
