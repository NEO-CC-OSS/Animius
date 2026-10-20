package com.lanlinju.animius.domain.matching

/**
 * 人工确认、无歧义的常见中文别名（航海王/海贼王这类）。
 *
 * 只收录「两个名字指同一部番」且不会误伤其他作品的别名；不追求大而全。
 * 更多别名由每日任务从 Bangumi 条目的 infobox（别名）抓取，随 metadata 一起下发。
 * 匹配时别名会同时用于「搜索关键词」和「候选标题校验」，见 [TitleMatcher.pickBest]。
 */
object TitleAliases {

    private val aliases: Map<String, List<String>> = buildMap {
        fun link(vararg names: String) {
            names.forEach { name ->
                put(TitleMatcher.normalize(name), names.filterNot { it == name })
            }
        }

        link("航海王", "海贼王", "ONE PIECE")
        link("哆啦A梦", "机器猫", "小叮当")
        link("精灵宝可梦", "宠物小精灵", "神奇宝贝", "口袋妖怪")
        link("东京喰种", "东京食尸鬼")
        link("钢之炼金术师", "钢炼")
        link("新世纪福音战士", "EVA")
        link("魔卡少女樱", "百变小樱")
        link("足球小将", "队长小翼")
        link("灌篮高手", "篮球飞人")
    }

    /** 按归一化标题查别名；没有就返回空列表 */
    fun forTitle(title: String): List<String> =
        aliases[TitleMatcher.normalize(title)].orEmpty()
}
