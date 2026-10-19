package com.lanlinju.animius.parse

import com.lanlinju.animius.data.remote.parse.parseDetailTitle
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 嘶哩嘶哩详情标题清洗回归。
 * 站方把评分块放进 h1.entry-title，直接取 text 会粘成「标题10.0×6次评分」。
 */
class SilisiliTitleTest {

    @Test
    fun `removes div score block`() {
        val html = """
            <h1 class="entry-title">葬送的芙莉莲 第二季<div class="score-wrap">10.0 ×6次评分</div></h1>
        """.trimIndent()
        assertEquals("葬送的芙莉莲 第二季", parseDetailTitle(Jsoup.parse(html)))
    }

    @Test
    fun `removes span score block`() {
        val html = """
            <h1 class="entry-title">间谍过家家<span class="score-wrap">9.8 ×3次评分</span></h1>
        """.trimIndent()
        assertEquals("间谍过家家", parseDetailTitle(Jsoup.parse(html)))
    }

    @Test
    fun `keeps full title without score block`() {
        val html = """<h1 class="entry-title">药屋少女的呢喃 第二季</h1>"""
        assertEquals("药屋少女的呢喃 第二季", parseDetailTitle(Jsoup.parse(html)))
    }

    @Test
    fun `returns empty when title is absent`() {
        assertEquals("", parseDetailTitle(Jsoup.parse("<div>no title</div>")))
    }
}
