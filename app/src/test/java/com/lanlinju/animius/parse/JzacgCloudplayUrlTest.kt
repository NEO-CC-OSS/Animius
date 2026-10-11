package com.lanlinju.animius.parse

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 橘子动漫 cloudplay 取址链的 URL 编码回归测试（2026-10-11）：
 * 播放密文以 URL 编码形态（%2B/%2F）内嵌在 query 里传给站方解析域，
 * ktor 的 URL 解析→重建往返若把 %2B 解成裸 +，站方会把 + 当空格、密文损坏。
 * 此测试锁定 DownloadManager.getHtml 同款路径（HttpClient.get(String)）的往返保真性。
 */
class JzacgCloudplayUrlTest {

    private val encrypted =
        "NyFi35UfMBWk0Oc%2BqGrqImUoPP%2BiXCErRsBx6IbBdsDD7EOYtDL%2Ba0ZU7SUUuc%2Fxyz12"
    private val parseBase = "https://play.mknacg.top:8585/cloudplay/?url="

    @Test
    fun `URLBuilder parse-rebuild keeps percent-encoded query intact`() {
        val built = URLBuilder("$parseBase$encrypted").build().toString()
        assertTrue("built=$built", built.contains("%2B"))
        assertTrue("built=$built", built.contains("%2F"))
        assertFalse(
            "built=$built（出现裸 + 表示密文会被站方当作空格）",
            Regex("\\+").containsMatchIn(built.substringAfter("url="))
        )
    }

    @Test
    fun `HttpClient get keeps percent-encoded query intact like DownloadManager getHtml`() = runBlocking {
        var captured: String? = null
        val client = HttpClient(MockEngine { request ->
            captured = request.url.toString()
            respond(
                content = "<html>ok</html>",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type" to listOf(ContentType.Text.Html.toString())),
            )
        })
        client.get("$parseBase$encrypted")
        client.close()
        val url = captured.orEmpty()
        assertTrue("captured=$url", url.contains("%2B"))
        assertTrue("captured=$url", url.contains("%2F"))
        assertFalse(
            "captured=$url（出现裸 + 表示密文会被站方当作空格）",
            Regex("\\+").containsMatchIn(url.substringAfter("url="))
        )
    }
}
