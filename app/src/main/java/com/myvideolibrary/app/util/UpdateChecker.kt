package com.myvideolibrary.app.util

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Checks GitHub Releases for a newer build. The app ships from a single rolling
 * release (tag "apk-latest") whose name carries the version, e.g.
 * "My Video Library — v1.0.124". We parse the build number out of that name and
 * compare it with this build's own number.
 *
 * This is the only "phone-home": an optional version check. The app stays
 * offline-first — nothing about the library is ever sent anywhere.
 */
object UpdateChecker {

    private const val RELEASE_API =
        "https://api.github.com/repos/alaoufi/vedio_sp/releases/tags/apk-latest"
    /** Stable direct link to the newest APK. */
    const val APK_URL =
        "https://github.com/alaoufi/vedio_sp/releases/download/apk-latest/vedio_lb.apk"

    data class Result(val latestBuild: Int, val latestVersion: String)

    /** Matches the build number in "…v1.0.124" or "1.0.124-debug". */
    private val VERSION_RE = Regex("""1\.0\.(\d+)""")

    /**
     * @param currentBuild this build's number (BuildConfig.VERSION_CODE).
     * @return a [Result] when a strictly newer build is available, else null
     *         (including on any network/parse error — a failed check is silent).
     */
    suspend fun check(currentBuild: Int, client: OkHttpClient): Result? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(RELEASE_API)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "VideoLibrary")
                    .build()
                val body = client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    resp.body?.string()
                } ?: return@runCatching null

                val root = JsonParser.parseString(body).asJsonObject
                val name = root.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                val latest = VERSION_RE.find(name)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@runCatching null

                if (latest > currentBuild) Result(latest, "1.0.$latest") else null
            }.getOrNull()
        }
}
