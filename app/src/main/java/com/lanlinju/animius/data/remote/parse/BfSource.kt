package com.lanlinju.animius.data.remote.parse

import com.lanlinju.animius.util.getDefaultDomain

/**
 * 暴风采集源（苹果CMS JSON）。
 *
 * 2026-10-22 实测：35 部样本合并命中 23/35、主干名补救 26/35（74%），明文直链 23/23、
 * 平均 428 ms、动漫库存 5931；与量子/魔都共用同一套上游资源库。
 */
object BfSource : CmsSource() {

    override val CANDIDATE_DOMAINS: List<String> = listOf(
        "https://bfzyapi.com/",
        "https://app.bfzyapi.com/",
    )

    override val modeTag: String = "Bf"

    override var baseUrl: String = getDefaultDomain()
}
