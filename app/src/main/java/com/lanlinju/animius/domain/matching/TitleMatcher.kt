package com.lanlinju.animius.domain.matching

/**
 * 元数据标题 → 源站标题的保守匹配。
 *
 * 源站标题常带季数、年份、标点、副标题；直接 `contains` 很容易认错番，所以规则刻意收紧：
 * - 归一化：去掉年份括号与季数标记，只保留字母/数字并转小写；
 * - 季数单独比较：目标没有季数、候选是「第二季」→ 拒绝；目标第二季、候选没写季数 → 拒绝；
 * - 候选比目标长（多出篇章名/副标题）→ 拒绝；
 * - 候选是目标的子串且长度差 ≤ [MAX_CANDIDATE_SHORTER] → 接受（源站常带系列前缀）。
 *
 * 总原则：宁可找不到源，也不能进错番。与弹幕匹配的启发式相互独立（弹幕模块不动）。
 */
object TitleMatcher {

    /** 候选名允许比目标名短的最大字符数（源站标题常带系列前缀） */
    const val MAX_CANDIDATE_SHORTER = 8

    /** 打分权重（任务卡 §6.1）：只决定「几个都可信时先验谁」，绝不参与门槛 */
    /** 与目标精确相等：必须高于「中文别名精确 35 + 10 = 45」，否则别名会盖过精确标题 */
    const val SCORE_EXACT_TITLE = 50
    const val SCORE_EXACT_ALIAS = 35
    const val SCORE_TARGET_CONTAINS_CANDIDATE = 30
    const val SCORE_EPISODE_EXACT = 15
    const val SCORE_EPISODE_NEAR = 8
    const val SCORE_EPISODE_FAR = -10
    const val SCORE_CHINESE_ALIAS_EXACT = 10
    const val SCORE_RANK_FIRST = 5
    const val SCORE_RANK_TOP_THREE = 2

    private val yearPattern = Regex("[（(\\[]\\s*\\d{4}\\s*[）)\\]]")
    private val chineseSeasonPattern = Regex("第\\s*([一二三四五六七八九十0-9０-９]+)\\s*[季期部]")
    private val seasonWordPattern = Regex("(?i)season\\s*(\\d+)")
    private val shortSeasonPattern = Regex("(?i)(?<![a-z0-9])s\\s*(\\d+)(?![0-9])")
    private val ordinalSeasonPattern = Regex("(?i)(\\d+)\\s*(?:st|nd|rd|th)\\s+season")
    private val moviePattern = Regex(
        "剧场版|映画|电影版|最终章|完结篇|the\\s+movie",
        RegexOption.IGNORE_CASE,
    )

    /** 「最终季」不是数字季，单独给一个哨兵值：只与同类标记匹配，绝不与第一季混同 */
    private const val FINAL_SEASON = 99
    private val finalSeasonPattern = Regex("最终季|final\\s+season", RegexOption.IGNORE_CASE)

    /**
     * 标题末尾的短拉丁/数字后缀（S/T/R/2/re/ova…）。
     * 目标比候选只多这一小段时一律拒绝：这类后缀常常是季标或续作标记，
     * 接受就会从「超电磁炮T」进到第一季。宁可找不到源，也不能进错季。
     */
    private val seasonSuffixPattern = Regex("[a-z0-9]{1,3}$")

    /** 常见罗马数字季标：无职转生Ⅱ / 进击的巨人 Ⅲ；只认标题末尾 */
    private val unicodeRomanSuffix = Regex("[ⅡⅢⅣⅤⅥⅦⅧⅨⅩ]$")
    private val asciiRomanSuffix = Regex("(?i)(?<![a-z])(ii|iii|iv|v|vi)$")

    private val seasonPatterns = listOf(
        chineseSeasonPattern,
        seasonWordPattern,
        ordinalSeasonPattern,
        shortSeasonPattern,
    )

    private val chineseDigits = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    /**
     * 采集站库存里的杂项条目（2026-10-22 tv.9 切片 2）：候选标题含这些词、目标不含 → 不是正片。
     * 只用于拒绝，不参与打分；目标自身是剧场版/特典时不误杀。
     */
    private val JUNK_WORDS = listOf(
        "解说", "预告", "合集", "花絮", "片段", "剪辑",
        "剧场版", "电影版", "总集篇", "特典",
    )

    /** 比较用的主干名：去年份、去季标、去标点空格、转小写 */
    fun normalize(name: String): String =
        stripSeasons(name.replace(yearPattern, ""))
            .filter { it.isLetterOrDigit() }
            .lowercase()

    /** 季数；没写季标返回 null。第一季显式写「第一季」返回 1；「最终季」返回哨兵值 */
    fun seasonOf(name: String): Int? {
        if (finalSeasonPattern.containsMatchIn(name)) return FINAL_SEASON

        unicodeRomanSuffix.find(name.trimEnd())?.let { match ->
            return when (match.value) {
                "Ⅱ" -> 2
                "Ⅲ" -> 3
                "Ⅳ" -> 4
                "Ⅴ" -> 5
                "Ⅵ" -> 6
                "Ⅶ" -> 7
                "Ⅷ" -> 8
                "Ⅸ" -> 9
                "Ⅹ" -> 10
                else -> null
            }
        }
        asciiRomanSuffix.find(name.trimEnd())?.let { match ->
            return when (match.groupValues[1].lowercase()) {
                "ii" -> 2
                "iii" -> 3
                "iv" -> 4
                "v" -> 5
                "vi" -> 6
                else -> null
            }
        }

        for (pattern in seasonPatterns) {
            val match = pattern.find(name) ?: continue
            return parseNumber(match.groupValues[1]) ?: continue
        }
        return null
    }

    fun isMovie(name: String): Boolean = moviePattern.containsMatchIn(name)

    /**
     * 候选是不是采集站里的杂项（解说/预告/合集/剧场版…）：
     * 候选归一化后含排除词、而目标不含 → true（拒绝）。
     *
     * 目标自身含该词（例如目标就是「XX 剧场版」）时不拒绝，避免误杀正片。
     */
    fun isJunkCandidate(target: String, candidate: String): Boolean {
        val targetBase = normalize(target)
        val candidateBase = normalize(candidate)
        if (candidateBase.isEmpty()) return false
        return JUNK_WORDS.any { word -> candidateBase.contains(word) && !targetBase.contains(word) }
    }

    /**
     * 候选标题是否可信：归一化主干相等（或候选是目标子串且长度差在阈值内），且季数/剧场版标记兼容。
     */
    fun isTrustworthy(target: String, candidate: String): Boolean {
        val targetBase = normalize(target)
        val candidateBase = normalize(candidate)
        if (targetBase.isEmpty() || candidateBase.isEmpty()) return false
        if (isMovie(target) != isMovie(candidate)) return false
        if (!seasonCompatible(seasonOf(target), seasonOf(candidate))) return false

        if (targetBase == candidateBase) return true
        // 候选更长（多出篇章名/副标题）一律拒绝：容易进错季/错篇章
        if (!targetBase.contains(candidateBase)) return false
        // 目标只在末尾多出 1~3 个拉丁字母/数字（S/T/R/2/re…）：按季标/续作处理，拒绝
        if (targetBase.startsWith(candidateBase) &&
            seasonSuffixPattern.matches(targetBase.removePrefix(candidateBase))
        ) {
            return false
        }
        return targetBase.length - candidateBase.length <= MAX_CANDIDATE_SHORTER
    }

    /**
     * 给「已通过门槛」的候选打分；返回 null 表示候选不可信（门槛与 [isTrustworthy] 完全一致）。
     *
     * 门槛零改动：负分只让候选排到后面，不会变成「被接受」的候选；不可信候选永远返回 null，
     * 所以 [rankCandidates] 里不会出现被门槛拒绝的条目。
     *
     * @param episodeCount 候选详情的集数，搜索阶段拿不到就传 null（不加不减）
     * @param expectedEpisodes 元数据的 `total_episodes`，≤0 时不做集数比较
     * @param rank 候选在搜索结果里的名次（1 起）；[rankCandidates] 按位置自动传入
     */
    fun score(
        target: String,
        candidate: String,
        aliases: List<String> = emptyList(),
        episodeCount: Int? = null,
        expectedEpisodes: Int = 0,
        rank: Int = 0,
    ): Int? {
        val aliasBases = aliases.map(::normalize).filter { it.isNotEmpty() }
        if (!isCandidateTrusted(target, candidate, aliases)) return null

        val targetBase = normalize(target)
        val candidateBase = normalize(candidate)
        var total = 0

        when {
            candidateBase == targetBase -> total += SCORE_EXACT_TITLE
            targetBase.contains(candidateBase) ->
                total += SCORE_TARGET_CONTAINS_CANDIDATE - (targetBase.length - candidateBase.length)
        }
        if (aliasBases.any { it == candidateBase }) total += SCORE_EXACT_ALIAS
        if (aliases.any { isChineseText(it) && normalize(it) == candidateBase }) {
            total += SCORE_CHINESE_ALIAS_EXACT
        }

        if (episodeCount != null && episodeCount > 0 && expectedEpisodes > 0) {
            val diff = kotlin.math.abs(episodeCount - expectedEpisodes)
            total += when {
                diff == 0 -> SCORE_EPISODE_EXACT
                diff <= 2 -> SCORE_EPISODE_NEAR
                else -> SCORE_EPISODE_FAR
            }
        }

        // 排名加分只给「非精确命中」的候选：精确标题 50 / 精确别名 45 已是同类上限，
        // 再加排名分会让「中文别名排第 1」压过「精确标题排第 4」（2026-10-22 独立审查 must-fix）
        val exactTitle = candidateBase == targetBase
        val exactAlias = aliasBases.any { it == candidateBase }
        if (!exactTitle && !exactAlias) {
            total += when (rank) {
                1 -> SCORE_RANK_FIRST
                2, 3 -> SCORE_RANK_TOP_THREE
                else -> 0
            }
        }
        return total
    }

    /**
     * 可信候选按分数降序排列，同分保持原始搜索顺序；不可信候选不出现在结果里。
     * 返回值带原始下标，调用方据此回到候选列表（按顺序做详情验证）。
     */
    fun rankCandidates(
        target: String,
        candidates: List<String>,
        aliases: List<String> = emptyList(),
    ): List<RankedCandidate> {
        if (normalize(target).isEmpty()) return emptyList()
        return candidates.indices
            .mapNotNull { index ->
                val title = candidates[index]
                val score = score(target, title, aliases, rank = index + 1) ?: return@mapNotNull null
                RankedCandidate(index = index, title = title, score = score)
            }
            .sortedWith(compareByDescending<RankedCandidate> { it.score }.thenBy { it.index })
    }

    /**
     * 从候选标题里挑最可信的一条；没有可信候选返回 null。
     * [aliases] 是同一部番的其他名字（内置别名表 + Bangumi 别名）：
     * 候选只要与目标或任一别名可信匹配就算命中。
     * 优先级：与目标完全相等 > 与别名完全相等 > 长度差更小 > 原始顺序靠前。
     *
     * 兼容包装：行为与 tv.7 完全一致（[rankCandidates] 的打分只用于「几个都可信时先验谁」，
     * 不改变本函数的挑选结果）。
     */
    fun pickBest(
        target: String,
        candidates: List<String>,
        aliases: List<String> = emptyList(),
    ): Int? {
        val targetBase = normalize(target)
        if (targetBase.isEmpty()) return null
        val aliasBases = aliases.map(::normalize).filter { it.isNotEmpty() }.toSet()
        return candidates.indices
            .filter { index -> isCandidateTrusted(target, candidates[index], aliases) }
            .minWithOrNull(
                compareBy(
                    { index ->
                        when (normalize(candidates[index])) {
                            targetBase -> 0
                            in aliasBases -> 1
                            else -> 2
                        }
                    },
                    { index -> targetBase.length - normalize(candidates[index]).length },
                    { it },
                )
            )
    }

    /**
     * 门槛：与 [pickBest] 完全同一条规则（目标可信，或目标上下文内某别名可信）。
     *
     * [aliases] 必须传**原始别名**（不要先 normalize）：别名里的季标/剧场版标记要参与
     * [isTrustworthy] 的季数比较；先 normalize 会把季标剥掉，导致「某番 第3期」这类
     * 别名永远匹配不上带季标的候选（2026-10-22 实测暴露）。
     */
    private fun isCandidateTrusted(
        target: String,
        candidate: String,
        aliases: List<String>,
    ): Boolean {
        if (normalize(target).isEmpty()) return false
        return isTrustworthy(target, candidate) ||
            // 别名匹配也要服从目标的季数/剧场版上下文：
            // 否则「某番 第二季」会被别名「某番」带到第一季
            (contextCompatible(target, candidate) &&
                aliases.any { alias -> isTrustworthy(alias, candidate) })
    }

    /** 候选是否与目标处于同一季/同一剧场版上下文 */
    private fun contextCompatible(target: String, candidate: String): Boolean =
        isMovie(target) == isMovie(candidate) &&
            seasonCompatible(seasonOf(target), seasonOf(candidate))

    private fun seasonCompatible(targetSeason: Int?, candidateSeason: Int?): Boolean = when {
        targetSeason == null && candidateSeason == null -> true
        targetSeason == candidateSeason -> true
        // 目标没写季标 = 第一季；候选显式写第一季也算同一条目
        targetSeason == null -> candidateSeason == 1
        // 目标写了季标、候选没写：只有目标就是第一季才接受
        candidateSeason == null -> targetSeason == 1
        else -> false
    }

    private fun stripSeasons(name: String): String {
        var result = name
            .replace(finalSeasonPattern, "")
            .replace(unicodeRomanSuffix, "")
            .replace(asciiRomanSuffix, "")
        for (pattern in seasonPatterns) {
            result = result.replace(pattern, "")
        }
        return result
    }

    private fun parseNumber(raw: String): Int? {
        val normalized = raw.map { char ->
            if (char in '０'..'９') '0' + (char - '０') else char
        }.joinToString("")

        normalized.toIntOrNull()?.let { return it }

        return when {
            normalized == "十" -> 10
            normalized.startsWith("十") -> 10 + (chineseDigits[normalized.getOrNull(1)] ?: return null)
            normalized.endsWith("十") -> (chineseDigits[normalized.first()] ?: return null) * 10
            normalized.length == 1 -> chineseDigits[normalized[0]]
            normalized.length == 2 && normalized[1] == '十' ->
                (chineseDigits[normalized[0]] ?: return null) * 10
            else -> null
        }
    }
}

/**
 * 打过分的候选：`index` 回到原始候选列表，`score` 只用于排序（门槛另由 [TitleMatcher] 判定）。
 */
data class RankedCandidate(
    val index: Int,
    val title: String,
    val score: Int,
)
