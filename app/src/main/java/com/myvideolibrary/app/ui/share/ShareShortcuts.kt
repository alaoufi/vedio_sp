package com.myvideolibrary.app.ui.share

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.myvideolibrary.app.R
import com.myvideolibrary.app.ui.security.LockActivity

/**
 * Publishes a long-lived Direct Share target so the app can appear in the TOP row
 * of the Android share sheet — the row Android reserves for Sharing Shortcuts —
 * instead of only in the ranked app list that often hides behind "More".
 *
 * The shortcut's category matches the `<share-target>` in res/xml/shortcuts.xml,
 * which is what links an incoming video/image share to [ShareReceiverActivity].
 */
object ShareShortcuts {

    private const val SHARE_CATEGORY = "com.myvideolibrary.app.category.SHARE_TARGET"
    private const val ID = "share_target_add_to_library"

    fun publish(context: Context) {
        // Tapping the shortcut itself (e.g. from a launcher long-press) opens the app.
        val launch = Intent(context, LockActivity::class.java).apply { action = Intent.ACTION_MAIN }
        val shortcut = ShortcutInfoCompat.Builder(context, ID)
            .setShortLabel(context.getString(R.string.app_name))
            .setLongLabel(context.getString(R.string.share_add_to_library))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setCategories(setOf(SHARE_CATEGORY))
            .setLongLived(true)
            .setIntent(launch)
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
    }
}
