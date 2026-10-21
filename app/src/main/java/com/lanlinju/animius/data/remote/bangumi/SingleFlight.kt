package com.lanlinju.animius.data.remote.bangumi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 共享 in-flight 的单飞执行器（纯逻辑，便于 JVM 单测）。
 *
 * 语义：
 * - 同一时刻只执行一份 [load]；并发调用者 `await` 同一个结果（成功或失败都共享）；
 * - 失败结果同样共享 → 两个 ViewModel 同时触发加载时，不会在第一个超时后再串行跑第二轮；
 * - 执行完成后清空 in-flight，下一次调用重新执行（失败后用户点重试能真正重试）。
 *
 * 锁只保护 in-flight 的登记/清理，等待走 `Deferred.await()` 不持锁，避免死锁。
 */
internal class SingleFlight<T>(private val scope: CoroutineScope) {

    private val mutex = Mutex()

    @Volatile
    private var inFlight: Deferred<T>? = null

    suspend fun load(block: suspend () -> T): T {
        val deferred = mutex.withLock {
            val current = inFlight
            if (current != null && current.isActive) {
                current
            } else {
                val started = scope.async {
                    try {
                        block()
                    } catch (e: CancellationException) {
                        throw e
                    }
                }
                inFlight = started
                // 不持锁回调：完成后另起协程拿锁清空，避免与 await 方互相等待
                started.invokeOnCompletion {
                    scope.launch {
                        mutex.withLock {
                            if (inFlight === started) inFlight = null
                        }
                    }
                }
                started
            }
        }
        return deferred.await()
    }
}
