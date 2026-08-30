package com.myvideolibrary.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.myvideolibrary.app.data.repository.SettingsRepository
import com.myvideolibrary.app.security.AppLockManager
import com.myvideolibrary.app.ui.settings.ThemeManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Annotated with [HiltAndroidApp] to trigger Hilt's code generation and act as the
 * dependency container root. Also provides the [WorkManager][androidx.work.WorkManager]
 * configuration so background workers can receive Hilt-injected dependencies.
 */
@HiltAndroidApp
class MyVideoLibraryApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var appLockManager: AppLockManager

    @Inject
    lateinit var themeManager: ThemeManager

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var autoBackupManager: com.myvideolibrary.app.data.backup.AutoBackupManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Record any uncaught crash locally so it can be viewed/shared (no server).
        com.myvideolibrary.app.util.CrashLogger.install(this)
        // Apply the saved day/night theme before any activity is shown.
        themeManager.apply()
        // Re-lock the app whenever it is sent to the background.
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLockManager)
        normalizeDownloadDefaultsOnce()
        // Self-heal: if the library was wiped but an external auto-backup exists,
        // restore it automatically so categories/titles/settings come back.
        appScope.launch { runCatching { autoBackupManager.autoRestoreIfEmpty() } }
        // Register the Direct Share target so the app appears in the share sheet's top row.
        com.myvideolibrary.app.ui.share.ShareShortcuts.publish(this)
    }

    /**
     * One-time fix for installs upgraded from a build whose default was Wi-Fi-only.
     * That stored value keeps cellular downloads stuck in "Waiting", so reset it once.
     */
    private fun normalizeDownloadDefaultsOnce() {
        val prefs = getSharedPreferences("mvl_flags", MODE_PRIVATE)
        if (prefs.getBoolean("wifi_only_reset_done", false)) return
        appScope.launch {
            settingsRepository.update { it.copy(wifiOnlyDownloads = false) }
            prefs.edit().putBoolean("wifi_only_reset_done", true).apply()
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
