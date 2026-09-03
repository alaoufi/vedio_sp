package com.myvideolibrary.app.security

/**
 * In-memory unlock state for individually-obscured clips (the per-clip "obscure"
 * flag, [com.myvideolibrary.app.data.local.entity.VideoEntity.isPrivate]). Entering
 * the obscure password reveals every obscured clip for the rest of the foreground
 * session; returning from another screen or backgrounding re-locks them, so their
 * covers hide again. Process-memory only — a cold start is always locked.
 */
object ObscuredClipsSession {

    @Volatile
    private var unlocked = false

    fun isUnlocked(): Boolean = unlocked

    fun unlock() { unlocked = true }

    fun lockAll() { unlocked = false }
}
