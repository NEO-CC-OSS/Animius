package com.lanlinju.animius.presentation.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanlinju.animius.data.remote.bangumi.CalendarLoadResult
import com.lanlinju.animius.data.remote.bangumi.MetadataCoverPrefetcher
import com.lanlinju.animius.data.remote.bangumi.MetadataIndexClient
import com.lanlinju.animius.domain.model.Home
import com.lanlinju.animius.util.Resource
import com.lanlinju.animius.util.log
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 首页 ViewModel（2026-10-22 设备统一）。
 *
 * 手机/平板与 TV 走同一条链路：只读轻包 index.json（实测约 244 KB），渲染
 * 「今日放送 / 本周新番 / 高分推荐」三行；**不再调用源站首页接口**。
 * 失败/空数据给出可重试的错误态，不回退源站内容（原 TV 拍板，现对所有设备生效）。
 *
 * 设备差异只剩焦点样式/按键/尺寸（在 Composable 层用 `isAndroidTV` 判断），数据与流程不分叉。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val metadataIndexClient: MetadataIndexClient,
    private val coverPrefetcher: MetadataCoverPrefetcher,
) : ViewModel() {

    /** 首页三行元数据：Loading / Success(rows) / Error（可重试，不回退源站） */
    private val _metadataState: MutableStateFlow<Resource<List<Home>>> =
        MutableStateFlow(value = Resource.Loading)
    val metadataState: StateFlow<Resource<List<Home>>>
        get() = _metadataState

    private var metadataJob: Job? = null

    /** 封面预取只做一次：首次进入首页后台补齐非可见行，之后命中 Coil 磁盘缓存 */
    private var coverPrefetchStarted = false

    init {
        loadMetadata()
    }

    /**
     * 首页只读轻包元数据。
     *
     * @param forceRefresh 用户点重试/刷新时强制重新拉取（24 h 内默认用缓存）
     */
    fun loadMetadata(forceRefresh: Boolean = false) {
        metadataJob?.cancel()
        metadataJob = viewModelScope.launch {
            _metadataState.value = Resource.Loading
            when (val result = metadataIndexClient.load(forceRefresh)) {
                is CalendarLoadResult.Success -> {
                    // 旧产物没有 home_rows 标记：不显示半套元数据行，直接给错误态
                    if (!result.calendar.supportsHomeRows) {
                        "HOME meta rows skipped reason=no_home_rows".log("BehaviorTrace")
                        _metadataState.value =
                            Resource.Error(IllegalStateException("metadata has no home rows"))
                        return@launch
                    }
                    val rows = MetadataHomeRows.build(
                        calendar = result.calendar,
                        todayWeekday = LocalDate.now().dayOfWeek.value,
                    )
                    if (rows.isEmpty()) {
                        "HOME meta empty items=${result.calendar.itemCount}".log("BehaviorTrace")
                        _metadataState.value =
                            Resource.Error(IllegalStateException("metadata empty"))
                        return@launch
                    }
                    _metadataState.value = Resource.Success(rows)
                    (
                        "HOME rows=${rows.joinToString { "${it.title}:${it.animeList.size}" }} " +
                            "origin=${if (result.fromCache) "cache" else "network"}"
                    ).log("BehaviorTrace")
                }

                is CalendarLoadResult.Unavailable -> {
                    "HOME meta unavailable reason=${result.reason}".log("BehaviorTrace")
                    _metadataState.value = Resource.Error(IllegalStateException(result.reason))
                }
            }
        }
    }

    /**
     * 后台预取「非可见行」的封面（今日行之后的周表/高分行），不阻塞 UI。
     *
     * **必须由页面级协程调用**（HomeScreen 的 `LaunchedEffect`）：页面销毁（进详情/播放器）时
     * 协程被取消，预取随之停止，不会在播放时继续抢带宽。先延迟 [PREFETCH_DELAY_MS] 再开始，
     * 让首屏可见行的封面先加载。
     *
     * 只预取 http(s) 地址；并发/超时/重试/缓存策略由 [MetadataCoverPrefetcher] 统一约束。
     */
    suspend fun prefetchHiddenRowCovers(rows: List<Home>) {
        if (coverPrefetchStarted || rows.isEmpty()) return
        val urls = rows.drop(1)
            .flatMap { it.animeList }
            .map { it.img }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_PREFETCH_COVERS)
        if (urls.isEmpty()) return
        // 取消（页面离开）时不留"已预取"标记，下次进首页还能补
        delay(PREFETCH_DELAY_MS)
        coverPrefetchStarted = true
        coverPrefetcher.prefetch(urls)
    }

    fun refresh() {
        loadMetadata(forceRefresh = true)
    }

    private companion object {
        /** 预取上限：周表 40 + 高分 30，留一点余量；全部封面由用户滚动时按需加载 */
        const val MAX_PREFETCH_COVERS = 80

        /** 先让首屏可见行加载 2.5 s，再开始后台预取，避免抢带宽 */
        const val PREFETCH_DELAY_MS = 2_500L
    }
}
