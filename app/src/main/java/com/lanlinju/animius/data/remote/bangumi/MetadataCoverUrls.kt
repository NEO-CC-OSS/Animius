package com.lanlinju.animius.data.remote.bangumi

/**
 * 封面地址组装（纯函数，便于 JVM 单测）。
 *
 * 通道实测（2026-10-21，用户无梯子）：
 * - 直连 raw.githubusercontent.com：000（连接被重置）
 * - gh-proxy.com：40–104 KB/s，偶发抖动
 * - gcore.jsdelivr.net / testingcf.jsdelivr.net：170–210 KB/s（并发 6 时 1/6 卡 30 s）
 *
 * 因此默认走 jsDelivr（快），失败回退 gh-proxy（稳）；`?v={cover_hash}` 只用于内容变化时失效缓存。
 */
object MetadataCoverUrls {

    const val JSDELIVR_PRIMARY_PREFIX =
        "https://gcore.jsdelivr.net/gh/NEO-CC-OSS/Animius-TV@metadata/"

    const val JSDELIVR_BACKUP_PREFIX =
        "https://testingcf.jsdelivr.net/gh/NEO-CC-OSS/Animius-TV@metadata/"

    const val GH_PROXY_PREFIX =
        "https://gh-proxy.com/https://raw.githubusercontent.com/NEO-CC-OSS/Animius-TV/metadata/"

    /** 首选地址（jsDelivr 主域名） */
    fun primary(coverPath: String, coverHash: String = ""): String =
        build(JSDELIVR_PRIMARY_PREFIX, coverPath, coverHash)

    /** 第二级回退（jsDelivr 备用域名；与主域名同为 170–210 KB/s，只是解析/边缘节点不同） */
    fun backup(coverPath: String, coverHash: String = ""): String =
        build(JSDELIVR_BACKUP_PREFIX, coverPath, coverHash)

    /** 最后一级回退（gh-proxy → raw.githubusercontent.com，慢但稳） */
    fun fallback(coverPath: String, coverHash: String = ""): String =
        build(GH_PROXY_PREFIX, coverPath, coverHash)

    /**
     * 完整候选顺序：jsDelivr 主域名 → jsDelivr 备用域名 → gh-proxy。
     *
     * MediaSmall 的三级加载顺序与 [MetadataCover] 的 `primaryUrl/backupUrl/fallbackUrl` 一致，
     * 由本函数统一组装，避免两处各写一份前缀。
     */
    fun candidates(coverPath: String, coverHash: String = ""): List<String> {
        if (coverPath.isBlank()) return emptyList()
        return listOf(
            primary(coverPath, coverHash),
            backup(coverPath, coverHash),
            fallback(coverPath, coverHash),
        ).filter { it.isNotEmpty() }.distinct()
    }

    private fun build(prefix: String, coverPath: String, coverHash: String): String {
        val path = coverPath.trim().trimStart('/')
        if (path.isEmpty()) return ""
        val hash = coverHash.trim()
        return if (hash.isEmpty()) prefix + path else prefix + path + "?v=" + hash
    }
}
