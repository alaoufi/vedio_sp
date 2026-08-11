package com.myvideolibrary.app.data.backup

import android.net.Uri
import androidx.room.withTransaction
import com.google.gson.Gson
import com.myvideolibrary.app.data.local.AppDatabase
import com.myvideolibrary.app.data.local.entity.FolderEntity
import com.myvideolibrary.app.data.local.entity.SettingsEntity
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.util.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/** Serializable snapshot of the library's logical data. */
data class BackupData(
    val version: Int = 1,
    val exportedAt: Long,
    val videos: List<VideoEntity>,
    val folders: List<FolderEntity>,
    val settings: SettingsEntity?
)

/**
 * Manual, password-encrypted local backup and restore.
 *
 * The backup contains the library's logical records (metadata, folders, settings)
 * as JSON, encrypted with AES-256-GCM using a key derived from the user's password
 * via PBKDF2. There is no cloud component — the file stays on the device unless the
 * user explicitly shares it.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val database: AppDatabase,
    private val storageManager: StorageManager,
    private val gson: Gson
) {

    /** Exports an encrypted backup file and returns it. */
    suspend fun export(password: String): File = withContext(Dispatchers.IO) {
        val data = BackupData(
            exportedAt = System.currentTimeMillis(),
            videos = database.videoDao().getAllOnce(),
            folders = database.folderDao().getAllOnce(),
            settings = database.settingsDao().get()
        )

        val json = gson.toJson(data).toByteArray(Charsets.UTF_8)
        val encrypted = encrypt(json, password)

        val outFile = File(storageManager.backupsDir, "mvl_backup_${data.exportedAt}.mvlbak")
        outFile.writeBytes(encrypted)
        outFile
    }

    /** Restores from an encrypted backup [uri]; returns the number of videos imported. */
    suspend fun restore(uri: Uri, password: String): Int = withContext(Dispatchers.IO) {
        val encrypted = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Cannot read backup file")
        val json = decrypt(encrypted, password)
        val data = gson.fromJson(String(json, Charsets.UTF_8), BackupData::class.java)
        require(data.version == BACKUP_VERSION) { "Unsupported backup version" }

        // Restore folders first and remember old→new id mapping so video folder
        // references stay valid (they are foreign keys).
        database.withTransaction {
            val folderIdMap = HashMap<Long, Long>()
            data.folders.forEach { folder ->
                val newId = database.folderDao().insert(folder.copy(id = 0))
                folderIdMap[folder.id] = newId
            }
            data.videos.forEach { video ->
                val remappedFolder = video.folderId?.let { folderIdMap[it] }
                database.videoDao().insert(video.copy(id = 0, folderId = remappedFolder))
            }
            data.settings?.let {
                database.settingsDao().insert(it)
                database.settingsDao().update(it)
            }
        }
        data.videos.size
    }

    // ---- Crypto ----

    private fun encrypt(plain: ByteArray, password: String): ByteArray {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val cipherText = cipher.doFinal(plain)

        // Layout: MAGIC | salt | iv | cipherText
        return MAGIC + salt + iv + cipherText
    }

    private fun decrypt(data: ByteArray, password: String): ByteArray {
        require(data.size > MAGIC.size + SALT_LEN + IV_LEN) { "Corrupt backup" }
        require(data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Not a valid backup" }

        var offset = MAGIC.size
        val salt = data.copyOfRange(offset, offset + SALT_LEN); offset += SALT_LEN
        val iv = data.copyOfRange(offset, offset + IV_LEN); offset += IV_LEN
        val cipherText = data.copyOfRange(offset, data.size)

        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(cipherText)
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec: KeySpec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERS, KEY_BITS)
        val key = factory.generateSecret(spec).encoded
        return SecretKeySpec(key, "AES")
    }

    companion object {
        private val MAGIC = "MVLBAK01".toByteArray(Charsets.US_ASCII)
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val SALT_LEN = 16
        private const val IV_LEN = 12
        private const val TAG_BITS = 128
        private const val KEY_BITS = 256
        private const val PBKDF2_ITERS = 120_000
        private const val BACKUP_VERSION = 1
    }
}
