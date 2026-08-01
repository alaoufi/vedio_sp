package com.myvideolibrary.app.ui.help

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.myvideolibrary.app.databinding.ActivityHelpBinding

/**
 * Shows the bundled usage guide as an offline HTML page, in the language that
 * best matches the app's current locale (falling back to English).
 */
class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.webView.settings.apply {
            // The guide is a bundled, offline asset (no network/remote content), and
            // the 3D interactive guide uses JS for the card tilt/flip animations.
            @android.annotation.SuppressLint("SetJavaScriptEnabled")
            javaScriptEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        binding.webView.loadUrl("file:///android_asset/guide/${guideFile()}")
    }

    /** Picks the guide file for the current UI language, defaulting to English. */
    private fun guideFile(): String {
        val lang = resources.configuration.locales[0].language
        return if (lang in AVAILABLE) "$lang.html" else "en.html"
    }

    private companion object {
        val AVAILABLE = setOf(
            "ar", "en", "tr", "es", "de", "fil", "fr", "in", "id", "it", "ms", "hi", "bn", "fa", "ru"
        )
    }
}
