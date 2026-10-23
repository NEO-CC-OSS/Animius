package com.lanlinju.animius.parse

import com.lanlinju.animius.data.remote.parse.CmsDomainPolicy
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采集源域名策略（纯函数，2026-10-23 任务 B / C②）。
 *
 * 覆盖：候选顺序与去重、域名合法性校验、超时判定、JSON 判定、绝对地址 → 请求路径。
 */
class CmsDomainPolicyTest {

    @Test
    fun `candidate order is user then cache then builtin`() {
        val ordered = CmsDomainPolicy.orderedDomains(
            userDomain = "https://my.example.com/",
            cached = listOf("https://cached.example.com"),
            builtIn = listOf("https://cj.lziapi.com/", "https://cached.example.com/"),
        )
        assertEquals(
            listOf(
                "https://my.example.com",
                "https://cached.example.com",
                "https://cj.lziapi.com",
            ),
            ordered,
        )
    }

    @Test
    fun `blank user domain is ignored`() {
        val ordered = CmsDomainPolicy.orderedDomains(
            userDomain = "   ",
            cached = emptyList(),
            builtIn = listOf("https://cj.lziapi.com/"),
        )
        assertEquals(listOf("https://cj.lziapi.com"), ordered)
    }

    @Test
    fun `valid domains are accepted`() {
        assertEquals(
            "https://cj.lziapi.com",
            CmsDomainPolicy.normalizeDomain("https://cj.lziapi.com/"),
        )
        assertEquals(
            "https://sub.example.co",
            CmsDomainPolicy.normalizeDomain("  https://SUB.example.co/  "),
        )
        assertEquals("https://1.2.3.4", CmsDomainPolicy.normalizeDomain("https://1.2.3.4/"))
    }

    @Test
    fun `invalid domains are rejected`() {
        val invalid = listOf(
            null,
            "",
            "http://plain-http.com/",
            "https://",
            "https://bad_host.com/",
            "https://a.com/api/",
            "https://a.com:8080/",
            "https://999.1.1.1/",
            "https://localhost/",
            "https:// user@example.com/",
        )
        invalid.forEach { raw ->
            assertNull("应当拒绝：$raw", CmsDomainPolicy.normalizeDomain(raw))
        }
    }

    @Test
    fun `timeout failures are recognized`() {
        assertTrue(CmsDomainPolicy.isTimeoutFailure(SocketTimeoutException("read timeout")))
        assertTrue(
            CmsDomainPolicy.isTimeoutFailure(
                IOException("wrapped", SocketTimeoutException("timeout")),
            ),
        )
        assertFalse(CmsDomainPolicy.isTimeoutFailure(IOException("Connection reset")))
        assertFalse(CmsDomainPolicy.isTimeoutFailure(IllegalStateException("非 JSON")))
    }

    @Test
    fun `json detection skips bom and whitespace`() {
        assertTrue(CmsDomainPolicy.looksLikeJsonObject("""{"list":[]}"""))
        assertTrue(CmsDomainPolicy.looksLikeJsonObject("\uFEFF \n{\"list\":[]}"))
        assertFalse(CmsDomainPolicy.looksLikeJsonObject("<html>challenge</html>"))
        assertFalse(CmsDomainPolicy.looksLikeJsonObject("404: Not Found"))
    }

    @Test
    fun `request target keeps path and query`() {
        assertEquals(
            "/api.php/provide/vod/?ac=detail&ids=123",
            CmsDomainPolicy.requestTarget(
                "https://cj.lziapi.com/api.php/provide/vod/?ac=detail&ids=123",
            ),
        )
        assertEquals(
            "/api.php/provide/vod/",
            CmsDomainPolicy.requestTarget("https://cj.lziapi.com/api.php/provide/vod/"),
        )
        assertNull(CmsDomainPolicy.requestTarget("not a url"))
    }
}
