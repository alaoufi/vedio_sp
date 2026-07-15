package com.myvideolibrary.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.myvideolibrary.app.security.AppLockManager
import com.myvideolibrary.app.ui.settings.ThemeManager
import dagger.hilt.android.HiltAndroidApp
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

    override fun onCreate() {
        super.onCreate()
        // Apply the saved day/night theme before any activity is shown.
        themeManager.apply()
        // Re-lock the app whenever it is sent to the background.
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLockManager)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
