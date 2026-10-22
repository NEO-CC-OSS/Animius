package com.lanlinju.animius.data.repository

import com.lanlinju.animius.data.remote.api.AnimeApi
import com.lanlinju.animius.data.remote.dto.AnimeBean
import com.lanlinju.animius.util.SourceMode
import com.lanlinju.animius.util.log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一个源在聚合搜索里的结果：成功带条目，失败带原因（超时/风控/站点不可用）。
 * 空结果且 error 为 null = 该源还在搜索中（UI 显示「搜索中」）。
 */
data class AggregateGroup(
    val mode: SourceMode,
    val results: List<AnimeBean> = emptyList(),
    val error: String? = null,
)

/**
 * 聚合搜索（tv.9 切片 3）：用户搜索作为兜底，一次把所有可用源都搜一遍，按源分组展示。
 *
 * 约束（用户 2026-10-22 拍板）：
 * - 只显示源站结果，不再叠一层元数据搜索结果；
 * - 按源分组；先到先显示（[onGroup] 每完成一个源就回调一次）；
 * - 并发 3、单源 5 s、整轮 12 s；不做分页（每源第 1 页）。
 *
 * 失败源不阻塞其他源：某个源超时/403 只影响它自己那一组。
 */
@Singleton
class AggregateSearch @Inject constructor(
    private val animeApi: AnimeApi,
) {

    companion object {
        /** 单源预算：搜索第 1 页足够快，慢源不拖累整轮 */
        const val PER_SOURCE_TIMEOUT_MS = 5_000L

        /** 整轮预算：到点还没回来的源直接记超时 */
        const val OVERALL_TIMEOUT_MS = 12_000L

        /** 并发上限：与选源一致（3），避免同时打太多源触发风控 */
        const val CONCURRENCY = 3
    }

    suspend fun search(
        query: String,
        sources: List<SourceMode> = SourceMode.entries.filter { it.selectable },
        onGroup: (AggregateGroup) -> Unit = {},
    ): List<AggregateGroup> = withContext(Dispatchers.IO) {
        if (query.isBlank() || sources.isEmpty()) return@withContext emptyList()

        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + OVERALL_TIMEOUT_MS
        // 多个 worker 协程并发写：必须用同步列表，普通 MutableList 会丢结果
        val completed = java.util.Collections.synchronizedList(mutableListOf<AggregateGroup>())

        coroutineScope {
            val queue = Channel<SourceMode>(Channel.UNLIMITED)
            sources.forEach { queue.send(it) }
            queue.close()

            val workers = List(minOf(CONCURRENCY, sources.size)) {
                launch {
                    for (mode in queue) {
                        val remaining = deadline - System.currentTimeMillis()
                        val group = if (remaining <= 0) {
                            AggregateGroup(mode, error = "超时")
                        } else {
                            searchOne(query, mode, remaining)
                        }
                        completed += group
                        onGroup(group)
                    }
                }
            }
            workers.forEach { it.join() }
        }

        val ordered = sources.mapNotNull { mode -> completed.firstOrNull { it.mode == mode } }
        "SEARCH aggregate query=$query sources=${sources.size} " +
            "ok=${ordered.count { it.error == null }} " +
            "failed=${ordered.count { it.error != null }} " +
            "ms=${System.currentTimeMillis() - startedAt}"
            .log("BehaviorTrace")
        ordered
    }

    private suspend fun searchOne(query: String, mode: SourceMode, remaining: Long): AggregateGroup {
        return try {
            val list = withTimeoutOrNull(minOf(PER_SOURCE_TIMEOUT_MS, remaining)) {
                animeApi.getSearchData(query, 1, mode)
            } ?: throw IllegalStateException("超时")
            AggregateGroup(mode, list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AggregateGroup(mode, error = e.message ?: e.javaClass.simpleName)
        }
    }
}
