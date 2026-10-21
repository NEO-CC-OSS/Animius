package com.lanlinju.animius.bangumi

import com.lanlinju.animius.data.remote.bangumi.SingleFlight
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F2 守卫：in-flight 单飞。
 *
 * 时间表（默认首屏）与首页会同时调 load()；共享同一个结果后，
 * 主通道失败时不会在第一个 20 s 预算之后再来一轮（首页最坏 40 s 的根因）。
 */
class SingleFlightTest {

    @Test
    fun `concurrent callers share one execution`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val flight = SingleFlight<Int>(scope)
            val calls = AtomicInteger(0)

            val results = (1..5).map {
                async(Dispatchers.Default) {
                    flight.load {
                        calls.incrementAndGet()
                        delay(150)
                        42
                    }
                }
            }.awaitAll()

            assertEquals(1, calls.get())
            assertEquals(List(5) { 42 }, results)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `failure is shared by concurrent callers`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val flight = SingleFlight<String>(scope)
            val calls = AtomicInteger(0)

            val outcomes = (1..3).map {
                async(Dispatchers.Default) {
                    runCatching {
                        flight.load {
                            calls.incrementAndGet()
                            delay(100)
                            throw IllegalStateException("boom")
                        }
                    }
                }
            }.awaitAll()

            // 失败结果也共享：只执行一次，三个调用者都拿到同一个失败
            assertEquals(1, calls.get())
            assertTrue(outcomes.all { it.exceptionOrNull() is IllegalStateException })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `next call runs again after completion`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val flight = SingleFlight<Int>(scope)
            val calls = AtomicInteger(0)

            assertEquals(1, flight.load { calls.incrementAndGet() })
            // 等 in-flight 清理协程跑完：失败/完成后的下一次调用必须真正重试
            delay(100)
            assertEquals(2, flight.load { calls.incrementAndGet() })
            assertEquals(2, calls.get())
        } finally {
            scope.cancel()
        }
    }
}
