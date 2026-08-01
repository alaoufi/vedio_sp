package com.myvideolibrary.app.ui.imageeditor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myvideolibrary.app.R
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.data.repository.VideoRepository
import com.myvideolibrary.app.databinding.ActivityImageEditorBinding
import com.myvideolibrary.app.util.StorageManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/** Simple image editor: crop, hide (pixelate), add text, and extract text (OCR). */
@AndroidEntryPoint
class ImageEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageEditorBinding

    @Inject lateinit var videoRepository: VideoRepository
    @Inject lateinit var storageManager: StorageManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val path = intent.getStringExtra(EXTRA_PATH)
        val bitmap = path?.let { loadBitmap(it) }
        if (bitmap == null) {
            Toast.makeText(this, R.string.playback_error, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        binding.editorView.setImage(bitmap)

        binding.btnCrop.setOnClickListener {
            if (!binding.editorView.applyCrop()) needSelection()
        }
        binding.btnHide.setOnClickListener {
            if (!binding.editorView.applyHide()) needSelection()
        }
        binding.btnText.setOnClickListener { promptAddText() }
        binding.btnOcr.setOnClickListener { runOcr() }
        binding.btnSave.setOnClickListener { save() }
    }

    /** Decodes the image, downsampling very large files to stay within memory. */
    private fun loadBitmap(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        val maxDim = 2560
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, opts)
    }

    private fun needSelection() {
        Toast.makeText(this, R.string.editor_need_selection, Toast.LENGTH_SHORT).show()
    }

    private fun promptAddText() {
        val input = EditText(this).apply { hint = getString(R.string.editor_text) }
        val colors = intArrayOf(Color.WHITE, Color.YELLOW, Color.RED, Color.BLACK)
        val colorNames = arrayOf(
            getString(R.string.color_white), getString(R.string.color_yellow),
            getString(R.string.color_red), getString(R.string.color_black)
        )
        var chosen = 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.editor_text)
            .setView(input)
            .setSingleChoiceItems(colorNames, 0) { _, which -> chosen = which }
            .setPositiveButton(R.string.editor_add) { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) binding.editorView.addText(t, colors[chosen])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runOcr() {
        val region = binding.editorView.selectionOrWholeBitmap() ?: return
        val dialog = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.editor_ocr_running)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val text = withContext(Dispatchers.Default) {
                TessOcr.recognize(this@ImageEditorActivity, region)
            }
            dialog.dismiss()
            if (text.isBlank()) {
                Toast.makeText(this@ImageEditorActivity, R.string.editor_ocr_empty, Toast.LENGTH_SHORT).show()
            } else {
                val clip = ContextCompat.getSystemService(this@ImageEditorActivity, ClipboardManager::class.java)
                clip?.setPrimaryClip(ClipData.newPlainText("ocr", text))
                showOcrResult(text)
            }
        }
    }

    private fun showOcrResult(text: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.editor_ocr_copied)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun save() {
        val out = binding.editorView.exportBitmap()
        if (out == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val f = storageManager.newVideoFile("jpg")
                FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                f
            }
            addToLibrary(file, out.width, out.height)
            Toast.makeText(this@ImageEditorActivity, R.string.editor_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private suspend fun addToLibrary(file: File, width: Int, height: Int) {
        videoRepository.addVideo(
            VideoEntity(
                title = getString(R.string.edit_image) + " " + file.nameWithoutExtension,
                thumbnailPath = file.absolutePath,
                localPath = file.absolutePath,
                source = "other",
                mediaType = "image",
                fileSize = file.length(),
                width = width,
                height = height,
                createdDate = System.currentTimeMillis(),
                contentHash = "${file.length()}_edited"
            )
        )
    }

    companion object {
        private const val EXTRA_PATH = "extra_path"
        fun intent(context: Context, imagePath: String): Intent =
            Intent(context, ImageEditorActivity::class.java).putExtra(EXTRA_PATH, imagePath)
    }
}
