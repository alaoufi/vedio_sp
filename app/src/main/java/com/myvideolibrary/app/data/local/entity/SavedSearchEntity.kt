package com.myvideolibrary.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A named snapshot of the library's filter + sort state, so a user can re-apply a
 * combination of filters in one tap. Set-valued filters (sources, categories,
 * media types, tags) are stored newline-joined — names never contain a newline,
 * which keeps them delimiter-safe without escaping.
 */
@Entity(tableName = "saved_searches")
data class SavedSearchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "created_date") val createdDate: Long,
    val search: String? = null,
    @ColumnInfo(name = "favorites_only") val favoritesOnly: Boolean = false,
    @ColumnInfo(name = "protected_mode") val protectedMode: Boolean = false,
    val sources: String? = null,
    val categories: String? = null,
    @ColumnInfo(name = "media_types") val mediaTypes: String? = null,
    val tags: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: String? = null
) {
    companion object {
        /** Joins a set of values for storage, or null when empty. */
        fun join(values: Collection<String>): String? =
            values.joinToString("\n").takeIf { it.isNotEmpty() }

        /** Splits a stored value back into its individual entries. */
        fun split(value: String?): List<String> =
            value?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()
    }
}
