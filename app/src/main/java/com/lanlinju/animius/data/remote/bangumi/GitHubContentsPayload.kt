package com.lanlinju.animius.data.remote.bangumi

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * GitHub contents API 响应体解码（纯逻辑，便于 JVM 单测）。
 *
 * `GET /repos/{owner}/{repo}/contents/index.json?ref=metadata` 返回：
 * ```json
 * { "name": "index.json", "size": 294737, "encoding": "base64", "content": "eyJzY2hlbWEi..." }
 * ```
 * `content` 是带换行的 base64；解码后就是 index.json 原文。
 *
 * 失败（不是 base64 / 缺 content / 解不开）返回 null，调用方回退下一个通道。
 */
object GitHubContentsPayload {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun decode(body: String): String? {
        val obj = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null

        val encoding = obj["encoding"]?.jsonPrimitive?.contentOrNull
        if (!encoding.equals("base64", ignoreCase = true)) return null

        val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return null
        // GitHub 每 60 字符插一个换行；标准 decoder 不接受换行，先去掉空白
        val compact = content.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return null

        val bytes = try {
            Base64.getDecoder().decode(compact)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return try {
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
