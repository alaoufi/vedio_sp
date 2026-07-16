package com.myvideolibrary.app.data.model

/** What part of a video the user chose to download. */
enum class DownloadKind(val id: String) {
    /** Video + audio (the normal download). */
    FULL("full"),
    /** Video with the audio track removed. */
    VIDEO_ONLY("video"),
    /** Audio track only (saved as .m4a). */
    AUDIO_ONLY("audio"),
    /** Just the cover/thumbnail image. */
    IMAGE_ONLY("image");

    companion object {
        fun fromId(id: String?): DownloadKind = entries.firstOrNull { it.id == id } ?: FULL
    }
}
