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
    val sourceFilter: SourceFilter = SourceFilter.ALL,
    /** When true show only locked (protected) videos; when false hide them. */
    val protectedOnly: Boolean = false,
    /** "video"/"audio"/"image", or null for all types. */
    val mediaType: String? = null,
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
        }
        mediaType?.let {
            where.append(" AND media_type = ?")
            args.add(it)
        }
        when (sourceFilter) {
            SourceFilter.TIKTOK -> {
                where.append(" AND source = ?")
                args.add(VideoSource.TIKTOK.id)
            }
            SourceFilter.YOUTUBE -> {
                where.append(" AND source = ?")
                args.add(VideoSource.YOUTUBE.id)
            }
            SourceFilter.OTHER -> {
                where.append(" AND source NOT IN (?, ?)")
                args.add(VideoSource.TIKTOK.id)
                args.add(VideoSource.YOUTUBE.id)
            }
            SourceFilter.ALL -> Unit
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
