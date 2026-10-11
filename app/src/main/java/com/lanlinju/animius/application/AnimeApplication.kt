package com.lanlinju.animius.application

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.lanlinju.animius.util.TlsChainFix
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException

@HiltAndroidApp
class AnimeApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()

        _instance = this

    }

    /**
     * 全局图片加载器（C-1）：
     * - 共享 TlsChainFix 的证书补全配置，救 Girigiri/Mxdm 等缺链图片域（探测证实封面失败主因是 TLS 而非防盗链）
     * - 图片请求用浏览器 UA（Coil 默认 UA 一眼机器人）
     * - 开磁盘缓存，重复封面不再走网络
     * 探测未发现需要 Referer 的源站，不注入 Referer。
     */
    override fun newImageLoader(): ImageLoader {
        val (socketFactory, trustManager) = TlsChainFix.socketFactoryWithTrustManager()
        val okHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(socketFactory, trustManager)
            .addInterceptor(imageUserAgentInterceptor)
            .addInterceptor(imageRetryInterceptor)
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .crossfade(true)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(IMAGE_CACHE_MAX_BYTES)
                    .build()
            }
            .build()
    }

    private val imageUserAgentInterceptor = Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header("User-Agent", BROWSER_USER_AGENT)
                .build()
        )
    }

    /**
     * 橘子图床（pic.mknacg.top:458）通过 302 分发到轮换节点（dx-*.mknacg.top:4588），
     * 部分节点不可达（2026-10-11 取证）：失败时重试整个请求，302 会轮换到新节点。
     * 仅对该图床域生效，其他源图片不受影响。
     */
    private val imageRetryInterceptor = Interceptor { chain ->
        val request = chain.request()
        if (!request.url.host.endsWith("mknacg.top")) return@Interceptor chain.proceed(request)
        var lastError: IOException? = null
        for (attempt in 1..IMAGE_RETRY_ATTEMPTS) {
            try {
                val response = chain.proceed(request)
                if (response.isSuccessful) return@Interceptor response
                response.close()
                lastError = IOException("HTTP ${response.code}")
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("image retry exhausted")
    }

    companion object {
        private lateinit var _instance: Application

        fun getInstance(): Context {
            return _instance
        }

        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val IMAGE_CACHE_MAX_BYTES = 256L * 1024 * 1024
        private const val IMAGE_RETRY_ATTEMPTS = 3
    }
}
