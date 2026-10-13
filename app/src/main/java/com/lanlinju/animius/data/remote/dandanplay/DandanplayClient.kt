package com.lanlinju.animius.data.remote.dandanplay

import com.lanlinju.animius.BuildConfig
import com.lanlinju.animius.data.remote.dandanplay.dto.DandanplayDanmaku
import com.lanlinju.animius.data.remote.dandanplay.dto.DandanplayDanmakuListResponse
import com.lanlinju.animius.data.remote.dandanplay.dto.DandanplayMatchRequest
import com.lanlinju.animius.data.remote.dandanplay.dto.DandanplayMatchResponse
import com.lanlinju.animius.data.remote.dandanplay.dto.DandanplaySearchEpisodeResponse
import com.lanlinju.animius.util.log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import java.lang.System.currentTimeMillis
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** 弹弹play 服务返回的错误结构（鉴权失败、参数错误等），与网络层异常区分开 */
class DandanplayApiException(
    val errorCode: Int,
    message: String,
) : Exception(message)

class DandanplayClient(
    private val client: HttpClient,
    private val appId: String = BuildConfig.DANDANPLAY_APP_ID,
    private val appSecret: String = BuildConfig.DANDANPLAY_APP_SECRET,
) {

    suspend fun searchEpisode(
        subjectName: String,
        episodeName: String?,
    ): DandanplaySearchEpisodeResponse {
        "搜索弹幕集数: anime=$subjectName, episode=$episodeName".log(TAG)
        val response = client.get("https://api.dandanplay.net/api/v2/search/episodes") {
            configureTimeout()
            accept(ContentType.Application.Json)
            addAuthorizationHeaders()
            parameter("anime", subjectName)
            parameter("episode", episodeName)
        }

        return response.body<DandanplaySearchEpisodeResponse>()
    }

    suspend fun getDanmakuList(episodeId: Long): List<DandanplayDanmaku> {
        val chConvert = 0
        "获取弹幕列表: episodeId=$episodeId".log(TAG)
        val response =
            client.get("https://api.dandanplay.net/api/v2/comment/${episodeId}?chConvert=$chConvert&withRelated=true") {
                configureTimeout()
                accept(ContentType.Application.Json)
                addAuthorizationHeaders()
            }.body<DandanplayDanmakuListResponse>()

        if (!response.success || response.errorCode != 0) {
            throw DandanplayApiException(response.errorCode, response.errorMessage ?: "errorCode=${response.errorCode}")
        }
        return response.comments
    }

    /**
     * 文件识别（/api/v2/match）：按文件名做模糊匹配，用于 search/episodes 失配时的兜底。
     *
     * 流媒体播放没有本地文件、无法计算真实文件哈希，因此以占位哈希 + fileNameOnly 模式发起
     * （实测：fileHash 缺失会被判为参数错误，占位哈希不影响文件名匹配结果）。
     */
    suspend fun matchEpisode(fileName: String): DandanplayMatchResponse {
        "文件识别: fileName=$fileName".log(TAG)
        val response = client.post("https://api.dandanplay.net/api/v2/match") {
            configureTimeout()
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            addAuthorizationHeaders()
            setBody(
                DandanplayMatchRequest(
                    fileName = fileName,
                    fileHash = DandanplayMatchRequest.PLACEHOLDER_FILE_HASH,
                    matchMode = DandanplayMatchRequest.MATCH_MODE_FILE_NAME_ONLY,
                )
            )
        }
        return response.body<DandanplayMatchResponse>()
    }

    private fun HttpRequestBuilder.addAuthorizationHeaders() {
        val timestamp = currentTimeMillis() / 1000
        header("X-AppId", appId)
        header("X-Timestamp", timestamp)
        header("X-Signature", generateSignature(appId, timestamp, url.encodedPath, appSecret))
    }

    companion object {
        private const val TAG = "Dandanplay"
    }

    private fun HttpRequestBuilder.configureTimeout() {
        timeout {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun generateSignature(
        appId: String,
        timestamp: Long,
        path: String,
        appSecret: String
    ): String {
        val data = appId + timestamp + path + appSecret
        val hash = MessageDigest.getInstance("SHA-256").digest(data.toByteArray())
        return Base64.encode(hash)
    }
}