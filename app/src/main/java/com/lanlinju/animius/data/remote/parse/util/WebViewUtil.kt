package com.lanlinju.animius.data.remote.parse.util

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.CallSuper
import com.lanlinju.animius.application.AnimeApplication
import com.lanlinju.animius.util.DefaultUserAgent
import com.lanlinju.animius.util.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

private const val LOG_TAG = "WebViewUtil"

class WebViewUtil {

    private var webView: WebView? = null

    /**
     * 根据正则表达式regex获取视频链接，通过过滤拦截[WebView]所有发送的http url请求
     *
     * @param url 要访问的视频所在网页的url
     * @param regex 通过regex匹配拦截对应视频链接
     * @param timeoutMs 请求超时的时间,单位毫秒
     *
     * @return 返回匹配到的视频链接url，匹配不到结果会报一个[SocketTimeoutException]超时异常
     */
    suspend fun interceptRequest(
        url: String,
        regex: String = ".mp4|.m3u8",
        timeoutMs: Long = 10_000L,
        userAgent: String = DefaultUserAgent,
    ): String = withContext(Dispatchers.Main) {
        createWebView(userAgent)

        val regexPattern = regex.toRegex()
        var matchedUrl: String? = null

        val client = object : BlockedResWebViewClient() {
            override fun onLoadResource(view: WebView?, requestUrl: String) {

                requestUrl.log(LOG_TAG, "InterceptRequest")

                if (requestUrl.contains(regexPattern)) {
                    matchedUrl = requestUrl
                    requestUrl.log(LOG_TAG, "Regex match succeeded")
                }
            }
        }
        // 拦截命中时由 client 自己转交，替代原先的 view.webViewClient.onLoadResource(...)
        // （WebView#getWebViewClient 是 API 26 API，minSdk 24 上不能引用）。
        //
        // 现状说明（2026-10-23 复核）：这里 createWebView 造出来的 WebView 从未 attach 到窗口，
        // 而未 attach 的 View#post 只会进 HandlerActionQueue、等 attach 才 flush，
        // 所以这条转发路径在所有版本上都是"不会执行"的——7.0 上也不会因此崩溃。
        // 真正让匹配生效的是框架自己的 onLoadResource（上面 override 的那个），
        // 它对 XHR 拉取的 m3u8 不可靠（见 AgedmSource 注释）。
        // 保留自持回调而不是删掉整段：它是这条转发唯一不依赖 API 26 的正确写法，
        // 将来 WebView 一旦 attach 起来就会生效；删掉会丢掉这个意图。
        client.onResourceIntercepted = { view, requestUrl -> client.onLoadResource(view, requestUrl) }
        webView?.webViewClient = client

        webView?.loadUrl(url) // 加载视频播放所在的Web网页

        // 等待匹配结果或超时
        try {
            withTimeout(timeoutMs) {
                while (matchedUrl == null) {
                    delay(100)
                }
            }
            matchedUrl ?: throw TimeoutException("No matching URL found")
        } catch (_: TimeoutCancellationException) {
            throw TimeoutException("Web connection timeout exception")
        } finally {
            destroyWebView()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(userAgent: String) {
        destroyWebView()
        webView = WebView(AnimeApplication.getInstance()).apply {
            settings.javaScriptEnabled = true
            settings.userAgentString = userAgent
        }
    }

    private fun destroyWebView() {
        webView?.destroy()
        webView = null
        "DestroyWebView".log(LOG_TAG)
    }

    fun clearWeb() {
        webView?.clear()
        destroyWebView()
    }

    private fun WebView.clear() {
        clearCache(true)
        clearHistory()
        clearFormData()
        clearMatches()
    }
}

abstract class BlockedResWebViewClient(
    private val blockRes: Array<String> = arrayOf(
        ".css", ".ts",
        ".mp3", ".m4a",
        ".gif", ".jpg", ".png", ".webp"
    )
) : WebViewClient() {

    /**
     * shouldInterceptRequest 命中拦截规则时的转发回调（原实现走 `view.webViewClient`，
     * 但 `WebView#getWebViewClient` 是 API 26 API，minSdk 24 的机器上不可用）。
     * 由宿主设置；为空时只拦截、不转发。
     */
    var onResourceIntercepted: ((WebView?, String) -> Unit)? = null

    private val blockWebResourceRequest =
        WebResourceResponse("text/html", "utf-8", ByteArrayInputStream("".toByteArray()))

    // Reference code: https://github.com/RyensX/MediaBox/blob/1aefca13656eada4da2ff515cc9f893f407c53e0/app/src/main/java/com/su/mediabox/plugin/WebUtilImpl.kt#L138
    /**
     * 拦截无关资源文件
     *
     * 注意，该方法运行在线程池内
     */
    @CallSuper
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest?
    ) = run {
        val url = request?.url?.toString() ?: return null
        if (blockRes.any { url.contains(it) }) {
            url.log(LOG_TAG, "BlockedRes")
            onResourceIntercepted?.let { callback -> view.post { callback(view, url) } }
            blockWebResourceRequest
        } else {
            null
        }
    }
}