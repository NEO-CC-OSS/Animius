package com.lanlinju.animius.util

const val CROSSFADE_DURATION = 500
const val VIDEO_ASPECT_RATIO = 1.778f

const val LOW_CONTENT_ALPHA = 0.35f

val TABS = listOf("一", "二", "三", "四", "五", "六", "日")

const val CRASH_LOG_FILE = "anime_crash_logs.txt"

// 指向本 fork（NEO-CC-OSS/Animius）：更新检查与管理链接均指向 TV 版自己的发布，
// 避免收到上游版本提示、或把用户引导到不含 TV 优化的上游安装包
const val GITHUB_ADDRESS = "https://github.com/NEO-CC-OSS/Animius"
const val CHECK_UPDATE_ADDRESS = "https://api.github.com/repos/NEO-CC-OSS/Animius/releases/latest"
const val GITHUB_RELEASE_ADDRESS = "https://github.com/NEO-CC-OSS/Animius/releases/latest"

const val ANIME_DATABASE = "anime_database.db"
const val FAVOURITE_TABLE = "favourite_table"
const val HISTORY_TABLE = "history_table"
const val EPISODE_TABLE = "episode_table"
const val DOWNLOAD_TABLE = "download_table"
const val DOWNLOAD_DETAIL_TABLE = "download_detail_table"

const val SEARCH_PAGE_SIZE = 10

const val KEY_DOWNLOAD_UPDATE_URL = "downloadUpdateUrl"
const val KEY_DOWNLOAD_UPDATE_SHA256 = "downloadUpdateSha256"
const val KEY_DOWNLOAD_UPDATE_SIZE = "downloadUpdateSize"

/** 后台更新任务失败原因（WorkManager outputData），用于 UI 给出对应的提示文案 */
const val KEY_UPDATE_FAIL_REASON = "updateFailReason"

/** 更新任务失败时的异常详情（诊断用，UI 原样展示） */
const val KEY_UPDATE_ERROR_DETAIL = "updateErrorDetail"

/** 当前下载速度（字节/秒） */
const val KEY_UPDATE_SPEED = "updateSpeed"

/** 后台更新任务的下载进度（WorkManager progress）：百分比整数（-1 表示未知）与已下载字节数 */
const val KEY_UPDATE_PROGRESS = "updateProgress"
const val KEY_UPDATE_DOWNLOADED = "updateDownloaded"

/** 更新任务在 WorkManager 中的唯一名称：两条入口（主页自动 / 时间表页手动）共用同一个任务，天然防重入 */
const val UPDATE_WORK_NAME = "updateWork"

// GitHub 加速镜像（GitHub 直连在国内时通时不通；下载/检查更新按顺序回退）
val GITHUB_MIRROR_PREFIXES = listOf(
    "https://gh-proxy.com/",
    "https://gh-proxy.net/",
)

const val DefaultUserAgent =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3"