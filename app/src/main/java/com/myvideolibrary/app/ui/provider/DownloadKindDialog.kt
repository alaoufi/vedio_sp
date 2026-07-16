package com.myvideolibrary.app.ui.provider

import android.view.View
import android.widget.PopupMenu
import com.myvideolibrary.app.R
import com.myvideolibrary.app.data.model.DownloadKind

/**
 * "What to download" chooser, shown as a small dropdown anchored right next to
 * the download button. Full video is listed first (the default choice).
 */
object DownloadKindDialog {

    private val ORDER = listOf(
        DownloadKind.FULL to R.string.kind_full,
        DownloadKind.VIDEO_ONLY to R.string.kind_video_only,
        DownloadKind.AUDIO_ONLY to R.string.kind_audio_only,
        DownloadKind.IMAGE_ONLY to R.string.kind_image_only
    )

    fun show(anchor: View, onPick: (DownloadKind) -> Unit) {
        val popup = PopupMenu(anchor.context, anchor)
        ORDER.forEachIndexed { i, (_, labelRes) ->
            popup.menu.add(0, i, i, anchor.context.getString(labelRes))
        }
        popup.setOnMenuItemClickListener { item ->
            ORDER.getOrNull(item.itemId)?.let { onPick(it.first) }
            true
        }
        popup.show()
    }
}
