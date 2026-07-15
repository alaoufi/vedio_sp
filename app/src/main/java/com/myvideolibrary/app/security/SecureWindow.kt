package com.myvideolibrary.app.security

import android.app.Activity
import android.view.WindowManager

/**
 * Applies FLAG_SECURE when the user has enabled screenshot blocking or hiding the
 * app's content from the recent-apps preview. Call in onCreate before content is
 * shown.
 */
fun Activity.applyScreenshotPolicy(securityManager: SecurityManager) {
    if (securityManager.preventScreenshots || securityManager.hideInRecents) {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
