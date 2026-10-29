package com.myvideolibrary.app.provider.snapchat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.provider.model.ProviderErrorType
import com.myvideolibrary.app.provider.model.ProviderException
import com.myvideolibrary.app.provider.model.ResolvedVideo
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Selects a public story snap by ID, never an unrelated JSON-LD preview. */
internal object SnapchatStoryParser {
    fun resolve(html: String, finalUrl: String, sourceUrl: String): ResolvedVideo? {
        val script = Regex(
            """<script\b[^>]*\bid\s*=\s*["']__NEXT_DATA__["'][^>]*>(.*?)</script\s*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)?.groupValues?.get(1) ?: return null
        val root = runCatching { JsonParser.parseString(script).asJsonObject }.getOrElse {
            throw ProviderException(ProviderErrorType.EXTRACTION_FAILED, "Could not read Snapchat story data", it)
        }
        val props = root.obj("props")?.obj("pageProps") ?: return null
        val path = finalUrl.toHttpUrlOrNull()?.pathSegments.orEmpty()
        val pathId = when {
            path.firstOrNull()?.startsWith("@") == true && path.size == 2 -> path[1]
            path.firstOrNull() == "u" -> path.getOrNull(2)
            else -> null
        }
        val requested = pathId?.takeIf { it.isNotBlank() } ?: root.obj("query")?.str("snapId")
        val story = props.obj("story")
        if (story == null && requested == null) return null // Spotlight uses Open Graph.
        val rows = story?.get("snapList")?.takeIf { it.isJsonArray }?.asJsonArray
        if (requested.isNullOrBlank()) {
            throw ProviderException(ProviderErrorType.INVALID_LINK, "Share a specific public Snapchat snap")
        }
        val snap = rows?.firstOrNull { row ->
            row.isJsonObject && row.asJsonObject.obj("snapId")?.str("value") == requested
        }?.asJsonObject ?: throw ProviderException(
            ProviderErrorType.NOT_FOUND, "The shared Snapchat snap is unavailable or expired"
        )
        val urls = snap.obj("snapUrls")
        val media = urls?.str("mediaUrl")?.takeIf { it.toHttpUrlOrNull() != null }
            ?: throw ProviderException(ProviderErrorType.EXTRACTION_FAILED, "No media for the shared Snapchat snap")
        val kind = runCatching { snap.get("snapMediaType")?.asInt }.getOrNull()
        if (kind != 0 && kind != 1) {
            throw ProviderException(ProviderErrorType.UNSUPPORTED, "Unsupported Snapchat media type")
        }
        val image = kind == 0
        return ResolvedVideo(
            source = VideoSource.SNAPCHAT,
            sourceUrl = sourceUrl,
            title = snap.str("snapTitle") ?: story?.str("storyTitle") ?: if (image) "Snapchat photo" else "Snapchat video",
            directUrl = media,
            thumbnailUrl = if (image) media else urls.obj("mediaPreviewUrl")?.str("value"),
            isImage = image
        )
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }
        ?.let { runCatching { it.asString }.getOrNull() }?.takeIf { it.isNotBlank() }
}
