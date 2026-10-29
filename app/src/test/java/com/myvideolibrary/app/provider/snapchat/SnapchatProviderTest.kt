package com.myvideolibrary.app.provider.snapchat

import com.myvideolibrary.app.provider.model.ProviderErrorType
import com.myvideolibrary.app.provider.model.ProviderException
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SnapchatProviderTest {
    private fun client(requested: String, mediaType: Int = 1, expired: Boolean = false): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val row = if (expired) "" else """,{"snapId":{"value":"chosen"},"snapMediaType":$mediaType,"snapUrls":{"mediaUrl":"https://cdn.example.test/chosen","mediaPreviewUrl":{"value":"https://cdn.example.test/preview.jpg"}}}"""
            val html = """
                <script type="application/ld+json">{"contentUrl":"https://cdn.example.test/unrelated-preview.jpg"}</script>
                <script id="__NEXT_DATA__" type="application/json">{"query":{"snapId":"$requested"},"props":{"pageProps":{"story":{"snapList":[
                {"snapId":{"value":"other"},"snapMediaType":1,"snapUrls":{"mediaUrl":"https://cdn.example.test/other.mp4"}}
                $row]}}}}</script>
            """.trimIndent()
            Response.Builder().request(chain.request().newBuilder().url("https://www.snapchat.com/@example/$requested").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(html.toResponseBody("text/html".toMediaType())).build()
        }.build()

    @Test fun `short story share selects the requested snap instead of a preview or first story item`() = runBlocking {
        val result = SnapchatProvider(client("chosen")).resolve("https://snapchat.com/t/share")
        assertEquals("https://cdn.example.test/chosen", result.directUrl)
        assertEquals("https://snapchat.com/t/share", result.sourceUrl)
        assertEquals("https://cdn.example.test/preview.jpg", result.thumbnailUrl)
        assertFalse(result.isImage)
    }

    @Test fun `expired requested snap must not download another story item`() = runBlocking {
        try {
            SnapchatProvider(client("chosen", expired = true)).resolve("https://snapchat.com/t/share")
            fail("Expired snap must not become a different download")
        } catch (error: ProviderException) {
            assertEquals(ProviderErrorType.NOT_FOUND, error.type)
        }
    }

    @Test fun `a photo snap is explicitly returned as an image rather than a broken video`() = runBlocking {
        val result = SnapchatProvider(client("chosen", mediaType = 0)).resolve("https://snapchat.com/t/share")
        assertEquals("https://cdn.example.test/chosen", result.directUrl)
        assertTrue(result.isImage)
        assertEquals("https://cdn.example.test/chosen", result.thumbnailUrl)
    }

    @Test fun `regular spotlight video metadata remains supported`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request().newBuilder().url("https://www.snapchat.com/@example/spotlight/example").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""<meta property="og:video" content="https://cdn.example.test/spotlight.mp4">
                    <script id="__NEXT_DATA__" type="application/json">{"query":{"username":"@example","snapID":"example"},"props":{"pageProps":{"spotlightFeed":{}}}}</script>"""
                    .toResponseBody("text/html".toMediaType())).build()
        }.build()
        assertEquals("https://cdn.example.test/spotlight.mp4",
            SnapchatProvider(client).resolve("https://www.snapchat.com/spotlight/example").directUrl)
    }
}
