package com.lanlinju.animius.data.remote.dandanplay

import com.anime.danmaku.api.DanmakuSession
import com.anime.danmaku.api.TimeBasedDanmakuSession
import com.lanlinju.animius.data.remote.dandanplay.DandanplayDanmakuProvider.Companion.ID
import com.lanlinju.animius.data.remote.dandanplay.dto.SearchAnimeEpisodes
import com.lanlinju.animius.data.remote.dandanplay.dto.SearchEpisodeDetails
import com.lanlinju.animius.data.remote.dandanplay.dto.toDanmakuOrNull
import com.lanlinju.animius.domain.model.DanmakuResult
import com.lanlinju.animius.util.createHttpClient
import com.lanlinju.animius.util.log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A [DanmakuProvider] provides a stream of danmaku for a specific episode.
 *
 * @see DanmakuProviderFactory
 */
interface DanmakuProvider : AutoCloseable {
    // 弹幕提供者的唯一标识符
    val id: String

    // 挂起函数，用于获取弹幕会话
    suspend fun fetch(subjectName: String, episodeName: String?): DanmakuResult
}

interface DanmakuProviderFactory { // SPI 接口
    /**
     * @see DanmakuProvider.id
     * 获取弹幕提供者的唯一标识符
     */
    val id: String

    // 创建一个新的弹幕提供者实例
    fun create(): DanmakuProvider
}

@Singleton
class DandanplayDanmakuProvider @Inject constructor(
    private val client: HttpClient
) : DanmakuProvider {

    companion object {
        const val ID = "弹弹play"
        private const val TAG = "DanmakuProvider"
    }

    override val id: String get() = ID

    private val dandanplayClient = DandanplayClient(client)
    private val moviePattern = Regex("全集|HD|正片|剧场版|映画")
    private val rangePattern = Regex("^(\\d+)\\s*[-~—]\\s*\\d+$") // 01-02 -> 取首集
    private val leadingNumberPattern = Regex("^(\\d+)") // 01v2/01.5 -> 01
    private val seasonPattern =
        Regex("第[一二三四五六七八九十0-9０-９]+[季期部]|Season\\s*\\d+|\\bS\\d+\\b", RegexOption.IGNORE_CASE)
    private val yearPattern = Regex("[（(\\[]\\d{4}[）)\\]]")

    override suspend fun fetch(
        subjectName: String, episodeName: String?
    ): DanmakuResult {
        if (episodeName.isNullOrBlank()) {
            return DanmakuResult.UnsupportedEpisode("剧集名缺失，无法匹配弹幕")
        }
        val targetEpisode = normalizeEpisodeName(episodeName)
            ?: return DanmakuResult.UnsupportedEpisode("「$episodeName」暂不支持自动匹配弹幕")

        // 搜索阶段（含网络异常）统一分类，不让异常穿透到 UI 层
        val searchResponse = try {
            fetchWithFallback(subjectName, targetEpisode)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "弹幕搜索失败: $e".log(TAG)
            return DanmakuResult.NetworkError("弹幕服务连接失败: ${e.message ?: e.javaClass.simpleName}")
        }

        if (!searchResponse.success) {
            return classifyApiError(searchResponse.errorCode, searchResponse.errorMessage)
        }
        if (searchResponse.animes.isEmpty()) {
            return DanmakuResult.NotFound("弹幕库中未找到「$subjectName」")
        }

        // 番剧回找：优先标题精确/包含匹配，不盲取第一条
        val cleanedSubject = cleanSubjectName(subjectName)
        val anime = searchResponse.animes.firstOrNull { it.animeTitle == subjectName }
            ?: searchResponse.animes.firstOrNull { cleanedSubject.isNotBlank() && it.animeTitle.contains(cleanedSubject) }
            ?: searchResponse.animes.first()

        // 集数回找：按归一化集数在候选列表中定位目标集，避免取到第一集的弹幕
        val episode: SearchEpisodeDetails =
            findTargetEpisode(anime.episodes, targetEpisode)
                ?: if (anime.episodes.size == 1) {
                    anime.episodes[0]
                } else {
                    return DanmakuResult.NotFound("弹幕库中未匹配到第 $targetEpisode 集")
                }

        return createSession(episode.episodeId.toLong())
    }

    /** 搜索降级链：原名 -> 去掉「第X季/年份括号」后的主干名 */
    private suspend fun fetchWithFallback(subjectName: String, targetEpisode: String) =
        dandanplayClient.searchEpisode(subjectName, targetEpisode).let { first ->
            val cleaned = cleanSubjectName(subjectName)
            if (!first.success || first.animes.isNotEmpty() || cleaned == subjectName || cleaned.isBlank()) {
                first
            } else {
                "「$subjectName」搜索无结果，用「$cleaned」重试".log(TAG)
                dandanplayClient.searchEpisode(cleaned, targetEpisode)
            }
        }

    /** 剧集名归一化为弹弹play 可识别的集数；无法识别返回 null */
    private fun normalizeEpisodeName(name: String): String? {
        val trimmed = name.trim()
        return when {
            moviePattern.containsMatchIn(trimmed) -> "movie" // 剧场版
            rangePattern.matches(trimmed) -> rangePattern.find(trimmed)!!.groupValues[1]
            trimmed.contains("第") -> trimmed.filter { it.isDigit() }.ifEmpty { null } // 第01集/第01话 -> 01
            trimmed.matches(Regex("\\d+")) -> trimmed // girigiri tv的剧集只有数字
            else -> leadingNumberPattern.find(trimmed)?.groupValues[1] // 01v2/01.5 -> 01；纯「上/下」无数字 -> null
        }
    }

    /** 去掉标题里的季数标注与年份括号，作为搜索降级的主干名 */
    private fun cleanSubjectName(name: String): String =
        name.replace(yearPattern, "").replace(seasonPattern, "").trim(' ', '-', '·', '~', '～')

    /**
     * 在候选集列表中定位目标集：
     * 归一化后相等优先；全列表唯一时直接采用；否则视为未命中（宁可无弹幕也不错挂其他集的时间轴）。
     */
    private fun findTargetEpisode(
        episodes: List<SearchEpisodeDetails>,
        target: String,
    ): SearchEpisodeDetails? {
        episodes.firstOrNull { normalizeEpisodeName(it.episodeTitle) == target }?.let { return it }
        return if (episodes.size == 1) episodes[0] else null
    }

    private fun classifyApiError(errorCode: Int, errorMessage: String?): DanmakuResult {
        val message = errorMessage ?: "未知错误(errorCode=$errorCode)"
        "弹弹play API 错误: errorCode=$errorCode, $message".log(TAG)
        return if (errorMessage?.contains("应用不存在") == true || message.contains("appId", ignoreCase = true)) {
            DanmakuResult.AuthFailed("弹弹play 鉴权失败（AppId 无效或未生效）: $message")
        } else {
            DanmakuResult.NetworkError("弹弹play 服务返回错误: $message")
        }
    }

    private suspend fun createSession(
        episodeId: Long,
    ): DanmakuResult {
        return try {
            val list = dandanplayClient.getDanmakuList(episodeId = episodeId)
            if (list.isEmpty()) {
                DanmakuResult.NotFound("弹幕库中该集暂无弹幕数据")
            } else {
                DanmakuResult.Success(
                    TimeBasedDanmakuSession.create(
                        list.asSequence().mapNotNull { it.toDanmakuOrNull() },
                        coroutineContext = Dispatchers.Default,
                    )
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: DandanplayApiException) {
            classifyApiError(e.errorCode, e.message)
        } catch (e: Exception) {
            "弹幕列表获取失败(episodeId=$episodeId): $e".log(TAG)
            DanmakuResult.NetworkError("弹幕数据获取失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun close() {
        //client.close()
    }
}

class DandanplayDanmakuProviderFactory : DanmakuProviderFactory {
    override val id: String get() = ID

    override fun create(): DandanplayDanmakuProvider {
        return DandanplayDanmakuProvider(createHttpClient())
    }
}
