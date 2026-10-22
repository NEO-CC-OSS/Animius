package com.lanlinju.animius.detail

import com.lanlinju.animius.data.remote.bangumi.MetadataItem
import com.lanlinju.animius.domain.model.AnimeDetail
import com.lanlinju.animius.presentation.screen.detail.AnimeDetailState
import com.lanlinju.animius.util.Resource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情页状态转移（tv.8 切片 2）。
 *
 * ViewModel 依赖 Hilt/Room 难以纯测，所以把「骨架先于源结果 / 源失败不清空骨架 / retry 只重跑选源」
 * 这三条转移抽成 [AnimeDetailState] 纯函数（`presentation/screen/detail/AnimeDetailState.kt`）来测。
 */
class AnimeDetailStateTest {

    private val meta = MetadataItem(
        id = 42,
        title = "测试番",
        originalTitle = "テスト",
        image = "https://img/1.webp",
        summary = "简介",
        tags = listOf("奇幻"),
        totalEpisodes = 12,
    )

    @Test
    fun `tv with metadata shows the skeleton before any source result`() {
        val state = AnimeDetailState.initial(meta, isTv = true)

        assertTrue(state is Resource.Success)
        val detail = state.data
        assertNotNull(detail)
        assertEquals("测试番", detail?.title)
        assertTrue(detail?.episodes.orEmpty().isEmpty())
        assertEquals(0, detail?.lastPosition)
    }

    @Test
    fun `mobile never gets a skeleton`() {
        val state = AnimeDetailState.initial(meta, isTv = false)

        assertTrue(state is Resource.Loading)
        assertNull(state.data)
    }

    @Test
    fun `tv without metadata keeps the original loading state`() {
        val state = AnimeDetailState.initial(meta = null, isTv = true)

        assertTrue(state is Resource.Loading)
    }

    @Test
    fun `resolve failure keeps the skeleton on screen`() {
        val skeleton = AnimeDetailState.initial(meta, isTv = true)

        val afterFailure = AnimeDetailState.onResolveFailure(skeleton, "Skr: 搜索无匹配")

        assertTrue(afterFailure is Resource.Success)
        assertSame(skeleton.data, afterFailure.data)
    }

    @Test
    fun `resolve failure without a skeleton becomes an error`() {
        val afterFailure = AnimeDetailState.onResolveFailure(Resource.Loading, "Skr: 搜索无匹配")

        assertTrue(afterFailure is Resource.Error)
        assertNull(afterFailure.data)
        assertEquals("Skr: 搜索无匹配", afterFailure.error?.message)
    }

    @Test
    fun `retry with a skeleton only reruns source resolution`() {
        val skeleton = AnimeDetailState.initial(meta, isTv = true)

        assertEquals(AnimeDetailState.RetryPlan.ResolveOnly, AnimeDetailState.onRetry(skeleton))
    }

    @Test
    fun `retry without a skeleton reloads the whole page`() {
        assertEquals(AnimeDetailState.RetryPlan.Full, AnimeDetailState.onRetry(Resource.Loading))
    }

    /** 完整详情（有剧集）同样满足「有骨架」判定：重试只重跑选源 */
    @Test
    fun `retry with a full detail also only reruns source resolution`() {
        val full = Resource.Success(
            AnimeDetail(
                title = "测试番",
                img = "",
                desc = "",
                tags = emptyList(),
                lastPosition = 3,
                episodes = emptyList(),
                relatedAnimes = emptyList(),
            )
        )

        assertEquals(AnimeDetailState.RetryPlan.ResolveOnly, AnimeDetailState.onRetry(full))
    }

    /** M1：骨架态 detailUrl 为空，收藏会写入坏收藏、下载只会打开空 sheet → 两个入口必须禁用 */
    @Test
    fun `blank detail url disables favourite and download`() {
        assertEquals(false, AnimeDetailState.canUseSourceActions(""))
        assertEquals(false, AnimeDetailState.canUseSourceActions("   "))
    }

    @Test
    fun `resolved detail url keeps favourite and download usable`() {
        assertEquals(true, AnimeDetailState.canUseSourceActions("/anime/1"))
    }

    /** M2：只有显式骨架标志才渲染占位；零剧集详情（源站入口 / 手机）必须走原剧集区逻辑 */
    @Test
    fun `episode placeholder follows the explicit skeleton flag, not the episode count`() {
        assertEquals(
            true,
            AnimeDetailState.isEpisodePlaceholder(skeleton = true, hasDetail = true),
        )
        // 零剧集详情：剧集为空但不是骨架 → 不占位（线路/反转/更多 不能消失）
        assertEquals(
            false,
            AnimeDetailState.isEpisodePlaceholder(skeleton = false, hasDetail = true),
        )
        // 没有内容可渲染时也不占位（走整页 Loading/Error）
        assertEquals(
            false,
            AnimeDetailState.isEpisodePlaceholder(skeleton = true, hasDetail = false),
        )
    }

    @Test
    fun `skeleton title falls back to the route title when metadata title is blank`() {
        val state = AnimeDetailState.initial(
            meta = meta.copy(title = ""),
            isTv = true,
            fallbackTitle = "路由标题",
        )

        assertEquals("路由标题", state.data?.title)
    }
}
