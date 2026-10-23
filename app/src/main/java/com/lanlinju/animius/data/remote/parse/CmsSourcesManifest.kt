package com.lanlinju.animius.data.remote.parse

import com.lanlinju.animius.data.remote.bangumi.MetadataCoverUrls
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 远程域名清单 `sources.json`（metadata 分支根目录，2026-10-23 任务 C②）。
 *
 * 格式固定（schema 1，由元数据管道产出）：
 * ```json
 * {"schema":1,"updated_at":"2026-10-23T00:00:00Z",
 *  "sources":{"Lz":{"domains":["https://cj.lziapi.com/"]},
 *             "Bf":{"domains":["https://bfzyapi.com/"]}}}
 * ```
 * `sources` 的 key 就是 App 的 `modeTag` 原样大小写（Lz/Bf/Md）。
 *
 * 通道集合与 [MetadataCoverUrls] 相同（同一套镜像，别自创），但**顺序有意不同**：
 * 清单是 1 KB 小文件、gh-proxy 实测最快，所以清单优先 gh-proxy；封面才是 jsDelivr 双域名竞速。
 * 顺序：gh-proxy → gcore.jsdelivr → testingcf.jsdelivr；**跳过 api.github.com**（未认证 429 不可依赖）。
 */
internal data class CmsSourcesManifest(
    val updatedAt: String,
    val sources: Map<String, List<String>>,
) {

    fun domainsOf(modeTag: String): List<String> = sources[modeTag].orEmpty()

    companion object {
        /** 认识的清单版本；不认识就整体丢弃（宁可没有，也不拿未来的格式乱试域名） */
        const val SCHEMA = 1

        const val FILE_NAME = "sources.json"

        /** 404 页都很短；长页面（captive portal/挑战页）不算「文件不存在」，别误记 24 h */
        private const val MAX_MISSING_BODY_LENGTH = 400

        /** 三条镜像通道的「文件不存在」文案（2026-10-23 实测）：
         * gh-proxy → raw.githubusercontent：`404: Not Found`
         * jsDelivr 两个域名：`Couldn't find the requested file /gh/... in ...`
         */
        private val MISSING_MARKERS = listOf(
            // 裸 "404" 偏宽：captive portal 短错误页可能只写 404，会被误记 24 h；
            // 两种真实 404 文案：gh-proxy「404: Not Found」与 nginx 默认页「404 Not Found」（无冒号）
            "404: not found",
            "404 not found",
            "couldn't find the requested file",
            "could not find the requested file",
        )

        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /** 三条镜像通道，顺序即尝试顺序（gh-proxy 实测最快） */
        fun channelUrls(): List<String> = listOf(
            MetadataCoverUrls.GH_PROXY_PREFIX + FILE_NAME,
            MetadataCoverUrls.JSDELIVR_PRIMARY_PREFIX + FILE_NAME,
            MetadataCoverUrls.JSDELIVR_BACKUP_PREFIX + FILE_NAME,
        )

        /** 日志用的通道短名 */
        fun channelName(url: String): String = when {
            url.contains("gh-proxy") -> "ghproxy"
            url.contains("gcore.jsdelivr") -> "jsdelivr"
            url.contains("testingcf.jsdelivr") -> "jsdelivr-cf"
            else -> "unknown"
        }

        /**
         * 远端是否**明确回答「文件不存在」**（清单还没上线）。
         *
         * 只认短错误文本里的固定标记：这样「清单未上线」的 404 能被记住 24 h（零请求），
         * 而网络失败、captive portal、挑战页都不会被误判成「不存在」。
         */
        fun looksMissing(body: String): Boolean {
            val text = body.trim()
            if (text.isEmpty() || text.length > MAX_MISSING_BODY_LENGTH) return false
            val lower = text.lowercase()
            return MISSING_MARKERS.any { it in lower }
        }

        /**
         * 解析 + 校验。整体不可用一律返回 null：非 JSON（404 文本/挑战页）、schema 不认识、
         * 缺 `sources`、一条合法域名都没有。调用方据此静默回退内置候选，行为等同现状。
         *
         * 单条域名不合法只丢这一条；某个源没有合法域名就整个源不出现。
         */
        fun parse(raw: String): CmsSourcesManifest? {
            if (!CmsDomainPolicy.looksLikeJsonObject(raw)) return null
            val trimmed = raw.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            val root = try {
                JSON.parseToJsonElement(trimmed) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: return null
            val schema = (root["schema"] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()
                ?: return null
            if (schema != SCHEMA) return null
            val sourcesNode = root["sources"] as? JsonObject ?: return null
            val sources = LinkedHashMap<String, List<String>>()
            sourcesNode.forEach { (mode, value) ->
                val domains = (value as? JsonObject)?.get("domains") as? JsonArray ?: return@forEach
                val valid = domains.mapNotNull { element ->
                    CmsDomainPolicy.normalizeDomain((element as? JsonPrimitive)?.contentOrNull)
                }.distinct()
                if (valid.isNotEmpty()) sources[mode] = valid
            }
            if (sources.isEmpty()) return null
            val updatedAt = (root["updated_at"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            return CmsSourcesManifest(updatedAt, sources)
        }
    }
}
