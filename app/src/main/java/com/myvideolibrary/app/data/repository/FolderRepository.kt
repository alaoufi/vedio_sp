package com.myvideolibrary.app.data.repository

import com.myvideolibrary.app.data.local.dao.FolderDao
import com.myvideolibrary.app.data.local.dao.FolderVideoCount
import com.myvideolibrary.app.data.local.dao.VideoDao
import com.myvideolibrary.app.data.local.entity.FolderEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

interface FolderRepository {
    fun observeFolders(): Flow<List<FolderEntity>>
    fun observeFolderCounts(): Flow<List<FolderVideoCount>>
    suspend fun getFolder(id: Long): FolderEntity?
    /** Returns the new folder id, or -1 if a folder with that name already exists. */
    suspend fun createFolder(name: String, color: Int?): Long
    suspend fun renameFolder(folder: FolderEntity, newName: String)
    suspend fun deleteFolder(folder: FolderEntity)
}

@Singleton
class FolderRepositoryImpl @Inject constructor(
    private val folderDao: FolderDao,
    private val videoDao: VideoDao
) : FolderRepository {

    override fun observeFolders(): Flow<List<FolderEntity>> = folderDao.observeAll()

    override fun observeFolderCounts(): Flow<List<FolderVideoCount>> =
        videoDao.observeFolderCounts()

    override suspend fun getFolder(id: Long): FolderEntity? = folderDao.getById(id)

    override suspend fun createFolder(name: String, color: Int?): Long {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || folderDao.countByName(trimmed) > 0) return -1
        return folderDao.insert(
            FolderEntity(
                name = trimmed,
                color = color,
                createdDate = System.currentTimeMillis()
            )
        )
    }

    override suspend fun renameFolder(folder: FolderEntity, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isNotEmpty()) folderDao.update(folder.copy(name = trimmed))
    }

    override suspend fun deleteFolder(folder: FolderEntity) = folderDao.delete(folder)
}
