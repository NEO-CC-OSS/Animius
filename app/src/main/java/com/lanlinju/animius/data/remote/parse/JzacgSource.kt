package com.lanlinju.animius.data.remote.parse

import com.lanlinju.animius.data.remote.dto.AnimeBean
import com.lanlinju.animius.data.remote.dto.AnimeDetailBean
import com.lanlinju.animius.data.remote.dto.EpisodeBean
import com.lanlinju.animius.data.remote.dto.HomeBean
import com.lanlinju.animius.data.remote.dto.VideoBean
import com.lanlinju.animius.util.DownloadManager
import com.lanlinju.animius.util.getDefaultDomain
import com.lanlinju.animius.util.getDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 橘子动漫（jzacg.com）
 *
 * 2026-10-11 接入取证（tools/source_probe.py、源站评测报告-20261011.md）：
 * - 苹果CMS「神马MV版」模板，首页/详情/播放页为服务端渲染，可纯解析
 * - 搜索页/分类列表页/周表页均为 JS 渲染（服务端无数据）：搜索用 suggest 联想接口降级
 *   （联想式匹配，覆盖有限）；周表返回空
 * - 播放取址为原生直解（2026-10-11 二次取证并 python 端到端验证，替代初版 WebView 方案）：
 *   播放页 player_aaaa.url 为轮换密文 → 站方 cloudplay 中转页（ps=1 线路走 parse 域）→
 *   中转页内 config.url（AES 密文）+ 两个轮换 meta id → 按 id 派生 AES-128-CBC 密钥解出
 *   MP4 直链（.mknvideo 伪装后缀，sign 带过期时间戳，每次起播现解、不可缓存）
 * - 直链强制无 Referer（带 Referer 会被 400），VideoBean headers 留空即不带
 * - 站点 DNS 间歇性解析失败、连接抖动频繁：全请求带重试；封面必须 http(s) 域
 * - 封面在 img 的 data-src（src 为 1px 占位图）；图床 302 到轮换节点（重试逻辑见
 *   AnimeApplication.imageRetryInterceptor），图片域无防盗链
 */
class JzacgSource : AnimeSource {

    override val DEFAULT_DOMAIN = "https://www.jzacg.com/"
    override var baseUrl: String = getDefaultDomain()

    /** 站内 href 均带前导斜杠，统一在此拼接，避免 Gugufan 式 "$baseUrl/$url" 的双斜杠 */
    private fun fullUrl(path: String): String = baseUrl.trimEnd('/') + path

    override suspend fun getHomeData(): List<HomeBean> = withRetry {
        val document = getDocument(baseUrl)
        val homeBeanList = mutableListOf<HomeBean>()
        document.select("div.box-width").forEach { element ->
            val title = element.select("h4.title-h").text().ifEmpty { element.select("h4").text() }
            val animes = element.select("div.public-list-box").mapNotNull { el ->
                val a = el.selectFirst("a.public-list-exp") ?: return@mapNotNull null
                val url = a.attr("href")
                // 资讯等非番剧区块（链接不含 /media/）在此过滤
                if (!url.contains("/media/")) return@mapNotNull null
                val title2 = a.attr("title").ifEmpty { el.select("div.public-list-button > a").text() }
                if (title2.isEmpty()) return@mapNotNull null
                AnimeBean(
                    title = title2,
                    img = el.select("img").attr("data-src"),
                    url = url,
                    episodeName = el.select("span.public-list-prb").text()
                )
            }
            if (title.isNotEmpty() && animes.isNotEmpty()) {
                homeBeanList.add(HomeBean(title = title, animes = animes))
            }
        }
        homeBeanList
    }

    override suspend fun getAnimeDetail(detailUrl: String): AnimeDetailBean = withRetry {
        val document = getDocument(fullUrl(detailUrl))
        val title = document.select("h3.slide-info-title").text()
        val imgUrl = document.select(".detail-pic img").attr("data-src")
        // 简介：#height_limit 与参数块"简介："同文；参数块的 em 文本实为"简介："（带冒号），
        // 取 id 元素更稳（取证 2026-10-11）
        val desc = document.selectFirst("#height_limit")?.text().orEmpty()
        val tags = document.select("span.slide-info-remarks").eachText()
            .filter { it.isNotEmpty() }
            .distinct()
            .take(6)
        // 相关影片：区块 h4.title-h 文本=相关影片，容器 div.public-r.hide-b-16 在 media 页唯一命中
        val relatedAnimes = document.select("div.public-r.hide-b-16 div.public-list-box")
            .mapNotNull { el ->
                val a = el.selectFirst("a.public-list-exp") ?: return@mapNotNull null
                val url = a.attr("href")
                if (!url.contains("/media/")) return@mapNotNull null
                val relatedTitle = a.attr("title").ifEmpty { el.select("a.time-title").text() }
                if (relatedTitle.isEmpty()) return@mapNotNull null
                AnimeBean(title = relatedTitle, img = el.select("img").attr("data-src"), url = url)
            }
            .distinctBy { it.url }
        AnimeDetailBean(
            title = title,
            imgUrl = imgUrl,
            desc = desc,
            tags = tags,
            relatedAnimes = relatedAnimes,
            channels = getAnimeChannels(document)
        )
    }

    /** 多播放源：div.anthology-tab a（源名 tab）与 div.anthology-list-box 按顺序一一对应；sid 以集链接 href 为准 */
    private fun getAnimeChannels(document: org.jsoup.nodes.Document): Map<Int, List<EpisodeBean>> {
        val channels = mutableMapOf<Int, List<EpisodeBean>>()
        document.select("div.anthology-list-box").forEachIndexed { index, box ->
            val episodes = mutableListOf<EpisodeBean>()
            box.select("ul.anthology-list-play li a").forEach { a ->
                val href = a.attr("href")
                if (href.contains("/bangumi/") && a.text().isNotEmpty()) {
                    episodes.add(EpisodeBean(name = a.text(), url = href))
                }
            }
            if (episodes.isNotEmpty()) channels[index] = episodes
        }
        return channels
    }

    /**
     * 播放取址原生直解链（每步细节见类注释；python 端到端已验证）。
     * 全链路包重试：密文每请求轮换，重试时整链重来即用新密文，无状态。
     */
    override suspend fun getVideoData(episodeUrl: String): VideoBean = withRetry {
        // 1) 播放页：player_aaaa JSON（url=站方密文、from=线路标识）
        val html = DownloadManager.getHtml(fullUrl(episodeUrl))
        val playerJson = PLAYER_DATA_REGEX.find(html)?.groupValues?.get(1)
            ?: error("橘子：播放页未找到 player_aaaa 数据")
        val player = Json.parseToJsonElement(playerJson).jsonObject
        val encrypted = player["url"]?.jsonPrimitive?.content
            ?: error("橘子：player_aaaa 缺少 url 字段")
        val from = player["from"]?.jsonPrimitive?.content ?: ""

        // 2) 线路 → 解析域（ps=1 的线路密文原样透传给 parse 域，取证自站方 player.js/playerconfig.js）
        val parseBase = resolveParseUrl(from)
            ?: error("橘子：未知线路 from=$from，站点配置可能已变更，需重新取证")

        // 3) cloudplay 中转页：config.url（新一轮 AES 密文）+ 派生密钥用的两个轮换 meta id
        val cloudplayHtml = DownloadManager.getHtml(parseBase + encrypted)
        val cloudDoc = Jsoup.parse(cloudplayHtml)
        val configJson = CONFIG_DATA_REGEX.find(cloudplayHtml)?.groupValues?.get(1)
            ?: error("橘子：播放中转页未找到 config 数据")
        val aesCipherText = Json.parseToJsonElement(configJson).jsonObject["url"]?.jsonPrimitive?.content
            ?: error("橘子：播放中转页 config 缺少 url 字段")
        val chars = cloudDoc.select("meta[name=viewport]").attr("id").removePrefix(META_ID_PREFIX)
        val digits = cloudDoc.select("meta[charset]").attr("id").removePrefix(META_ID_PREFIX)
        if (chars.isEmpty() || chars.length != digits.length || digits.any { !it.isDigit() }) {
            error("橘子：播放中转页 meta 指纹异常（页面结构可能已变更）")
        }

        // 4) 密钥派生：viewport id 字符与 charset id 数字按位配对、按数字升序拼接得 S，
        //    md5(S + 固定盐) 前 16 字节为 IV、后 16 字节为 AES-128 密钥
        val seed = chars.toList().zip(digits.map { it.digitToInt() })
            .sortedBy { it.second }
            .map { it.first }
            .joinToString("")
        val md5Hex = md5Hex(seed + DECRYPT_SALT)

        // 5) AES-128-CBC 解出 MP4 直链（sign 带过期时间戳，不可缓存）
        val key = md5Hex.substring(16, 32).toByteArray(Charsets.UTF_8)
        val iv = md5Hex.substring(0, 16).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val videoUrl = String(
            cipher.doFinal(Base64.getDecoder().decode(aesCipherText)),
            Charsets.UTF_8
        )
        VideoBean(videoUrl)
    }

    /** from → parse 域：已知线路查表，未知线路动态拉站方 playerconfig.js 解析 */
    private suspend fun resolveParseUrl(from: String): String? {
        KNOWN_PARSE_URLS[from]?.let { return it }
        return try {
            val js = DownloadManager.getHtml("$baseUrl/static/js/playerconfig.js")
            Regex("\"${Regex.escape(from)}\"\\s*:\\s*\\{[^}]*?\"parse\"\\s*:\\s*\"([^\"]+)\"")
                .find(js)?.groupValues?.get(1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 搜索降级：站方搜索页 JS 渲染无服务端结果，改用 suggest 联想接口
     * （GET /index.php/ajax/suggest?wd=&mid=1，返回 id/name/pic）。
     * 覆盖为联想式匹配（2026-10-11 实测"海贼王"仅命中"海贼王女"），非完整搜索结果。
     * 重试后仍失败返回空列表（SearchPagingSource 以空列表终止分页，不中断）。
     */
    override suspend fun getSearchData(query: String, page: Int): List<AnimeBean> {
        if (page > 1) return emptyList()
        return try {
            withRetry {
                val kw = URLEncoder.encode(query, "UTF-8")
                val body = DownloadManager.getHtml("$baseUrl/index.php/ajax/suggest?wd=$kw&mid=1")
                val list = Json.parseToJsonElement(body).jsonObject["list"]?.jsonArray
                    ?: return@withRetry emptyList()
                list.mapNotNull { el ->
                    val obj = el.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    AnimeBean(
                        title = name,
                        img = obj["pic"]?.jsonPrimitive?.content ?: "",
                        url = "/media/$id/"
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 站方周表页与数据接口均为 JS 渲染且 API 参数未破出（2026-10-11 取证），纯解析不可得，
     * 返回空：时间表页对本源显示空白，时间表数据请使用其他源。
     */
    override suspend fun getWeekData(): Map<Int, List<AnimeBean>> = emptyMap()

    /** 站点连接抖动频繁（DNS 失败/RST 实测频发）：统一重试，CancellationException 必须重抛 */
    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var lastError: Exception? = null
        repeat(REQUEST_RETRY_TIMES) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                delay(500L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("橘子：请求重试耗尽")
    }

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val PLAYER_DATA_REGEX = Regex("""player_aaaa\s*=\s*(\{.*?\})\s*[;<]""", RegexOption.DOT_MATCHES_ALL)
        val CONFIG_DATA_REGEX = Regex("""var\s+config\s*=\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL)
        const val META_ID_PREFIX = "now_"
        const val DECRYPT_SALT = "Mknacg123321"
        const val REQUEST_RETRY_TIMES = 3

        /** from → parse 域映射（取证自 /static/js/playerconfig.js；未知 from 走动态解析） */
        val KNOWN_PARSE_URLS = mapOf("2_" to "https://play.mknacg.top:8585/cloudplay/?url=")
    }
}
