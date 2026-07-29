package com.myvideolibrary.app.data.repository

/**
 * Canonical format for the comma-separated [tags] column: trimmed, non-empty
 * tokens joined by a plain comma with no surrounding spaces (e.g. "trip,2024,kids").
 *
 * Keeping storage delimiter-clean lets the library query match a whole tag safely
 * with `(',' || tags || ',') LIKE '%,'||tag||',%'` — no partial-word false hits.
 */
object TagFormat {

    /** Splits a stored/raw tags string into individual trimmed tags. */
    fun split(raw: String?): List<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    /**
     * Normalises raw user input (any spacing, duplicates, blanks) to the canonical
     * comma-joined form, de-duplicated case-insensitively. Returns null when empty
     * so the column is cleared rather than storing "".
     */
    fun normalise(raw: String?): String? {
        val tokens = split(raw).distinctBy { it.lowercase() }
        return tokens.joinToString(",").takeIf { it.isNotEmpty() }
    }
}
