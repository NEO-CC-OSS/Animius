package com.lanlinju.animius.bangumi

import com.lanlinju.animius.data.remote.bangumi.GitHubContentsPayload
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GitHub contents API 响应解码（index.json 首选通道）。
 *
 * 真实响应形如 `{"encoding":"base64","content":"eyJzY2hlbWEi..."}`，
 * content 每 60 字符带换行，解码后就是 index.json 原文。
 */
class GitHubContentsPayloadTest {

    private val payload = """{"schema":2,"updated_at":"2026-10-21T08:53:19Z"}"""

    private fun encoded(content: String = payload): String =
        Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))

    @Test
    fun `decodes base64 content`() {
        val body = """{"encoding":"base64","content":"${encoded()}"}"""

        assertEquals(payload, GitHubContentsPayload.decode(body))
    }

    @Test
    fun `decodes content with embedded newlines`() {
        val raw = encoded()
        val wrapped = raw.chunked(60).joinToString("\n")
        val body = """{"encoding":"base64","content":"$wrapped"}"""

        assertEquals(payload, GitHubContentsPayload.decode(body))
    }

    @Test
    fun `rejects non base64 encoding`() {
        val body = """{"encoding":"utf-8","content":"${encoded()}"}"""

        assertNull(GitHubContentsPayload.decode(body))
    }

    @Test
    fun `rejects missing or blank content`() {
        assertNull(GitHubContentsPayload.decode("""{"encoding":"base64"}"""))
        assertNull(GitHubContentsPayload.decode("""{"encoding":"base64","content":""}"""))
    }

    @Test
    fun `rejects invalid base64 and non json bodies`() {
        assertNull(GitHubContentsPayload.decode("""{"encoding":"base64","content":"%%%not-base64%%%"}"""))
        assertNull(GitHubContentsPayload.decode("<html>rate limited</html>"))
        assertNull(GitHubContentsPayload.decode(""))
    }
}
