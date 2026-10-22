package com.lanlinju.animius.domain.matching

/**
 * 搜索关键词构造（tv.8 切片 3）。
 *
 * 中文源站搜中文命中率最高，日文原名几乎搜不到（本地 109 部周表番里 77 部 `name` 是日文），
 * 所以关键词按字符集分流排序：
 * ① 中文标题 → ② 含中文的别名 → ③ 日文原名（含假名） → ④ 拉丁/英文/罗马音别名 → ⑤ 其余。
 *
 * 原名排在拉丁别名之前：拉丁别名（罗马音/英文）实测 0 增益，而原名是条目的规范名；
 * 6 条上限下宁可先丢拉丁别名，也不要把原名丢掉。
 *
 * 语言判定只用字符集规则（CJK / 假名 / 拉丁），不引入依赖、不调外部服务。
 * 排序稳定（同桶保持传入顺序），去空白、去重后 `take(max)`。
 * 注意：去重不剥季标——「某番 第二季」与「某番」是两个不同的查询，都值得试。
 */
object SearchQueries {

    /** 默认关键词上限：主标题 + 原名 + 4 条别名；再多只会拖垮单源预算 */
    const val DEFAULT_MAX = 6

    fun build(
        title: String,
        originalTitle: String,
        aliases: List<String>,
        max: Int = DEFAULT_MAX,
    ): List<String> {
        if (max <= 0) return emptyList()

        val entries = ArrayList<Pair<String, Int>>(2 + aliases.size)
        add(entries, title, isTitle = true)
        add(entries, originalTitle, isTitle = true)
        aliases.forEach { add(entries, it, isTitle = false) }

        return entries
            .sortedBy { it.second }
            .distinctBy { dedupeKey(it.first) }
            .map { it.first }
            .take(max)
    }

    private fun add(entries: MutableList<Pair<String, Int>>, raw: String, isTitle: Boolean) {
        val value = raw.trim()
        if (value.isEmpty()) return
        entries += value to bucketOf(value, isTitle)
    }

    private fun bucketOf(value: String, isTitle: Boolean): Int = when {
        isChineseText(value) -> if (isTitle) BUCKET_CHINESE_TITLE else BUCKET_CHINESE_ALIAS
        isLatinText(value) -> BUCKET_LATIN_ALIAS
        isJapaneseText(value) -> BUCKET_JAPANESE_TITLE
        else -> BUCKET_OTHER
    }

    /** 去重键：去掉标点空格并小写；纯标点串退化为自身小写，避免把不同串并成空键 */
    private fun dedupeKey(value: String): String {
        val key = value.filter { it.isLetterOrDigit() }.lowercase()
        return key.ifEmpty { value.lowercase() }
    }

    private const val BUCKET_CHINESE_TITLE = 1
    private const val BUCKET_CHINESE_ALIAS = 2
    private const val BUCKET_JAPANESE_TITLE = 3
    private const val BUCKET_LATIN_ALIAS = 4
    private const val BUCKET_OTHER = 5
}

/** 含平假名/片假名（含半角片假名）：日文原名的判定依据 */
internal fun containsKana(text: String): Boolean = text.any { char ->
    char in '\u3040'..'\u309F' ||
        char in '\u30A0'..'\u30FF' ||
        char in '\u31F0'..'\u31FF' ||
        char in '\uFF66'..'\uFF9F'
}

/** 含中日共用的汉字（含扩展 A 与兼容区） */
internal fun containsCjk(text: String): Boolean = text.any { char ->
    char in '\u4E00'..'\u9FFF' ||
        char in '\u3400'..'\u4DBF' ||
        char in '\uF900'..'\uFAFF'
}

internal fun containsLatin(text: String): Boolean =
    text.any { char -> char in 'a'..'z' || char in 'A'..'Z' }

/** 中文名：有汉字且没有假名（日文原名如「ロメリア戦記」含汉字但也有假名，归日文） */
internal fun isChineseText(text: String): Boolean = containsCjk(text) && !containsKana(text)

internal fun isJapaneseText(text: String): Boolean = containsKana(text)

/** 拉丁名：只有字母/数字，没有汉字也没有假名（罗马音、英文名） */
internal fun isLatinText(text: String): Boolean =
    !containsCjk(text) && !containsKana(text) && containsLatin(text)
