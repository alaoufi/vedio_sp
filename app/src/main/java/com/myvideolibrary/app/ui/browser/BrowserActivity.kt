package com.myvideolibrary.app.ui.browser

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.myvideolibrary.app.R
import com.myvideolibrary.app.data.model.DownloadKind
import com.myvideolibrary.app.databinding.ActivityBrowserBinding
import com.myvideolibrary.app.download.DownloadManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A general-purpose in-app browser that watches the page's network traffic and
 * detects downloadable media (progressive video/audio streams). When it finds
 * some, a button lets the user save it to the library — no site-specific code.
 */
@AndroidEntryPoint
class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding

    @Inject lateinit var downloadManager: DownloadManager

    /** Detected media: dedup key (URL without query) -> freshest full URL. */
    private val found = LinkedHashMap<String, String>()

    private val rescanHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val rescan = object : Runnable {
        override fun run() {
            binding.webView.evaluateJavascript(SCAN_JS) { onScanResult(it) }
            // Keep re-scanning: many players attach the <video> src seconds later.
            rescanHandler.postDelayed(this, RESCAN_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.closeButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.reloadButton.setOnClickListener { binding.webView.reload() }
        binding.downloadFab.setOnClickListener { showFoundMedia() }
        maybeShowIntro()

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
        }

        binding.webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progress.isVisible = newProgress in 1..99
                binding.progress.progress = newProgress
            }
        }
        binding.webView.webViewClient = SniffingClient()

        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                go(binding.urlInput.text?.toString().orEmpty()); true
            } else false
        }

        // Open a specific page when asked (e.g. an Instagram/Snapchat post to sniff),
        // otherwise the default start page.
        val start = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() } ?: START_URL
        binding.webView.loadUrl(start)
    }

    /** Loads a typed address, or searches for it if it isn't a URL. */
    /** Explains the browser's purpose the first time it's opened. */
    private fun maybeShowIntro() {
        val prefs = getSharedPreferences("browser", MODE_PRIVATE)
        if (prefs.getBoolean("intro_shown", false)) return
        prefs.edit().putBoolean("intro_shown", true).apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.browser_intro_title)
            .setMessage(R.string.browser_intro_message)
            .setPositiveButton(R.string.got_it, null)
            .show()
    }

    private fun go(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://www.google.com/search?q=" + android.net.Uri.encode(t)
        }
        binding.webView.loadUrl(url)
        binding.urlInput.clearFocus()
    }

    // ---- Media detection ----

    private inner class SniffingClient : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            request?.url?.toString()?.let { maybeMedia(it) }
            return null
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            binding.urlInput.setText(url)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            // Read <video>/<source>/og:video now, then keep re-scanning for a while
            // to catch players that attach their source only after user interaction.
            rescanHandler.removeCallbacks(rescan)
            rescanHandler.post(rescan)
        }
    }

    /** Records [url] if it looks like a progressive media stream. */
    private fun maybeMedia(url: String) {
        if (url.startsWith("blob:") || url.startsWith("data:")) return
        val lower = url.substringBefore('?').lowercase()
        val ext = MEDIA_EXT.firstOrNull { lower.endsWith(it) }
        val isMedia = ext != null ||
            url.contains("mime=video", true) || url.contains("mime=audio", true) ||
            url.contains("videoplayback", true) || url.contains(".m3u8", true)
        if (!isMedia) return
        // Dedup by the URL without its query, so the same file seen with a rotating
        // token isn't listed many times; keep the freshest full URL for downloading.
        val key = url.substringBefore('?')
        synchronized(found) {
            val isNew = !found.containsKey(key)
            found[key] = url
            if (isNew) runOnUiThread { updateFab() }
        }
    }

    private fun onScanResult(json: String?) {
        // evaluateJavascript hands back a JSON-encoded string of a JSON array.
        val raw = json?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: return
        if (!raw.startsWith("[")) return
        runCatching { org.json.JSONArray(raw) }.getOrNull()?.let { arr ->
            for (i in 0 until arr.length()) maybeMedia(arr.optString(i))
        }
    }

    private fun updateFab() {
        val n = synchronized(found) { found.size }
        binding.downloadFab.isVisible = n > 0
        binding.downloadFab.text = getString(R.string.browser_download_count, n)
    }

    private fun showFoundMedia() {
        val entries = synchronized(found) { found.entries.reversed().toList() }
        if (entries.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.browser_no_media, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // Label each hit by its file name (from the dedup key); download the full URL.
        val labels = entries.map { it.key.substringAfterLast('/').ifBlank { it.key.take(40) } }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.browser_found_title)
            .setItems(labels) { _, which -> download(entries[which].value) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun download(mediaUrl: String) {
        val page = binding.webView.url ?: mediaUrl
        val title = binding.webView.title?.takeIf { it.isNotBlank() }
            ?: mediaUrl.substringBefore('?').substringAfterLast('/').ifBlank { "video" }
        val audio = AUDIO_EXT.any { mediaUrl.substringBefore('?').lowercase().endsWith(it) }
        lifecycleScope.launch {
            runCatching {
                downloadManager.enqueue(
                    title = title,
                    source = com.myvideolibrary.app.data.model.VideoSource.OTHER.id,
                    sourceUrl = page,
                    directUrl = mediaUrl,
                    kind = if (audio) DownloadKind.AUDIO_ONLY else DownloadKind.FULL
                )
            }
            android.widget.Toast.makeText(
                this@BrowserActivity, R.string.download_started, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        rescanHandler.removeCallbacks(rescan)
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val START_URL = "https://www.google.com"
        private const val RESCAN_MS = 2500L

        /** Opens the sniffing browser directly on [url] (used for IG/Snap links). */
        fun intent(context: android.content.Context, url: String): android.content.Intent =
            android.content.Intent(context, BrowserActivity::class.java).putExtra(EXTRA_URL, url)

        val MEDIA_EXT = listOf(".mp4", ".webm", ".mkv", ".mov", ".m4v", ".m3u8",
            ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav")
        val AUDIO_EXT = listOf(".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav")

        // Collects <video>/<source> src and og:video that may not appear as requests.
        const val SCAN_JS = """
            (function(){
              var out=[];
              try{
                document.querySelectorAll('video').forEach(function(v){
                  if(v.currentSrc) out.push(v.currentSrc);
                  if(v.src) out.push(v.src);
                  v.querySelectorAll('source').forEach(function(s){ if(s.src) out.push(s.src); });
                });
                var m=document.querySelector('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"]');
                if(m&&m.content) out.push(m.content);
              }catch(e){}
              return JSON.stringify(out);
            })();
        """
    }
}
