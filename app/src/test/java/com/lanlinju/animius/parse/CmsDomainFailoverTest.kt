package com.lanlinju.animius.parse

import com.lanlinju.animius.data.remote.parse.CmsDomainException
import com.lanlinju.animius.data.remote.parse.CmsDomainFailover
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采集源域名顺延（2026-10-23 任务 B）。
 *
 * 覆盖：
 * - 快速失败（连接重置 / DNS 失败 / 非 JSON 挑战页）才换下一个候选；
 * - 超时类失败**不换**（换域名只会让总耗时翻倍）；
 * - 最多顺延 2 个候选；
 * - 首批全挂后给一次「二次机会」（远程清单），额外候选仍受限；
 * - 全失败抛 [CmsDomainException]，消息带「已尝试哪些域名」，第一句是用户能看懂的文案。
 */
class CmsDomainFailoverTest {

    private val okBody = """{"code":1,"list":[]}"""

    private fun failover() = CmsDomainFailover(modeTag = "Lz", log = {})

    @Test
    fun `fast failure falls back to next candidate`() = runBlocking {
        val calls = mutableListOf<String>()
        val outcome = failover().fetch(
            domains = listOf("https://a.com", "https://b.com", "https://c.com"),
            fetch = { domain ->
                calls += domain
                if (domain == "https://a.com") throw IOException("Connection reset")
                okBody
            },
        )
        assertEquals("https://b.com", outcome.domain)
        assertEquals(listOf("https://a.com", "https://b.com"), calls)
        assertEquals(listOf("https://a.com", "https://b.com"), outcome.tried)
        assertEquals(okBody, outcome.body)
    }

    @Test
    fun `dns failure also falls back`() = runBlocking {
        val outcome = failover().fetch(
            domains = listOf("https://dead.com", "https://alive.com"),
            fetch = { domain ->
                if (domain == "https://dead.com") throw UnknownHostException("dead.com")
                okBody
            },
        )
        assertEquals("https://alive.com", outcome.domain)
    }

    @Test
    fun `non json challenge page counts as fast failure and falls back`() = runBlocking {
        val calls = mutableListOf<String>()
        val outcome = failover().fetch(
            domains = listOf("https://a.com", "https://b.com"),
            fetch = { domain ->
                calls += domain
                if (domain == "https://a.com") "<html>challenge</html>" else okBody
            },
        )
        assertEquals("https://b.com", outcome.domain)
        assertEquals(2, calls.size)
    }

    @Test
    fun `timeout does not fall back`() {
        val calls = mutableListOf<String>()
        val error = assertThrows(CmsDomainException::class.java) {
            runBlocking {
                failover().fetch(
                    domains = listOf("https://a.com", "https://b.com"),
                    fetch = { domain ->
                        calls += domain
                        throw SocketTimeoutException("timeout")
                    },
                )
            }
        }
        // 超时立刻收工：第二个候选根本没发出去
        assertEquals(listOf("https://a.com"), calls)
        assertTrue(error.message!!.contains("播放源域名可能已失效"))
        assertTrue(error.message!!.contains("a.com"))
    }

    @Test
    fun `fallback stops after two candidates`() {
        val calls = mutableListOf<String>()
        val error = assertThrows(CmsDomainException::class.java) {
            runBlocking {
                failover().fetch(
                    domains = listOf(
                        "https://a.com",
                        "https://b.com",
                        "https://c.com",
                        "https://d.com",
                    ),
                    fetch = { domain ->
                        calls += domain
                        throw IOException("Connection reset")
                    },
                )
            }
        }
        // 首个 + 最多 2 个顺延候选
        assertEquals(listOf("https://a.com", "https://b.com", "https://c.com"), calls)
        assertFalse(error.message!!.contains("d.com"))
    }

    @Test
    fun `remote candidates are tried after first round exhausted`() = runBlocking {
        var exhausted = 0
        val outcome = failover().fetch(
            domains = listOf("https://a.com", "https://b.com", "https://c.com"),
            fetch = { domain ->
                if (domain == "https://remote.com") okBody else throw IOException("reset")
            },
            onExhausted = { tried ->
                exhausted++
                assertEquals(setOf("https://a.com", "https://b.com", "https://c.com"), tried)
                listOf("https://remote.com")
            },
        )
        assertEquals("https://remote.com", outcome.domain)
        assertEquals(1, exhausted)
        assertEquals(4, outcome.tried.size)
    }

    @Test
    fun `on exhausted is not called when first candidate works`() = runBlocking {
        var exhausted = 0
        val outcome = failover().fetch(
            domains = listOf("https://a.com"),
            fetch = { okBody },
            onExhausted = {
                exhausted++
                emptyList()
            },
        )
        assertEquals("https://a.com", outcome.domain)
        assertEquals(0, exhausted)
    }

    @Test
    fun `error message lists every tried domain`() {
        val error = assertThrows(CmsDomainException::class.java) {
            runBlocking {
                failover().fetch(
                    domains = listOf("https://cj.lziapi.com", "https://backup.example.com"),
                    fetch = { throw IOException("Connection reset") },
                )
            }
        }
        val message = error.message.orEmpty()
        assertTrue(message.startsWith("播放源域名可能已失效，可在设置里修改域名"))
        assertTrue(message.contains("cj.lziapi.com"))
        assertTrue(message.contains("backup.example.com"))
    }
}
