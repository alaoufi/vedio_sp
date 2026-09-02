package com.myvideolibrary.app.provider.web

import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.provider.model.ProviderErrorType
import com.myvideolibrary.app.provider.model.ProviderException
import com.myvideolibrary.app.provider.model.ResolvedVideo
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Best-effort resolver for platforms (Instagram, Snapchat) that publish the media
 * on their public share page via Open Graph tags. It fetches the page with a
 * browser User-Agent — following share-link redirects — and reads
 * `og:video` / `og:image` / `og:title`, with a couple of embedded-JSON fallbacks.
 *
 * These platforms actively gate content behind login, so extraction can fail for
 * private or login-walled posts. That is surfaced as a typed [ProviderException]
 * (EXTRACTION_FAILED), never a crash. Only the public page is fetched; nothing
 * else leaves the device and the media is stored locally.
 */
object OpenGraphResolver {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    fun resolve(
        client: OkHttpClient,
        url: String,
        source: VideoSource,
        fallbackTitle: String
    ): ResolvedVideo {
        val html = fetch(client, url)

        val video = meta(html, "og:video:secure_url")
            ?: meta(html, "og:video:url")
            ?: meta(html, "og:video")
            ?: meta(html, "twitter:player:stream")
            ?: json(html, "video_url")
            ?: json(html, "videoUrl")
            ?: json(html, "playbackUrl")
            // Schema.org VideoObject (Snapchat Spotlight and others embed this).
            ?: json(html, "contentUrl")
            ?: throw ProviderException(
                ProviderErrorType.EXTRACTION_FAILED,
                "No downloadable media found — the post may be private or login-only"
            )

        val image = meta(html, "og:image") ?: json(html, "thumbnail_url")
        val title = meta(html, "og:title")?.takeIf { it.isNotBlank() } ?: fallbackTitle

        return ResolvedVideo(
            source = source,
            sourceUrl = url,
            title = decode(title),
            directUrl = decode(video),
            thumbnailUrl = image?.let { decode(it) }
        )
    }

    private fun fetch(client: OkHttpClient, url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                if (resp.code == 404) {
                    throw ProviderException(ProviderErrorType.NOT_FOUND, "Not found")
                }
                if (!resp.isSuccessful) {
                    throw ProviderException(ProviderErrorType.NETWORK, "HTTP ${resp.code}")
                }
                return resp.body?.string()
                    ?: throw ProviderException(ProviderErrorType.EXTRACTION_FAILED, "Empty page")
            }
        } catch (e: IOException) {
            throw ProviderException(ProviderErrorType.NETWORK, "Network error", e)
        }
    }

    /** Reads a `<meta property|name="prop" content="…">` tag, either attribute order. */
    private fun meta(html: String, prop: String): String? {
        val esc = Regex.escape(prop)
        Regex(
            "<meta[^>]+(?:property|name)=[\"']$esc[\"'][^>]+content=[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE
        ).find(html)?.let { return it.groupValues[1] }
        return Regex(
            "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"']$esc[\"']",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)
    }

    /** Reads a `"key":"…"` value from embedded JSON, unescaping URL characters. */
    private fun json(html: String, key: String): String? {
        val m = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]+)\"").find(html) ?: return null
        return m.groupValues[1]
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .takeIf { it.startsWith("http") }
    }

    private fun decode(s: String): String =
        s.replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&#38;", "&")
            .replace("&#x2F;", "/")
            .replace("&quot;", "\"")
}
