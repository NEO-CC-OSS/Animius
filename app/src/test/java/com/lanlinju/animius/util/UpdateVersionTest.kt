package com.lanlinju.animius.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {

    @Test
    fun newerTvVersionIsDetected() {
        assertTrue(isNewerTvVersion("v1.3.5-tv.5", "1.3.5-tv.4"))
        assertTrue(isNewerTvVersion("v1.3.5-tv.10", "1.3.5-tv.9"))
        assertTrue(isNewerTvVersion("v1.3.6-tv.1", "1.3.5-tv.9"))
        assertTrue(isNewerTvVersion("v1.3.10-tv.1", "1.3.9-tv.9"))
    }

    @Test
    fun sameOrOlderVersionDoesNotPrompt() {
        assertFalse(isNewerTvVersion("v1.3.5-tv.4", "1.3.5-tv.4"))
        assertFalse(isNewerTvVersion("v1.3.5-tv.3", "1.3.5-tv.4"))
        assertFalse(isNewerTvVersion("v1.3.5-tv.1", "1.3.5-tv.4"))
        // 上游同基础版本没有 -tv 后缀：不提示（签名不同，装了也覆盖不了）
        assertFalse(isNewerTvVersion("v1.3.5", "1.3.5-tv.4"))
    }

    @Test
    fun malformedTagsAreIgnored() {
        assertFalse(isNewerTvVersion("", "1.3.5-tv.4"))
        assertFalse(isNewerTvVersion("v1.3.5-tv.x", "1.3.5-tv.4"))
        assertFalse(isNewerTvVersion("v1.3.5-tv.5", ""))
    }
}
