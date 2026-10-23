package com.lanlinju.animius.data.remote.parse

import com.lanlinju.animius.util.getDefaultDomain

/**
 * 暴风采集源（苹果CMS JSON）。
 *
 * 2026-10-22 实测：35 部样本合并命中 23/35、主干名补救 26/35（74%），明文直链 23/23、
 * 平均 428 ms、动漫库存 5931；与量子/魔都共用同一套上游资源库。
 *
 * 备用域 `app.bfzyapi.com` 实测已死（连接被重置，2026-10-23 直连复测），但**保留在列表末尾**：
 * - 顺延只对「快速失败」生效，它最多消耗一次几百毫秒的重置，代价可控；
 * - 站点换域后老域名偶尔会复活，删掉就再也用不上；
 * - 真死了也无所谓：内置候选全挂后会自动去拉 `sources.json`（任务 C②）拿最新域名。
 */
object BfSource : CmsSource() {

    override val CANDIDATE_DOMAINS: List<String> = listOf(
        "https://bfzyapi.com/",
        // 实测已死（连接被重置）：保留在末尾作为「万一复活」的兜底，不要提到前面
        "https://app.bfzyapi.com/",
    )

    override val modeTag: String = "Bf"

    /** 顺延成功会并发改写它（搜索结果/详情请求可能同时在跑），加 @Volatile 保证可见性 */
    @Volatile
    override var baseUrl: String = getDefaultDomain()
}
