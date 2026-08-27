package com.myvideolibrary.app.ui.settings

data class ReleaseNotesEntry(
    val version: String,
    val changes: List<String>
)

/** Keeps the in-app page focused on the newest released version only. */
object ReleaseNotes {
    fun latest(): ReleaseNotesEntry = ReleaseNotesEntry(
        version = "1.0.175",
        changes = listOf(
            "Added a saved 0–200% volume slider.",
            "Added optional bass, surround, and speech clarity effects.",
            "Audio controls are also available from the player menu."
        )
    )
}
