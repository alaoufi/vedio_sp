package com.myvideolibrary.app.ui.settings

data class ReleaseNotesEntry(
    val version: String,
    val changes: List<String>
)

/** Keeps the in-app page focused on the newest released version only. */
object ReleaseNotes {
    fun latest(): ReleaseNotesEntry = ReleaseNotesEntry(
        version = "1.0.174",
        changes = listOf(
            "TikTok download retries now clearly explain when the link is refreshed.",
            "Failed TikTok downloads retain a visible retry option."
        )
    )
}
