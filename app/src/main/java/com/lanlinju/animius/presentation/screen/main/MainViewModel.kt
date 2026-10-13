package com.lanlinju.animius.presentation.screen.main

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanlinju.animius.BuildConfig
import com.lanlinju.animius.application.AnimeApplication
import com.lanlinju.animius.domain.model.UpdateState
import com.lanlinju.animius.util.GitHubRelease
import com.lanlinju.animius.util.KEY_IS_AUTO_CHECK_UPDATE
import com.lanlinju.animius.util.KEY_LAST_CHECK_UPDATE_TIME
import com.lanlinju.animius.util.fetchLatestRelease
import com.lanlinju.animius.util.log
import com.lanlinju.animius.util.preferenceForBoolean
import com.lanlinju.animius.util.preferenceForLong
import com.lanlinju.animius.util.preferences
import com.lanlinju.animius.work.cancelUpdateWork
import com.lanlinju.animius.work.enqueueUpdateWork
import com.lanlinju.animius.work.observeUpdateWork
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.System.currentTimeMillis
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val httpClient: HttpClient
) : ViewModel() {

    // 显示版本更新对话框的状态
    private val _showVersionUpdateDialog: MutableStateFlow<Boolean> =
        MutableStateFlow(value = false)
    val showVersionUpdateDialog = _showVersionUpdateDialog.asStateFlow()

    // 下载阶段状态（驱动进度对话框；检查阶段维持原有行为，不在此模型内）
    private val _updateState: MutableStateFlow<UpdateState> = MutableStateFlow(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    // 应用的偏好设置
    private val preferences = AnimeApplication.getInstance().preferences

    // 更新相关数据
    lateinit var updateDownloadUrl: String
    lateinit var updateDescription: String
    lateinit var updateVersionName: String
    private var updateSha256: String? = null
    private var updateSize: Long = 0L
    lateinit var gitHubRelease: GitHubRelease

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val isAutoCheckUpdate =
                preferenceForBoolean(KEY_IS_AUTO_CHECK_UPDATE, true) // 是否启用自动检查更新
            if (isAutoCheckUpdate && shouldCheckForUpdates()) {
                // 更新上次检查时间为当前时间
                updateLastCheckTime()
                // 开始检查更新
                checkForUpdates()
            }
        }
    }

    /**
     * 检查是否需要更新
     * @return true 如果距离上次检查更新已经超过三天
     */
    private fun shouldCheckForUpdates(): Boolean {
        val currentTime = currentTimeMillis()
        val lastCheckTime = preferenceForLong(KEY_LAST_CHECK_UPDATE_TIME, 0) // 上次检查更新的时间（时间戳）
        val diffInMillis = currentTime - lastCheckTime
        // 将毫秒差转换为天数
        val diffInDays = TimeUnit.MILLISECONDS.toDays(diffInMillis)
        return diffInDays >= 3
    }

    /**
     * 执行更新检查逻辑（直连失败自动走镜像）
     */
    private suspend fun checkForUpdates() {
        try {
            // 从 GitHub 获取更新信息（多通道）
            val release = fetchLatestRelease(httpClient) ?: return

            // 保存更新信息
            gitHubRelease = release
            val asset = release.assets.firstOrNull() ?: return
            this.updateDownloadUrl = asset.browserDownloadUrl
            this.updateSha256 = asset.digest
            this.updateSize = asset.size
            this.updateDescription = release.body
            this.updateVersionName = release.tagName

            // 获取当前应用版本号
            val curVersionName = "v${BuildConfig.VERSION_NAME}"
            // 判断是否为新版本
            val isUpdateVersion = updateVersionName != curVersionName

            // 更新对话框状态
            _showVersionUpdateDialog.value = isUpdateVersion
        } catch (e: Exception) {
            e.log()
        }
    }

    /**
     * 开始下载更新：入队后台任务并观察其状态/进度。
     *
     * 与时间表页手动检查共用同一个 WorkManager 唯一任务（KEEP 策略），
     * 因此重复触发不会并发下载；用户切走页面后任务继续、回来仍能看到进度。
     */
    fun downloadUpdate(context: Context, lifecycleOwner: LifecycleOwner) {
        enqueueUpdateWork(context, updateDownloadUrl, updateSha256, updateSize)
        observeUpdateWork(context, lifecycleOwner) { state ->
            if (state is UpdateState.Completed) {
                // 已调起系统安装器：关闭对话框并复位状态，避免对话框停留在"正在调起安装程序…"
                _showVersionUpdateDialog.value = false
                _updateState.value = UpdateState.Idle
            } else {
                _updateState.value = state
            }
        }
    }

    /** 用户取消下载（任务内的半截安装包由 Worker 负责清理） */
    fun cancelUpdate(context: Context) {
        cancelUpdateWork(context)
    }

    /** 关闭对话框后把下载阶段状态复位 */
    fun resetUpdateState() {
        _updateState.value = UpdateState.Idle
    }

    /**
     * 更新上次检查时间
     */
    private fun updateLastCheckTime() {
        preferences.edit { putLong(KEY_LAST_CHECK_UPDATE_TIME, currentTimeMillis()) }
    }

    /**
     * 关闭版本更新对话框
     */
    fun dismissUpdateDialog() {
        _showVersionUpdateDialog.value = false
        _updateState.value = UpdateState.Idle
    }
}