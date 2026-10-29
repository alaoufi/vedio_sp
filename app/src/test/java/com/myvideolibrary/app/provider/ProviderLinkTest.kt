package com.myvideolibrary.app.provider

import com.google.gson.Gson
import com.myvideolibrary.app.provider.youtube.YouTubeProvider
import com.myvideolibrary.app.provider.snapchat.SnapchatProvider
import com.myvideolibrary.app.provider.instagram.InstagramProvider
import com.myvideolibrary.app.provider.tiktok.TikTokProvider
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ProviderLinkTest {
    private val client = OkHttpClient()
    private val youtube = YouTubeProvider(client, Gson())

    @Test fun `YouTube accepts public video share forms`() {
        listOf(
            "https://youtube.com/watch?v=aqz-KE-bpKQ",
            "https://youtu.be/aqz-KE-bpKQ?si=share",
            "https://www.youtube.com/shorts/aqz-KE-bpKQ",
            "https://www.youtube.com/live/aqz-KE-bpKQ?si=share",
            "https://www.youtube.com/embed/aqz-KE-bpKQ",
            "https://www.youtube-nocookie.com/embed/aqz-KE-bpKQ",
            "https://music.youtube.com/watch?v=aqz-KE-bpKQ"
        ).forEach { assertTrue(it, youtube.canHandle(it)) }
    }

    @Test fun `unrelated hosts and query text do not select a social provider`() {
        val providers = listOf(youtube, InstagramProvider(client), SnapchatProvider(client), TikTokProvider(client, Gson()))
        listOf(
            "https://example.com/?url=https://youtube.com/watch?v=aqz-KE-bpKQ",
            "https://notinstagram.com/reel/test/",
            "https://snapchat.com.example.com/spotlight/test",
            "https://tiktok.com.example.com/@test/video/123"
        ).forEach { url -> providers.forEach { assertFalse("${it.source}: $url", it.canHandle(url)) } }
    }
}
