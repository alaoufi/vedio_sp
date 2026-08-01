package com.myvideolibrary.app.data.repository

import com.myvideolibrary.app.data.local.dao.SettingsDao
import com.myvideolibrary.app.data.local.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface SettingsRepository {
    fun observeSettings(): Flow<SettingsEntity>
    suspend fun getSettings(): SettingsEntity
    suspend fun update(transform: (SettingsEntity) -> SettingsEntity)
}

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val settingsDao: SettingsDao
) : SettingsRepository {

    override fun observeSettings(): Flow<SettingsEntity> =
        settingsDao.observe().map { it ?: SettingsEntity() }

    override suspend fun getSettings(): SettingsEntity {
        ensureExists()
        return settingsDao.get() ?: SettingsEntity()
    }

    override suspend fun update(transform: (SettingsEntity) -> SettingsEntity) {
        ensureExists()
        val current = settingsDao.get() ?: SettingsEntity()
        settingsDao.update(transform(current))
    }

    private suspend fun ensureExists() {
        // Insert with IGNORE creates the singleton row exactly once.
        settingsDao.insert(SettingsEntity())
    }
}
