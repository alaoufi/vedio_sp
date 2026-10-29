package com.myvideolibrary.app.download

import com.myvideolibrary.app.data.model.DownloadKind

internal object DownloadPayloadPolicy {
    fun accepts(kind: DownloadKind, prefix: ByteArray): Boolean {
        if (prefix.isEmpty()) return false
        val text = prefix.toString(Charsets.US_ASCII).trimStart().lowercase()
        if (text.startsWith("<!doctype") || text.startsWith("<html") ||
            text.startsWith("<?xml") || text.startsWith("#extm3u") ||
            text.startsWith("{") || text.startsWith("[")) return false
        if (kind == DownloadKind.IMAGE_ONLY) return true
        val jpeg = prefix.size >= 3 && prefix[0] == (-1).toByte() &&
            prefix[1] == (-40).toByte() && prefix[2] == (-1).toByte()
        val png = prefix.size >= 8 && prefix.copyOf(8).contentEquals(
            byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        val gif = text.startsWith("gif87a") || text.startsWith("gif89a")
        val webp = text.startsWith("riff") && text.drop(8).startsWith("webp")
        val imageContainer = prefix.size >= 12 && text.substring(4, 8) == "ftyp" &&
            text.substring(8, 12) in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis")
        return !jpeg && !png && !gif && !webp && !imageContainer
    }
}
