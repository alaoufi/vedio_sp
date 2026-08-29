package com.myvideolibrary.app.download

/** URLs that can be saved by the progressive-file download worker. */
object DownloadUrlPolicy {
    fun isDirectMedia(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return !path.endsWith(".m3u8")
    }
}
