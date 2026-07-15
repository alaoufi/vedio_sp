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
    val category: String? = null,
    val sourceFilter: SourceFilter = SourceFilter.ALL,
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
        category?.let {
            where.append(" AND category = ?")
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
        }

        val sql = "SELECT * FROM videos $where $orderBy"
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }
}
