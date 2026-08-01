package com.myvideolibrary.app.ui.imageeditor

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * Offline OCR via Tesseract, recognizing Arabic + English. Language data ships
 * in assets/tessdata and is copied to internal storage on first use.
 */
object TessOcr {

    private val LANGS = listOf("ara.traineddata", "eng.traineddata")

    /** Copies the bundled traineddata to filesDir/tessdata; returns the data path. */
    private fun ensureData(context: Context): String {
        val dir = File(context.filesDir, "tessdata")
        if (!dir.exists()) dir.mkdirs()
        for (name in LANGS) {
            val out = File(dir, name)
            if (!out.exists() || out.length() == 0L) {
                context.assets.open("tessdata/$name").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
        }
        // TessBaseAPI expects the parent of the "tessdata" folder.
        return context.filesDir.absolutePath
    }

    /** Blocking recognition — call off the main thread. Returns trimmed text. */
    fun recognize(context: Context, bitmap: Bitmap): String {
        val dataPath = ensureData(context)
        val tess = TessBaseAPI()
        return try {
            if (!tess.init(dataPath, "ara+eng")) return ""
            tess.setImage(bitmap)
            tess.getUTF8Text()?.trim().orEmpty()
        } catch (e: Exception) {
            ""
        } finally {
            runCatching { tess.recycle() }
        }
    }
}
