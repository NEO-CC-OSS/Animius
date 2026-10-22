package com.lanlinju.animius.presentation.screen.week

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanlinju.animius.BuildConfig
import com.lanlinju.animius.R
import com.lanlinju.animius.data.remote.bangumi.CalendarFreshness
import com.lanlinju.animius.data.remote.bangumi.CalendarLoadResult
import com.lanlinju.animius.data.remote.bangumi.MetadataIndexClient
import com.lanlinju.animius.domain.model.Anime
import com.lanlinju.animius.domain.model.UpdateState
import com.lanlinju.animius.util.Resource
import com.lanlinju.animius.util.fetchLatestRelease
import com.lanlinju.animius.util.isNewerTvVersion
import com.lanlinju.animius.util.log
import com.lanlinju.animius.util.toast
import com.lanlinju.animius.work.cancelUpdateWork
import com.lanlinju.animius.work.enqueueUpdateWork
import com.lanlinju.animius.work.observeUpdateWork
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 周表数据来自哪里，决定顶部提示与点击落点 */
enum class WeekDataOrigin {
    Loading,
    Metadata,
    MetadataCache,

    /** 历史：手机/平板曾读源站周表（tv.6）；2026-10-22 设备统一后不再使用 */
    SourceSite,

    /** TV：元数据不可用（网络/缓存都失败），空态 + 重试 */
    Unavailable,

    /** TV：元数据结构合法但 7 天都没有条目，与"不可用"区分 */
    Empty,
}

/**
 * 周表数据源策略（2026-10-22 设备统一）：手机/平板与 TV 一样只读轻包 index.json。
 * 不可用/全空 → 空态 + 重试，**不回退源站**（原 TV 拍板，现已对所有设备生效）。
 */

data class WeekMetadataInfo(
    val origin: WeekDataOrigin = WeekDataOrigin.Loading,
    val updatedAt: Instant? = null,
    val freshness: CalendarFreshness = CalendarFreshness.FRESH,
    val failureReason: String? = null,
)

@HiltViewModel
class WeekViewModel @Inject constructor(
    private val httpClient: HttpClient,
    private val metadataIndexClient: MetadataIndexClient,
) : ViewModel() {

    private val _weekDataMap: MutableStateFlow<Resource<Map<Int, List<Anime>>>> =
        MutableStateFlow(value = Resource.Loading)
    val weekDataMap: StateFlow<Resource<Map<Int, List<Anime>>>> get() = _weekDataMap

    private val _metadataInfo: MutableStateFlow<WeekMetadataInfo> =
        MutableStateFlow(value = WeekMetadataInfo())
    val metadataInfo: StateFlow<WeekMetadataInfo> get() = _metadataInfo

    private val _isUpdateAvailable: MutableStateFlow<Boolean> = MutableStateFlow(value = false)
    val isUpdateAvailable: StateFlow<Boolean> get() = _isUpdateAvailable

    private val _isUpdateCheckInProgress: MutableStateFlow<Boolean> =
        MutableStateFlow(value = false)
    val isUpdateCheckInProgress: StateFlow<Boolean> get() = _isUpdateCheckInProgress

    // 下载阶段状态（驱动进度对话框）
    private val _updateState: MutableStateFlow<UpdateState> = MutableStateFlow(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> get() = _updateState

    private lateinit var updateDownloadUrl: String
    lateinit var updateDescription: String
    lateinit var updateVersionName: String
    private var updateSha256: String? = null
    private var updateSize: Long = 0L

    init {
        loadWeekData(forceRefresh = false)
    }

    private fun loadWeekData(forceRefresh: Boolean) {
        viewModelScope.launch {
            // 2026-10-22 设备统一：手机/平板与 TV 一样只读轻包 index，不回退源站
            loadMetadataWeek(forceRefresh)
        }
    }

    /** TV 路径：轻包 index.json */
    private suspend fun loadMetadataWeek(forceRefresh: Boolean) {
        _metadataInfo.value = WeekMetadataInfo(origin = WeekDataOrigin.Loading)
        when (val result = metadataIndexClient.load(forceRefresh)) {
            is CalendarLoadResult.Success -> {
                val calendar = result.calendar
                if (calendar.itemCount == 0) {
                    // 结构合法但 7 天全空：与"不可用"区分，仍可重试
                    _weekDataMap.value = Resource.Error(IllegalStateException("week empty"))
                    _metadataInfo.value = WeekMetadataInfo(
                        origin = WeekDataOrigin.Empty,
                        updatedAt = calendar.updatedAt,
                        freshness = result.freshness,
                    )
                    "META ui origin=Empty items=0".log("BehaviorTrace")
                    return
                }
                _weekDataMap.value = Resource.Success(calendar.days)
                _metadataInfo.value = WeekMetadataInfo(
                    origin = if (result.fromCache) {
                        WeekDataOrigin.MetadataCache
                    } else {
                        WeekDataOrigin.Metadata
                    },
                    updatedAt = calendar.updatedAt,
                    freshness = result.freshness,
                    failureReason = result.failureReason,
                )
                (
                    "META ui origin=${_metadataInfo.value.origin} " +
                        "freshness=${result.freshness} items=${calendar.itemCount}"
                ).log("BehaviorTrace")
            }

            is CalendarLoadResult.Unavailable -> {
                // 用户拍板：TV 不回退源站 → 空态 + 重试（StateHandler 的 onFailure）
                _weekDataMap.value = Resource.Error(IllegalStateException(result.reason))
                _metadataInfo.value = WeekMetadataInfo(
                    origin = WeekDataOrigin.Unavailable,
                    failureReason = result.reason,
                )
                "META ui origin=Unavailable reason=${result.reason}".log("BehaviorTrace")
            }
        }
    }

    fun refresh() {
        _weekDataMap.value = Resource.Loading
        loadWeekData(forceRefresh = true)
    }

    /**
     * 切换数据源/修改域名时调用。
     *
     * 周表是元数据（与源站无关）：切源/改域名不需要重新取任何周表数据，也不重下轻包。
     */
    fun onSourceChanged() {
        "META ui source changed ignore origin=${_metadataInfo.value.origin}"
            .log("BehaviorTrace")
    }

    fun checkVersionUpdate(context: Context) {

        _isUpdateCheckInProgress.value = true

        viewModelScope.launch {
            try {
                val release = fetchLatestRelease(httpClient)
                    ?: throw IllegalStateException("all update channels failed")
                val asset = release.assets.firstOrNull()
                    ?: throw IllegalStateException("no assets in release")

                this@WeekViewModel.updateDownloadUrl = asset.browserDownloadUrl
                this@WeekViewModel.updateSha256 = asset.digest
                this@WeekViewModel.updateSize = asset.size
                this@WeekViewModel.updateDescription = release.body
                this@WeekViewModel.updateVersionName = release.tagName

                // 仅当线上 tv 序号高于本地时才提示（避免装测试包/降级时误报）
                val isUpdateVersion = isNewerTvVersion(release.tagName, BuildConfig.VERSION_NAME)

                _isUpdateCheckInProgress.value = false
                _isUpdateAvailable.value = isUpdateVersion
                if (!isUpdateVersion) {
                    context.toast(R.string.no_new_version)
                }
            } catch (_: Exception) {
                _isUpdateCheckInProgress.value = false
                context.toast(R.string.check_updates_failed)
            }
        }
    }

    /**
     * 开始下载更新：入队后台任务并观察状态/进度。
     *
     * 与主页入口共用同一个 WorkManager 唯一任务（KEEP 策略）→ 重复触发不会并发下载；
     * 不关闭对话框，改由 [updateState] 驱动对话框切换到进度视图。
     */
    fun downloadVersionUpdate(context: Context, lifecycleOwner: LifecycleOwner) {
        enqueueUpdateWork(context, updateDownloadUrl, updateSha256, updateSize)
        observeUpdateWork(context, lifecycleOwner) { state ->
            if (state is UpdateState.Completed) {
                // 已调起系统安装器：关闭对话框并复位状态，避免对话框停留在"正在调起安装程序…"
                _isUpdateAvailable.value = false
                _updateState.value = UpdateState.Idle
            } else {
                _updateState.value = state
            }
        }
    }

    /** 用户取消下载（半截安装包由 Worker 清理） */
    fun cancelUpdate(context: Context) {
        cancelUpdateWork(context)
    }

    fun dismissVersionUpdateDialog() {
        _isUpdateAvailable.value = false
        _updateState.value = UpdateState.Idle
    }

    fun dismissLoadingIndicationDialog() {
        _isUpdateCheckInProgress.value = false
    }
}