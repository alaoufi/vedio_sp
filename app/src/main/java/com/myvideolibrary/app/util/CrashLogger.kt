package com.myvideolibrary.app.util

import android.content.Context
import android.os.Build
import com.myvideolibrary.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Records the last uncaught crash to a local file so a user can view or share it
 * — no server, no analytics SDK, nothing leaves the device unless the user
 * chooses to share it. Delegates to the platform handler afterwards, so the
 * system still shows/handles the crash normally.
 */
object CrashLogger {

    private fun logFile(context: Context): File =
        File(File(context.filesDir, "crash").apply { mkdirs() }, "last_crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(app, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("App: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
            appendLine(
                "Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            )
            appendLine("Thread: ${thread.name}")
            appendLine("----")
            append(trace)
        }
        logFile(context).writeText(text)
    }

    /** The last recorded crash, or null if none. */
    fun lastCrash(context: Context): String? =
        logFile(context).takeIf { it.exists() && it.length() > 0 }?.readText()

    fun clear(context: Context) {
        runCatching { logFile(context).delete() }
    }
}
