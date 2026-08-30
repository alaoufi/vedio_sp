package com.myvideolibrary.app.data.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Automatic, encrypted backups written to a **user-chosen external folder** (a SAF
 * tree). Because the folder lives outside app-private storage, its backups survive
 * uninstalling or clearing the app — the failure mode that can otherwise wipe the
 * whole library. A daily [AutoBackupWorker] and an on-demand [backupNow] both write
 * an encrypted `.mvlbak` there, keeping the most recent [KEEP] files.
 *
 * Config (enabled flag, folder uri, last-run info) is kept in ordinary prefs; the
 * backup password is kept in [EncryptedSharedPreferences]. Losing the password only
 * means re-entering it — unlike the database key, it is never catastrophic.
 */
@Singleton
class AutoBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupManager: BackupManager,
    private val videoDao: com.myvideolibrary.app.data.local.dao.VideoDao
) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val secure by lazy {
        runCatching {
            val master = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context, SECURE_PREFS, master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrNull()
    }

    val isEnabled: Boolean get() = prefs.getBoolean(K_ENABLED, false)
    val folderUri: Uri? get() = prefs.getString(K_TREE, null)?.let(Uri::parse)
    val lastBackupAt: Long get() = prefs.getLong(K_LAST, 0L)
    val lastResult: String? get() = prefs.getString(K_RESULT, null)

    /** Turns on automatic backups to [treeUri], encrypted with [password]. */
    fun enable(treeUri: Uri, password: String) {
        prefs.edit()
            .putBoolean(K_ENABLED, true)
            .putString(K_TREE, treeUri.toString())
            .apply()
        runCatching { secure?.edit()?.putString(K_PASSWORD, password)?.apply() }
        schedule()
    }

    fun disable() {
        prefs.edit().putBoolean(K_ENABLED, false).apply()
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK) }
    }

    private fun schedule() {
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun password(): String? =
        runCatching { secure?.getString(K_PASSWORD, null) }.getOrNull()

    /**
     * Self-heal after a wipe: if auto-backup is on and the library is EMPTY but the
     * external folder still holds a backup, restore the newest one automatically so
     * categories, titles and settings come back without the user doing anything.
     * A no-op when data is present or the backup can't be read. Returns true if it
     * restored something.
     */
    suspend fun autoRestoreIfEmpty(): Boolean {
        if (!isEnabled) return false
        val tree = folderUri ?: return false
        val pw = password() ?: return false
        return runCatching {
            if (videoDao.getAllOnce().isNotEmpty()) return@runCatching false
            val dir = DocumentFile.fromTreeUri(context, tree) ?: return@runCatching false
            val newest = dir.listFiles()
                .filter { it.isFile && it.name?.endsWith(".mvlbak") == true }
                .maxByOrNull { it.lastModified() } ?: return@runCatching false
            backupManager.restore(newest.uri, pw) > 0
        }.getOrDefault(false)
    }

    /** Writes one encrypted backup into the chosen folder, rotating old ones. */
    suspend fun backupNow(): kotlin.Result<Unit> = kotlin.runCatching {
        val tree = folderUri ?: error("No backup folder chosen")
        val pw = password() ?: error("No backup password set")
        val dir = DocumentFile.fromTreeUri(context, tree) ?: error("Backup folder unavailable")
        if (!dir.canWrite()) error("Backup folder is not writable")

        val bytes = backupManager.exportBytes(pw)
        val name = "mvl_backup_${System.currentTimeMillis()}.mvlbak"
        val file = dir.createFile(MIME, name) ?: error("Could not create the backup file")
        context.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) }
            ?: error("Could not write the backup file")

        rotate(dir)
        prefs.edit()
            .putLong(K_LAST, System.currentTimeMillis())
            .putString(K_RESULT, RESULT_OK)
            .apply()
    }.onFailure { e ->
        prefs.edit().putString(K_RESULT, e.message ?: "failed").apply()
    }

    /** Keeps only the newest [KEEP] backup files in [dir]. */
    private fun rotate(dir: DocumentFile) {
        dir.listFiles()
            .filter { it.name?.endsWith(".mvlbak") == true }
            .sortedByDescending { it.lastModified() }
            .drop(KEEP)
            .forEach { runCatching { it.delete() } }
    }

    companion object {
        const val RESULT_OK = "ok"
        private const val WORK = "auto_backup"
        private const val KEEP = 5
        private const val MIME = "application/octet-stream"
        private const val PREFS = "mvl_backup_prefs"
        private const val SECURE_PREFS = "mvl_backup_secure"
        private const val K_ENABLED = "enabled"
        private const val K_TREE = "tree_uri"
        private const val K_LAST = "last_backup_at"
        private const val K_RESULT = "last_result"
        private const val K_PASSWORD = "password"
    }
}
