package com.myvideolibrary.app.util

import java.security.MessageDigest

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

    // ---- Password-protected categories ("name\thash" per line) ----

    /** Parsed map of category name -> password hash. */
    fun parsePasswords(stored: String?): Map<String, String> {
        if (stored.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (line in stored.split(LINE)) {
            val parts = line.split(FIELD)
            if (parts.size >= 2) {
                val name = parts[0].trim()
                val hash = parts[1].trim()
                if (name.isNotEmpty() && hash.isNotEmpty()) out[name] = hash
            }
        }
        return out
    }

    fun serializePasswords(map: Map<String, String>): String =
        map.entries
            .filter { it.key.trim().isNotEmpty() && it.value.isNotEmpty() }
            .joinToString(LINE) { "${it.key.trim()}$FIELD${it.value}" }

    fun protectedNames(stored: String?): Set<String> = parsePasswords(stored).keys

    fun hasPassword(stored: String?, name: String): Boolean =
        parsePasswords(stored).keys.any { it.equals(name.trim(), ignoreCase = true) }

    /** Sets (password != null) or clears (password == null) a category's password. */
    fun setPassword(stored: String?, name: String, password: String?): String {
        val map = LinkedHashMap(parsePasswords(stored))
        map.keys.filter { it.equals(name.trim(), ignoreCase = true) }.forEach { map.remove(it) }
        if (!password.isNullOrEmpty()) map[name.trim()] = hash(password)
        return serializePasswords(map)
    }

    /** True when [password] matches the stored hash for [name]. */
    fun verify(stored: String?, name: String, password: String): Boolean {
        val expected = parsePasswords(stored).entries
            .firstOrNull { it.key.equals(name.trim(), ignoreCase = true) }
            ?.value ?: return false
        return expected.equals(hash(password), ignoreCase = true)
    }

    /** Renames any hidden/password entry for [oldName] to [newName]. */
    fun renameHidden(stored: String?, oldName: String, newName: String): String {
        val set = parseHidden(stored).toMutableSet()
        val had = set.removeAll { it.equals(oldName.trim(), ignoreCase = true) }
        if (had) set.add(newName.trim())
        return serializeHidden(set)
    }

    fun renamePassword(stored: String?, oldName: String, newName: String): String {
        val map = LinkedHashMap(parsePasswords(stored))
        val old = map.keys.firstOrNull { it.equals(oldName.trim(), ignoreCase = true) }
        if (old != null) {
            val hash = map.remove(old)
            if (hash != null) map[newName.trim()] = hash
        }
        return serializePasswords(map)
    }

    fun removeAll(hiddenStored: String?, passwordStored: String?, name: String):
        Pair<String, String> {
        val hidden = parseHidden(hiddenStored).filterNot {
            it.equals(name.trim(), ignoreCase = true)
        }
        val pw = parsePasswords(passwordStored).filterKeys {
            !it.equals(name.trim(), ignoreCase = true)
        }
        return serializeHidden(hidden) to serializePasswords(pw)
    }

    private fun hash(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(password.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
