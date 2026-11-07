package io.github.jayeshb.walletsmssync

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class GroqApiTest {
    @Test
    fun requestsStrictExtractionAndReadsOnlyCompletionContent() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"choices":[{"finish_reason":"stop","message":{"content":"{\"kind\":\"EXPENSE\"}","reasoning":"ignored"}}]}"""
            ))
            val api = GroqApi("synthetic-key", server.url("/openai/v1/chat/completions").toString())
            assertEquals("""{"kind":"EXPENSE"}""", api.infer("synthetic SMS"))
            val request = server.takeRequest()
            val payload = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals("Bearer synthetic-key", request.getHeader("Authorization"))
            assertEquals(GroqApi.MODEL, payload["model"].asString)
            val format = payload.getAsJsonObject("response_format").getAsJsonObject("json_schema")
            assertTrue(format["strict"].asBoolean)
            val schema = format.getAsJsonObject("schema")
            assertEquals(schema.getAsJsonObject("properties").size(), schema.getAsJsonArray("required").size())
        }
    }

    @Test
    fun preservesCooldownAndKeepsProviderErrorBodyPrivate() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "45").setBody("private payload"))
            val api = GroqApi("synthetic-key", server.url("/").toString())
            val failure = runCatching { api.infer("synthetic SMS") }.exceptionOrNull() as GroqFailure
            assertEquals(45L, failure.retryAfterSeconds)
            assertFalse(failure.message.orEmpty().contains("private payload"))
            assertEquals(1, server.requestCount)
        }
    }
}
