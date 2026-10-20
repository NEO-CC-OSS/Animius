package com.lanlinju.animius.matching

import com.lanlinju.animius.domain.matching.EpisodeNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeNumberTest {

    @Test
    fun `arabic episode names normalize to the same number`() {
        assertEquals("1", EpisodeNumber.normalize("第01集"))
        assertEquals("1", EpisodeNumber.normalize("第1话"))
        assertEquals("1", EpisodeNumber.normalize("01"))
        assertEquals("1", EpisodeNumber.normalize("1"))
    }

    @Test
    fun `canonical removes leading zeros`() {
        assertEquals("1", EpisodeNumber.canonical("01"))
        assertEquals("12", EpisodeNumber.canonical("012"))
        assertEquals("movie", EpisodeNumber.canonical("movie"))
    }

    @Test
    fun `range takes the first episode`() {
        assertEquals("1", EpisodeNumber.normalize("01-02"))
        assertEquals("3", EpisodeNumber.normalize("03~04"))
    }

    @Test
    fun `version suffix keeps the base episode`() {
        assertEquals("1", EpisodeNumber.normalize("01v2"))
    }

    @Test
    fun `movie markers normalize to movie`() {
        assertEquals("movie", EpisodeNumber.normalize("全集"))
        assertEquals("movie", EpisodeNumber.normalize("剧场版"))
        assertEquals("movie", EpisodeNumber.normalize("HD"))
    }

    @Test
    fun `chinese episode numbers are supported`() {
        assertEquals("3", EpisodeNumber.normalize("第三集"))
        assertEquals("10", EpisodeNumber.normalize("第十话"))
        assertEquals("12", EpisodeNumber.normalize("第十二集"))
    }

    @Test
    fun `unknown names return null instead of guessing`() {
        assertNull(EpisodeNumber.normalize("SP"))
        assertNull(EpisodeNumber.normalize("特别篇"))
        assertNull(EpisodeNumber.normalize(""))
    }
}
