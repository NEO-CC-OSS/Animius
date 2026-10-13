package com.lanlinju.animius.work

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.lanlinju.animius.domain.model.UpdateState
import com.lanlinju.animius.util.KEY_DOWNLOAD_UPDATE_SHA256
import com.lanlinju.animius.util.KEY_DOWNLOAD_UPDATE_SIZE
import com.lanlinju.animius.util.KEY_DOWNLOAD_UPDATE_URL
import com.lanlinju.animius.util.KEY_UPDATE_DOWNLOADED
import com.lanlinju.animius.util.KEY_UPDATE_FAIL_REASON
import com.lanlinju.animius.util.KEY_UPDATE_PROGRESS
import com.lanlinju.animius.util.UPDATE_WORK_NAME

/**
 * 更新任务的统一入口：主页自动检查弹窗与时间表页手动检查两条路径共用。
 *
 * 使用 WorkManager 唯一任务名 + [ExistingWorkPolicy.KEEP]，因此重复触发不会并发下载，
 * 且任务在应用退到后台/界面切换后仍继续执行。
 */

/** 入队更新任务（已有任务在跑时忽略本次请求，避免并发重复下载） */
fun enqueueUpdateWork(context: Context, url: String, sha256: String?, size: Long) {
    val request = OneTimeWorkRequestBuilder<UpdateWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setInputData(
            workDataOf(
                KEY_DOWNLOAD_UPDATE_URL to url,
                KEY_DOWNLOAD_UPDATE_SHA256 to (sha256 ?: ""),
                KEY_DOWNLOAD_UPDATE_SIZE to size,
            )
        )
        .build()
    WorkManager.getInstance(context)
        .enqueueUniqueWork(UPDATE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
}

/** 取消更新任务（用户点「取消下载」）；任务内的半截文件由 Worker 负责清理 */
fun cancelUpdateWork(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork(UPDATE_WORK_NAME)
}

/** 观察更新任务状态，映射为 [UpdateState] 回调（状态或进度变化时触发） */
fun observeUpdateWork(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    onState: (UpdateState) -> Unit,
) {
    WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkLiveData(UPDATE_WORK_NAME)
        .observe(lifecycleOwner) { infos ->
            val info = infos?.firstOrNull() ?: return@observe
            onState(info.toUpdateState())
        }
}

private fun WorkInfo.toUpdateState(): UpdateState = when (state) {
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> UpdateState.Preparing
    WorkInfo.State.RUNNING -> {
        val percent = progress.getInt(KEY_UPDATE_PROGRESS, -1)
        val downloaded = progress.getLong(KEY_UPDATE_DOWNLOADED, 0L)
        when {
            percent < 0 -> UpdateState.Downloading(progress = null, downloadedBytes = downloaded)
            percent >= 100 -> UpdateState.Verifying
            else -> UpdateState.Downloading(progress = percent / 100f, downloadedBytes = downloaded)
        }
    }
    WorkInfo.State.SUCCEEDED -> UpdateState.Completed
    WorkInfo.State.FAILED -> UpdateState.Failed(parseFailReason(outputData.getString(KEY_UPDATE_FAIL_REASON)))
    WorkInfo.State.CANCELLED -> UpdateState.Cancelled
}

private fun parseFailReason(name: String?): UpdateState.FailReason =
    UpdateState.FailReason.entries.firstOrNull { it.name == name } ?: UpdateState.FailReason.Unknown
