package com.myvideolibrary.app.download

import com.myvideolibrary.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadRetryFeedbackTest {

    @Test
    fun `TikTok retry explains that its temporary link is refreshed`() {
        assertEquals(
            R.string.status_refreshing_tiktok,
            DownloadRetryFeedback.messageResFor("tiktok")
        )
    }

    @Test
    fun `other retry explains that download is retried`() {
        assertEquals(R.string.status_retrying_download, DownloadRetryFeedback.messageResFor("youtube"))
    }
}
