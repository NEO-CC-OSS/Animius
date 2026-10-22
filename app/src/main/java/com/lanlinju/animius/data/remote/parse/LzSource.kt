package com.lanlinju.animius.data.remote.parse

import com.lanlinju.animius.util.getDefaultDomain

/**
 * 量子采集源（苹果CMS JSON）。
 *
 * 2026-10-22 实测：35 部样本合并命中 24/35、主干名补救 26/35（74%），明文直链 24/24、
 * 平均 344 ms、动漫库存 2061；与暴风/魔都共用同一套上游资源库（量子是超集）。
 */
object LzSource : CmsSource() {

    override val CANDIDATE_DOMAINS: List<String> = listOf(
        "https://cj.lziapi.com/",
    )

    override val modeTag: String = "Lz"

    override var baseUrl: String = getDefaultDomain()
}
