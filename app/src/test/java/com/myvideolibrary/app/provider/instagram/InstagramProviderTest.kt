package com.myvideolibrary.app.provider.instagram

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class InstagramProviderTest {
    @Test fun `relay can return the video directly instead of an HTML preview`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val relay = chain.request().url.host == "kkinstagram.com"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (relay) 200 else 404).message("fixture")
                .body(if (relay) byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112)
                    .toResponseBody("video/mp4".toMediaType())
                    else "<html></html>".toResponseBody("text/html".toMediaType()))
                .build()
        }.build()
        val result = InstagramProvider(client).resolve("https://www.instagram.com/reel/Example/")
        assertEquals("https://kkinstagram.com/reel/Example/", result.directUrl)
        assertEquals("https://www.instagram.com/reel/Example/", result.sourceUrl)
    }

    @Test fun `relay accepts the standard og video url property`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val relay = chain.request().url.host == "kkinstagram.com"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (relay) 200 else 404).message("fixture")
                .body("""<meta property="og:video:url" content="https://cdn.example.test/video.mp4?a=1&amp;b=2">"""
                    .toResponseBody("text/html".toMediaType())).build()
        }.build()
        val result = InstagramProvider(client).resolve("https://www.instagram.com/reel/Example/")
        assertEquals("https://cdn.example.test/video.mp4?a=1&b=2", result.directUrl)
    }
}
