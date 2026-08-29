package com.myvideolibrary.app.security

/** Pure policy used by activities that can be launched from outside the app. */
object ExternalEntryPolicy {
    fun requiresUnlock(lockConfigured: Boolean, authenticated: Boolean): Boolean =
        lockConfigured && !authenticated
}
