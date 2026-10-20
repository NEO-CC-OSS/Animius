package com.lanlinju.animius.presentation.navigation

import com.lanlinju.animius.domain.model.Episode
import com.lanlinju.animius.util.SourceMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
sealed class Screen {
    @Serializable
    object Main : Screen()

    @Serializable
    data class Search(val query: String = "") : Screen()

    @Serializable
    object HistoryScreen : Screen()

    @Serializable
    object Download : Screen()

    @Serializable
    object Appearance : Screen()

    @Serializable
    object DanmakuSettings : Screen()

    @Serializable
    data class DownloadDetail(val detailUrl: String, val title: String) : Screen()

    /**
     * 源站详情与元数据详情共用同一个页面。
     *
     * - 普通入口（搜索/历史/收藏/下载）：detailUrl 非空、metadataId 为 0；
     * - 周表元数据条目：detailUrl 为空、metadataId > 0，进页后先解析可用源。
     */
    @Serializable
    data class AnimeDetail(
        val detailUrl: String,
        val mode: SourceMode,
        val metadataId: Long = 0,
        val metadataTitle: String = "",
    ) : Screen()

    @Serializable
    data class VideoPlayer(val parameters: String) : Screen()
}

@Serializable
data class PlayerParameters(
    val title: String,              /* 动漫名称 */
    val episodeIndex: Int,          /* 剧集列表索引 */
    val episodes: List<Episode>,    /* 剧集列表 */
    val mode: SourceMode?,           /* 来源 */
    val isLocalVideo: Boolean = false,   /* 是否为本地视频 */
    val autoSource: Boolean = false,     /* 取不到播放地址时自动换源（仅元数据点播为 true） */
    val originalTitle: String = "",      /* 元数据原名：中文标题搜不到时的兜底查询 */
) {
    companion object {
        suspend fun serialize(
            title: String,
            episodeIndex: Int,
            episodes: List<Episode>,
            mode: SourceMode? = null,
            isLocalVideo: Boolean = false,
            autoSource: Boolean = false,
            originalTitle: String = "",
        ): String {
            return Json.encodeToString(
                PlayerParameters(
                    title,
                    episodeIndex,
                    episodes,
                    mode,
                    isLocalVideo,
                    autoSource,
                    originalTitle,
                )
            )
        }

        suspend fun deserialize(value: String): PlayerParameters {
            return Json.decodeFromString<PlayerParameters>(value)
        }
    }
}

/*
val PlayerParametersType = NavType(
    parseValue = { value ->
        Json.decodeFromString<PlayerParameters>(value)
    },
    serializeAsValue = { value ->
        Json.encodeToString(value)
    }
)

val PlayerParametersType = object : NavType<PlayerParameters>(isNullableAllowed = false) {
    override fun get(bundle: Bundle, key: String): PlayerParameters? {
        return bundle.getString(key)?.let { Json.decodeFromString(it) }
    }

    override fun parseValue(value: String): PlayerParameters {
        return Json.decodeFromString(value)
    }

    override fun put(bundle: Bundle, key: String, value: PlayerParameters) {
        bundle.putString(key, Json.encodeToString(value))
    }
}

inline fun <reified T> NavType(
    crossinline parseValue: (String) -> T,
    crossinline serializeAsValue: (T) -> String,
    isNullableAllowed: Boolean = false
) = object : NavType<T>(isNullableAllowed = isNullableAllowed) {

    override fun get(bundle: Bundle, key: String): T? =
        bundle.getString(key)?.let(::parseValue)

    override fun put(bundle: Bundle, key: String, value: T) =
        bundle.putString(key, serializeAsValue(value))

    override fun parseValue(value: String) = parseValue(value)

    override fun serializeAsValue(value: T) = serializeAsValue(value)

    override val name: String = T::class.simpleName ?: super.name
}*/