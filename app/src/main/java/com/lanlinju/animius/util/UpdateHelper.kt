package com.lanlinju.animius.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * 应用内更新：检查 + 下载。
 *
 * GitHub 直连在国内时通时不通，因此所有请求都走「直连优先、镜像回退」的候选列表；
 * 更新包下载完成后必须通过官方 sha256 与安装包签名双重校验，校验不通过一律拒绝安装。
 */

/** GitHub Release 信息（检查更新用） */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String = "",
    val body: String = "",
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0,
    val digest: String? = null, // 官方 sha256，形如 "sha256:xxxx"
)

/** 更新包下载结果 */
sealed interface UpdateDownloadResult {
    /** 下载完成且通过全部校验 */
    object Success : UpdateDownloadResult

    /** 所有通道都连不上/下载失败 */
    object AllChannelsFailed : UpdateDownloadResult

    /** 下载到了数据但哈希/体积校验不通过（通道被篡改或传输损坏），已拒绝 */
    object ChecksumMismatch : UpdateDownloadResult

    /** 下载包的签名与当前应用不一致，已拒绝安装 */
    object SignatureMismatch : UpdateDownloadResult
}

/**
 * 判断线上 tag 是否比本地版本更新。
 *
 * 版本格式：`v1.3.5-tv.N` ↔ `1.3.5-tv.N`。只认本项目带 `-tv.` 的发布版，
 * 避免把上游同基础版本（`v1.3.5`，签名不同装不上）误判为更新。
 * 基础版本按数字段比较，相同再比 tv 序号；线上不高于本地时一律不提示。
 */
fun isNewerTvVersion(remoteTag: String, localVersionName: String): Boolean {
    val remote = parseTvVersion(remoteTag) ?: return false
    val local = parseTvVersion(localVersionName) ?: return false
    return compareTvVersion(remote, local) > 0
}

private data class TvVersion(val base: List<Int>, val tv: Int)

private fun parseTvVersion(raw: String): TvVersion? {
    val text = raw.trim().removePrefix("v")
    val match = Regex("""^(\d+(?:\.\d+)*)-tv\.(\d+)$""").find(text) ?: return null
    val base = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
    val tv = match.groupValues[2].toIntOrNull() ?: return null
    return TvVersion(base, tv)
}

private fun compareTvVersion(a: TvVersion, b: TvVersion): Int {
    val size = maxOf(a.base.size, b.base.size)
    for (i in 0 until size) {
        val diff = a.base.getOrElse(i) { 0 } - b.base.getOrElse(i) { 0 }
        if (diff != 0) return diff
    }
    return a.tv - b.tv
}

private const val TAG = "UpdateHelper"

private val updateJson = Json { ignoreUnknownKeys = true }

/** 候选地址：直连优先，其次镜像加速（任一成功即可） */
private fun githubUrlCandidates(url: String): List<String> = buildList {
    add(url)
    GITHUB_MIRROR_PREFIXES.forEach { add(it + url) }
}

/**
 * 检查更新：GitHub 直连失败时自动走镜像代理。
 * @return null 表示所有通道都失败
 */
suspend fun fetchLatestRelease(client: HttpClient): GitHubRelease? {
    for (candidate in githubUrlCandidates(CHECK_UPDATE_ADDRESS)) {
        try {
            val text = client.get(candidate).bodyAsText()
            return updateJson.decodeFromString<GitHubRelease>(text)
        } catch (e: Exception) {
            e.message?.log("checkUpdate: ")
        }
    }
    return null
}

/**
 * 下载更新包：逐通道尝试，成功下载后校验官方 sha256 与安装包签名。
 * 哈希校验不过会换下一个通道重试；全部失败返回对应原因。
 *
 * @param onProgress 下载进度回调（已下载字节, 总字节；总字节未知时为 0）；
 *                   切换通道重试时会以 0 重新开始上报。
 */
suspend fun downloadUpdateApk(
    client: HttpClient,
    url: String,
    target: File,
    expectedSha256: String?,
    expectedSize: Long,
    context: Context,
    onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
): UpdateDownloadResult {
    var sawChecksumMismatch = false
    for (candidate in githubUrlCandidates(url)) {
        try {
            // 换通道重试：进度归零重新上报
            onProgress?.invoke(0L, expectedSize)
            val bytes = client.get(candidate) {
                onDownload { sent, total ->
                    onProgress?.invoke(sent, total ?: expectedSize)
                }
            }.bodyAsBytes()
            if (bytes.isEmpty()) continue
            "更新包已下载: ${bytes.size} bytes（通道 ${candidate.take(40)}）".log(TAG)
            // 官方体积校验
            if (expectedSize > 0 && bytes.size.toLong() != expectedSize) {
                "体积校验不通过: ${bytes.size} != $expectedSize".log(TAG)
                sawChecksumMismatch = true
                continue
            }
            // 官方 sha256 校验：走镜像通道时防止被篡改或传输损坏
            val expected = expectedSha256?.removePrefix("sha256:")?.trim()?.takeIf { it.isNotEmpty() }
            if (expected != null && !sha256Hex(bytes).equals(expected, ignoreCase = true)) {
                "sha256 校验不通过（通道 ${candidate.take(40)}）".log(TAG)
                sawChecksumMismatch = true
                continue
            }
            if (target.exists()) target.delete()
            target.writeBytes(bytes)
            // 纵深防御：安装包签名必须与当前应用一致（防降级/替换）
            if (!isApkSignatureTrusted(context, target)) {
                "安装包签名校验不通过，已删除".log(TAG)
                target.delete()
                return UpdateDownloadResult.SignatureMismatch
            }
            "更新包下载完成并通过全部校验".log(TAG)
            return UpdateDownloadResult.Success
        } catch (e: Exception) {
            e.message?.log("downloadUpdate: ")
        }
    }
    return if (sawChecksumMismatch) UpdateDownloadResult.ChecksumMismatch
    else UpdateDownloadResult.AllChannelsFailed
}

/**
 * 校验下载的安装包签名是否与当前已安装应用一致。
 * 读不到已装应用签名时（个别低版本系统）以哈希校验为准放行。
 */
fun isApkSignatureTrusted(context: Context, apkFile: File): Boolean {
    return try {
        val pm = context.packageManager
        val installedSigs = installedSignatureDigests(context) ?: return true
        val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        val archiveSigs = archiveInfo?.signingInfo?.apkContentsSigners
            ?.map { it.toCharsString() }?.toSet()
            ?: return false
        installedSigs.isNotEmpty() && installedSigs == archiveSigs
    } catch (e: Exception) {
        e.message?.log("verifyApk: ")
        false
    }
}

private fun installedSignatureDigests(context: Context): Set<String>? = try {
    val pm = context.packageManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            .signatures?.map { it.toCharsString() }?.toSet()
    }
} catch (e: Exception) {
    e.message?.log("installedSig: ")
    null
}

/** 调起系统安装器；部分 TV 系统缺少安装器组件时会抛异常 */
fun Context.installApkSafely(file: File): Boolean = try {
    installApk(file)
    true
} catch (e: Exception) {
    e.message?.log("installApk: ")
    false
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
