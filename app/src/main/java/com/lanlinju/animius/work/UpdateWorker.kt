package com.lanlinju.animius.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lanlinju.animius.di.DefaultHttpClient
import com.lanlinju.animius.domain.model.UpdateState
import com.lanlinju.animius.util.KEY_DOWNLOAD_UPDATE_SHA256
import com.lanlinju.animius.util.KEY_DOWNLOAD_UPDATE_SIZE
import com.lanlinju.animius.util.KEY_DOWNLOAD_UPDATE_URL
import com.lanlinju.animius.util.KEY_UPDATE_DOWNLOADED
import com.lanlinju.animius.util.KEY_UPDATE_ERROR_DETAIL
import com.lanlinju.animius.util.KEY_UPDATE_FAIL_REASON
import com.lanlinju.animius.util.KEY_UPDATE_PROGRESS
import com.lanlinju.animius.util.UpdateDownloadResult
import com.lanlinju.animius.util.downloadUpdateApk
import com.lanlinju.animius.util.installApkSafely
import com.lanlinju.animius.util.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class UpdateWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_DOWNLOAD_UPDATE_URL) ?: return Result.failure(
            workDataOf(
                KEY_UPDATE_FAIL_REASON to UpdateState.FailReason.Unknown.name,
                KEY_UPDATE_ERROR_DETAIL to "更新地址为空",
            )
        )
        val sha256 = inputData.getString(KEY_DOWNLOAD_UPDATE_SHA256)?.takeIf { it.isNotBlank() }
        val size = inputData.getLong(KEY_DOWNLOAD_UPDATE_SIZE, 0L)

        val dir = applicationContext.getExternalFilesDir("apk/") ?: applicationContext.filesDir
        val file = File(dir, "base.apk")

        return withContext(Dispatchers.IO) {
            try {
                var lastPercent = -2
                var lastBytes = 0L
                val result = downloadUpdateApk(
                    client = DefaultHttpClient,
                    url = url,
                    target = file,
                    expectedSha256 = sha256,
                    expectedSize = size,
                    context = applicationContext,
                ) { downloaded, total ->
                    // 节流：有总长度时按百分比变化上报；总长度未知时按每 512KB 上报，避免只报一次后不再更新
                    val percent = if (total > 0) (downloaded * 100 / total).toInt() else -1
                    val shouldReport = if (percent >= 0) {
                        percent != lastPercent
                    } else {
                        downloaded - lastBytes >= 512 * 1024
                    }
                    if (shouldReport) {
                        lastPercent = percent
                        lastBytes = downloaded
                        setProgressAsync(
                            workDataOf(
                                KEY_UPDATE_PROGRESS to percent,
                                KEY_UPDATE_DOWNLOADED to downloaded,
                            )
                        )
                    }
                }

                when (result) {
                    UpdateDownloadResult.Success -> {
                        if (applicationContext.installApkSafely(file)) {
                            Result.success()
                        } else {
                            Result.failure(workDataOf(KEY_UPDATE_FAIL_REASON to UpdateState.FailReason.Install.name))
                        }
                    }
                    UpdateDownloadResult.ChecksumMismatch ->
                        Result.failure(workDataOf(KEY_UPDATE_FAIL_REASON to UpdateState.FailReason.Checksum.name))
                    UpdateDownloadResult.SignatureMismatch ->
                        Result.failure(workDataOf(KEY_UPDATE_FAIL_REASON to UpdateState.FailReason.Signature.name))
                    UpdateDownloadResult.AllChannelsFailed ->
                        Result.failure(workDataOf(KEY_UPDATE_FAIL_REASON to UpdateState.FailReason.Network.name))
                }
            } catch (e: CancellationException) {
                // 用户取消下载：清理半截安装包后向上抛出（WorkManager 会记为 CANCELLED）
                file.delete()
                throw e
            } catch (e: Throwable) {
                e.message?.log("UpdateWorker: ")
                file.delete()
                Result.failure(
                    workDataOf(
                        KEY_UPDATE_FAIL_REASON to UpdateState.FailReason.Unknown.name,
                        KEY_UPDATE_ERROR_DETAIL to e.stackTraceToString().take(1200),
                    )
                )
            }
        }
    }
}
