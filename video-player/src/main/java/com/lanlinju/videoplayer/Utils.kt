package com.lanlinju.videoplayer

import android.net.Uri
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.ui.unit.Constraints
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.File
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes


internal fun VideoSize.aspectRatio(): Float =
    if (height == 0 || width == 0) 0f else (width * pixelWidthHeightRatio) / height

/**
 * The [FrameLayout] will not resize itself if the fractional difference between its natural
 * aspect ratio and the requested aspect ratio falls below this threshold.
 *
 *
 * This tolerance allows the view to occupy the whole of the screen when the requested aspect
 * ratio is very close, but not exactly equal to, the aspect ratio of the screen. This may reduce
 * the number of view layers that need to be composited by the underlying system, which can help
 * to reduce power consumption.
 */
private const val MAX_ASPECT_RATIO_DIFFERENCE_FRACTION = 0.01f
private const val VIDEO_ASPECT_RATIO_16_9 = 16f.div(9f) // 16 : 9, 1.7777778
private const val VIDEO_ASPECT_RATIO_4_3 = 4f.div(3f)  //   4 : 3, 1.3333334

internal fun Constraints.resizeForVideo(
    mode: ResizeMode,
    aspectRatio: Float
): Constraints {
    if (aspectRatio <= 0f) {
        // 设置默认视频显示大小为：横屏模式下宽高比为16:9的大小
        val width = (maxHeight * VIDEO_ASPECT_RATIO_16_9).toInt() // default 16 : 9
        return this.copy(maxWidth = width)
    }

    var width = maxWidth
    var height = maxHeight
    val constraintAspectRatio: Float = (width / height).toFloat()
    val difference = aspectRatio / constraintAspectRatio - 1

    if (kotlin.math.abs(difference) <= MAX_ASPECT_RATIO_DIFFERENCE_FRACTION) {
        // 视频比例与屏幕比例十分接近，不处理
        return this
    }

    when (mode) {
        ResizeMode.Fit -> {
            if (difference > 0) { /* difference 大于零 为竖屏模式 */
                height = (width / aspectRatio).toInt()
            } else { /* 横屏模式 */
                width = (height * aspectRatio).toInt()
            }
        }

        ResizeMode.Zoom -> {
            if (difference > 0) {
                width = (height * aspectRatio).toInt()
            } else {
                height = (width / aspectRatio).toInt()
            }
        }

        ResizeMode.FixedWidth -> {
            height = (width / aspectRatio).toInt()
        }

        ResizeMode.FixedHeight -> {
            width = (height * aspectRatio).toInt()
        }

        ResizeMode.FixedRatio_16_9 -> {
            if (difference > 0) {
                height = (width / VIDEO_ASPECT_RATIO_16_9).toInt()
            } else {
                width = (height * VIDEO_ASPECT_RATIO_16_9).toInt()
            }
        }

        ResizeMode.FixedRatio_4_3 -> {
            if (difference > 0) {
                height = (width / VIDEO_ASPECT_RATIO_4_3).toInt()
            } else {
                width = (height * VIDEO_ASPECT_RATIO_4_3).toInt()
            }
        }

        ResizeMode.Full -> {
            if (difference > 0) {
                width = (height * aspectRatio).toInt()
            } else {
                height = (width / aspectRatio).toInt()
            }
        }

        ResizeMode.Fill -> Unit
    }

    return this.copy(maxWidth = width, maxHeight = height)
}


/**
 * Will return a timestamp denoting the current video [position] and the [duration] in the following
 * format "mm:ss / mm:ss"
 * **/
fun prettyVideoTimestamp(
    position: Duration,
    duration: Duration
): String = buildString {
    appendMinutesAndSeconds(position)
    append("/")
    appendMinutesAndSeconds(duration)
}

/**
 * Will split [duration] in minutes and seconds and append it to [this] in the following format "mm:ss"
 * */
private fun StringBuilder.appendMinutesAndSeconds(duration: Duration) {
    val minutes = duration.inWholeMinutes
    val seconds = (duration - minutes.minutes).inWholeSeconds
    appendDoubleDigit(minutes)
    append(':')
    appendDoubleDigit(seconds)
}

/**
 * Will append [value] as double digit to [this].
 * If a single digit value is passed, ex: 4 then a 0 will be added as prefix resulting in 04
 * */
private fun StringBuilder.appendDoubleDigit(value: Long) {
    if (value < 10) {
        append(0)
        append(value)
    } else {
        append(value)
    }
}

internal fun mediaItemCreator(url: String): MediaItem {
    if (url.contains("/storage/emulated")) { // 本地视频文件处理
        return MediaItem.fromUri(Uri.fromFile(File(url)))
    }
    val builder = MediaItem.Builder().setUri(url) // 远程视频文件类型处理
    if (url.contains(".m3u8")) {
        builder.setMimeType(MimeTypes.APPLICATION_M3U8)
    }
    return builder.build()
}

/** HTTP 连接/读取超时：默认 8s 对慢网络（TV 盒子 WiFi、播放 CDN 首包慢）太紧，放宽到 20s */
internal const val HTTP_TIMEOUT_MS = 20_000

/**
 * 网络类瞬时错误的重试上限：默认策略 3 次（退避 0/1/2s，约 3 秒即报"视频加载失败"，
 * 2026-10-11 实测 DNS 瞬时失败 3 秒就弹错误页）。放宽到 6 次、网络类错误退避 2s×n 封顶 8s，
 * 失败窗口约 35-40 秒，期间 DNS/网络恢复即可自动续播。
 */
internal const val LOAD_RETRY_LIMIT = 6

@OptIn(UnstableApi::class)
internal fun loadHttpDataSourceFactory(headers: Map<String, String> = emptyMap()): DefaultHttpDataSource.Factory {
    val factory = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(HTTP_TIMEOUT_MS)
        .setReadTimeoutMs(HTTP_TIMEOUT_MS)
    if (headers.isNotEmpty()) {
        factory.setDefaultRequestProperties(headers)
    }
    return factory
}

/**
 * 加载错误策略：DNS 解析失败/连接拒绝/超时/握手被重置这类网络瞬时错误给更长重试窗口
 * （每次 2s×n 封顶 8s）；非网络错误（404 死链等）保持默认策略行为，不陪着重试。
 */
@OptIn(UnstableApi::class)
internal fun loadErrorHandlingPolicy(): LoadErrorHandlingPolicy {
    return object : DefaultLoadErrorHandlingPolicy(LOAD_RETRY_LIMIT) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            if (isTransientNetworkError(loadErrorInfo.exception)) {
                return (loadErrorInfo.errorCount * 2_000L).coerceAtMost(8_000L)
            }
            return super.getRetryDelayMsFor(loadErrorInfo)
        }

        private fun isTransientNetworkError(e: Throwable?): Boolean {
            var t: Throwable? = e
            while (t != null) {
                if (t is UnknownHostException || t is ConnectException || t is SocketTimeoutException
                    || t is SocketException || t is SSLException
                ) {
                    return true
                }
                t = t.cause
            }
            return false
        }
    }
}

@OptIn(UnstableApi::class)
internal fun mediaSourceCreator(url: String, headers: Map<String, String>): MediaSource {
    val dataSourceFactory = loadHttpDataSourceFactory(headers)
    val videoSource: MediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
        .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy())
        .createMediaSource(mediaItemCreator(url))
    return videoSource
}

@OptIn(UnstableApi::class)
internal fun ExoPlayer.setVideoUrl(url: String, headers: Map<String, String>) {
    if (headers.isEmpty()) {
        setMediaItem(mediaItemCreator(url))
    } else {
        setMediaSource(mediaSourceCreator(url, headers))
    }
}

@OptIn(UnstableApi::class)
internal fun loadControlCreator(): LoadControl {
    return DefaultLoadControl.Builder()
        .setBufferDurationsMs(50_000, 90_000, 2000, 5000)
        .setBackBuffer(20_000, true)
        .build()
}
