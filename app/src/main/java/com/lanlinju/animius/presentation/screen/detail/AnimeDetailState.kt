package com.lanlinju.animius.presentation.screen.detail

import com.lanlinju.animius.data.remote.bangumi.MetadataDetailMerger
import com.lanlinju.animius.data.remote.bangumi.MetadataItem
import com.lanlinju.animius.domain.model.AnimeDetail
import com.lanlinju.animius.util.Resource

/**
 * 详情页「骨架 → 完整」的状态转移（纯函数，便于单测；ViewModel 直接调用）。
 *
 * 两条状态流的分工（tv.8 切片 2）：
 * - [com.lanlinju.animius.presentation.screen.detail.AnimeDetailViewModel.metadataDetailState]：
 *   只负责「页面内容」——元数据一到就是 `Success(骨架)`，选源失败也不回退成 `Error`；
 * - `sourceInfo`：只负责「选源进度/结果」，驱动剧集区占位与「播放源」信息行。
 *
 * 骨架与选源对所有设备统一（2026-10-22）：只要拿到元数据就先出 `Success(骨架)`，
 * 手机/平板不再走 tv.6 的整页 Loading 行为。
 */
object AnimeDetailState {

    /** 重试策略：已有骨架时只重跑选源，不把已显示的内容清空 */
    enum class RetryPlan {
        /** 只重跑选源（骨架已在屏上） */
        ResolveOnly,

        /** 整页重来（没有骨架：源站入口 / 手机 / 元数据都没取到） */
        Full,
    }

    /**
     * 进入页面时的初始状态：拿到了元数据 → 立刻 `Success(骨架)`，不等任何网络；
     * 没有元数据（源站入口 / 元数据缺失）保持原有 `Loading`。
     *
     * @param fallbackTitle 元数据标题为空时的兜底（路由里带的标题），避免骨架页出现空白标题
     */
    fun initial(
        meta: MetadataItem?,
        fallbackTitle: String = "",
    ): Resource<AnimeDetail?> =
        if (meta != null) {
            Resource.Success(MetadataDetailMerger.skeleton(meta, fallbackTitle))
        } else {
            Resource.Loading
        }

    /**
     * 选源失败：有骨架就保留骨架（页面继续可看 + 剧集区显示失败原因），
     * 没有骨架才把整页置 Error（沿用原有 Warning + 重试逻辑）。
     */
    fun onResolveFailure(current: Resource<AnimeDetail?>, reason: String): Resource<AnimeDetail?> =
        if (current.data != null) current else Resource.Error(IllegalStateException(reason))

    /** 重试：有骨架 → 只重跑选源；没有骨架 → 保持原有整页重载 */
    fun onRetry(current: Resource<AnimeDetail?>): RetryPlan =
        if (current.data != null) RetryPlan.ResolveOnly else RetryPlan.Full

    /**
     * 剧集区该渲染「匹配中 / 匹配失败」占位吗？
     *
     * 只认显式骨架标志（`AnimeDetailViewModel.isSkeleton`），**不再用 `episodes.isEmpty()` 推断**：
     * 源站入口 / 手机的「零剧集详情」（Age 的 PV-only 线路、通道全空）剧集也是空的，但那时
     * 不是骨架，必须继续走原有剧集区逻辑（线路/反转/更多 不能消失，卡 §6.1、§5 手机行为不变）。
     */
    fun isEpisodePlaceholder(skeleton: Boolean, hasDetail: Boolean): Boolean =
        skeleton && hasDetail

    /**
     * 骨架态能不能收藏 / 下载？
     *
     * 骨架态 [detailUrl] 还是空的：此时点收藏会写入 `detailUrl=""` 的 Favourite，从收藏页
     * （`FavouriteScreen.kt` 只带 detailUrl）点开就变成 `metadataId=0`、标题为空 → 永久 Error；
     * 下载同样只能打开一个空 sheet。所以两个入口在骨架态禁用，完整态行为不变。
     */
    fun canUseSourceActions(detailUrl: String): Boolean = detailUrl.isNotBlank()
}
