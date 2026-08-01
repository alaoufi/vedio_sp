package com.myvideolibrary.app.data.model

/** The kind of media a library item holds. */
enum class MediaType(val id: String) {
    VIDEO("video"),
    AUDIO("audio"),
    IMAGE("image");

    companion object {
        fun fromId(id: String?): MediaType = entries.firstOrNull { it.id == id } ?: VIDEO
    }
}
