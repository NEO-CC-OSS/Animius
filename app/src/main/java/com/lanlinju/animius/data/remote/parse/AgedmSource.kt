package com.lanlinju.animius.data.remote.parse

import com.lanlinju.animius.data.remote.dto.AnimeBean
import com.lanlinju.animius.data.remote.dto.AnimeDetailBean
import com.lanlinju.animius.data.remote.dto.EpisodeBean
import com.lanlinju.animius.data.remote.dto.HomeBean
import com.lanlinju.animius.data.remote.dto.VideoBean
import com.lanlinju.animius.data.remote.parse.util.WebViewUtil
import com.lanlinju.animius.util.DownloadManager
import com.lanlinju.animius.util.getDefaultDomain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.select.Elements

object AgedmSource : AnimeSource {

    private const val LOG_TAG = "AgedmSource"

    /** WebView 兜底的拦截规则：只认真正的媒体请求（原 `obj|video|playurl|hsl|bili` 会命中对象存储与脚本） */
    private const val MEDIA_URL_REGEX = ".m3u8|.mp4"

    /** 顺延线路之间的礼貌间隔（每条目最多试 3 条，串行，不并发） */
    private const val LINE_RETRY_DELAY_MS = 300L

    /** 单条顺延线路的超时（覆盖全局 30s/300s，避免加密线路条目起播被拖死） */
    private const val LINE_CONNECT_TIMEOUT_MS = 8_000L
    private const val LINE_REQUEST_TIMEOUT_MS = 15_000L

    override val DEFAULT_DOMAIN: String = "https://www.agedm.io/"

    override var baseUrl: String = getDefaultDomain()

    private val webViewUtil: WebViewUtil by lazy { WebViewUtil() }

    override fun onExit() {
        webViewUtil.clearWeb()
    }

    override suspend fun getWeekData(): MutableMap<Int, List<AnimeBean>> {
        val source = DownloadManager.getHtml(baseUrl)
        val document = Jsoup.parse(source)
        val weekMap = mutableMapOf<Int, List<AnimeBean>>()
        document.select("div.text_list_box").select("div.tab-pane")
            .forEachIndexed { index, element ->
                val dayList = mutableListOf<AnimeBean>()
                element.select("li").forEach { el ->
                    val title = el.select("a").text()
                    val episodeName = el.select("div.title_sub").text()
                    val url = el.select("a").attr("href")
                    dayList.add(AnimeBean(title = title, img = "", url = url, episodeName))
                }
                weekMap[index] = dayList
            }
        return weekMap
    }

    override suspend fun getSearchData(query: String, page: Int): List<AnimeBean> {
        val source = DownloadManager.getHtml("$baseUrl/search?query=$query&page=$page")
        val document = Jsoup.parse(source)
        val animeList = mutableListOf<AnimeBean>()
        document.select("div.card").forEach { el ->
            val title = el.select("h5").text()
            val url = el.select("h5 > a").attr("href")
            val imgUrl = el.select("img").attr("data-original")
            animeList.add(AnimeBean(title = title, img = imgUrl, url = url))
        }
        return animeList
    }

    override suspend fun getHomeData(): List<HomeBean> {
        val source = DownloadManager.getHtml(baseUrl)
        val document = Jsoup.parse(source)

        val homeBeanList = mutableListOf<HomeBean>()
        document.select("div.container").select("div.video_list_box").forEach { element ->
            val title = element.select("h6").text().replace("更多 »", "")
            val moreUrl = element.select("a").attr("href")
            val homeItemBeanList = getAnimeList(element.select("div.video_item"))
            homeBeanList.add(HomeBean(title = title, moreUrl = moreUrl, animes = homeItemBeanList))
        }

        return homeBeanList
    }

    override suspend fun getAnimeDetail(detailUrl: String): AnimeDetailBean {
        val source = DownloadManager.getHtml(detailUrl.toHttps())
        val document = Jsoup.parse(source)
        val videoDetailRight = document.select("div.video_detail_right")
        val title = videoDetailRight.select("h2").text()
        val desc = videoDetailRight.select("div.video_detail_desc").text()
        val imgUrl = document.select("div.video_detail_cover > img").attr("data-original")
        val detailBoxList = document.select("div.video_detail_box").select("li")
        val tags = detailBoxList[9].text().split("：")[1].split(" ").toMutableList()
        tags.add(detailBoxList[0].text().split("：")[1])
        tags.add(detailBoxList[1].text().split("：")[1])
        val playlist = document.select("div.tab-content").select("div.tab-pane")
        val channels = getAnimeEpisodes(playlist)
        val relatedAnimes =
            getAnimeList(document.select("div.video_list_box").select("div.video_item"))
        val animeDetailBean =
            AnimeDetailBean(title, imgUrl, desc, tags, relatedAnimes, channels = channels)

        return animeDetailBean
    }

    override suspend fun getVideoData(episodeUrl: String): VideoBean {
        val source = DownloadManager.getHtml("${baseUrl}/$episodeUrl")
        val document = Jsoup.parse(source)
        /*val elements = document.select("div.cata_video_item")
        val title = elements.select("h5").text()
        var episodeName = ""
        val playlist = document.select("div.playlist-source-tab").select("div.tab-pane")
        val episodes = getAnimeEpisodes(playlist, action = { episodeName = it })*/
        val videoUrl = getVideoUrl(document, episodeUrl)

        return VideoBean(videoUrl)
    }

    private suspend fun getAnimeList(elements: Elements): List<AnimeBean> {
        val animeList = mutableListOf<AnimeBean>()
        elements.forEach { el ->
            val title = el.select("a").text()
            val url = el.select("a").attr("href")
            val imgUrl = el.select("img").attr("data-original")
            val episodeName = el.select("span.video_item--info").text()
            animeList.add(AnimeBean(title = title, img = imgUrl, url = url, episodeName))
        }
        return animeList
    }

    private suspend fun getAnimeEpisodes(elements: Elements): Map<Int, List<EpisodeBean>> {
        val raw = elements.map { e ->
            e.select("li").map { el ->
                EpisodeBean(name = el.text(), url = el.select("a").attr("href"))
            }
        }
        return buildAgedmChannels(raw)
    }

    private suspend fun getVideoUrl(document: Document, episodeUrl: String): String {
        val iframeUrl = document.select("#iframeForVideo").attr("src")
        if (iframeUrl.isBlank()) {
            error("Age动漫：该条目在网页端不提供播放（站点内容限制，如版权下架）")
        }
        if (iframeUrl.contains("/m3u8/")) {
            // /m3u8/ 明文线路：解析器页直接暴露 var Vurl，HTTP 直取即可
            // （hls.js 经 XHR 拉 m3u8，WebView 的 onLoadResource 对 XHR 不可靠，故不走拦截）
            fetchVurl(iframeUrl)?.let { return it.requirePlayable() }
        } else {
            // /vip/ 等加密线路：先纯 HTTP 顺延同条目其它线路（line+1..+3），找明文 /m3u8/ 线路。
            // 与体检脚本 AgedmProbe.video 口径对齐；顺延全失败才交 WebView（WASM 签名无法纯 HTTP 解）。
            findM3u8Iframe(episodeUrl)?.let { m3u8Iframe ->
                fetchVurl(m3u8Iframe)?.let { return it.requirePlayable() }
            }
        }
        // WebView 兜底：执行站点脚本拦截媒体请求，规则只认 .m3u8/.mp4
        return webViewUtil.interceptRequest(url = iframeUrl, regex = MEDIA_URL_REGEX).requirePlayable()
    }

    /** /m3u8/ 解析器页：明文 `var Vurl = '...'`（无需 Referer/Cookie），取不到返回 null */
    private suspend fun fetchVurl(parserUrl: String): String? {
        val page = DownloadManager.getHtml(parserUrl)
        return Regex("var Vurl = '([^']+)'").find(page)?.groupValues?.get(1)
    }

    /**
     * 顺延同条目的其它线路（最多 3 条），返回含 `/m3u8/` 的 iframe 地址；找不到返回 null。
     *
     * 单条线路请求失败只跳过、不抛异常——顺延本来就是「能捡到就捡」，
     * 最终兜底仍由 WebView 负责。
     */
    private suspend fun findM3u8Iframe(episodeUrl: String): String? {
        for ((index, pageUrl) in playPageUrls(episodeUrl, baseUrl).withIndex()) {
            if (index > 0) delay(LINE_RETRY_DELAY_MS)
            val html = try {
                // 顺延是「尽力而为」：单条线路给较短超时，慢线路直接跳过，别把起播拖成几十秒
                DownloadManager.getHtml(
                    pageUrl,
                    connectTimeoutMs = LINE_CONNECT_TIMEOUT_MS,
                    requestTimeoutMs = LINE_REQUEST_TIMEOUT_MS,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            val iframeUrl = Jsoup.parse(html).select("#iframeForVideo").attr("src")
            if (iframeUrl.contains("/m3u8/")) return iframeUrl
        }
        return null
    }

    /** 取址校验：非媒体地址直接判失败，让上层按集号自动换源（不再把 .js 送进播放器） */
    private fun String.requirePlayable(): String {
        if (!isPlayableMediaUrl(this)) {
            throw IllegalStateException("Age动漫：取到的地址不是视频（$this）")
        }
        return this
    }

    private fun String.toHttps(): String = replace(Regex("^http:"), "https:")
}

