package com.lanlinju.animius.data.remote.parse

/**
 * Age动漫取址相关的纯函数（便于单测）。
 *
 * 背景（2026-10-22）：加密线路（`/vip/` 等）的播放页 iframe 不是明文 m3u8，原实现直接交
 * WebView 拦截；拦截正则里的 `/obj/` 命中了对象存储路径，把播放器脚本 `.play.min.js`
 * 当视频返回，播放器 0.29 秒后报 `UnrecognizedInputFormatException`。
 *
 * 站点同一部番通常还挂着明文 `/m3u8/` 线路（播放页 `/play/{date}/{line}/{ep}` 里的 line），
 * 纯 HTTP 顺延即可取到——体检脚本 `AgedmProbe.video` 一直是这么做的，App 现在对齐它的口径。
 */

/** 播放页路径：`/play/{date}/{line}/{ep}`，第 2 组是线路号 line（可带尾斜杠；允许无前导斜杠的相对路径） */
private val PLAY_PAGE_REGEX = Regex("""(?:^|/)play/(\d+)/(\d+)/(\d+)/?$""")

/** 非媒体后缀：命中即判失败（典型是播放器脚本 `.play.min.js`） */
private val NON_MEDIA_SUFFIXES = listOf(".js", ".css", ".html")

/**
 * 取址校验：必须是真正的媒体地址（含 `.m3u8` / `.mp4`，允许带 query，如 `index.m3u8?token=x`），
 * 且不能是脚本 / 样式 / 页面。不满足时由调用方抛错，触发既有自动换源链路。
 */
internal fun isPlayableMediaUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return false
    val lower = trimmed.lowercase()
    if (NON_MEDIA_SUFFIXES.any { lower.endsWith(it) }) return false
    // 只看 path 部分判断媒体类型（query 里出现 .m3u8 不算数）
    val path = lower.substringBefore('?').substringBefore('#')
    return path.contains(".m3u8") || path.contains(".mp4")
}

/**
 * 由当前播放页构造顺延线路的播放页 URL（同条目 line+1 .. line+maxExtraLines）。
 *
 * 解析不出 `/play/{date}/{line}/{ep}` 时返回空列表，调用方直接跳过顺延（不抛异常）。
 */
internal fun playPageUrls(
    episodeUrl: String,
    baseUrl: String,
    maxExtraLines: Int = 3,
): List<String> {
    if (maxExtraLines <= 0) return emptyList()
    val path = episodeUrl.trim().substringBefore('?').substringBefore('#')
    val match = PLAY_PAGE_REGEX.find(path) ?: return emptyList()
    val (date, lineText, ep) = match.destructured
    val line = lineText.toIntOrNull() ?: return emptyList()
    val base = baseUrl.trim().trimEnd('/')
    return (1..maxExtraLines).map { offset -> "$base/play/$date/${line + offset}/$ep" }
}
