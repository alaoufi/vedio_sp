package com.myvideolibrary.app.provider.tiktok

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TikTokProviderRecoveryTest {
    @Test fun `rate limited photo link waits and recovers instead of exhausting hosts immediately`() = runBlocking {
        val starts = mutableListOf<Long>()
        val attempts = mutableMapOf<String, Int>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            var request = chain.request()
            val body = if (request.url.host.endsWith("tikwm.com")) {
                val now = System.nanoTime()
                val key = request.url.toString()
                val count = (attempts[key] ?: 0) + 1
                attempts[key] = count
                val tooSoon = count == 1 || now - starts.last() < 1_000_000_000L
                starts.add(now)
                if (tooSoon) """{"code":-1,"msg":"Free Api Limit: 1 request/second."}"""
                else """{"code":0,"data":{"title":"Photo with music","images":["https://cdn.example.test/photo.jpg"],"music":"https://cdn.example.test/music.mp3","play":"https://cdn.example.test/music.mp3"}}"""
            } else {
                request = request.newBuilder().url("https://www.tiktok.com/@fixture/photo/123").build()
                "<html></html>"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = TikTokProvider(client, Gson()).resolve("https://vt.tiktok.com/Fixture/")
        assertTrue(result.isSlideshow)
        assertEquals(1, result.imageUrls.size)
        assertEquals("https://cdn.example.test/music.mp3", result.audioUrl)
        assertTrue(starts.size >= 2)
        assertTrue(starts[1] - starts[0] >= 1_000_000_000L)
    }
}
