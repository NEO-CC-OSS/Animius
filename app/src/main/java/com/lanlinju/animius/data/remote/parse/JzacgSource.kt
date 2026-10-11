package com.lanlinju.animius.data.remote.parse

import com.lanlinju.animius.data.remote.dto.AnimeBean
import com.lanlinju.animius.data.remote.dto.AnimeDetailBean
import com.lanlinju.animius.data.remote.dto.EpisodeBean
import com.lanlinju.animius.data.remote.dto.HomeBean
import com.lanlinju.animius.data.remote.dto.VideoBean
import com.lanlinju.animius.data.remote.parse.util.WebViewUtil
import com.lanlinju.animius.util.DownloadManager
import com.lanlinju.animius.util.getDefaultDomain
import com.lanlinju.animius.util.getDocument
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * 橘子动漫（jzacg.com）
 *
 * 2026-10-11 接入取证（tools/source_probe.py、源站评测报告-20261011.md）：
 * - 苹果CMS「神马MV版」模板，首页/详情/播放页为服务端渲染，可纯解析
 * - **搜索页、分类列表页、周表页均为 JS 渲染（服务端无数据）**，纯解析不可得：
 *   搜索用站方 suggest 联想接口降级（覆盖有限，联想式匹配）；周表返回空
 * - 播放页 player_aaaa 的 url 为站方二次加密，服务端无法还原直链，
 *   按用户拍板走 WebViewUtil 拦截兜底（隐形浏览器跑完站方解密 JS 后截取 m3u8/mp4）
 * - 站点 DNS 间歇性解析失败：ktor 超时自然报错（首页加载失败重进即可）
 * - 封面在 img 的 data-src（src 为 1px 占位图），图片域无防盗链
 */
class JzacgSource : AnimeSource {

    override val DEFAULT_DOMAIN = "https://www.jzacg.com/"
    override var baseUrl: String = getDefaultDomain()

    private val webViewUtil: WebViewUtil by lazy { WebViewUtil() }

    override fun onExit() {
        webViewUtil.clearWeb()
    }

    /** 站内 href 均带前导斜杠，统一在此拼接，避免 Gugufan 式 "$baseUrl/$url" 的双斜杠 */
    private fun fullUrl(path: String): String = baseUrl.trimEnd('/') + path

    override suspend fun getHomeData(): List<HomeBean> {
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
        return homeBeanList
    }

    override suspend fun getAnimeDetail(detailUrl: String): AnimeDetailBean {
        val document = getDocument(fullUrl(detailUrl))
        val title = document.select("h3.slide-info-title").text()
        val imgUrl = document.select(".detail-pic img").attr("data-src")
        // 参数块：li 内 em=标签，值为其余文本；简介/状态等按标签取
        var desc = ""
        document.select("div.info-parameter li").forEach { li ->
            val label = li.select("em").text()
            val value = li.text().removePrefix(label).trim()
            when (label) {
                "简介" -> desc = value
            }
        }
        val tags = document.select("span.slide-info-remarks").eachText()
            .filter { it.isNotEmpty() }
            .distinct()
            .take(6)
        return AnimeDetailBean(
            title = title,
            imgUrl = imgUrl,
            desc = desc,
            tags = tags,
            relatedAnimes = emptyList(),
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
     * 播放地址为站方二次加密，WebView 拦截兜底：隐形浏览器加载播放页跑完站方解密 JS，
     * 拦截其发出的 m3u8/mp4 请求。比直链方式慢数秒（内核启动+页面 JS），只发生在换集瞬间。
     */
    override suspend fun getVideoData(episodeUrl: String): VideoBean {
        val videoUrl = webViewUtil.interceptRequest(
            url = fullUrl(episodeUrl),
            regex = "\\.m3u8|\\.mp4",
            timeoutMs = 20_000,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        )
        return VideoBean(videoUrl)
    }

    /**
     * 搜索降级：站方搜索页 JS 渲染无服务端结果，改用 suggest 联想接口
     * （GET /index.php/ajax/suggest?wd=&mid=1，返回 id/name/pic）。
     * 覆盖为联想式匹配（2026-10-11 实测"海贼王"仅命中"海贼王女"），非完整搜索结果。
     * 接口失败返回空列表（SearchPagingSource 以空列表终止分页，不中断）。
     */
    override suspend fun getSearchData(query: String, page: Int): List<AnimeBean> {
        if (page > 1) return emptyList()
        return try {
            val kw = URLEncoder.encode(query, "UTF-8")
            val body = DownloadManager.getHtml("$baseUrl/index.php/ajax/suggest?wd=$kw&mid=1")
            val list = Json.parseToJsonElement(body).jsonObject["list"]?.jsonArray
                ?: return emptyList()
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
}
