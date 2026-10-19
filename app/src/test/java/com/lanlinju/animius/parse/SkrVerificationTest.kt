package com.lanlinju.animius.parse

import com.lanlinju.animius.data.remote.parse.skrVerificationSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 樱之空人机验证签名回归。
 * 站方 JS 的 stringtoHex 实际是 charCode 十进制拼接，不是十六进制；
 * 误按十六进制实现会一直拿不到 cookie。
 */
class SkrVerificationTest {

    @Test
    fun `uses decimal char codes, not hex`() {
        // "abc" -> "979899"
        assertEquals(
            "f3d2426e52be7c11edd6099fa01714c6",
            skrVerificationSignature("abc"),
        )
        // 十六进制拼接 "616263" 的结果，必须不同
        assertNotEquals(
            "0e04049912772820000827e2da893ae2",
            skrVerificationSignature("abc"),
        )
    }

    @Test
    fun `empty value hashes empty string`() {
        assertEquals(
            "d41d8cd98f00b204e9800998ecf8427e",
            skrVerificationSignature(""),
        )
    }
}
