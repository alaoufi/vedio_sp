package com.myvideolibrary.app.security

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks whether the user has unlocked the app in the current foreground session.
 * Authentication is cleared whenever the whole app goes to the background, so the
 * lock screen reappears on return.
 */
@Singleton
class AppLockManager @Inject constructor(
    private val securityManager: SecurityManager
) : DefaultLifecycleObserver {

    @Volatile
    private var authenticated = false

    /** True when the lock screen must be shown before content. */
    fun shouldLock(): Boolean = securityManager.isLockConfigured && !authenticated

    fun markAuthenticated() { authenticated = true }

    fun lockNow() { authenticated = false }

    // ProcessLifecycleOwner callbacks: re-lock as soon as the app is backgrounded.
    override fun onStop(owner: LifecycleOwner) {
        authenticated = false
        // Also re-obscure protected-category covers until unlocked again.
        ProtectedCategoriesSession.lockAll()
    }
}
