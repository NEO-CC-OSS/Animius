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

        // 2026-10-22 实测补充：站点用了另一种中文译名，Bangumi 里没有（见 docs/tasks/tv.8/03a）
        link("マゼンチュ", "洋红色")
        link("しろたん", "海豹小白")
        link("ゆるゆる図鑑", "悠悠哉哉图鉴")
        link("ねずみくんのチョッキ", "鼠小弟的小背心", "鼠小弟的小背心 第2期")
        link("ケロロ軍曹", "Keroro军曹", "青蛙军曹")
        // 季标必须写进别名：否则 seasonCompatible(目标=3, 别名=null) 会拒绝
        link("らんま1/2 第3期", "乱马1/2 第三季", "新 乱马1/2 第3期")
        link("Dreamland", "梦境乐园")
        link("都市古医仙2", "大陆剧都市古医仙2026")
        // 2026-10-22 tv.9 切片 2 实测补充：采集站标题与元数据差一个字/连字符
        link("转生哥布林有问题吗？", "转生成哥布林有问题吗？")
        link("PSYREN -决战游戏-", "PSYREN 决战游戏")
    }

    /** 按归一化标题查别名；没有就返回空列表 */
    fun forTitle(title: String): List<String> =
        aliases[TitleMatcher.normalize(title)].orEmpty()
}
