package com.myvideolibrary.app.util

import java.security.MessageDigest

/**
 * How a password-protected category behaves in the library:
 *
 * - [VISIBLE]  — its covers show normally, but opening a clip asks for the password.
 * - [HIDDEN]   — the whole category is dropped from the library view.
 * - [OBSCURED] — its covers are blurred (same size) and opening asks for the password.
 *
 * Stored as the third tab-separated field on a category's password line; a legacy
 * line with only name + hash is read as [OBSCURED] (the previous behaviour).
 */
enum class CategoryProtectionMode(val id: String) {
    VISIBLE("visible"),
    HIDDEN("hidden"),
    OBSCURED("obscured");

    companion object {
        fun fromId(id: String?): CategoryProtectionMode =
            entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: OBSCURED
    }
}

/**
 * Serialises the per-category visibility (hidden) and password metadata that
 * lives in the settings row, and hashes category passwords.
 *
 * Passwords are never stored in the clear: only a SHA-256 hash is kept, and the
 * whole database is already encrypted at rest (SQLCipher). Category names are
 * compared trimmed and case-insensitively, matching how categories are treated
 * everywhere else in the app.
 */
object CategorySecurity {

    private const val LINE = "\n"
    private const val FIELD = "\t"

    // ---- Hidden categories (newline-separated names) ----

    fun parseHidden(stored: String?): Set<String> =
        stored?.split(LINE)?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    fun serializeHidden(names: Collection<String>): String =
        names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(LINE)

    fun toggleHidden(stored: String?, name: String, hidden: Boolean): String {
        val set = parseHidden(stored).toMutableSet()
        set.removeAll { it.equals(name.trim(), ignoreCase = true) }
        if (hidden) set.add(name.trim())
        return serializeHidden(set)
    }

    fun isHidden(stored: String?, name: String): Boolean =
        parseHidden(stored).any { it.equals(name.trim(), ignoreCase = true) }

    // ---- Password-protected categories ("name\thash\tmode" per line) ----

    /** A category's password hash together with how it behaves in the library. */
    private data class Protection(val hash: String, val mode: CategoryProtectionMode)

    private fun parseProtections(stored: String?): LinkedHashMap<String, Protection> {
        val out = LinkedHashMap<String, Protection>()
        if (stored.isNullOrEmpty()) return out
        for (line in stored.split(LINE)) {
            val parts = line.split(FIELD)
            if (parts.size >= 2) {
                val name = parts[0].trim()
                val hash = parts[1].trim()
                // Legacy lines (name + hash only) mean the old blur-cover behaviour.
                val mode = if (parts.size >= 3) {
                    CategoryProtectionMode.fromId(parts[2])
                } else {
                    CategoryProtectionMode.OBSCURED
                }
                if (name.isNotEmpty() && hash.isNotEmpty()) out[name] = Protection(hash, mode)
            }
        }
        return out
    }

    private fun serializeProtections(map: Map<String, Protection>): String =
        map.entries
            .filter { it.key.trim().isNotEmpty() && it.value.hash.isNotEmpty() }
            .joinToString(LINE) { "${it.key.trim()}$FIELD${it.value.hash}$FIELD${it.value.mode.id}" }

    /** Parsed map of category name -> password hash (mode-agnostic). */
    fun parsePasswords(stored: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        parseProtections(stored).forEach { (name, p) -> out[name] = p.hash }
        return out
    }

    fun protectedNames(stored: String?): Set<String> = parseProtections(stored).keys

    fun hasPassword(stored: String?, name: String): Boolean =
        parseProtections(stored).keys.any { it.equals(name.trim(), ignoreCase = true) }

    /** The protection mode for [name], or null when the category isn't protected. */
    fun modeOf(stored: String?, name: String): CategoryProtectionMode? =
        parseProtections(stored).entries
            .firstOrNull { it.key.equals(name.trim(), ignoreCase = true) }?.value?.mode

    /** Names of every protected category whose mode is [mode]. */
    fun namesWithMode(stored: String?, mode: CategoryProtectionMode): Set<String> =
        parseProtections(stored).filterValues { it.mode == mode }.keys

    /**
     * Sets or updates a category's protection. A non-blank [password] (re)hashes;
     * a blank/null [password] keeps the existing hash (used when only [mode] changes).
     * Does nothing if the category has no existing hash and no new password.
     */
    fun setProtection(
        stored: String?,
        name: String,
        password: String?,
        mode: CategoryProtectionMode
    ): String {
        val map = parseProtections(stored)
        val existing = map.entries.firstOrNull { it.key.equals(name.trim(), ignoreCase = true) }?.value
        val newHash = if (!password.isNullOrEmpty()) hash(password) else existing?.hash
        map.keys.filter { it.equals(name.trim(), ignoreCase = true) }.toList().forEach { map.remove(it) }
        if (newHash != null) map[name.trim()] = Protection(newHash, mode)
        return serializeProtections(map)
    }

    /** Removes any protection entry for [name]. */
    fun removeProtection(stored: String?, name: String): String {
        val map = parseProtections(stored)
        map.keys.filter { it.equals(name.trim(), ignoreCase = true) }.toList().forEach { map.remove(it) }
        return serializeProtections(map)
    }

    /** True when [password] matches the stored hash for [name]. */
    fun verify(stored: String?, name: String, password: String): Boolean {
        val expected = parseProtections(stored).entries
            .firstOrNull { it.key.equals(name.trim(), ignoreCase = true) }
            ?.value?.hash ?: return false
        return expected.equals(hash(password), ignoreCase = true)
    }

    /** Renames any hidden entry for [oldName] to [newName]. */
    fun renameHidden(stored: String?, oldName: String, newName: String): String {
        val set = parseHidden(stored).toMutableSet()
        val had = set.removeAll { it.equals(oldName.trim(), ignoreCase = true) }
        if (had) set.add(newName.trim())
        return serializeHidden(set)
    }

    /** Renames a protection entry (hash + mode preserved) from [oldName] to [newName]. */
    fun renamePassword(stored: String?, oldName: String, newName: String): String {
        val map = parseProtections(stored)
        val old = map.keys.firstOrNull { it.equals(oldName.trim(), ignoreCase = true) }
        if (old != null) {
            val p = map.remove(old)
            if (p != null) map[newName.trim()] = p
        }
        return serializeProtections(map)
    }

    fun removeAll(hiddenStored: String?, passwordStored: String?, name: String):
        Pair<String, String> {
        val hidden = parseHidden(hiddenStored).filterNot {
            it.equals(name.trim(), ignoreCase = true)
        }
        return serializeHidden(hidden) to removeProtection(passwordStored, name)
    }

    // ---- Single-password helpers (used by the management-screen lock) ----

    /** SHA-256 hex of [password], for storing a standalone password hash. */
    fun hashPassword(password: String): String = hash(password)

    /** True when [password] matches a stored standalone hash. */
    fun verifyHash(storedHash: String?, password: String): Boolean =
        !storedHash.isNullOrEmpty() && storedHash.equals(hash(password), ignoreCase = true)

    private fun hash(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(password.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
