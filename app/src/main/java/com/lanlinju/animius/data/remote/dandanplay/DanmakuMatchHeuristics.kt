package com.lanlinju.animius.data.remote.dandanplay

import com.lanlinju.animius.data.remote.dandanplay.dto.DandanplayMatchResult

/**
 * 弹幕匹配的启发式规则（纯函数，便于单元测试）。
 *
 * 规则基于 2026-10-13 对 /api/v2/match 的实测校准（样本：转学后班上的清纯可爱美少女 / 葬送的芙莉莲 /
 * 进击的巨人 / 平家物语：犬王 / 脑洞学生会）：
 * - 候选番剧名归一化后须与目标番剧名「相等」，或为「目标名的子串且长度差 ≤ [MAX_CANDIDATE_SHORTER]」
 *   （允许源站标题带系列前缀，如「平家物语：犬王」→ 弹弹play「犬王」）；
 * - 候选名比目标名更长（多出篇章名/副标题）一律拒绝——实测「进击的巨人」会匹配到「反击的狼烟」
 *   等特定篇章，错挂风险高；
 * - 候选集数归一化后须与目标集数一致，且忽略前导零差异（源站「第01集」与弹弹play「第1话」是同一集）。
 *
 * 总原则：宁可没有弹幕，也不错挂其他剧集的弹幕。
 */
internal object DanmakuMatchHeuristics {

    private val moviePattern = Regex("全集|HD|正片|剧场版|映画")
    private val rangePattern = Regex("^(\\d+)\\s*[-~—]\\s*\\d+$")
    private val leadingNumberPattern = Regex("^(\\d+)")
    private val seasonPattern =
        Regex("第[一二三四五六七八九十0-9０-９]+[季期部]|Season\\s*\\d+|\\bS\\d+\\b", RegexOption.IGNORE_CASE)
    private val yearPattern = Regex("[（(\\[]\\d{4}[）)\\]]")

    /** 候选名允许比目标名短的最大字符数（源站标题常带系列前缀/副标题） */
    private const val MAX_CANDIDATE_SHORTER = 8

    /** 剧集名归一化为弹弹play 可识别的集数；无法识别返回 null */
    fun normalizeEpisodeName(name: String): String? {
        val trimmed = name.trim()
        return when {
            moviePattern.containsMatchIn(trimmed) -> "movie" // 剧场版
            rangePattern.matches(trimmed) -> rangePattern.find(trimmed)!!.groupValues[1] // 01-02 -> 取首集
            trimmed.contains("第") -> trimmed.filter { it.isDigit() }.ifEmpty { null } // 第01集/第01话 -> 01
            trimmed.matches(Regex("\\d+")) -> trimmed // 部分源剧集只有数字
            else -> leadingNumberPattern.find(trimmed)?.groupValues[1] // 01v2/01.5 -> 01
        }
    }

    /** 集数比较用的规范形式：去掉前导零（源站「01」与弹弹play「1」应视为同一集） */
    fun canonicalEpisode(episode: String): String =
        if (episode == "movie") episode else episode.trimStart('0').ifEmpty { "0" }

    /** 去掉标题里的季数标注与年份括号，作为搜索降级的主干名 */
    fun cleanSubjectName(name: String): String =
        name.replace(yearPattern, "").replace(seasonPattern, "").trim(' ', '-', '·', '~', '～')

    /** 构造文件识别用的虚拟文件名（格式经实测验证可用） */
    fun buildMatchFileName(subjectName: String, targetEpisode: String): String =
        if (targetEpisode == "movie") "$subjectName 剧场版" else "$subjectName 第${targetEpisode}集"

    /**
     * 从文件识别候选里挑最可信的一项；无满足条件者返回 null（宁可无弹幕，不错挂）。
     * 候选顺序由官方按匹配度排序，取第一个通过校验的即可（实测基季排在分季之前）。
     */
    fun pickBestMatch(
        subjectName: String,
        targetEpisode: String,
        candidates: List<DandanplayMatchResult>,
    ): DandanplayMatchResult? {
        val subject = normalizeTitle(subjectName)
        if (subject.isEmpty()) return null
        val target = canonicalEpisode(targetEpisode)
        return candidates.firstOrNull { candidate ->
            val episode = normalizeEpisodeName(candidate.episodeTitle)
            episode != null &&
                canonicalEpisode(episode) == target &&
                isTitleTrustworthy(subject, normalizeTitle(candidate.animeTitle))
        }
    }

    /** 标题归一化：去季标/年份，仅保留字母数字（抹平标点、空格差异）并转小写 */
    fun normalizeTitle(name: String): String =
        name.replace(yearPattern, "").replace(seasonPattern, "")
            .filter { it.isLetterOrDigit() }
            .lowercase()

    /**
     * 候选番剧名是否可信：与目标名相等，或为目标名的子串且长度差在阈值内。
     * 候选名更长（多出篇章名/副标题）一律不可信。
     */
    private fun isTitleTrustworthy(subject: String, candidate: String): Boolean {
        if (candidate.isEmpty()) return false
        if (candidate == subject) return true
        return subject.contains(candidate) && subject.length - candidate.length <= MAX_CANDIDATE_SHORTER
    }
}
