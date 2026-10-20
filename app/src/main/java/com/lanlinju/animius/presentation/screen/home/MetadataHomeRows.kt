package com.lanlinju.animius.presentation.screen.home

import com.lanlinju.animius.data.remote.bangumi.BangumiCalendar
import com.lanlinju.animius.domain.model.Home

/**
 * 首页元数据三行（只在 TV 使用）。
 *
 * - 今日放送：设备当天星期对应的元数据周表；
 * - 本周新番：其余日期的番，排除今日已出现，最多 40；
 * - 高分推荐：Bangumi 评分排名，排除前两行已出现，最多 30。
 *
 * 空行不返回，元数据缺字段时首页不会出现只有标题的空行。
 */
object MetadataHomeRows {
    const val TITLE_TODAY = "今日放送"
    const val TITLE_WEEK = "本周新番"
    const val TITLE_FEATURED = "高分推荐"

    private const val WEEK_LIMIT = 40
    private const val FEATURED_LIMIT = 30

    fun build(calendar: BangumiCalendar, todayWeekday: Int): List<Home> {
        val dayIndex = (todayWeekday - 1).coerceIn(0, 6)
        val today = calendar.days[dayIndex].orEmpty()
            .filter { it.metadataId > 0 }
            .distinctBy { it.metadataId }

        val todayIds = today.mapTo(mutableSetOf()) { it.metadataId }
        val week = calendar.days.toSortedMap().values.asSequence()
            .flatten()
            .filter { it.metadataId > 0 && it.metadataId !in todayIds }
            .distinctBy { it.metadataId }
            .take(WEEK_LIMIT)
            .toList()

        val shownIds = buildSet {
            addAll(todayIds)
            addAll(week.map { it.metadataId })
        }
        val featured = calendar.featured.asSequence()
            .filter { it.metadataId > 0 && it.metadataId !in shownIds }
            .distinctBy { it.metadataId }
            .take(FEATURED_LIMIT)
            .toList()

        return buildList {
            if (today.isNotEmpty()) add(Home(TITLE_TODAY, today))
            if (week.isNotEmpty()) add(Home(TITLE_WEEK, week))
            if (featured.isNotEmpty()) add(Home(TITLE_FEATURED, featured))
        }
    }
}
