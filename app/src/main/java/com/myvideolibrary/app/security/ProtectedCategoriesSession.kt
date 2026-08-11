package com.myvideolibrary.app.security

import java.util.Collections

/**
 * In-memory unlock state for password-protected categories whose covers are
 * obscured. Entering a category's password unlocks it for the rest of the
 * foreground session (its covers un-obscure and its clips open); returning from
 * another screen or backgrounding the app re-locks every category, so covers
 * hide again. Deliberately process-memory only — a cold start is always locked.
 */
object ProtectedCategoriesSession {

    private val unlocked = Collections.synchronizedSet(mutableSetOf<String>())

    private fun key(category: String?): String? =
        category?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    fun isUnlocked(category: String?): Boolean {
        val k = key(category) ?: return false
        return unlocked.contains(k)
    }

    fun unlock(category: String?) {
        key(category)?.let { unlocked.add(it) }
    }

    fun anyUnlocked(): Boolean = unlocked.isNotEmpty()

    fun lockAll() = unlocked.clear()
}
