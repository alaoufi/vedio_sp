package com.myvideolibrary.app.data.model

/**
 * Where a video originated. Kept as a stable string in the database so new
 * providers can be added without a schema migration.
 */
enum class VideoSource(val id: String) {
    LOCAL_IMPORT("local_import"),
    TIKTOK("tiktok"),
    YOUTUBE("youtube"),
    OTHER("other");

    companion object {
        fun fromId(id: String?): VideoSource =
            entries.firstOrNull { it.id == id } ?: OTHER
    }
}

/** Lifecycle states of a download job. */
enum class DownloadStatus(val id: String) {
    WAITING("waiting"),
    DOWNLOADING("downloading"),
    PAUSED("paused"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELED("canceled");

    companion object {
        fun fromId(id: String?): DownloadStatus =
            entries.firstOrNull { it.id == id } ?: WAITING
    }
}

/** How the library grid/list is ordered. */
enum class SortOrder(val id: String) {
    NAME_ASC("name_asc"),
    NAME_DESC("name_desc"),
    DATE_ADDED_DESC("date_desc"),
    DATE_ADDED_ASC("date_asc"),
    DURATION_DESC("duration_desc"),
    DURATION_ASC("duration_asc"),
    SIZE_DESC("size_desc"),
    SIZE_ASC("size_asc");

    companion object {
        fun fromId(id: String?): SortOrder =
            entries.firstOrNull { it.id == id } ?: DATE_ADDED_DESC
    }
}

/** How the library is presented on the dashboard. */
enum class LibraryViewMode(val id: String) {
    GRID("grid"),
    LIST("list");

    companion object {
        fun fromId(id: String?): LibraryViewMode =
            entries.firstOrNull { it.id == id } ?: GRID
    }
}

/** UI theme preference. */
enum class AppTheme(val id: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromId(id: String?): AppTheme =
            entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}
