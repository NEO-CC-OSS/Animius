package com.lanlinju.animius.util

import com.lanlinju.animius.application.AnimeApplication
import com.lanlinju.animius.data.remote.parse.AgedmSource
import com.lanlinju.animius.data.remote.parse.AnimeSource
import com.lanlinju.animius.data.remote.parse.BfSource
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
import com.lanlinju.animius.data.remote.parse.LzSource
import com.lanlinju.animius.data.remote.parse.MdSource
import com.lanlinju.animius.data.remote.parse.SilisiliSource
import com.lanlinju.animius.data.remote.parse.SkrSource
// 2026-10 整站改版为 next.xifanacg.com（Next.js 新站），旧解析全部失效，等上游跟进
// import com.lanlinju.animius.data.remote.parse.XifanSource
// import com.lanlinju.animius.data.remote.parse.YhdmSource

object SourceHolder {
    private lateinit var _currentSource: AnimeSource
    private lateinit var _currentSourceMode: SourceMode

    /**
     * 默认动漫源：2026-10-22 起改为量子采集源（旧默认 Skr 已失效且不参与选源）。
     */
    val DEFAULT_ANIME_SOURCE = SourceMode.Lz

    val currentSource: AnimeSource
        get() = _currentSource

    val currentSourceMode: SourceMode
        get() = _currentSourceMode


    init {
        val preferences = AnimeApplication.getInstance().preferences
        val saved = preferences.getEnum(KEY_SOURCE_MODE, DEFAULT_ANIME_SOURCE)
        // 老用户偏好里可能还是已停用的源（Skr/Girigiri/Gugufan/Jzacg）：回落到默认源，避免一进首页就空
        initDefaultSource(if (saved.selectable) saved else DEFAULT_ANIME_SOURCE)
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
        if (!mode.selectable) return
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
            SourceMode.Lz -> LzSource
            SourceMode.Bf -> BfSource
            SourceMode.Md -> MdSource
        }
    }
}

/** 源备注依据的体检日期；更新备注时同步修改此值 */
const val SOURCE_SUMMARY_UPDATED = "2026-10-22"

/**
 * @param selectable 是否出现在源切换列表里。false = 代码保留、不再参与选源（2026-10-22 tv.9 切片 1：
 *   Skr/Girigiri/Gugufan/Jzacg 已失效或库存过小，移出候选；Silisili 保留兜底）。
 */
enum class SourceMode(val summary: String, val selectable: Boolean = true) {
    // 2026-10-22 采集源接入：量子/暴风/魔都共用同一上游库，量子是超集，设为默认源
    Lz("新番全、明文直链，推荐首选"),
    Bf("资源多，与量子同库，备用"),
    Md("资源多，与量子同库，备用"),
    Silisili("资源多，弹幕偶尔偏少（兜底）"),
    Agedm("稳定，资源较全（日文原名可用）"),
    // 以下四个 2026-10-22 起不再参与选源：代码保留，等待域名恢复
    Skr("已失效，暂停使用", selectable = false),
    Girigiri("已失效，暂停使用", selectable = false),
    Gugufan("已失效，暂停使用", selectable = false),
    Jzacg("库存小，暂停使用", selectable = false),
    // Cycanime,
    // Mxdm,
    // Xifan,
    // Ntdm,
    // Nyafun,

    //    Anfuns,
    // Gogoanime,
    // Yhdm
}
