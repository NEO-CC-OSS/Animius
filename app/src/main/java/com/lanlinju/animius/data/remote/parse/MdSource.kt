package com.lanlinju.animius.data.remote.parse

import com.lanlinju.animius.util.getDefaultDomain

/**
 * 魔都采集源（苹果CMS JSON）。
 *
 * 2026-10-22 实测：35 部样本合并命中 8/35、主干名补救 18/35，明文直链 8/8、
 * 平均 359 ms、动漫库存 4526；与量子/暴风共用同一套上游资源库，作为第三个冗余源。
 */
object MdSource : CmsSource() {

    override val CANDIDATE_DOMAINS: List<String> = listOf(
        "https://www.mdzyapi.com/",
    )

    override val modeTag: String = "Md"

    /** 顺延成功会并发改写它（搜索结果/详情请求可能同时在跑），加 @Volatile 保证可见性 */
    @Volatile
    override var baseUrl: String = getDefaultDomain()
}
