package com.lanlinju.animius.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRiskMemoryTest {

    private val all = listOf(
        SourceMode.Lz,
        SourceMode.Bf,
        SourceMode.Md,
        SourceMode.Agedm,
        SourceMode.Silisili,
    )

    @Test
    fun `risked sources move to the end and keep their relative order`() {
        val ordered = SourceRiskMemory.order(all, setOf(SourceMode.Bf, SourceMode.Agedm))
        assertEquals(
            listOf(SourceMode.Lz, SourceMode.Md, SourceMode.Silisili, SourceMode.Bf, SourceMode.Agedm),
            ordered,
        )
    }

    @Test
    fun `current source stays first even when risked`() {
        assertEquals(all, SourceRiskMemory.order(all, setOf(SourceMode.Lz), current = SourceMode.Lz))
    }

    @Test
    fun `when current is excluded risked sources still go last`() {
        // 换源场景：当前源已被 exclude，候选头是兜底第一个，不能因为「头」就跳过风控排序
        val modes = listOf(SourceMode.Bf, SourceMode.Md, SourceMode.Agedm)
        val ordered = SourceRiskMemory.order(modes, setOf(SourceMode.Bf), current = SourceMode.Lz)
        assertEquals(listOf(SourceMode.Md, SourceMode.Agedm, SourceMode.Bf), ordered)
    }

    @Test
    fun `no risked source keeps original order`() {
        assertEquals(all, SourceRiskMemory.order(all, emptySet()))
    }

    @Test
    fun `single candidate is untouched`() {
        assertEquals(
            listOf(SourceMode.Md),
            SourceRiskMemory.order(listOf(SourceMode.Md), setOf(SourceMode.Md)),
        )
    }

    @Test
    fun `skip window is two hours`() {
        assertEquals(2 * 60 * 60 * 1000L, SourceRiskMemory.SKIP_WINDOW_MS)
    }

    @Test
    fun `window boundary excludes exactly two hours and clock rollback`() {
        val now = 1_000_000_000L
        // 从未记录
        assertFalse(SourceRiskMemory.isWithinWindow(0L, now))
        // 刚记录
        assertTrue(SourceRiskMemory.isWithinWindow(now, now))
        // 边界前 1 ms 仍在窗口内
        assertTrue(SourceRiskMemory.isWithinWindow(now - SourceRiskMemory.SKIP_WINDOW_MS + 1, now))
        // 恰好 2 小时算过期
        assertFalse(SourceRiskMemory.isWithinWindow(now - SourceRiskMemory.SKIP_WINDOW_MS, now))
        // 时钟回拨（记录时间在“未来”）视为不在窗口
        assertFalse(SourceRiskMemory.isWithinWindow(now + 1, now))
    }
}
