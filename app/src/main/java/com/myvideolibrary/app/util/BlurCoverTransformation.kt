package com.myvideolibrary.app.util

import android.graphics.Bitmap
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * Blurs a cover while keeping its dimensions, for password-protected categories
 * shown in "blur cover" mode. The real thumbnail is heavily downscaled (which on
 * its own reads as a blur once drawn back up), smoothed with a couple of cheap
 * box-blur passes, then scaled back to the source size so the card keeps its
 * shape. Deliberately RenderScript-free so it works on every API level with no
 * extra dependency.
 */
class BlurCoverTransformation(
    private val sampling: Int = 14,
    private val passes: Int = 2
) : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap {
        val w = toTransform.width
        val h = toTransform.height
        if (w <= 0 || h <= 0) return toTransform
        val sw = (w / sampling).coerceAtLeast(1)
        val sh = (h / sampling).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(toTransform, sw, sh, true)
        boxBlur(small, passes)
        val result = Bitmap.createScaledBitmap(small, w, h, true)
        if (small !== result) small.recycle()
        return result
    }

    /** A few 3×3 box-blur passes (separable H then V) over a small bitmap. */
    private fun boxBlur(bitmap: Bitmap, passes: Int) {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(passes) {
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    var r = 0; var g = 0; var b = 0; var n = 0
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx in 0 until w) {
                            val c = pixels[row + xx]
                            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                        }
                    }
                    tmp[row + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                }
            }
            for (x in 0 until w) {
                for (y in 0 until h) {
                    var r = 0; var g = 0; var b = 0; var n = 0
                    for (dy in -1..1) {
                        val yy = y + dy
                        if (yy in 0 until h) {
                            val c = tmp[yy * w + x]
                            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                        }
                    }
                    pixels[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                }
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("$ID:$sampling:$passes".toByteArray(com.bumptech.glide.load.Key.CHARSET))
    }

    override fun equals(other: Any?): Boolean =
        other is BlurCoverTransformation && other.sampling == sampling && other.passes == passes

    override fun hashCode(): Int = ID.hashCode() + sampling * 31 + passes

    private companion object {
        const val ID = "com.myvideolibrary.app.util.BlurCoverTransformation"
    }
}
