package com.lanlinju.animius.presentation.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanlinju.animius.data.remote.bangumi.BangumiCalendarClient
import com.lanlinju.animius.data.remote.bangumi.CalendarLoadResult
import com.lanlinju.animius.domain.model.Home
import com.lanlinju.animius.domain.repository.AnimeRepository
import com.lanlinju.animius.util.Resource
import com.lanlinju.animius.util.log
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: AnimeRepository,
    private val calendarClient: BangumiCalendarClient,
) : ViewModel() {
    private val _homeDataList: MutableStateFlow<Resource<List<Home>>> =
        MutableStateFlow(value = Resource.Loading)
    val homeDataList: StateFlow<Resource<List<Home>>>
        get() = _homeDataList

    /** TV 首页顶部的三行元数据推荐；加载失败时为空，源站首页不受影响 */
    private val _metadataRows: MutableStateFlow<List<Home>> = MutableStateFlow(emptyList())
    val metadataRows: StateFlow<List<Home>>
        get() = _metadataRows

    private var metadataRequested = false
    private var metadataEnabled = false

    init {
        getHomeData()
    }

    private fun getHomeData() {
        viewModelScope.launch {
            _homeDataList.value = repository.getHomeData()
        }
    }

    /**
     * 只有 TV 首页会调用；加载不阻塞源站数据，失败就保持空列表。
     * 元数据走现有 24h 本地缓存，不新增网络通道。
     */
    fun loadMetadataRows() {
        metadataEnabled = true
        if (metadataRequested) return
        metadataRequested = true
        viewModelScope.launch {
            when (val result = calendarClient.load()) {
                is CalendarLoadResult.Success -> {
                    // 旧缓存没有 home_rows 标记：保持源站首页，避免半套元数据行
                    if (!result.calendar.supportsHomeRows) {
                        "HOME meta rows skipped reason=no_home_rows".log("BehaviorTrace")
                        _metadataRows.value = emptyList()
                        return@launch
                    }
                    val rows = MetadataHomeRows.build(
                        calendar = result.calendar,
                        todayWeekday = LocalDate.now().dayOfWeek.value,
                    )
                    _metadataRows.value = rows
                    "HOME meta rows=${rows.joinToString { "${it.title}:${it.animeList.size}" }}"
                        .log("BehaviorTrace")
                }

                is CalendarLoadResult.Unavailable -> {
                    "HOME meta unavailable reason=${result.reason}".log("BehaviorTrace")
                    _metadataRows.value = emptyList()
                }
            }
        }
    }

    fun refresh() {
        _homeDataList.value = Resource.Loading
        getHomeData()
        // 元数据首次加载失败后允许随刷新重试；手机端没启用元数据行，不会多发请求
        if (metadataEnabled) {
            metadataRequested = false
            loadMetadataRows()
        }
    }
}
