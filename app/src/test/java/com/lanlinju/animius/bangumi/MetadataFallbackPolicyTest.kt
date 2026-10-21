package com.lanlinju.animius.bangumi

import com.lanlinju.animius.data.remote.bangumi.MetadataFallbackPolicy
import com.lanlinju.animius.data.remote.bangumi.BangumiCalendar
import com.lanlinju.animius.data.remote.bangumi.CalendarFreshness
import com.lanlinju.animius.data.remote.bangumi.CalendarLoadResult
import com.lanlinju.animius.data.remote.bangumi.MetadataIndexCache
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** index.json 全失败时的兜底决策：有缓存用缓存，没缓存 Unavailable。 */
class MetadataFallbackPolicyTest {

    private val calendar = BangumiCalendar(
        updatedAt = Instant.parse("2026-10-20T08:00:00Z"),
        days = emptyMap(),
        itemCount = 0,
    )

    @Test
    fun `uses cache when network fails`() {
        val entry = MetadataIndexCache.Entry(calendar, CalendarFreshness.STALE)

        val result = MetadataFallbackPolicy.onNetworkFailure(entry, "all channels failed")

        assertTrue(result is CalendarLoadResult.Success)
        val success = result as CalendarLoadResult.Success
        assertTrue(success.fromCache)
        assertEquals(CalendarFreshness.STALE, success.freshness)
        assertEquals("all channels failed", success.failureReason)
        assertEquals(calendar, success.calendar)
    }

    @Test
    fun `unavailable when no cache`() {
        val result = MetadataFallbackPolicy.onNetworkFailure(null, "all channels failed")

        assertTrue(result is CalendarLoadResult.Unavailable)
        assertEquals("all channels failed", (result as CalendarLoadResult.Unavailable).reason)
    }
}
