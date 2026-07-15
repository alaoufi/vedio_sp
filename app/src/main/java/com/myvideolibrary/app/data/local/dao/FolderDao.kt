package com.myvideolibrary.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.myvideolibrary.app.data.local.entity.FolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    @Delete
    suspend fun delete(folder: FolderEntity)

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: Long): FolderEntity?

    /** All folders in one shot (used by backup export). */
    @Query("SELECT * FROM folders ORDER BY created_date ASC")
    suspend fun getAllOnce(): List<FolderEntity>

    @Query("SELECT COUNT(*) FROM folders WHERE name = :name COLLATE NOCASE")
    suspend fun countByName(name: String): Int
}
