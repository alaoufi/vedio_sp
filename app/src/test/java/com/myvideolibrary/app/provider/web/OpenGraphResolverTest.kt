package com.myvideolibrary.app.provider.web

import com.myvideolibrary.app.data.model.VideoSource
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenGraphResolverTest {
    @Test fun `share redirects that return video do not decode binary as HTML`() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request().newBuilder().url("https://cdn.example.test/clip.mp4").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112).toResponseBody("video/mp4".toMediaType())).build()
        }.build()
        val resolved = OpenGraphResolver.resolve(client, "https://www.snapchat.com/spotlight/test", VideoSource.SNAPCHAT, "Snapchat video")
        assertEquals("https://cdn.example.test/clip.mp4", resolved.directUrl)
        assertEquals("https://www.snapchat.com/spotlight/test", resolved.sourceUrl)
    }

    @Test fun `public Snapchat embedded metadata extracts the video URL and entities`() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""<script type="application/ld+json">{"contentUrl":"https:\/\/cdn.example.test\/clip.mp4?a=1\u0026b=2"}</script>"""
                    .toResponseBody("text/html".toMediaType())).build()
        }.build()
        val resolved = OpenGraphResolver.resolve(client, "https://www.snapchat.com/spotlight/test", VideoSource.SNAPCHAT, "Snapchat video")
        assertEquals("https://cdn.example.test/clip.mp4?a=1&b=2", resolved.directUrl)
    }
}
