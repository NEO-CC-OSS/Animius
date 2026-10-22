package com.lanlinju.animius.matching

import com.lanlinju.animius.domain.matching.TitleMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采集站杂项过滤（tv.9 切片 2）：解说/预告/合集/剧场版等候选不能当正片。
 */
class TitleMatcherJunkTest {

    @Test
    fun `candidate with junk word is rejected`() {
        assertTrue(TitleMatcher.isJunkCandidate("测试番", "测试番 解说"))
        assertTrue(TitleMatcher.isJunkCandidate("测试番", "测试番 预告"))
        assertTrue(TitleMatcher.isJunkCandidate("测试番", "测试番 合集"))
        assertTrue(TitleMatcher.isJunkCandidate("测试番", "测试番 剧场版"))
        assertTrue(TitleMatcher.isJunkCandidate("测试番", "测试番 总集篇"))
    }

    @Test
    fun `target itself containing the word is not rejected`() {
        // 目标就是剧场版：候选也带剧场版不能误杀
        assertFalse(TitleMatcher.isJunkCandidate("测试番 剧场版", "测试番 剧场版"))
        assertFalse(TitleMatcher.isJunkCandidate("测试番 特别篇", "测试番 特别篇"))
    }

    @Test
    fun `plain candidate is not junk`() {
        assertFalse(TitleMatcher.isJunkCandidate("测试番", "测试番"))
        assertFalse(TitleMatcher.isJunkCandidate("测试番", "测试番 第二季"))
    }
}
