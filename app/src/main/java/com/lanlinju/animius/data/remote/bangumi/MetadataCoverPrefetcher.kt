package com.lanlinju.animius.data.remote.bangumi

import android.content.Context
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.lanlinju.animius.util.log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 封面预取（TV 首页首次出现元数据行后，后台补齐非可见行的封面）。
 *
 * 走 Coil 的全局 ImageLoader（[com.lanlinju.animius.application.AnimeApplication] 已配 256 MB 磁盘缓存），
 * 预取结果与用户实际滚动到该卡片时是同一份磁盘缓存，不会重复下载。
 *
 * **只暖磁盘缓存**：请求显式禁用内存缓存（[CachePolicy.DISABLED]）。Coil 默认内存缓存上限约为堆的 25%，
 * 70 张 480×672 全尺寸位图 ≈ 90 MB，会把 tv.8「PSS 下降 ≥60 MB」的收益直接吃掉，还会挤掉可见行已解码的图。
 * 磁盘缓存保留（那才是预取的目的）。
 *
 * **低优先级**：Coil 2.6 的 `ImageRequest` 没有 per-request priority API（`Priority` 是 Coil 3 才有），
 * 因此用三层等效手段：① 页面级协程延迟 [HomeViewModel] 2.5 s 再开始；② 并发上限 3；
 * ③ 解码尺寸压到 [PREFETCH_DECODE_SIZE_PX] 像素（预取只要磁盘里的编码文件，不需要大位图）。
 *
 * 约束（通道实测 2026-10-21：jsDelivr 并发 6 时 1/6 会卡 30 s）：
 * - 并发上限 3；
 * - 单请求超时 8 s；
 * - 失败重试 1 次；
 * - 调用方必须放在**页面级协程**里（Compose `LaunchedEffect` 等），页面销毁即取消，不会在播放时继续抢带宽。
 */
@Singleton
class MetadataCoverPrefetcher @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        /** 并发上限：实测并发 6 会有 1/6 请求卡 30 s，3 是稳定上限 */
        const val MAX_CONCURRENT = 3

        /** 单请求超时：轻包封面单张约 44 KB，8 s 足够 */
        private const val REQUEST_TIMEOUT_MS = 8_000L

        /** 失败重试次数 */
        private const val RETRY = 1

        /**
         * 预取只需把编码文件写进 Coil 磁盘缓存，不需要全尺寸位图：
         * 解码到 2×2 可把每次预取的解码内存/CPU 压到最低（磁盘缓存仍是原图）。
         */
        private const val PREFETCH_DECODE_SIZE_PX = 2

        private const val TAG = "MetadataIndex"
    }

    private val semaphore = Semaphore(MAX_CONCURRENT)

    /**
     * 预取一批封面地址；已经是 http(s) 的才处理（data URI 由 MediaSmall 本地解码，不走网络）。
     *
     * 协程可取消：调用方（页面级协程）销毁时立即取消，未开始的请求不会再发。
     */
    suspend fun prefetch(urls: List<String>) {
        val targets = urls.asSequence()
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .toList()
        if (targets.isEmpty()) return
        "META cover prefetch start count=${targets.size}".log("BehaviorTrace")
        coroutineScope {
            targets.map { url ->
                async { semaphore.withPermit { fetchWithRetry(url) } }
            }.awaitAll()
        }
        "META cover prefetch done count=${targets.size}".log("BehaviorTrace")
    }

    private suspend fun fetchWithRetry(url: String): Boolean {
        repeat(RETRY + 1) { attempt ->
            if (fetchOnce(url)) return true
            if (attempt < RETRY) {
                "META cover prefetch retry url=${url.take(64)}".log(TAG)
            }
        }
        return false
    }

    private suspend fun fetchOnce(url: String): Boolean = try {
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            val request = ImageRequest.Builder(context)
                .data(url)
                // 只暖磁盘缓存：不进内存缓存（否则 70 张全尺寸位图 ≈ 90 MB 灌进 Coil 25% 堆缓存）
                .memoryCachePolicy(CachePolicy.DISABLED)
                // 低优先级（Coil 2.6 无 priority API）：解码尺寸压到最小，只留磁盘缓存
                .size(PREFETCH_DECODE_SIZE_PX)
                .build()
            // 走 Coil 全局 ImageLoader：磁盘缓存写入后，用户滚动到该卡片时直接命中，不再下载
            context.imageLoader.execute(request).drawable != null
        } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "META cover prefetch failed url=${url.take(64)}: ${e.message}".log(TAG)
        false
    }
}
