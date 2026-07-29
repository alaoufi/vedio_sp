package com.myvideolibrary.app.util

import android.content.Context
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions

/**
 * App-wide Glide tuning aimed at making the library open fast and stay smooth.
 *
 * Two things matter for this app's thumbnails:
 *  - **Disk cache the transformed result.** Every thumbnail we show is a
 *    downsampled, centre-cropped bitmap. Caching that finished result on disk
 *    ([DiskCacheStrategy.RESOURCE][com.bumptech.glide.load.engine.DiskCacheStrategy.RESOURCE])
 *    means re-opening the library reads a small ready-to-draw bitmap instead of
 *    re-decoding the source — and for clips with no cached thumbnail image the
 *    expensive video-frame decode happens only once, not on every scroll back.
 *  - **RGB_565.** Thumbnails have no transparency, so half-size 16-bit bitmaps
 *    look the same while using half the memory and bitmap-pool pressure.
 *
 * A dedicated ~200 MB image cache is kept separate from other Glide state so it
 * can grow without evicting quickly. This is all local; nothing leaves the device.
 */
@GlideModule
class MvlGlideModule : AppGlideModule() {

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        builder.setDefaultRequestOptions(
            RequestOptions()
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(
                    com.bumptech.glide.load.engine.DiskCacheStrategy.RESOURCE
                )
        )
        builder.setDiskCache(
            InternalCacheDiskCacheFactory(context, "image_cache", DISK_CACHE_BYTES)
        )
    }

    /** We register no components, so Glide can skip manifest-module parsing. */
    override fun isManifestParsingEnabled(): Boolean = false

    private companion object {
        const val DISK_CACHE_BYTES = 200L * 1024 * 1024 // 200 MB
    }
}
