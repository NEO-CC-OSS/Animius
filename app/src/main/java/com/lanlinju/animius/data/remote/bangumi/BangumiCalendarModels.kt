package com.lanlinju.animius.data.remote.bangumi

import com.lanlinju.animius.domain.model.Anime
import java.time.Instant

/** 解析后的周表：key 为 0..6（周一..周日），与现有 TABS 顺序一致 */
data class BangumiCalendar(
    val updatedAt: Instant,
    val days: Map<Int, List<Anime>>,
    val itemCount: Int,
    /* 按 Bangumi subject id 索引的详情字段（切片 2）；旧 JSON 没有摘要时为默认值 */
    val items: Map<Long, MetadataItem> = emptyMap(),
    /* 首页「高分推荐」行（切片 3）；旧 JSON 为空 */
    val featured: List<Anime> = emptyList(),
    /* 生成物是否带切片 3 的首页/相关推荐数据；旧缓存为 false，首页不显示元数据行 */
    val supportsHomeRows: Boolean = false,
    /* 轻包（schema 2）的封面索引：id → 相对路径/内容哈希/LQIP；旧 calendar.json 为空 */
    val covers: Map<Long, MetadataCover> = emptyMap(),
)

/**
 * 轻包封面：独立文件按需加载（不再内嵌 9 MB base64）。
 *
 * @param path 相对路径，形如 covers/390200.webp
 * @param hash 封面内容哈希，拼 `?v=` 做缓存失效
 * @param lqip 32×45 WebP 的 data URI，加载中先显示
 */
data class MetadataCover(
    val path: String,
    val hash: String,
    val lqip: String,
) {
    /** 三级候选：jsDelivr 主域名 → jsDelivr 备用域名 → gh-proxy（与 MediaSmall 的加载顺序一致） */
    val urls: List<String> get() = MetadataCoverUrls.candidates(path, hash)

    val primaryUrl: String get() = urls.getOrElse(0) { "" }
    val backupUrl: String get() = urls.getOrElse(1) { "" }
    val fallbackUrl: String get() = urls.getOrElse(2) { "" }
}

/** 相关推荐条目：只保留详情页卡片需要的字段 */
data class MetadataRelated(
    val id: Long,
    val title: String,
    val image: String,
    /* 第二级回退（jsDelivr 备用域名）；旧内嵌封面为空 */
    val imageBackup: String = "",
    /* 首选封面失败时的回退地址（gh-proxy）；旧内嵌封面为空 */
    val imageFallback: String = "",
    /* 加载中的模糊占位图（data URI）；旧内嵌封面为空 */
    val lqip: String = "",
)

/** 元数据条目的详情字段：周表卡片只需要标题/封面，详情页再取简介/标签 */
data class MetadataItem(
    val id: Long,
    val title: String,
    val originalTitle: String,
    val image: String,
    val summary: String,
    val tags: List<String>,
    val totalEpisodes: Int,
    /** Bangumi 别名（如「海贼王」）；用于搜索与候选校验 */
    val aliases: List<String> = emptyList(),
    /** Bangumi 相关条目（切片 3）；为空时详情页回退源站相关推荐 */
    val related: List<MetadataRelated> = emptyList(),
    /* 第二级回退（jsDelivr 备用域名）；旧内嵌封面为空 */
    val imageBackup: String = "",
    /* 首选封面失败时的回退地址（gh-proxy）；旧内嵌封面为空 */
    val imageFallback: String = "",
    /* 加载中的模糊占位图（data URI）；旧内嵌封面为空 */
    val lqip: String = "",
)

enum class CalendarFreshness {
    /** 24 小时内生成 */
    FRESH,

    /** 超过 24 小时但不超过 7 天：可用，但提示是旧数据 */
    STALE,

    /** 超过 7 天：按失败处理，回退源站 */
    EXPIRED,
}

class CalendarParseException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

sealed interface CalendarLoadResult {
    /**
     * 拿到了可用数据。
     *
     * @param fromCache 数据来自本地缓存（本次网络请求失败或未发起请求）
     * @param freshness 数据的新鲜度
     * @param failureReason 网络失败但仍用了旧缓存时的原因（仅用于日志/诊断，不直接展示给用户）
     */
    data class Success(
        val calendar: BangumiCalendar,
        val fromCache: Boolean,
        val freshness: CalendarFreshness,
        val failureReason: String? = null,
    ) : CalendarLoadResult

    /** 没有可用数据（无缓存、缓存过期或解析失败），调用方应回退到源站周表 */
    data class Unavailable(val reason: String) : CalendarLoadResult
}
