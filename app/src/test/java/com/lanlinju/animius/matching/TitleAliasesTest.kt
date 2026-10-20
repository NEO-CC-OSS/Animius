package com.lanlinju.animius.matching

import com.lanlinju.animius.domain.matching.TitleAliases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleAliasesTest {

    @Test
    fun `one piece chinese aliases are linked both ways`() {
        assertTrue(TitleAliases.forTitle("航海王").contains("海贼王"))
        assertTrue(TitleAliases.forTitle("海贼王").contains("航海王"))
    }

    @Test
    fun `normalized lookup ignores spaces and punctuation`() {
        assertTrue(TitleAliases.forTitle(" 航海王 ").contains("海贼王"))
        assertTrue(TitleAliases.forTitle("航海王！").contains("海贼王"))
    }

    @Test
    fun `common aliases are available`() {
        assertTrue(TitleAliases.forTitle("哆啦A梦").contains("机器猫"))
        assertTrue(TitleAliases.forTitle("精灵宝可梦").contains("神奇宝贝"))
        assertTrue(TitleAliases.forTitle("东京喰种").contains("东京食尸鬼"))
    }

    @Test
    fun `unknown title has no aliases`() {
        assertEquals(emptyList<String>(), TitleAliases.forTitle("一部不存在的番"))
    }

    @Test
    fun `alias list never contains the title itself`() {
        assertTrue(TitleAliases.forTitle("航海王").none { it == "航海王" })
    }
}
