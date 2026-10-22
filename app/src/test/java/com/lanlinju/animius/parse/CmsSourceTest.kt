package com.lanlinju.animius.parse

import com.lanlinju.animius.data.remote.parse.CmsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采集源（苹果CMS JSON）解析测试（tv.9 切片 1）。
 *
 * 覆盖：
 * - `vod_play_url` 的线路/剧集拆分与明文过滤（分享页、脚本一律丢弃）；
 * - 搜索响应字段缺失/类型漂移（vod_id 是字符串）不崩；
 * - 详情响应多线路 → channels，episodes 置空；
 * - 非 JSON（挑战页/站点改版）明确抛错，让上层记为失败原因。
 */
class CmsSourceTest {

    @Test
    fun `parse single line episodes`() {
        val raw = "第01集\$https://cdn.example.com/1.m3u8#第02集\$https://cdn.example.com/2.m3u8"
        val channels = CmsSource.parsePlayUrls(raw)
        assertEquals(1, channels.size)
        assertEquals(2, channels.getValue(0).size)
        assertEquals("第01集", channels.getValue(0)[0].name)
        assertEquals("https://cdn.example.com/1.m3u8", channels.getValue(0)[0].url)
    }

    @Test
    fun `parse multi lines keep original line index`() {
        val raw = "第01集\$https://a/1.m3u8#第02集\$https://a/2.m3u8" +
            "\$\$\$" +
            "第01集\$https://b/1.mp4#第02集\$https://b/2.mp4"
        val channels = CmsSource.parsePlayUrls(raw)
        assertEquals(2, channels.size)
        assertEquals("https://a/1.m3u8", channels.getValue(0).first().url)
        assertEquals("https://b/1.mp4", channels.getValue(1).first().url)
    }

    @Test
    fun `share page and script urls are dropped`() {
        val raw = "第01集\$https://site/share/123#第02集\$https://site/play.js"
        assertTrue(CmsSource.parsePlayUrls(raw).isEmpty())
    }

    @Test
    fun `empty play url gives no episodes`() {
        assertTrue(CmsSource.parsePlayUrls("").isEmpty())
        assertTrue(CmsSource.parsePlayUrls("   ").isEmpty())
    }

    @Test
    fun `search response maps fields and tolerates string vod_id`() {
        val json = """
            {"code":1,"list":[
              {"vod_id":"123","vod_name":"战车椅子","vod_pic":"https://img/1.jpg",
               "vod_remarks":"更新至02集","vod_year":"2026"},
              {"vod_id":456,"vod_name":"脑洞学生会","vod_pic":"","vod_remarks":""},
              {"vod_id":0,"vod_name":"没有id","vod_pic":""}
            ]}
        """.trimIndent()
        val items = CmsSource.parseCmsSearchItems(json, "https://cj.lziapi.com/", "Lz")
        assertEquals(2, items.size)
        assertEquals("战车椅子", items[0].title)
        assertEquals("更新至02集", items[0].episodeName)
        assertEquals(
            "https://cj.lziapi.com/api.php/provide/vod/?ac=detail&ids=123",
            items[0].url,
        )
        assertEquals("脑洞学生会", items[1].title)
    }

    @Test
    fun `empty list returns empty and non json throws`() {
        assertTrue(CmsSource.parseCmsSearchItems("""{"list":[]}""", "https://x/", "Lz").isEmpty())
        assertThrows(IllegalStateException::class.java) {
            CmsSource.parseCmsSearchItems("<html>challenge</html>", "https://x/", "Lz")
        }
    }

    @Test
    fun `detail response builds channels and strips html`() {
        val json = """
            {"list":[{
              "vod_id":1,"vod_name":"战车椅子","vod_pic":"https://img/1.jpg",
              "type_name":"动漫","vod_year":"2026",
              "vod_blurb":"<p>简介&amp;说明</p>",
              "vod_play_from":"线路一${'$'}${'$'}${'$'}线路二",
              "vod_play_url":"第01集${'$'}https://a/1.m3u8#第02集${'$'}https://a/2.m3u8${'$'}${'$'}${'$'}第01集${'$'}https://b/1.m3u8"
            }]}
        """.trimIndent()
        val detail = CmsSource.parseCmsDetail(json, "Lz")
        assertEquals("战车椅子", detail.title)
        assertEquals(2, detail.channels.size)
        assertEquals(2, detail.channels.getValue(0).size)
        assertEquals(1, detail.channels.getValue(1).size)
        assertTrue(detail.episodes.isEmpty())
        assertEquals("简介&说明", detail.desc)
        assertEquals(listOf("动漫", "2026"), detail.tags)
    }

    @Test
    fun `detail without list throws`() {
        assertThrows(IllegalStateException::class.java) {
            CmsSource.parseCmsDetail("""{"list":[]}""", "Lz")
        }
    }
}
