package com.myvideolibrary.app.util

import java.util.Locale
import java.util.concurrent.TimeUnit

/** Formatting helpers for durations, file sizes, speeds and dates. */
object Formatters {

    /** Formats a millisecond duration as H:MM:SS or M:SS. */
    fun duration(millis: Long): String {
        if (millis <= 0) return "0:00"
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** Human-readable byte count, e.g. "1.4 GB". */
    fun fileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.lastIndex) {
            size /= 1024
            unitIndex++
        }
        return if (unitIndex == 0) {
            "${bytes} B"
        } else {
            String.format(Locale.US, "%.1f %s", size, units[unitIndex])
        }
    }

    /** Bytes-per-second formatted as a transfer speed, e.g. "2.1 MB/s". */
    fun speed(bytesPerSecond: Long): String =
        if (bytesPerSecond <= 0) "—" else "${fileSize(bytesPerSecond)}/s"

    /** Rough remaining time given bytes left and current speed. */
    fun remainingTime(remainingBytes: Long, bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0 || remainingBytes <= 0) return "—"
        val seconds = remainingBytes / bytesPerSecond
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }
}
