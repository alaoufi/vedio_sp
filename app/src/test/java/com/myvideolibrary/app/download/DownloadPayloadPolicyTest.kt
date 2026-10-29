package com.myvideolibrary.app.download

import com.myvideolibrary.app.data.model.DownloadKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPayloadPolicyTest {
    @Test fun `JPEG must never finish a video download`() {
        assertFalse(DownloadPayloadPolicy.accepts(DownloadKind.FULL, byteArrayOf(-1, -40, -1, 0)))
    }
    @Test fun `PNG must never finish a slideshow download`() {
        assertFalse(DownloadPayloadPolicy.accepts(DownloadKind.SLIDESHOW,
            byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
    }
    @Test fun `login HTML must not become a saved video`() {
        assertFalse(DownloadPayloadPolicy.accepts(DownloadKind.FULL, " <!DOCTYPE html>".toByteArray()))
    }
    @Test fun `explicit image downloads remain supported`() {
        assertTrue(DownloadPayloadPolicy.accepts(DownloadKind.IMAGE_ONLY, byteArrayOf(-1, -40, -1, 0)))
    }
    @Test fun `MP4 payload is accepted`() {
        assertTrue(DownloadPayloadPolicy.accepts(DownloadKind.FULL, byteArrayOf(0,0,0,24,102,116,121,112)))
    }
    @Test fun `WebP cannot masquerade as a video`() {
        assertFalse(DownloadPayloadPolicy.accepts(DownloadKind.FULL, "RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray()))
    }
    @Test fun `HEIC cannot masquerade as an MP4`() {
        assertFalse(DownloadPayloadPolicy.accepts(DownloadKind.FULL, byteArrayOf(0,0,0,24)+"ftypheic".toByteArray()))
    }
    @Test fun `a playlist is not a downloaded video file`() {
        assertFalse(DownloadPayloadPolicy.accepts(DownloadKind.FULL, "#EXTM3U\n".toByteArray()))
    }
    @Test fun `an empty download cannot complete`() {
        assertFalse(DownloadPayloadPolicy.accepts(DownloadKind.FULL, byteArrayOf()))
    }
}
