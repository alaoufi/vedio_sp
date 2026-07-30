package com.myvideolibrary.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.myvideolibrary.app.data.local.entity.SavedSearchEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedSearchDao {

    @Query("SELECT * FROM saved_searches ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<SavedSearchEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SavedSearchEntity): Long

    @Query("DELETE FROM saved_searches WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Replaces any existing saved search with the same name (case-insensitive). */
    @Query("DELETE FROM saved_searches WHERE name = :name COLLATE NOCASE")
    suspend fun deleteByName(name: String)
}
