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
    /** Authoritative version source: a tiny JSON asset rewritten on every build. */
    private const val VERSION_JSON_URL =
        "https://github.com/alaoufi/vedio_sp/releases/download/apk-latest/version.json"
    /** Stable direct link to the newest APK. */
    const val APK_URL =
        "https://github.com/alaoufi/vedio_sp/releases/download/apk-latest/vedio_lb.apk"

    data class Result(val latestBuild: Int, val latestVersion: String)

    /** Three distinct outcomes so a failed check is never mistaken for "up to date". */
    sealed interface Outcome {
        /** A strictly newer build is available. */
        data class Available(val result: Result) : Outcome
        /** The check succeeded and this build is current. */
        data object UpToDate : Outcome
        /** The check could not complete (no network, API error, parse failure). */
        data object Failed : Outcome
    }

    /** Matches the build number in "…v1.0.124" or "1.0.124-debug". */
    private val VERSION_RE = Regex("""1\.0\.(\d+)""")

    /**
     * Backwards-compatible helper: returns a [Result] only when a newer build
     * exists, and null otherwise (up-to-date *or* failed — indistinguishable).
     * Prefer [checkOutcome] so failures can be reported instead of silently
     * masquerading as "up to date".
     */
    suspend fun check(currentBuild: Int, client: OkHttpClient): Result? =
        (checkOutcome(currentBuild, client) as? Outcome.Available)?.result

    /**
     * @param currentBuild this build's number (BuildConfig.VERSION_CODE).
     * @return [Outcome.Available] when a strictly newer build exists,
     *         [Outcome.UpToDate] when the check ran and this build is current,
     *         or [Outcome.Failed] on any network/parse error.
     */
    suspend fun checkOutcome(currentBuild: Int, client: OkHttpClient): Outcome =
        withContext(Dispatchers.IO) {
            // Primary: the version.json asset (rewritten on every build, so it never
            // lags behind the way a release *name* can). Fallback: the Releases API,
            // reading the build number out of the release name. Two independent
            // sources so a hiccup in one doesn't hide a real update.
            latestBuildFromVersionJson(client)?.let { latest ->
                return@withContext verdict(latest, currentBuild)
            }
            latestBuildFromReleaseApi(client)?.let { latest ->
                return@withContext verdict(latest, currentBuild)
            }
            Outcome.Failed
        }

    private fun verdict(latest: Int, currentBuild: Int): Outcome =
        if (latest > currentBuild) Outcome.Available(Result(latest, "1.0.$latest"))
        else Outcome.UpToDate

    /** Reads the "build" number from the direct version.json asset; null on failure. */
    private fun latestBuildFromVersionJson(client: OkHttpClient): Int? = runCatching {
        val request = Request.Builder()
            .url(VERSION_JSON_URL)
            .header("User-Agent", "VideoLibrary")
            .build()
        val body = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        } ?: return null
        val root = JsonParser.parseString(body).asJsonObject
        root.get("build")?.takeIf { !it.isJsonNull }?.asInt
            ?: root.get("version")?.takeIf { !it.isJsonNull }?.asString
                ?.let { VERSION_RE.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    }.getOrNull()

    /** Reads the build number from the release NAME via the GitHub API; null on failure. */
    private fun latestBuildFromReleaseApi(client: OkHttpClient): Int? = runCatching {
        val request = Request.Builder()
            .url(RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "VideoLibrary")
            .build()
        val body = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        } ?: return null
        val root = JsonParser.parseString(body).asJsonObject
        val name = root.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        VERSION_RE.find(name)?.groupValues?.get(1)?.toIntOrNull()
    }.getOrNull()
}
