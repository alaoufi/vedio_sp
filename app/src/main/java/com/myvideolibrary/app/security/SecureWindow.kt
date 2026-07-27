package com.myvideolibrary.app.security

import android.app.Activity
import android.os.Build
import android.view.WindowManager

/**
 * Applies the two privacy toggles independently:
 *
 *  - "Prevent screenshots" → FLAG_SECURE (blocks screenshots/recording, and also
 *    hides the Recents preview).
 *  - "Hide from Recents" → hides only the Recents preview, WITHOUT blocking
 *    screenshots, via setRecentsScreenshotEnabled (API 33+).
 *
 * FLAG_SECURE was previously used for both, so turning on "hide from Recents"
 * wrongly blocked screenshots too. Call in onCreate before content is shown.
 */
fun Activity.applyScreenshotPolicy(securityManager: SecurityManager) {
    if (securityManager.preventScreenshots) {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // Hide the Recents thumbnail without the screenshot-blocking side effect.
        setRecentsScreenshotEnabled(!securityManager.hideInRecents)
    }
}
