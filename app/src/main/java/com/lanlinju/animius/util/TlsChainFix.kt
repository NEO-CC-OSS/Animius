package com.lanlinju.animius.util

import com.lanlinju.animius.R
import com.lanlinju.animius.application.AnimeApplication
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * 证书链补全：部分源站（实测 Mxdm 用 LE YE1、Nyafun 用 LE YE2）TLS 握手时不发中间证书，
 * Android 的严格 PKIX 校验因缺链必挂（SSLHandshakeException），而桌面系统会自动补链所以浏览器能开。
 * 处理：系统默认校验先走；失败时用内置的 Let's Encrypt 公共中间证书再严格验一次，仍失败则抛原始异常。
 * 两段都是严格 PKIX、信任锚全部是公共 CA，不降低安全性；站长补发证书后自动回到系统通道。
 */
object TlsChainFix {

    private const val TAG = "TlsChainFix"

    // res/raw 内置的 Let's Encrypt 公共中间证书：YE1/YE2 为 2026-09 实测源站缺失，其余为常用款兜底
    private val EXTRA_CERT_RES_IDS =
        listOf(R.raw.le_ye1, R.raw.le_ye2, R.raw.le_r10, R.raw.le_r11, R.raw.le_e5, R.raw.le_e6)

    @Volatile
    private var cached: Pair<SSLSocketFactory, X509TrustManager>? = null

    /** 返回 (SSLSocketFactory, X509TrustManager)，供 OkHttp/Coil 共用 */
    fun socketFactoryWithTrustManager(): Pair<SSLSocketFactory, X509TrustManager> =
        cached ?: synchronized(this) {
            cached ?: create().also { cached = it }
        }

    private fun create(): Pair<SSLSocketFactory, X509TrustManager> {
        val systemTm = systemDefaultTrustManager()
        val chainFixTm = try {
            chainFixTrustManager(systemTm)
        } catch (e: Exception) {
            "内置中间证书加载失败，证书补全降级为仅系统校验: $e".log(TAG)
            null
        }
        val wrapped = if (chainFixTm == null) systemTm else DelegatingTrustManager(systemTm, chainFixTm)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(wrapped), null)
        return sslContext.socketFactory to wrapped
    }

    private fun systemDefaultTrustManager(): X509ExtendedTrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509ExtendedTrustManager>().firstOrNull()
            ?: error("系统中未找到 X509ExtendedTrustManager")
    }

    private fun chainFixTrustManager(system: X509TrustManager): X509ExtendedTrustManager {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        // 系统信任锚全部并入，保证补链 TrustManager 的信任范围是「系统 + LE 公共中间证书」
        system.acceptedIssuers.forEachIndexed { i, cert -> ks.setCertificateEntry("sys-$i", cert) }
        val cf = CertificateFactory.getInstance("X.509")
        val app = AnimeApplication.getInstance()
        EXTRA_CERT_RES_IDS.forEachIndexed { i, resId ->
            app.resources.openRawResource(resId).use { input ->
                ks.setCertificateEntry("le-$i", cf.generateCertificate(input))
            }
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.filterIsInstance<X509ExtendedTrustManager>().firstOrNull()
            ?: error("补链 KeyStore 初始化后未找到 X509ExtendedTrustManager")
    }

    private class DelegatingTrustManager(
        private val system: X509ExtendedTrustManager,
        private val chainFix: X509ExtendedTrustManager,
    ) : X509ExtendedTrustManager() {

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            checkServer { system.checkServerTrusted(chain, authType) }
                .orFallback { chainFix.checkServerTrusted(chain, authType) }
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
            checkServer { system.checkServerTrusted(chain, authType, socket) }
                .orFallback { chainFix.checkServerTrusted(chain, authType, socket) }
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {
            checkServer { system.checkServerTrusted(chain, authType, engine) }
                .orFallback { chainFix.checkServerTrusted(chain, authType, engine) }
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            system.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
            system.checkClientTrusted(chain, authType, socket)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
            system.checkClientTrusted(chain, authType, engine)

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

        /**
         * 第一阶段：系统默认严格校验。返回捕获到的异常（成功时为 null），
         * 调用方仅在异常非空时进入补链阶段。
         */
        private inline fun checkServer(block: () -> Unit): CertificateException? = try {
            block()
            null
        } catch (e: CertificateException) {
            e
        }

        private fun CertificateException?.orFallback(fallback: () -> Unit) {
            if (this == null) return
            try {
                fallback()
                "TLS 证书缺链，已用内置公共中间证书补全".log(TAG)
            } catch (fallbackError: CertificateException) {
                fallbackError.addSuppressed(this)
                throw fallbackError
            }
        }
    }
}
