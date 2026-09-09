package com.myvideolibrary.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Downloads the newest APK and hands it to the system installer, so an update
 * installs in-app instead of bouncing the user to a browser. Because every build
 * is signed with the same committed debug key, the update installs over the
 * current app in place — user data is preserved.
 */
object ApkUpdateInstaller {

    /** Subfolder under cacheDir where the downloaded APK is staged. */
    private const val DIR = "updates"
    private const val FILE = "update.apk"

    /**
     * Streams [url] to a cache file, reporting progress 0..100 (or -1 when the
     * total size is unknown). Returns the downloaded file, or null on failure.
     */
    suspend fun download(
        context: Context,
        client: OkHttpClient,
        url: String,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            val out = File(dir, FILE)
            if (out.exists()) out.delete()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "VideoLibrary")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                val total = body.contentLength()
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var downloaded = 0L
                        var lastPct = -1
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val pct = ((downloaded * 100) / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            } else {
                                onProgress(-1)
                            }
                        }
                        output.flush()
                    }
                }
            }
            out
        }.getOrNull()
    }

    /** Android O+ gates APK installs behind a per-app "unknown sources" permission. */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Opens the system screen to grant this app permission to install packages. */
    fun openUnknownSourcesSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** Launches the system installer on the downloaded [apk]. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
