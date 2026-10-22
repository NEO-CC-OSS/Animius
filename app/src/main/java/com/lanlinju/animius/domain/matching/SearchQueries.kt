package com.lanlinju.animius.domain.matching

/**
 * 搜索关键词构造（tv.8 切片 3；tv.9 切片 2 加「主干名」）。
 *
 * 中文源站搜中文命中率最高，日文原名几乎搜不到（本地 109 部周表番里 77 部 `name` 是日文），
 * 所以关键词按字符集分流排序：
 * ① 中文全名 → ② **中文主干名** → ③ 含中文的别名 → ④ 日文原名（含假名）→ ⑤ 拉丁/英文别名 → ⑥ 其余。
 *
 * 主干名（2026-10-22 实验结论）：采集站搜索是包含式，带副标题/特别篇/季标的完整标题反而搜不到；
 * 截断成主干名重查，每个源能多命中 7–10 部。主干名排在中文别名之前（实验里收益最大），
 * 但短于 3 个字符不生成（避免过短误配）。
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

    /** 副标题分隔符：出现即截断（全角/半角都收） */
    private val SUBTITLE_SEPARATORS = listOf("：", ":", "～", "~", "—", "|", "【", "（", "(")

    /** 尾部可剥除的季标/特别篇/剧场版标记；反复剥直到不再变化 */
    private val TAIL_PATTERNS = listOf(
        Regex("特别篇.*$"),
        Regex("第\\s*[0-9一二三四五六七八九十０-９]+\\s*[季期部].*$"),
        Regex("(?i)season\\s*\\d+.*$"),
        Regex("剧场版.*$"),
        Regex("(?i)(?<![a-z])ova.*$"),
    )

    /** 年份括号：`（2026）` / `(2026)` / `[2026]` */
    private val YEAR_PATTERN = Regex("[（(\\[].*?[）)\\]]")

    fun build(
        title: String,
        originalTitle: String,
        aliases: List<String>,
        max: Int = DEFAULT_MAX,
    ): List<String> {
        if (max <= 0) return emptyList()

        val entries = ArrayList<Pair<String, Int>>(4 + aliases.size)
        add(entries, title, BUCKET_CHINESE_TITLE)
        mainStem(title)?.let { add(entries, it, BUCKET_CHINESE_STEM) }
        add(entries, originalTitle, BUCKET_JAPANESE_TITLE)
        mainStem(originalTitle)?.let { add(entries, it, BUCKET_JAPANESE_TITLE) }
        aliases.forEach { alias ->
            val bucket = when {
                isChineseText(alias) -> BUCKET_CHINESE_ALIAS
                isJapaneseText(alias) -> BUCKET_JAPANESE_TITLE
                isLatinText(alias) -> BUCKET_LATIN_ALIAS
                else -> BUCKET_OTHER
            }
            add(entries, alias, bucket)
        }

        return entries
            .sortedBy { it.second }
            .distinctBy { dedupeKey(it.first) }
            .map { it.first }
            .take(max)
    }

    /**
     * 主干名：截掉副标题/特别篇/季标/年份后的标题主干；不足 3 个字符返回 null（不生成查询）。
     *
     * 例：`某番：副标题` → `某番`；`某番 第二季` → `某番`；`某番（2026）` → `某番`。
     */
    fun mainStem(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty()) return null

        // 1) 副标题分隔符：取最靠前的那个截断
        val cut = SUBTITLE_SEPARATORS
            .map { value.indexOf(it) }
            .filter { it > 0 }
            .minOrNull()
        if (cut != null) value = value.substring(0, cut)

        // 2) 尾部季标/特别篇/剧场版：反复剥（可能同时带「特别篇 第2期」）
        var changed = true
        while (changed) {
            changed = false
            for (pattern in TAIL_PATTERNS) {
                val match = pattern.find(value)
                if (match != null && match.range.first > 0) {
                    value = value.substring(0, match.range.first)
                    changed = true
                }
            }
        }

        // 3) 年份括号与尾部标点
        value = YEAR_PATTERN.replace(value, "")
        value = value.trim().trimEnd(' ', '·', '-', '—', '~', '～')

        return value.takeIf { it.length >= 3 }
    }

    private fun add(entries: MutableList<Pair<String, Int>>, raw: String, bucket: Int) {
        val value = raw.trim()
        if (value.isEmpty()) return
        entries += value to bucket
    }

    /** 去重键：去掉标点空格并小写；纯标点串退化为自身小写，避免把不同串并成空键 */
    private fun dedupeKey(value: String): String {
        val key = value.filter { it.isLetterOrDigit() }.lowercase()
        return key.ifEmpty { value.lowercase() }
    }

    private const val BUCKET_CHINESE_TITLE = 1
    private const val BUCKET_CHINESE_STEM = 2
    private const val BUCKET_CHINESE_ALIAS = 3
    private const val BUCKET_JAPANESE_TITLE = 4
    private const val BUCKET_LATIN_ALIAS = 5
    private const val BUCKET_OTHER = 6
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
