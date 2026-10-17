package com.lanlinju.animius.util

import com.lanlinju.animius.application.AnimeApplication
import com.lanlinju.animius.data.remote.parse.AgedmSource
import com.lanlinju.animius.data.remote.parse.AnimeSource
// 2026-09 域名全部易主/停泊（发布页 nyadm.link 也已易主），等待上游跟进新域名后再恢复
// import com.lanlinju.animius.data.remote.parse.CycanimeSource
import com.lanlinju.animius.data.remote.parse.GirigiriSource
// import com.lanlinju.animius.data.remote.parse.GogoanimeSource
import com.lanlinju.animius.data.remote.parse.GugufanSource
// 2026-10 域名已被 ParkLogic 停泊（返回变现跳转页），死源不会恢复
// import com.lanlinju.animius.data.remote.parse.MxdmSource
// import com.lanlinju.animius.data.remote.parse.NtdmSource
// import com.lanlinju.animius.data.remote.parse.NyafunSource
// 2026-10 新增源（2026-10-11 源站评测报告，取证+全链路实测）
import com.lanlinju.animius.data.remote.parse.JzacgSource
import com.lanlinju.animius.data.remote.parse.SilisiliSource
import com.lanlinju.animius.data.remote.parse.SkrSource
// 2026-10 整站改版为 next.xifanacg.com（Next.js 新站），旧解析全部失效，等上游跟进
// import com.lanlinju.animius.data.remote.parse.XifanSource
// import com.lanlinju.animius.data.remote.parse.YhdmSource

object SourceHolder {
    private lateinit var _currentSource: AnimeSource
    private lateinit var _currentSourceMode: SourceMode

    /**
     * 默认动漫源
     */
    val DEFAULT_ANIME_SOURCE = SourceMode.Skr

    val currentSource: AnimeSource
        get() = _currentSource

    val currentSourceMode: SourceMode
        get() = _currentSourceMode

    var isSourceChanged = false

    init {
        val preferences = AnimeApplication.getInstance().preferences
        initDefaultSource(preferences.getEnum(KEY_SOURCE_MODE, DEFAULT_ANIME_SOURCE))
    }

    /**
     * 初始化加载默认的数据源，切换数据源请用方法[SourceHolder].switchSource()
     */
    private fun initDefaultSource(mode: SourceMode) {
        _currentSource = getSource(mode)
        _currentSourceMode = mode
        _currentSource.onEnter()
    }

    /**
     *切换数据源
     */
    fun switchSource(mode: SourceMode) {
        _currentSource.onExit()

        _currentSource = getSource(mode)
        _currentSourceMode = mode

        _currentSource.onEnter()
    }

    /**
     * 根据[SourceMode]获取对应的[AnimeSource]数据源
     * */
    fun getSource(mode: SourceMode): AnimeSource {
        return when (mode) {
            // SourceMode.Yhdm -> YhdmSource
            SourceMode.Silisili -> SilisiliSource
            // 2026-10 域名停泊死源（见 import 处说明）
            // SourceMode.Mxdm -> MxdmSource
            SourceMode.Agedm -> AgedmSource
//            SourceMode.Anfuns -> AnfunsSource
            SourceMode.Girigiri -> GirigiriSource
            // SourceMode.Nyafun -> NyafunSource
            // SourceMode.Cycanime -> CycanimeSource
            // SourceMode.Gogoanime -> GogoanimeSource
            // 2026-10 整站改版失效（见 import 处说明）
            // SourceMode.Xifan -> XifanSource()
            // SourceMode.Ntdm -> NtdmSource()
            SourceMode.Gugufan -> GugufanSource()
            SourceMode.Skr -> SkrSource()
            SourceMode.Jzacg -> JzacgSource()
        }
    }
}

/** 源备注依据的体检日期；更新备注时同步修改此值 */
const val SOURCE_SUMMARY_UPDATED = "2026-10-17"

enum class SourceMode(val summary: String) {
    // 2026-10-11 樱之空实测全绿、更新最快，设为默认源并置顶列表（用户拍板）
    // 备注依据 2026-10-17 体检结论，随版本更新；不按实时分数动态排序（源状态波动大）
    Skr("更新快、画质稳，推荐首选"),
    Silisili("资源多，弹幕偶尔偏少"),
    Agedm("稳定，资源较全"),
    Girigiri("整体可用，晚间偶尔连不上"),
    // Cycanime,
    Gugufan("近期打不开，暂不推荐"),
    Jzacg("新番全，个别线路偶尔失败"),
    // Mxdm,
    // Xifan,
    // Ntdm,
    // Nyafun,

    //    Anfuns,
    // Gogoanime,
    // Yhdm
}