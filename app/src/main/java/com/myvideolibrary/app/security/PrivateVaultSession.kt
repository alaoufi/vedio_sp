package com.myvideolibrary.app.security

/**
 * In-memory unlock state for extra-private videos. Once the vault password is
 * entered, private covers un-obscure and private clips open for the rest of the
 * foreground session; going to the background re-locks the vault (reset from
 * [AppLockManager.onStop]), so covers hide again on return.
 *
 * Deliberately process-memory only — nothing is persisted, so a cold start is
 * always locked.
 */
object PrivateVaultSession {

    @Volatile
    var unlocked: Boolean = false
        private set

    fun unlock() { unlocked = true }

    fun lock() { unlocked = false }
}
