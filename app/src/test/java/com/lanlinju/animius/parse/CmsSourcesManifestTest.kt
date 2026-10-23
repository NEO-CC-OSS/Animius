package com.lanlinju.animius.parse

import com.lanlinju.animius.data.remote.parse.CmsSourcesCache
import com.lanlinju.animius.data.remote.parse.CmsSourcesLoader
import com.lanlinju.animius.data.remote.parse.CmsSourcesManifest
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远程域名清单 `sources.json`（2026-10-23 任务 C②）。
 *
 * 覆盖：解析与校验（合法/非法/缺字段/schema 不认识）、通道顺序（跳过 api.github.com）、
 * 24 h 缓存不重复拉、远端 404（清单未上线）也记 24 h、网络失败不记、
 * 拉取失败静默回退缓存/内置（绝不抛异常）。
 */
class CmsSourcesManifestTest {

    private val now = 1_000_000_000L

    private val validJson = """
        {"schema":1,"updated_at":"2026-10-23T00:00:00Z",
         "sources":{
           "Lz":{"domains":["https://cj.lziapi.com/"]},
           "Bf":{"domains":["https://bfzyapi.com/","https://app.bfzyapi.com/"]},
           "Md":{"domains":["https://www.mdzyapi.com/"]}}}
    """.trimIndent()

    private class FakeCache : CmsSourcesCache {
        var raw: String? = null
        var fetchedAt: Long = 0L
        var writes: Int = 0
        var missingWrites: Int = 0

        override fun readRaw(): String? = raw

        override fun readFetchedAtMs(): Long = fetchedAt

        override fun write(raw: String, fetchedAtMs: Long) {
            this.raw = raw
            this.fetchedAt = fetchedAtMs
            writes++
        }

        override fun writeMissing(fetchedAtMs: Long) {
            this.fetchedAt = fetchedAtMs
            missingWrites++
        }
    }

    private fun loader(
        cache: FakeCache,
        fetch: suspend (String) -> String,
    ) = CmsSourcesLoader(cache = cache, fetch = fetch, now = { now }, log = {})

    // ---------------------------------------------------------------- 解析

    @Test
    fun `parse valid manifest`() {
        val manifest = CmsSourcesManifest.parse(validJson)!!
        assertEquals("2026-10-23T00:00:00Z", manifest.updatedAt)
        assertEquals(3, manifest.sources.size)
        assertEquals(listOf("https://cj.lziapi.com"), manifest.domainsOf("Lz"))
        assertEquals(
            listOf("https://bfzyapi.com", "https://app.bfzyapi.com"),
            manifest.domainsOf("Bf"),
        )
        assertTrue(manifest.domainsOf("Agedm").isEmpty())
    }

    @Test
    fun `unknown schema is dropped entirely`() {
        assertNull(CmsSourcesManifest.parse(validJson.replace("\"schema\":1", "\"schema\":2")))
    }

    @Test
    fun `missing or broken fields are dropped`() {
        assertNull(CmsSourcesManifest.parse("""{"schema":1}"""))
        assertNull(CmsSourcesManifest.parse("""{"sources":{"Lz":{"domains":["https://a.com"]}}}"""))
        assertNull(CmsSourcesManifest.parse("""{"schema":1,"sources":{}}"""))
        assertNull(CmsSourcesManifest.parse("404: Not Found"))
        assertNull(CmsSourcesManifest.parse("<html>challenge</html>"))
    }

    @Test
    fun `invalid domains are dropped but valid ones kept`() {
        val raw = """
            {"schema":1,"updated_at":"x","sources":{"Lz":{"domains":[
              "https://cj.lziapi.com/",
              "http://plain-http.com/",
              "https://",
              "https://bad_host.com/",
              "https://a.com/api/",
              "https://a.com:8080/",
              "https://1.2.3.4/",
              "https://999.1.1.1/",
              "https://sub.example.co/"
            ]}}}
        """.trimIndent()
        val manifest = CmsSourcesManifest.parse(raw)!!
        assertEquals(
            listOf("https://cj.lziapi.com", "https://1.2.3.4", "https://sub.example.co"),
            manifest.domainsOf("Lz"),
        )
    }

    @Test
    fun `source without domains is dropped`() {
        val raw = """
            {"schema":1,"sources":{
              "Lz":{"domains":[]},
              "Bf":{"other":1},
              "Md":{"domains":["https://www.mdzyapi.com/"]}}}
        """.trimIndent()
        val manifest = CmsSourcesManifest.parse(raw)!!
        assertEquals(setOf("Md"), manifest.sources.keys)
    }

    @Test
    fun `source with only invalid domains is dropped but manifest stays usable`() {
        val raw = """
            {"schema":1,"sources":{
              "Lz":{"domains":["http://bad.com/","https://bad_host.com/","https://a.com/api/"]},
              "Md":{"domains":["https://www.mdzyapi.com/"]}}}
        """.trimIndent()
        val manifest = CmsSourcesManifest.parse(raw)!!
        // 只丢这一个源，别因为一个源的域名全非法就把整份清单作废
        assertEquals(setOf("Md"), manifest.sources.keys)
        assertTrue(manifest.domainsOf("Lz").isEmpty())
        assertEquals(listOf("https://www.mdzyapi.com"), manifest.domainsOf("Md"))
    }

    @Test
    fun `manifest with no usable domain at all is dropped`() {
        val raw = """
            {"schema":1,"sources":{
              "Lz":{"domains":["http://bad.com/"]},
              "Bf":{"domains":["https://a.com:8080/"]}}}
        """.trimIndent()
        assertNull(CmsSourcesManifest.parse(raw))
    }

    @Test
    fun `channel order skips github api`() {
        val urls = CmsSourcesManifest.channelUrls()
        assertEquals(3, urls.size)
        assertTrue(urls[0].startsWith("https://gh-proxy.com/"))
        assertTrue(urls[1].startsWith("https://gcore.jsdelivr.net/"))
        assertTrue(urls[2].startsWith("https://testingcf.jsdelivr.net/"))
        assertTrue(urls.all { it.endsWith("/sources.json") })
        assertFalse(urls.any { it.contains("api.github.com") })
    }

    @Test
    fun `missing markers are recognized but html pages are not`() {
        assertTrue(CmsSourcesManifest.looksMissing("404: Not Found"))
        assertTrue(
            CmsSourcesManifest.looksMissing(
                "Couldn't find the requested file /gh/NEO-CC-OSS/Animius@metadata/sources.json " +
                    "in NEO-CC-OSS/Animius.",
            ),
        )
        // nginx 默认 404 页无冒号，也属「文件不存在」
        assertTrue(CmsSourcesManifest.looksMissing("<title>404 Not Found</title>"))
        assertFalse(CmsSourcesManifest.looksMissing("<html><body>challenge</body></html>"))
        assertFalse(CmsSourcesManifest.looksMissing(""))
        // 裸 404（无 not found 文案）不再认作「文件不存在」，避免 captive portal 误记 24 h
        assertFalse(CmsSourcesManifest.looksMissing("404"))
        // 长页面（captive portal / 挑战页）里出现 404 不算「文件不存在」
        assertFalse(CmsSourcesManifest.looksMissing("x".repeat(500) + "404"))
    }

    // ---------------------------------------------------------------- 拉取与缓存

    @Test
    fun `fresh cache skips network`() = runBlocking {
        val cache = FakeCache()
        cache.raw = validJson
        cache.fetchedAt = now - 60_000L
        var fetches = 0
        val domains = loader(cache) { fetches++; validJson }.load("Lz", graceMs = 5_000)
        assertEquals(0, fetches)
        assertEquals(listOf("https://cj.lziapi.com"), domains)
    }

    @Test
    fun `pull tries gh proxy first then jsdelivr`() = runBlocking {
        val cache = FakeCache()
        val urls = mutableListOf<String>()
        val domains = loader(cache) { url ->
            urls += url
            if (url.contains("gh-proxy")) "404: Not Found" else validJson
        }.load("Lz", graceMs = 5_000)

        assertEquals(2, urls.size)
        assertTrue(urls[0].contains("gh-proxy"))
        assertTrue(urls[1].contains("gcore.jsdelivr"))
        assertEquals(listOf("https://cj.lziapi.com"), domains)
        assertEquals(validJson, cache.raw)
        assertEquals(now, cache.fetchedAt)
        assertEquals(1, cache.writes)
        assertEquals(0, cache.missingWrites)
    }

    @Test
    fun `pull failure falls back silently`() = runBlocking {
        val cache = FakeCache()
        var fetches = 0
        val domains = loader(cache) {
            fetches++
            throw IOException("Connection reset")
        }.load("Lz", graceMs = 5_000)

        assertEquals(3, fetches)
        assertTrue(domains.isEmpty())
        assertNull(cache.raw)
        assertEquals(0L, cache.fetchedAt)
        assertEquals(0, cache.missingWrites)
    }

    @Test
    fun `missing manifest is remembered for 24h`() = runBlocking {
        val cache = FakeCache()
        var fetches = 0
        val subject = loader(cache) {
            fetches++
            "404: Not Found"
        }
        assertTrue(subject.load("Lz", graceMs = 5_000).isEmpty())
        assertEquals(3, fetches)
        assertEquals(now, cache.fetchedAt)
        assertEquals(1, cache.missingWrites)

        // 下一个请求（新实例，模拟新一次搜索）应被 24 h 标记挡住，零请求
        val next = loader(cache) {
            fetches++
            "404: Not Found"
        }
        assertTrue(next.load("Lz", graceMs = 5_000).isEmpty())
        assertEquals(3, fetches)
    }

    @Test
    fun `network failure is not remembered as missing`() = runBlocking {
        val cache = FakeCache()
        var fetches = 0
        loader(cache) {
            fetches++
            throw IOException("Connection reset")
        }.load("Lz", graceMs = 5_000)
        assertEquals(3, fetches)
        assertEquals(0L, cache.fetchedAt)
        assertEquals(0, cache.missingWrites)

        // 网络故障不该被记成「清单不存在」：新实例应重新试三条通道
        loader(cache) {
            fetches++
            throw IOException("Connection reset")
        }.load("Lz", graceMs = 5_000)
        assertEquals(6, fetches)
    }

    @Test
    fun `stale cache is used when pull fails`() = runBlocking {
        val cache = FakeCache()
        cache.raw = validJson
        cache.fetchedAt = 1L
        val domains = loader(cache) { throw IOException("Connection reset") }
            .load("Lz", graceMs = 5_000)
        assertEquals(listOf("https://cj.lziapi.com"), domains)
    }

    @Test
    fun `failed pull is backed off within ten minutes`() = runBlocking {
        val cache = FakeCache()
        var fetches = 0
        val subject = loader(cache) {
            fetches++
            throw IOException("Connection reset")
        }
        subject.load("Lz", graceMs = 5_000)
        subject.load("Lz", graceMs = 5_000)
        // 第二轮被进程内退避挡住，只跑了一轮的三条通道
        assertEquals(3, fetches)
    }
}
