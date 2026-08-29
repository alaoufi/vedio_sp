package com.myvideolibrary.app.download

import androidx.annotation.StringRes
import com.myvideolibrary.app.R

internal object DownloadRetryFeedback {
    @StringRes
    fun messageResFor(source: String): Int =
        if (source == "tiktok") R.string.status_refreshing_tiktok
        else R.string.status_retrying_download
}
