package com.myvideolibrary.app.util

/** Serialises and applies the user-defined category display order. */
object CategoryOrder {

    private const val SEP = "\n"

    fun parse(stored: String?): List<String> =
        stored?.split(SEP)?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    fun serialize(order: List<String>): String = order.joinToString(SEP)

    /**
     * Orders [present] categories by [stored] preference: known names first in
     * their saved order, then any new names appended alphabetically.
     */
    fun apply(present: List<String>, stored: String?): List<String> {
        val order = parse(stored)
        val presentSet = present.toSet()
        val ordered = order.filter { it in presentSet }
        val rest = present.filter { it !in ordered }.sortedBy { it.lowercase() }
        return ordered + rest
    }
}
