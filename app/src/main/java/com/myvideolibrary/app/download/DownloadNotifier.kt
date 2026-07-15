package com.myvideolibrary.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.myvideolibrary.app.R
import com.myvideolibrary.app.util.Formatters
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Builds notifications and foreground info for the download worker. */
@Singleton
class DownloadNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    init {
        ensureChannel()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.download_channel_desc)
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    fun foregroundInfo(
        notificationId: Int,
        title: String,
        progress: Int,
        indeterminate: Boolean,
        speed: Long
    ): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(
                if (indeterminate) context.getString(R.string.download_preparing)
                else "$progress%  ·  ${Formatters.speed(speed)}"
            )
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, indeterminate)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    fun showComplete(notificationId: Int, title: String, success: Boolean) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(
                context.getString(
                    if (success) R.string.download_complete else R.string.download_failed
                )
            )
            .setSmallIcon(R.drawable.ic_download)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
            .build()
        context.getSystemService(NotificationManager::class.java)
            ?.notify(notificationId + COMPLETE_OFFSET, notification)
    }

    companion object {
        const val CHANNEL_ID = "downloads"
        private const val COMPLETE_OFFSET = 100000
    }
}
