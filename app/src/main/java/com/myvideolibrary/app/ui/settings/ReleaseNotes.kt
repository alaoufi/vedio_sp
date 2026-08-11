package com.myvideolibrary.app.ui.settings

data class ReleaseNotesEntry(
    val version: String,
    val changes: List<String>
)

/** Keeps the in-app page focused on the newest released version only. */
object ReleaseNotes {
    fun latest(): ReleaseNotesEntry = ReleaseNotesEntry(
        version = "1.0.173",
        changes = listOf(
            "Latest changes are available in Settings.",
            "External entry points now respect the app lock.",
            "Unsupported HLS playlist downloads are filtered out.",
            "Backup restore now runs as one atomic operation."
        )
    )
}
