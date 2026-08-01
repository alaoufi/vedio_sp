package com.myvideolibrary.app.util

/** Serialises and applies the user-defined category display order. */
object CategoryOrder {

    private const val SEP = "\n"

    fun parse(stored: String?): List<String> =
        stored?.split(SEP)?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    fun serialize(order: List<String>): String = order.joinToString(SEP)

    /**
     * The full known category list: every name saved in [stored] (in the user's
     * order — these persist even with no videos yet, e.g. freshly added ones),
     * followed by any category present on a video but not yet in the order,
     * alphabetically.
     */
    fun apply(present: List<String>, stored: String?): List<String> {
        val order = parse(stored).distinct()
        val rest = present.filter { it !in order }.sortedBy { it.lowercase() }
        return order + rest
    }
}
