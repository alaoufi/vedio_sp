package com.myvideolibrary.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.myvideolibrary.app.data.local.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(settings: SettingsEntity)

    @Update
    suspend fun update(settings: SettingsEntity)

    @Query("SELECT * FROM settings WHERE id = :id")
    fun observe(id: Int = SettingsEntity.SINGLETON_ID): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = :id")
    suspend fun get(id: Int = SettingsEntity.SINGLETON_ID): SettingsEntity?
}
