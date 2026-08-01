package com.myvideolibrary.app.ui.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.myvideolibrary.app.data.model.AppTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists and applies the day/night theme. Uses plain SharedPreferences so the
 * value can be read and applied synchronously at process start (no theme flash).
 */
@Singleton
class ThemeManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var theme: AppTheme
        get() = AppTheme.fromId(prefs.getString(KEY, AppTheme.SYSTEM.id))
        set(value) {
            prefs.edit().putString(KEY, value.id).apply()
            apply(value)
        }

    fun apply(theme: AppTheme = this.theme) {
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                AppTheme.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                AppTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppTheme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
        )
    }

    companion object {
        private const val PREFS = "mvl_theme"
        private const val KEY = "app_theme"
    }
}
