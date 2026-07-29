package com.myvideolibrary.app.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.palette.graphics.Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

/**
 * Tints a view with a colour pulled from a thumbnail so covers feel cohesive
 * across the library, the "Continue watching" shelf and the list view.
 *
 * A tiny 64px bitmap is decoded and run through Palette off the main thread; the
 * dominant/muted colour is applied as a subtle gradient fading to transparent.
 *
 * Palette is asynchronous and RecyclerView rows are recycled, so the target is
 * tagged with a stable [key] (the video id) and the callback bails if the row has
 * since been rebound — a late colour never lands on the wrong card.
 */
object CoverTint {

    fun apply(
        target: View,
        model: Any?,
        key: Any,
        orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TOP_BOTTOM
    ) {
        target.tag = key
        target.background = null
        if (model == null) return
        Glide.with(target)
            .asBitmap()
            .load(model)
            .override(64, 64)
            .centerCrop()
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    Palette.from(resource).generate { palette ->
                        if (target.tag != key) return@generate
                        val base = palette?.let { it.getMutedColor(it.getDominantColor(0)) } ?: 0
                        if (base == 0) {
                            target.background = null
                        } else {
                            val tinted = (base and 0x00FFFFFF) or (0x40 shl 24) // ~25% alpha
                            target.background = GradientDrawable(
                                orientation, intArrayOf(tinted, Color.TRANSPARENT)
                            )
                        }
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    // Nothing to release; the view keeps whatever background it has.
                }
            })
    }
}
