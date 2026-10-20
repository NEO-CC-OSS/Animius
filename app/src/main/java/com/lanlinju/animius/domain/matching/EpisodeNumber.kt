package com.lanlinju.animius.domain.matching

/**
 * 剧集名 → 集号。跨源换源时只按集号对应，绝不按列表下标硬切。
 *
 * 与弹幕匹配里的同名逻辑保持同一口径（`第01集` / `第1话` / `01` / `1` 视为同一集；
 * 剧场版/全集统一为 `movie`），但本文件独立维护，避免改动弹幕模块。
 */
object EpisodeNumber {

    private val moviePattern = Regex("全集|HD|正片|剧场版|映画", RegexOption.IGNORE_CASE)
    private val rangePattern = Regex("^(\\d+)\\s*[-~—]\\s*\\d+$")
    private val leadingNumberPattern = Regex("^(\\d+)")
    private val chineseNumberPattern = Regex("第\\s*([一二三四五六七八九十]+)\\s*[集话話回]")

    /** 无法识别返回 null（调用方应放弃自动换源，而不是猜一个集号）；数字已去掉前导零 */
    fun normalize(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val raw = when {
            moviePattern.containsMatchIn(trimmed) -> "movie"
            rangePattern.matches(trimmed) -> rangePattern.find(trimmed)!!.groupValues[1]
            trimmed.contains("第") -> {
                val digits = trimmed.filter { it.isDigit() }
                if (digits.isNotEmpty()) {
                    digits
                } else {
                    chineseNumberPattern.find(trimmed)?.groupValues?.get(1)?.let(::chineseToArabic)
                }
            }
            trimmed.matches(Regex("\\d+")) -> trimmed
            else -> leadingNumberPattern.find(trimmed)?.groupValues?.get(1)
        } ?: return null
        return canonical(raw)
    }

    /** 集号比较用的规范形式：去掉前导零（`01` 与 `1` 是同一集） */
    fun canonical(episode: String): String =
        if (episode == "movie") episode else episode.trimStart('0').ifEmpty { "0" }

    private fun chineseToArabic(raw: String): String? {
        val digits = mapOf(
            '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
            '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        )
        val value = when {
            raw == "十" -> 10
            raw.startsWith("十") -> 10 + (digits[raw.getOrNull(1)] ?: return null)
            raw.endsWith("十") -> (digits[raw.first()] ?: return null) * 10
            raw.length == 1 -> digits[raw[0]]
            raw.length == 2 && raw[1] == '十' -> (digits[raw[0]] ?: return null) * 10
            else -> null
        }
        return value?.toString()
    }
}
