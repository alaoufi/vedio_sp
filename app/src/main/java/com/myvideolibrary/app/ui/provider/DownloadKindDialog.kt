package com.myvideolibrary.app.ui.provider

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.myvideolibrary.app.R
import com.myvideolibrary.app.data.model.DownloadKind

/** Shared "what to download" chooser: full / video-only / audio-only / image. */
object DownloadKindDialog {

    fun show(context: Context, onPick: (DownloadKind) -> Unit) {
        val labels = arrayOf(
            context.getString(R.string.kind_full),
            context.getString(R.string.kind_video_only),
            context.getString(R.string.kind_audio_only),
            context.getString(R.string.kind_image_only)
        )
        val kinds = arrayOf(
            DownloadKind.FULL,
            DownloadKind.VIDEO_ONLY,
            DownloadKind.AUDIO_ONLY,
            DownloadKind.IMAGE_ONLY
        )
        AlertDialog.Builder(context)
            .setTitle(R.string.download_options)
            .setItems(labels) { _, which -> onPick(kinds[which]) }
            .show()
    }
}
