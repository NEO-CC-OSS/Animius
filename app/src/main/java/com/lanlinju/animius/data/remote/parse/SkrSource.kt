package com.lanlinju.animius.data.remote.parse

import com.lanlinju.animius.data.remote.dto.AnimeBean
import com.lanlinju.animius.data.remote.dto.AnimeDetailBean
import com.lanlinju.animius.data.remote.dto.EpisodeBean
import com.lanlinju.animius.data.remote.dto.HomeBean
import com.lanlinju.animius.data.remote.dto.VideoBean
import com.lanlinju.animius.util.DownloadManager
import com.lanlinju.animius.util.getDefaultDomain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

/**
 * 樱之空动漫（skr.skr1.cc，非标端口 666）
 *
 * 2026-10-11 接入取证与全链路实测（tools/source_probe.py、源站评测报告-20261011.md）：
 * - 苹果CMS 自定义模板；未知路由返回 200 + 首页内容，各接口解析前必须校验内容特征
 * - 播放页 player_aaaa JSON 的 url 字段 = base64(urlencode(m3u8))，两步解码即得直链，无需 WebView
 * - 图片域对错误 Referer 直接断连（RST 而非 403）：本源所有请求不带 Referer（项目默认行为）
 * - 证书为 Let's Encrypt YR2 签发、公共 CA 链完整（openssl verify ok），老系统真机若报 SSL
 *   错误需往 TlsChainFix 内置证书清单补 YR2
 */
class SkrSource : AnimeSource {

    override val DEFAULT_DOMAIN = "https://skr.skr1.cc:666/"
    override var baseUrl: String = getDefaultDomain()

    /** 两站 href 均带前导斜杠，统一在此拼接，避免 Gugufan 式 "$baseUrl/$url" 的双斜杠 */
    private fun fullUrl(path: String): String = baseUrl.trimEnd('/') + path

    /**
     * 站方自 2026-10-15 起对纯 HTTP 请求弹人机验证（403 + renji_*.js）。
     * 验证本身是纯请求式的：解析 JS 里的 key/value/type → md5(ASCII 码拼接) → GET yanzheng_ip.php，
     * 服务端 Set-Cookie 后放行。ktor 的 HttpCookies 会在进程内自动保存并回带该 cookie。
     */
    private suspend fun fetchHtml(url: String, headers: Map<String, String> = emptyMap()): String {
        val html = DownloadManager.getHtml(url, headers)
        if (!isVerifyPage(html) || !passVerification(html)) return html
        return DownloadManager.getHtml(url, headers)
    }

    private suspend fun fetchDocument(url: String): Document = Jsoup.parse(fetchHtml(url))

    private fun isVerifyPage(html: String): Boolean =
        html.contains("bt-info") && html.contains("renji_")

    private suspend fun passVerification(page: String): Boolean {
        val jsPath = VERIFY_JS_SRC_REGEX.find(page)?.groupValues?.get(1) ?: return false
        val js = DownloadManager.getHtml(fullUrl(jsPath), mapOf("Referer" to baseUrl))
        val key = VERIFY_KEY_REGEX.find(js)?.groupValues?.get(1) ?: return false
        val value = VERIFY_VALUE_REGEX.find(js)?.groupValues?.get(1) ?: return false
        val type = VERIFY_TYPE_REGEX.find(js)?.groupValues?.get(1) ?: return false
        val path = VERIFY_PATH_REGEX.find(js)?.groupValues?.get(1) ?: return false
        // 站方 JS 的 stringtoHex 实际是把每个字符的 charCode 十进制拼接（不是十六进制）
        val sig = md5(value.map { it.code.toString() }.joinToString(""))
        val verifyUrl = fullUrl(path.substringBefore("?")) + "?type=$type&key=$key&value=$sig"
        val resp = DownloadManager.getHtml(
            verifyUrl,
            mapOf("Referer" to baseUrl, "X-Requested-With" to "XMLHttpRequest"),
        )
        return resp.isNotEmpty()
    }

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    override suspend fun getHomeData(): List<HomeBean> {
        val document = fetchDocument(baseUrl)
        val homeBeanList = mutableListOf<HomeBean>()
        document.select("div.pannel.clearfix").forEach { panel ->
            // 仅抓带通用条目列表的区块；"换一换"隐藏组（.cbox2/.cbox3.hide）不在 ul.vodlist_sy 内，天然排除
            val items = panel.select("ul.vodlist_sy > li.vodlist_item")
            if (items.isEmpty()) return@forEach
            val title = panel.select("div.pannel_head").select("h2").text()
                .ifEmpty { panel.select("div.pannel_head").text() }
            val animes = items.mapNotNull { parseVodlistItem(it) }
            if (title.isNotEmpty() && animes.isNotEmpty()) {
                homeBeanList.add(HomeBean(title = title, animes = animes))
            }
        }
        return homeBeanList
    }

    /** 首页/时间表/相关推荐共用的条目结构：封面在 a 的 data-original、标题在 alt 与 titbox（周表区块的 a 是 ranklist_thumb） */
    private fun parseVodlistItem(item: Element): AnimeBean? {
        val a = item.selectFirst("a.vodlist_thumb")
            ?: item.selectFirst("a.ranklist_thumb")
            ?: return null
        val url = a.attr("href")
        if (!url.contains("/voddetail/")) return null
        val title = item.select("div.vodlist_titbox p.vodlist_title a").text()
            .ifEmpty { a.attr("alt") }
        if (title.isEmpty()) return null
        val episodeName = item.selectFirst("span.pic_text")?.let { span ->
            span.select("p").text().ifEmpty { span.ownText() }
        } ?: ""
        return AnimeBean(title = title, img = a.attr("data-original"), url = url, episodeName = episodeName)
    }

    override suspend fun getWeekData(): Map<Int, List<AnimeBean>> {
        // 实测结构（2026-10-11 取证）：ul.vodlist_sjb 包在 div.vodlist_smt 内，归属由其前兄弟
        // div.pannel_head 内 h2.title 文本（周一..周日）决定。day1 的 id 挂在 <body> 上、"上季完结"
        // 区块无星期，均不能按 id 或 DOM 顺序假设，故按标题文本映射
        val document = fetchDocument(fullUrl("/vodtype/22/"))
        val weekMap = mutableMapOf<Int, List<AnimeBean>>()
        document.select("ul.vodlist_sjb").forEach { ul ->
            val weekIndex = weekIndexOf(ul) ?: return@forEach
            val animes = ul.select("li.vodlist_item").mapNotNull { parseVodlistItem(it) }
            if (animes.isNotEmpty()) weekMap[weekIndex] = animes
        }
        return weekMap
    }

    private fun weekIndexOf(ul: Element): Int? {
        val anchor = ul.parent() ?: ul
        val head = generateSequence(anchor.previousElementSibling()) { it.previousElementSibling() }
            .firstOrNull { it.hasClass("pannel_head") }
        val label = head?.selectFirst("h2.title")?.text()?.trim()
        return WEEK_LABEL_INDEX[label]
    }

    override suspend fun getSearchData(query: String, page: Int): List<AnimeBean> {
        // 站点搜索无分页（单页 ≤18 条）；SearchPagingSource 以空列表终止分页
        if (page > 1) return emptyList()
        val kw = URLEncoder.encode(query, "UTF-8")
        // 搜索地址必须为「关键词 + 13 个横杠」且不带 .html 后缀，否则 302 回首页
        val document = fetchDocument(fullUrl("/vodsearch/$kw-------------/"))
        if (document.selectFirst("li.searchlist_item") == null) return emptyList()
        return document.select("li.searchlist_item").mapNotNull { item ->
            val a = item.selectFirst("a.vodlist_thumb") ?: return@mapNotNull null
            val url = a.attr("href")
            if (!url.contains("/voddetail/")) return@mapNotNull null
            val title = item.select("h4.vodlist_title a").text().ifEmpty { a.attr("alt") }
            if (title.isEmpty()) return@mapNotNull null
            AnimeBean(title = title, img = a.attr("data-original"), url = url)
        }
    }

    override suspend fun getAnimeDetail(detailUrl: String): AnimeDetailBean {
        val document = fetchDocument(fullUrl(detailUrl))
        val title = document.select(".content_detail.content_top h1.title").text()
        val imgUrl = document.select(".content_thumb a.vodlist_thumb").attr("data-original")
        val desc = document.select(".content_desc.context span").text()
        // 元信息行结构松散（年份/地区/类型混排在 li.data 的 a 里），尽力提取去重供展示
        val tags = document.select("li.data a").eachText()
            .filter { it.isNotEmpty() && !it.contains("：") }
            .distinct()
            .take(6)
        // 相关推荐/猜你喜欢：div.container_d.pannel 内 h3.title 文本匹配 + 兄弟 ul.vodlist_sh（实测 2026-10-11）
        val relatedAnimes = document.select("div.container_d.pannel")
            .filter { panel ->
                val heading = panel.selectFirst("h3.title")?.text().orEmpty()
                heading.contains("相关推荐") || heading.contains("猜你喜欢")
            }
            .flatMap { panel ->
                panel.select("ul.vodlist_sh li.vodlist_item").mapNotNull { parseVodlistItem(it) }
            }
            .distinctBy { it.url }
        return AnimeDetailBean(
            title = title,
            imgUrl = imgUrl,
            desc = desc,
            tags = tags,
            relatedAnimes = relatedAnimes,
            channels = getAnimeChannels(document)
        )
    }

    /**
     * 多播放源：div#NumTab a（源名 tab，如"线一"）与 div.play_list_box 按顺序一一对应；
     * 各源的 sid 互不相同且与源名无规律，取集链接 href 里的 /vodplay/{id}-{sid}-{nid}/ 为准。
     * 每个源盒内含折叠（#playlistbox）与完整两份列表，只取第一份防重复。
     */
    private fun getAnimeChannels(document: org.jsoup.nodes.Document): Map<Int, List<EpisodeBean>> {
        val channels = mutableMapOf<Int, List<EpisodeBean>>()
        document.select("div.play_list_box").forEachIndexed { index, box ->
            val ul = box.select("ul.content_playlist").firstOrNull() ?: box.selectFirst("ul")
            val episodes = mutableListOf<EpisodeBean>()
            ul?.select("li > a")?.forEach { a ->
                val href = a.attr("href")
                if (href.contains("/vodplay/") && a.text().isNotEmpty()) {
                    episodes.add(EpisodeBean(name = a.text(), url = href))
                }
            }
            if (episodes.isNotEmpty()) channels[index] = episodes
        }
        return channels
    }

    override suspend fun getVideoData(episodeUrl: String): VideoBean {
        val html = fetchHtml(fullUrl(episodeUrl))
        val jsonText = PLAYER_DATA_REGEX.find(html)?.groupValues?.get(1)
            ?: error("樱之空：播放页未找到 player_aaaa 数据")
        // url 字段 = base64(urlencode(m3u8))；不依赖 encrypt 字段（实测不同页面存在数字/字符串两种形态）
        val encoded = Json.parseToJsonElement(jsonText).jsonObject["url"]?.jsonPrimitive?.content
            ?: error("樱之空：player_aaaa 缺少 url 字段")
        val videoUrl = URLDecoder.decode(
            String(Base64.getDecoder().decode(encoded), Charsets.UTF_8),
            "UTF-8"
        )
        // m3u8 直链实测无需 Referer（第三方播放域直连可放）
        return VideoBean(videoUrl)
    }

    private companion object {
        val PLAYER_DATA_REGEX = Regex("""player_aaaa\s*=\s*(\{.*?\})\s*[;<]""", RegexOption.DOT_MATCHES_ALL)

        // 人机验证页（2026-10-15 起）：renji_*.js 内含 key/value/type 与验证接口路径
        val VERIFY_JS_SRC_REGEX = Regex("""src="([^"]*renji_[^"]*\.js[^"]*)"""")
        val VERIFY_KEY_REGEX = Regex("""key="([0-9a-f]+)"""")
        val VERIFY_VALUE_REGEX = Regex("""value="([0-9a-f]+)"""")
        val VERIFY_TYPE_REGEX = Regex("""type=([0-9a-f]+)&key=""")
        val VERIFY_PATH_REGEX = Regex("""c\.get\("([^"]+)"""")

        // 时间表页每天区块的 h2 标题 → WeekScreen 页序（0=周一 .. 6=周日）
        val WEEK_LABEL_INDEX = mapOf(
            "周一" to 0, "周二" to 1, "周三" to 2, "周四" to 3,
            "周五" to 4, "周六" to 5, "周日" to 6,
        )
    }
}
