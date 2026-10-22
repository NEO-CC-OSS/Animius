package com.lanlinju.animius.matching

import com.lanlinju.animius.domain.matching.TitleAliases
import com.lanlinju.animius.domain.matching.TitleMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** tv.8 切片 3a：站点用另一种中文译名（Bangumi 无此数据，人工核对后补充） */
    @Test
    fun `site translations from 2026-10 misses are linked`() {
        assertTrue(TitleAliases.forTitle("マゼンチュ").contains("洋红色"))
        assertTrue(TitleAliases.forTitle("しろたん").contains("海豹小白"))
        assertTrue(TitleAliases.forTitle("ゆるゆる図鑑").contains("悠悠哉哉图鉴"))
        assertTrue(TitleAliases.forTitle("ねずみくんのチョッキ").contains("鼠小弟的小背心"))
        // 站点标题带季标：别名也要带，否则 seasonCompatible(目标=2, 别名=null) 会拒绝
        assertTrue(TitleAliases.forTitle("ねずみくんのチョッキ").contains("鼠小弟的小背心 第2期"))
        assertTrue(TitleAliases.forTitle("ケロロ軍曹").contains("青蛙军曹"))
        // 元数据中文名带 ☆：归一化会去掉，仍要能查到站点译名
        assertTrue(TitleAliases.forTitle("Keroro军曹☆").contains("青蛙军曹"))
        assertTrue(TitleAliases.forTitle("らんま1/2 第3期").contains("新 乱马1/2 第3期"))
        assertTrue(TitleAliases.forTitle("Dreamland").contains("梦境乐园"))
        assertTrue(TitleAliases.forTitle("都市古医仙2").contains("大陆剧都市古医仙2026"))
    }

    /** 站点译名经别名进入匹配：季标必须仍然卡住，不能因为别名放宽 */
    @Test
    fun `pickBest accepts site translation but rejects wrong season`() {
        assertEquals(
            0,
            TitleMatcher.pickBest(
                target = "らんま1/2 第3期",
                candidates = listOf("新 乱马1/2 第3期"),
                aliases = TitleAliases.forTitle("らんま1/2 第3期"),
            ) ?: -1,
        )
        assertNull(
            TitleMatcher.pickBest(
                target = "らんま1/2 第3期",
                candidates = listOf("新 乱马1/2 第2期"),
                aliases = TitleAliases.forTitle("らんま1/2 第3期"),
            ),
        )
        assertEquals(
            0,
            TitleMatcher.pickBest(
                target = "マゼンチュ",
                candidates = listOf("洋红色"),
                aliases = TitleAliases.forTitle("マゼンチュ"),
            ) ?: -1,
        )
    }
}
