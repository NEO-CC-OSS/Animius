package com.lanlinju.animius.data.remote.parse

import com.lanlinju.animius.application.AnimeApplication
import com.lanlinju.animius.data.remote.dto.AnimeBean
import com.lanlinju.animius.data.remote.dto.AnimeDetailBean
import com.lanlinju.animius.data.remote.dto.EpisodeBean
import com.lanlinju.animius.data.remote.dto.HomeBean
import com.lanlinju.animius.data.remote.dto.VideoBean
import com.lanlinju.animius.util.DownloadManager
import com.lanlinju.animius.util.log
import com.lanlinju.animius.util.preferences
import java.io.File
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup

/**
 * 采集源（苹果CMS v10 JSON API）的最小源契约。
 *
 * 与番剧站解析器（Jsoup 选择器）不同，采集源只暴露一个 JSON 接口：
 * - 搜索：`{base}/api.php/provide/vod/?ac=detail&wd={关键词}&pg={页}`
 * - 详情：`{base}/api.php/provide/vod/?ac=detail&ids={vod_id}`
 * - 取址：`vod_play_url` 里就是明文 `.m3u8/.mp4` 直链，**不再额外请求**（卡 §3.3）。
 *
 * 设计约束（2026-10-22 tv.9 切片 1）：
 * - 只用明文 m3u8/mp4；分享页（`/share/<id>`）与非媒体地址一律丢弃，判「无剧集」走既有失败路径；
 * - 不提供首页/周表（元数据已接管展示，手机/平板也走同一套元数据，见设备统一）；
 * - UA 用简洁 `Mozilla/5.0`（实测完整 Chrome UA 反而触发挑战页），单请求 10 s。
 *
 * 域名漂移（2026-10-23 任务 B / C②）：
 * - 候选顺序：当前 `baseUrl`（用户自定义或上次成功的域名）→ 远程清单缓存 → `CANDIDATE_DOMAINS`；
 * - **只在快速失败时顺延**（连接重置 / DNS 失败 / 非 200 / 非 JSON 挑战页），最多 2 个候选；
 *   超时类失败不顺延（换域名只会把总耗时翻倍）；
 * - 内置候选全挂后才按需拉 metadata 分支的 `sources.json`（常态零请求，24 h 内不重复拉）；
 * - 顺延成功即更新 [baseUrl]，后续详情/取址自动拼新域名；用户自定义域名始终第一优先，
 *   自动顺延**不写回设置**。
 */
abstract class CmsSource : AnimeSource {

    /** 候选域名（主域名在前）：内置兜底，用户自定义域名与远程清单优先于它 */
    abstract val CANDIDATE_DOMAINS: List<String>

    override val DEFAULT_DOMAIN: String
        get() = CANDIDATE_DOMAINS.first()

    /** 每个源的日志/诊断前缀，如 `Lz` */
    protected abstract val modeTag: String

    /** 域名顺延（快速失败才换下一个；超时不换） */
    private val failover: CmsDomainFailover by lazy { CmsDomainFailover(modeTag) }

    override suspend fun getHomeData(): List<HomeBean> = emptyList()

    override suspend fun getWeekData(): Map<Int, List<AnimeBean>> = emptyMap()

    override suspend fun getSearchData(query: String, page: Int): List<AnimeBean> {
        if (query.isBlank()) return emptyList()
        val startedAt = System.currentTimeMillis()
        val text = fetchCmsJson("/api.php/provide/vod/?ac=detail&wd=${encode(query)}&pg=$page")
        val items = parseCmsSearchItems(text, baseUrl, modeTag)
        val elapsed = System.currentTimeMillis() - startedAt
        "CMS search mode=$modeTag kw=$query page=$page total=${items.size} ms=$elapsed"
            .log("BehaviorTrace")
        return items
    }

    override suspend fun getAnimeDetail(detailUrl: String): AnimeDetailBean {
        // 搜索结果是绝对地址（可能带旧域名）：只取「路径 + 查询」重新按候选顺序请求，
        // 这样主域名失效后已缓存的旧详情地址也能落到新域名上
        val target = CmsDomainPolicy.requestTarget(detailUrl)
        val text = if (target == null) fetchText(detailUrl) else fetchCmsJson(target)
        return parseCmsDetail(text, modeTag)
    }

    override suspend fun getVideoData(episodeUrl: String): VideoBean {
        if (!isPlayableMediaUrl(episodeUrl)) {
            // 非媒体地址（分享页/脚本）直接判失败，让上层按集号换源，不把垃圾送进播放器
            throw IllegalStateException("$modeTag：取到的地址不是视频（$episodeUrl）")
        }
        "CMS play mode=$modeTag url=$episodeUrl".log("BehaviorTrace")
        return VideoBean(episodeUrl)
    }

    /**
     * 按候选顺序请求 `{domain}{target}`；快速失败换下一个，成功即更新 [baseUrl]。
     */
    private suspend fun fetchCmsJson(target: String): String {
        val outcome = failover.fetch(
            domains = domainCandidates(),
            fetch = { domain -> fetchText(domain + target) },
            onExhausted = { tried -> remoteDomains(tried) },
        )
        val accepted = outcome.domain
        if (accepted != CmsDomainPolicy.canonical(baseUrl)) {
            baseUrl = accepted
            "CMS domain switch mode=$modeTag base=$accepted tried=${outcome.tried.size}"
                .log("BehaviorTrace")
        }
        return outcome.body
    }

    /**
     * 候选顺序：当前域名（用户自定义或上次成功的）→ 清单缓存 → 内置。
     * 当前域名放第一位，避免「已经换到能用的域名，下次又从死掉的自定义域名重试一遍」。
     */
    private fun domainCandidates(): List<String> {
        val current = CmsDomainPolicy.canonical(baseUrl)
        val ordered = CmsDomainPolicy.orderedDomains(
            userDomain = userDomainOrNull(),
            cached = sharedSourcesLoader.cachedDomains(modeTag),
            builtIn = CANDIDATE_DOMAINS,
        )
        return if (current.isEmpty()) ordered else listOf(current) + ordered.filterNot { it == current }
    }

    /**
     * 首批候选全挂后的「二次机会」：只有内置候选**全部**试过才去拉远程清单（常态零请求）。
     * 拉到的域名最多再用 [CmsDomainPolicy.MAX_FALLBACK_CANDIDATES] 个。
     */
    private suspend fun remoteDomains(tried: Set<String>): List<String> {
        val builtIn = CANDIDATE_DOMAINS.map { CmsDomainPolicy.canonical(it) }
        if (!tried.containsAll(builtIn)) return emptyList()
        "CMS domain fallback mode=$modeTag try=${tried.size} pull=${CmsSourcesManifest.FILE_NAME}"
            .log("BehaviorTrace")
        val domains = sharedSourcesLoader.load(modeTag)
        val extra = domains.filterNot { it in tried }
            .take(CmsDomainPolicy.MAX_FALLBACK_CANDIDATES)
        if (extra.isNotEmpty()) {
            "CMS domain fallback mode=$modeTag try=${tried.size} extra=${extra.size}"
                .log("BehaviorTrace")
        }
        return extra
    }

    /** 用户自定义域名（设置页填写）；没填或读不到返回 null */
    private fun userDomainOrNull(): String? = try {
        AnimeApplication.getInstance().preferences.getString(KEY_SOURCE_DOMAIN, null)
    } catch (e: Exception) {
        null
    }

    private suspend fun fetchText(url: String): String =
        DownloadManager.getHtml(
            url = url,
            headers = mapOf("User-Agent" to SIMPLE_UA),
            connectTimeoutMs = REQUEST_TIMEOUT_MS,
            requestTimeoutMs = REQUEST_TIMEOUT_MS,
        )

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    companion object {
        /** 实测：完整 Chrome UA 更容易触发挑战页，简洁 UA 反而稳定 */
        internal const val SIMPLE_UA = "Mozilla/5.0"
        internal const val REQUEST_TIMEOUT_MS = 10_000L

        /** 远程域名清单的缓存目录（filesDir 下，小文件，不进 SharedPreferences） */
        private const val SOURCES_CACHE_DIR = "cms_sources"

        /**
         * 远程域名清单加载器：所有采集源共用一份（同一个缓存文件、同一条失败退避）。
         * 惰性初始化：只有真的走到「内置候选全挂」才会创建。
         */
        internal val sharedSourcesLoader: CmsSourcesLoader by lazy {
            CmsSourcesLoader(
                cache = FileCmsSourcesCache(
                    File(AnimeApplication.getInstance().filesDir, SOURCES_CACHE_DIR),
                ),
                fetch = { url ->
                    DownloadManager.getHtml(
                        url = url,
                        headers = mapOf("User-Agent" to SIMPLE_UA),
                        connectTimeoutMs = CmsSourcesLoader.REQUEST_TIMEOUT_MS,
                        requestTimeoutMs = CmsSourcesLoader.REQUEST_TIMEOUT_MS,
                    )
                },
            )
        }

        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * 苹果CMS `vod_play_url` 解析：
         * - 线路之间用 `$$$` 分隔，线路内剧集用 `#` 分隔，每集 `名称$地址`；
         * - 只收明文 `.m3u8/.mp4`，分享页/脚本地址直接丢弃（全丢 = 无剧集）；
         * - 键保持线路原始序号（详情页按 `keys.sorted()` 遍历，不按下标）。
         */
        internal fun parsePlayUrls(raw: String): Map<Int, List<EpisodeBean>> {
            if (raw.isBlank()) return emptyMap()
            val result = LinkedHashMap<Int, List<EpisodeBean>>()
            raw.split("$$$").forEachIndexed { index, group ->
                val episodes = group.split("#").mapNotNull { entry ->
                    val separator = entry.indexOf('$')
                    if (separator <= 0) return@mapNotNull null
                    val name = entry.substring(0, separator).trim()
                    val url = entry.substring(separator + 1).trim()
                    if (name.isEmpty() || !isPlayableMediaUrl(url)) return@mapNotNull null
                    EpisodeBean(name = name, url = url)
                }
                if (episodes.isNotEmpty()) result[index] = episodes
            }
            return result
        }

        /** 搜索响应 → 条目列表；`list` 缺失/为空/非 JSON 时返回空列表（不抛异常） */
        internal fun parseCmsSearchItems(
            text: String,
            baseUrl: String,
            modeTag: String,
        ): List<AnimeBean> {
            val items = parseListObjects(text, modeTag)
            return items.mapNotNull { item ->
                val title = item.str("vod_name")
                val vodId = item.long("vod_id")
                if (title.isBlank() || vodId <= 0) return@mapNotNull null
                AnimeBean(
                    title = title,
                    img = item.str("vod_pic"),
                    url = "${baseUrl.trim().trimEnd('/')}/api.php/provide/vod/?ac=detail&ids=$vodId",
                    episodeName = item.str("vod_remarks"),
                )
            }
        }

        /** 详情响应 → 详情模型；多线路解析成 `channels`，`episodes` 置空 */
        internal fun parseCmsDetail(text: String, modeTag: String): AnimeDetailBean {
            val item = parseListObjects(text, modeTag).firstOrNull()
                ?: throw IllegalStateException("$modeTag：详情为空")
            val title = item.str("vod_name")
            if (title.isBlank()) throw IllegalStateException("$modeTag：详情标题为空")
            val channels = parsePlayUrls(item.str("vod_play_url"))
            val desc = item.str("vod_blurb").ifBlank { item.str("vod_content") }
                .let { stripHtml(it) }
            val tags = listOf(item.str("type_name"), item.str("vod_year"))
                .filter { it.isNotBlank() }
            "CMS detail mode=$modeTag id=${item.long("vod_id")} " +
                "eps=${channels.values.sumOf { it.size }} lines=${channels.size}"
                .log("BehaviorTrace")
            return AnimeDetailBean(
                title = title,
                imgUrl = item.str("vod_pic"),
                desc = desc,
                tags = tags,
                relatedAnimes = emptyList(),
                episodes = emptyList(),
                channels = channels,
                totalEpisodes = item.int("vod_total"),
            )
        }

        private fun parseListObjects(text: String, modeTag: String): List<JsonObject> {
            // 去 BOM/空白：带 BOM 的 JSON 不能被误判成挑战页
            val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            if (!CmsDomainPolicy.looksLikeJsonObject(trimmed)) {
                // 关键词要能命中 SourceRiskSignal（403/验证码/challenge），否则风控降级不会触发
                throw IllegalStateException(
                    "$modeTag：返回非 JSON（疑似挑战页/验证码/403 或站点改版）",
                )
            }
            val root = try {
                JSON.parseToJsonElement(trimmed) as? JsonObject
            } catch (e: Exception) {
                throw IllegalStateException("$modeTag：JSON 解析失败（${e.message}）", e)
            } ?: throw IllegalStateException("$modeTag：JSON 根节点不是对象")
            val array: JsonArray = root["list"] as? JsonArray ?: return emptyList()
            return array.mapNotNull { it as? JsonObject }
        }

        // 字段可能是 JsonNull/JsonObject/JsonArray：强转 jsonPrimitive 会抛异常，必须先判类型
        private fun JsonObject.str(key: String): String =
            (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()

        private fun JsonObject.long(key: String): Long =
            (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L

        private fun JsonObject.int(key: String): Int =
            (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0

        private fun stripHtml(html: String): String =
            if (html.isBlank()) "" else Jsoup.parse(html).text()
    }
}
