package com.myvideolibrary.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton settings row. Always stored with [id] == 1 so reads/writes target a
 * single, well-known record.
 */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ID,

    /** [com.myvideolibrary.app.data.model.AppTheme.id]. */
    @ColumnInfo(name = "theme")
    val theme: String = "system",

    /** BCP-47 language tag or "system". */
    @ColumnInfo(name = "language")
    val language: String = "system",

    /** Optional custom storage path for downloads/imports. */
    @ColumnInfo(name = "storage_path")
    val storagePath: String? = null,

    // ---- Download preferences (used by the download manager phase) ----
    @ColumnInfo(name = "wifi_only_downloads")
    val wifiOnlyDownloads: Boolean = false,

    @ColumnInfo(name = "max_concurrent_downloads")
    val maxConcurrentDownloads: Int = 2,

    // ---- Library preferences ----
    /** [com.myvideolibrary.app.data.model.LibraryViewMode.id]. */
    @ColumnInfo(name = "view_mode")
    val viewMode: String = "grid",

    /** [com.myvideolibrary.app.data.model.SortOrder.id]. */
    @ColumnInfo(name = "sort_order")
    val sortOrder: String = "date_desc",

    // ---- Security preferences (used by the security phase) ----
    @ColumnInfo(name = "app_lock_enabled")
    val appLockEnabled: Boolean = false,

    /** Salted PIN hash. Never stores the raw PIN. */
    @ColumnInfo(name = "pin_hash")
    val pinHash: String? = null,

    @ColumnInfo(name = "biometric_enabled")
    val biometricEnabled: Boolean = false,

    @ColumnInfo(name = "hide_preview_in_recents")
    val hidePreviewInRecents: Boolean = true,

    @ColumnInfo(name = "prevent_screenshots")
    val preventScreenshots: Boolean = true,

    // ---- Maintenance ----
    @ColumnInfo(name = "auto_cleanup_enabled")
    val autoCleanupEnabled: Boolean = false,

    /** User-defined category display order, newline-separated names. */
    @ColumnInfo(name = "category_order")
    val categoryOrder: String? = null,

    /** Hidden categories, newline-separated names — excluded from the library view. */
    @ColumnInfo(name = "hidden_categories")
    val hiddenCategories: String? = null,

    /**
     * Password-protected categories, one per line as "name\tsha256hex". Their
     * contents are hidden from the library until opened with the password.
     */
    @ColumnInfo(name = "category_passwords")
    val categoryPasswords: String? = null,

    /** [com.myvideolibrary.app.data.model.EndOfClipAction.id] — stop/repeat/next. */
    @ColumnInfo(name = "end_of_clip_action")
    val endOfClipAction: String = "next"
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
