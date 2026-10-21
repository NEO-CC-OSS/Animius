package com.lanlinju.animius.week

import com.lanlinju.animius.presentation.screen.week.WeekDataSourcePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S5 守卫：时间表数据源只由 isAndroidTV 决定。
 *
 * - TV 走轻包 index（不可用/全空 → 空态 + 重试，不回退源站）；
 * - 手机/平板必须保持 tv.6 行为：只读源站周表，不请求元数据（也不新增封面请求）。
 */
class WeekDataSourcePolicyTest {

    @Test
    fun `tv reads metadata index only`() {
        assertEquals(
            WeekDataSourcePolicy.Source.MetadataIndex,
            WeekDataSourcePolicy.sourceFor(isTv = true),
        )
    }

    @Test
    fun `phone and tablet keep tv6 source week table`() {
        assertEquals(
            WeekDataSourcePolicy.Source.SourceSite,
            WeekDataSourcePolicy.sourceFor(isTv = false),
        )
    }
}
