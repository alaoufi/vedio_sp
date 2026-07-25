package com.myvideolibrary.app.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.myvideolibrary.app.data.model.SortOrder
import com.myvideolibrary.app.data.model.SourceFilter
import com.myvideolibrary.app.data.model.VideoSource

/**
 * Immutable description of the current library filter + sort. Converted to a
 * parameterised SQL statement for the Paging [RawQuery][androidx.room.RawQuery].
 */
data class LibraryQuery(
    val search: String? = null,
    val folderId: Long? = null,
    val favoritesOnly: Boolean = false,
    /** Show only videos in any of these categories; empty means all categories. */
    val categories: Set<String> = emptySet(),
    /**
     * Categories hidden or password-protected: their videos are excluded from the
     * general view. Ignored when [categories] explicitly selects a category, so a
     * hidden/locked category can still be opened deliberately.
     */
    val excludedCategories: Set<String> = emptySet(),
    /** Show videos from any of these sources; empty means all sources. */
    val sourceFilters: Set<SourceFilter> = emptySet(),
    /** When true show only locked (protected) videos; when false hide them. */
    val protectedOnly: Boolean = false,
    /** Any of "video"/"audio"/"image"; empty means all types. */
    val mediaTypes: Set<String> = emptySet(),
    val sortOrder: SortOrder = SortOrder.DATE_ADDED_DESC
) {

    /** Builds a safe, parameter-bound query for [VideoDao.pagingSource]. */
    fun toSupportQuery(): SupportSQLiteQuery {
        val args = mutableListOf<Any>()
        val where = StringBuilder("WHERE 1 = 1")

        search?.trim()?.takeIf { it.isNotEmpty() }?.let {
            where.append(" AND (title LIKE ? OR description LIKE ? OR tags LIKE ?)")
            val like = "%$it%"
            args.add(like); args.add(like); args.add(like)
        }
        folderId?.let {
            where.append(" AND folder_id = ?")
            args.add(it)
        }
        if (favoritesOnly) {
            where.append(" AND is_favorite = 1")
        }
        // Locked videos live in a separate protected view, hidden from the library.
        where.append(if (protectedOnly) " AND is_locked = 1" else " AND is_locked = 0")
        if (categories.isNotEmpty()) {
            val placeholders = categories.joinToString(", ") { "?" }
            where.append(" AND category IN ($placeholders)")
            categories.forEach { args.add(it) }
        } else if (excludedCategories.isNotEmpty()) {
            // General view: drop videos in hidden/locked categories (keep uncategorised).
            val placeholders = excludedCategories.joinToString(", ") { "?" }
            where.append(
                " AND (category IS NULL OR TRIM(category) COLLATE NOCASE NOT IN ($placeholders))"
            )
            excludedCategories.forEach { args.add(it.trim()) }
        }
        if (mediaTypes.isNotEmpty()) {
            val placeholders = mediaTypes.joinToString(", ") { "?" }
            where.append(" AND media_type IN ($placeholders)")
            mediaTypes.forEach { args.add(it) }
        }
        // Multi-select source: OR the chosen buckets together. Empty = all sources.
        if (sourceFilters.isNotEmpty()) {
            val clauses = mutableListOf<String>()
            if (SourceFilter.TIKTOK in sourceFilters) {
                clauses.add("source = ?"); args.add(VideoSource.TIKTOK.id)
            }
            if (SourceFilter.YOUTUBE in sourceFilters) {
                clauses.add("source = ?"); args.add(VideoSource.YOUTUBE.id)
            }
            if (SourceFilter.OTHER in sourceFilters) {
                clauses.add("source NOT IN (?, ?)")
                args.add(VideoSource.TIKTOK.id); args.add(VideoSource.YOUTUBE.id)
            }
            if (clauses.isNotEmpty()) {
                where.append(" AND (").append(clauses.joinToString(" OR ")).append(")")
            }
        }

        val orderBy = when (sortOrder) {
            SortOrder.NAME_ASC -> "ORDER BY title COLLATE NOCASE ASC"
            SortOrder.NAME_DESC -> "ORDER BY title COLLATE NOCASE DESC"
            SortOrder.DATE_ADDED_DESC -> "ORDER BY created_date DESC"
            SortOrder.DATE_ADDED_ASC -> "ORDER BY created_date ASC"
            SortOrder.DURATION_DESC -> "ORDER BY duration DESC"
            SortOrder.DURATION_ASC -> "ORDER BY duration ASC"
            SortOrder.SIZE_DESC -> "ORDER BY file_size DESC"
            SortOrder.SIZE_ASC -> "ORDER BY file_size ASC"
            // Group by category (uncategorised last), then title within each group.
            SortOrder.CATEGORY_ASC ->
                "ORDER BY (category IS NULL OR TRIM(category) = '') ASC, " +
                    "category COLLATE NOCASE ASC, title COLLATE NOCASE ASC"
        }

        val sql = "SELECT * FROM videos $where $orderBy"
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }
}
