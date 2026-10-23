package com.lanlinju.animius.data.remote.parse

import com.lanlinju.animius.util.log
import kotlinx.coroutines.CancellationException

/**
 * 采集源所有候选域名都失败。消息第一句是给用户看的人话，括号里留诊断（试过哪些域名/最后错误），
 * 不让裸异常直接冒到 UI。
 */
internal class CmsDomainException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * 采集源域名顺延（2026-10-23 任务 B）。
 *
 * 规则：
 * - 首个候选必试；之后**只在快速失败时**换下一个，最多 [maxFallbackCandidates] 个；
 * - 超时类失败立刻收工（既不换域名、也不拉清单），避免总耗时翻倍；
 * - 首批候选全挂后给一次「二次机会」[onExhausted]（用于按需拉远程域名清单），
 *   额外候选同样受 [maxFallbackCandidates] 约束；
 * - 成功即返回，由调用方把 `baseUrl` 换成实际成功的域名（后续详情/取址才会拼新域名）；
 * - 全失败抛 [CmsDomainException]，消息里带「已尝试哪些域名」。
 *
 * 不变量：**除了 [CancellationException]，本类只会抛 [CmsDomainException]**。
 * `onExhausted`（拉清单）里的任何异常都在内部兜住，按「没拿到额外候选」处理。
 */
internal class CmsDomainFailover(
    private val modeTag: String,
    private val maxFallbackCandidates: Int = CmsDomainPolicy.MAX_FALLBACK_CANDIDATES,
    private val isTimeout: (Throwable) -> Boolean = CmsDomainPolicy::isTimeoutFailure,
    private val log: (String) -> Unit = { it.log("BehaviorTrace") },
) {

    data class Outcome(
        val body: String,
        val domain: String,
        val tried: List<String>,
    )

    /**
     * @param domains 候选域名（调用方已按优先级排好）
     * @param fetch 对某个域名发一次请求，返回响应体
     * @param accept 响应体是否可用（非 JSON 挑战页 = false，按快速失败处理）
     * @param onExhausted 首批全挂后的二次机会；返回额外候选（已试过的会被过滤掉）
     */
    suspend fun fetch(
        domains: List<String>,
        fetch: suspend (domain: String) -> String,
        accept: (String) -> Boolean = CmsDomainPolicy::looksLikeJsonObject,
        onExhausted: suspend (tried: Set<String>) -> List<String> = { emptyList() },
    ): Outcome {
        val tried = mutableListOf<String>()
        var lastError: Throwable? = null

        suspend fun attempt(candidates: List<String>, budget: Int): Outcome? {
            var used = 0
            for (domain in candidates) {
                if (used >= budget) break
                used++
                tried += domain
                val startedAt = System.currentTimeMillis()
                try {
                    val body = fetch(domain)
                    if (!accept(body)) {
                        throw IllegalStateException(
                            "$modeTag：返回非 JSON（疑似挑战页/验证码/403 或站点改版）",
                        )
                    }
                    val elapsed = System.currentTimeMillis() - startedAt
                    if (tried.size > 1) {
                        "CMS domain fallback mode=$modeTag try=${tried.size} ok=$domain ms=$elapsed"
                            .log("BehaviorTrace")
                    } else {
                        "CMS domain ok mode=$modeTag try=1 ok=$domain ms=$elapsed"
                            .log("BehaviorTrace")
                    }
                    return Outcome(body, domain, tried.toList())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    val elapsed = System.currentTimeMillis() - startedAt
                    (
                        "CMS domain fail mode=$modeTag try=${tried.size} domain=$domain ms=$elapsed " +
                            "err=${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}"
                        ).log("BehaviorTrace")
                    if (isTimeout(e)) {
                        // 超时不顺延：换域名只会让总耗时翻倍
                        throw CmsDomainException(failureMessage(tried, e), e)
                    }
                }
            }
            return null
        }

        attempt(domains.distinct(), 1 + maxFallbackCandidates)?.let { return it }

        // 二次机会（拉远程清单）不许把异常漏出去：它只是增益，失败就当没拿到候选
        val extra = try {
            onExhausted(tried.toSet()).filterNot { it in tried }.distinct()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            (
                "CMS domain fallback mode=$modeTag onExhausted crashed: " +
                    "${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}"
                ).log("BehaviorTrace")
            emptyList()
        }
        if (extra.isNotEmpty()) {
            attempt(extra, maxFallbackCandidates)?.let { return it }
        }

        throw CmsDomainException(failureMessage(tried, lastError), lastError)
    }

    private fun failureMessage(tried: List<String>, cause: Throwable?): String {
        val hosts = tried.joinToString("、") { it.substringAfter("://").trimEnd('/') }
            .ifEmpty { "-" }
        val last = cause?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty().take(80)}" }
        val detail = if (last.isNullOrBlank()) "已尝试 $hosts" else "已尝试 $hosts；最后错误 $last"
        return "$USER_MESSAGE（$detail）"
    }

    companion object {
        /** 用户可见文案：第一句必须是人话，别把裸异常抛给 UI */
        const val USER_MESSAGE = "播放源域名可能已失效，可在设置里修改域名"
    }
}
