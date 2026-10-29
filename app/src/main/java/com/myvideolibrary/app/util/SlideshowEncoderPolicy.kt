package com.myvideolibrary.app.util

internal object SlideshowEncoderPolicy {
    fun order(hardware: List<String>, software: List<String>): List<String?> =
        (hardware + software).distinct() + listOf(null)
}
