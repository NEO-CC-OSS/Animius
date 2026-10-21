package com.lanlinju.animius.bangumi

import com.lanlinju.animius.data.remote.bangumi.MetadataIndexFetcher
import com.lanlinju.animius.data.remote.bangumi.MetadataIndexOrigin
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * index.json 通道顺序与回退（MockEngine）。
 *
 * 顺序：api.github.com（base64）→ gh-proxy → jsDelivr（兜底，可能滞后）。
 * 403（未认证限流）/404/非 base64 都必须回退到下一通道；全失败返回 Err。
 */
class MetadataIndexFetcherTest {

    private val now = Instant.parse("2026-10-21T09:20:00Z")

    private fun indexJson(updatedAt: String = "2026-10-21T08:53:19Z"): String {
        val days = (1..7).joinToString(",") { weekday ->
            val items = if (weekday == 1) {
                """{"id":1,"name":"A","name_cn":"甲","cover":"covers/1.webp","cover_hash":"h","lqip":"data:image/webp;base64,AA=="}"""
            } else {
                ""
            }
            """{"weekday":$weekday,"weekday_cn":"d$weekday","items":[$items]}"""
        }
        return """{"schema":2,"home_rows":1,"updated_at":"$updatedAt","source":"test","days":[$days]}"""
    }

    private fun apiBody(json: String): String =
        """{"encoding":"base64","content":"${Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))}"}"""

    private fun MockRequestHandleScope.jsonResponse(body: String) = respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun MockRequestHandleScope.failure(status: HttpStatusCode) =
        respond(content = "error", status = status)

    private fun client(
        api: MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData,
        proxy: MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData,
        jsdelivr: MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData,
    ): HttpClient {
        val engine = MockEngine { request ->
            when (request.url.host) {
                "api.github.com" -> api()
                "gh-proxy.com" -> proxy()
                else -> jsdelivr() // gcore.jsdelivr.net / testingcf.jsdelivr.net
            }
        }
        return HttpClient(engine) { install(HttpTimeout) }
    }

    @Test
    fun `prefers github api and decodes base64`() = runBlocking {
        val json = indexJson()
        val http = client(
            api = { jsonResponse(apiBody(json)) },
            proxy = { failure(HttpStatusCode.InternalServerError) },
            jsdelivr = { failure(HttpStatusCode.InternalServerError) },
        )

        val result = MetadataIndexFetcher(http).fetch(now)

        assertTrue(result is MetadataIndexFetcher.Result.Ok)
        val outcome = (result as MetadataIndexFetcher.Result.Ok).outcome
        assertEquals(MetadataIndexOrigin.API, outcome.origin)
        assertEquals(json, outcome.raw)
        assertEquals(1, outcome.calendar.itemCount)
    }

    @Test
    fun `api 403 rate limit falls back to gh proxy`() = runBlocking {
        val json = indexJson()
        val http = client(
            api = { failure(HttpStatusCode.Forbidden) },
            proxy = { jsonResponse(json) },
            jsdelivr = { failure(HttpStatusCode.InternalServerError) },
        )

        val result = MetadataIndexFetcher(http).fetch(now)

        assertTrue(result is MetadataIndexFetcher.Result.Ok)
        assertEquals(
            MetadataIndexOrigin.GH_PROXY,
            (result as MetadataIndexFetcher.Result.Ok).outcome.origin,
        )
    }

    @Test
    fun `api 404 falls back to gh proxy`() = runBlocking {
        val json = indexJson()
        val http = client(
            api = { failure(HttpStatusCode.NotFound) },
            proxy = { jsonResponse(json) },
            jsdelivr = { failure(HttpStatusCode.InternalServerError) },
        )

        val result = MetadataIndexFetcher(http).fetch(now)

        assertTrue(result is MetadataIndexFetcher.Result.Ok)
        assertEquals(
            MetadataIndexOrigin.GH_PROXY,
            (result as MetadataIndexFetcher.Result.Ok).outcome.origin,
        )
    }

    @Test
    fun `non base64 api payload falls back to gh proxy`() = runBlocking {
        val json = indexJson()
        val http = client(
            api = { jsonResponse("""{"encoding":"utf-8","content":"$json"}""") },
            proxy = { jsonResponse(json) },
            jsdelivr = { failure(HttpStatusCode.InternalServerError) },
        )

        val result = MetadataIndexFetcher(http).fetch(now)

        assertTrue(result is MetadataIndexFetcher.Result.Ok)
        assertEquals(
            MetadataIndexOrigin.GH_PROXY,
            (result as MetadataIndexFetcher.Result.Ok).outcome.origin,
        )
    }

    @Test
    fun `jsdelivr is last resort when api and proxy fail`() = runBlocking {
        val json = indexJson()
        val http = client(
            api = { failure(HttpStatusCode.Forbidden) },
            proxy = { failure(HttpStatusCode.Forbidden) },
            jsdelivr = { jsonResponse(json) },
        )

        val result = MetadataIndexFetcher(http).fetch(now)

        assertTrue(result is MetadataIndexFetcher.Result.Ok)
        assertEquals(
            MetadataIndexOrigin.JSDELIVR,
            (result as MetadataIndexFetcher.Result.Ok).outcome.origin,
        )
    }

    @Test
    fun `all channels failing returns error`() = runBlocking {
        val http = client(
            api = { failure(HttpStatusCode.Forbidden) },
            proxy = { failure(HttpStatusCode.Forbidden) },
            jsdelivr = { failure(HttpStatusCode.Forbidden) },
        )

        val result = MetadataIndexFetcher(http).fetch(now)

        assertTrue(result is MetadataIndexFetcher.Result.Err)
    }
}
