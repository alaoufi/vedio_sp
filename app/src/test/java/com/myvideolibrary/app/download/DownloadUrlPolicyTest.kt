package com.myvideolibrary.app.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadUrlPolicyTest {

    @Test
    fun `accepts a progressive media URL`() {
        assertTrue(DownloadUrlPolicy.isDirectMedia("https://cdn.example.com/clip.mp4?token=1"))
    }

    @Test
    fun `rejects an HLS playlist`() {
        assertFalse(DownloadUrlPolicy.isDirectMedia("https://cdn.example.com/master.m3u8?token=1"))
    }
}
