package com.myvideolibrary.app.data.backup

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic worker that writes an automatic encrypted backup to the user's chosen
 * external folder. A no-op when auto-backup is disabled; retries on failure so a
 * temporarily-unavailable folder (e.g. an unmounted SD card) is picked up later.
 */
@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val autoBackupManager: AutoBackupManager
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!autoBackupManager.isEnabled) return Result.success()
        return if (autoBackupManager.backupNow().isSuccess) Result.success() else Result.retry()
    }
}
