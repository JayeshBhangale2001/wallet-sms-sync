package io.github.jayeshb.walletsmssync

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CloudAiTest {
    @Test
    fun requestsStrictExtractionAndReadsOnlyCompletionContent() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"choices":[{"finish_reason":"stop","message":{"content":"{\"kind\":\"EXPENSE\"}","reasoning":"ignored"}}]}"""
            ))
            val api = CloudAi(AiProvider.GROQ, "synthetic-key", server.url("/openai/v1/chat/completions").toString())
            assertEquals("""{"kind":"EXPENSE"}""", api.infer("synthetic SMS"))
            val request = server.takeRequest()
            val payload = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals("Bearer synthetic-key", request.getHeader("Authorization"))
            assertEquals(AiProvider.GROQ.model, payload["model"].asString)
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
            val api = CloudAi(AiProvider.GROQ, "synthetic-key", server.url("/").toString())
            val failure = runCatching { api.infer("synthetic SMS") }.exceptionOrNull() as AiFailure
            assertEquals(45L, failure.retryAfterSeconds)
            assertFalse(failure.message.orEmpty().contains("private payload"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun usesXaiResponsesWithStrictSchemaAndStorageOff() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"{\"kind\":\"EXPENSE\"}"}]}]}"""
            ))
            val provider = AiProvider.XAI
            assertEquals("https://api.x.ai/v1/responses", provider.endpoint)
            val api = CloudAi(provider, "synthetic-xai-key", server.url("/v1/responses").toString())
            assertEquals("""{"kind":"EXPENSE"}""", api.infer("synthetic SMS"))
            val request = server.takeRequest()
            val payload = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals("/v1/responses", request.path)
            assertEquals("Bearer synthetic-xai-key", request.getHeader("Authorization"))
            assertEquals("grok-4.3", payload["model"].asString)
            assertEquals("synthetic SMS", payload["input"].asString)
            assertFalse(payload["store"].asBoolean)
            assertEquals("none", payload.getAsJsonObject("reasoning")["effort"].asString)
            val format = payload.getAsJsonObject("text").getAsJsonObject("format")
            assertEquals("json_schema", format["type"].asString)
            assertTrue(format["strict"].asBoolean)
        }
    }

    @Test
    fun distinguishesInvalidKeysFromModelAccessErrors() = runBlocking {
        MockWebServer().use { server ->
            val api = CloudAi(AiProvider.XAI, "synthetic-key", server.url("/").toString())
            for ((code, explanation) in listOf(401 to "rejected the key", 403 to "model permissions", 404 to "model access")) {
                server.enqueue(MockResponse().setResponseCode(code).setBody("private payload"))
                val failure = runCatching { api.infer("synthetic SMS") }.exceptionOrNull() as AiFailure
                assertTrue(failure.authFailed)
                assertTrue(failure.message.orEmpty().contains(explanation))
                assertFalse(failure.message.orEmpty().contains("private payload"))
            }
        }
    }
}
