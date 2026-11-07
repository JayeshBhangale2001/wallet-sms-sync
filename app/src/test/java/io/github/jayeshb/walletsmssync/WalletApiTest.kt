package io.github.jayeshb.walletsmssync

import io.github.jayeshb.walletsmssync.core.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class WalletApiTest {
    @Test fun `one transfer request creates a source and mirror`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"results":[{"inputIndex":0,"success":true,"id":"source","createdMirrorRecordId":"mirror"}]}"""))
            val t = Transaction(Kind.TRANSFER, "10.00", accountKey = "HDFC:ACCOUNT:1234", merchant = null,
                occurredAt = "2026-01-01T00:00:00Z", precision = Precision.DATE)
            val reply = WalletApi("test", server.url("/").toString()).create(
                Entry("local", "fp", Json.encode(t), sourceId = "synthetic", sourceAccount = "bank", targetAccount = "card"))
            val record = com.google.gson.JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonArray.single().asJsonObject
            assertEquals("bank", record["accountId"].asString)
            assertEquals("card", record.getAsJsonObject("transfer")["accountId"].asString)
            assertEquals("new", record.getAsJsonObject("transfer")["pairingMode"].asString)
            assertEquals("mirror", WriteResponse.interpret(reply.code, reply.body, true).mirrorId)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `catalog paginates and excludes archived accounts`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"accounts":[{"id":"a","name":"Cash","currencyCode":"INR"}],"nextOffset":1}"""))
            server.enqueue(MockResponse().setBody("""{"accounts":[{"id":"b","name":"Archived","archived":true}]}"""))
            val result = WalletApi("synthetic-test-token", server.url("/").toString()).catalog("accounts")
            assertEquals(listOf("a"), result.map { it.id })
            assertEquals("Bearer synthetic-test-token", server.takeRequest().getHeader("Authorization"))
            assertTrue(server.takeRequest().path!!.contains("offset=1"))
        }
    }
    @Test fun `post uses array signed amount and marker`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"results":[{"inputIndex":0,"success":true,"id":"r"}]}"""))
            val t = Transaction(Kind.EXPENSE, "12.34", accountKey = "IDFC:CARD:1234", merchant = "Sample", occurredAt = "2026-01-01T00:00:00Z", precision = Precision.SECOND)
            val reply = WalletApi("test", server.url("/").toString()).create(Entry("local", "fp", Json.encode(t), sourceId = "synthetic", sourceAccount = "a",smsTitle = "AD-HDFCBK · Transaction Successful!"))
            assertEquals(200, reply.code)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("agentHints=true"))
            val body = request.body.readUtf8()
            assertTrue(body.startsWith("[")); assertTrue(body.contains("-12.34")); assertTrue(body.contains("wallet-sms-sync:local")); assertFalse(body.contains("IDFC:CARD"))
            assertTrue(body.contains("AD-HDFCBK")); assertTrue(body.contains("Transaction Successful!"))
        }
    }
    @Test fun `rate limit retry-after is available to scheduler`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("{}"))
            val problem = assertThrows(ApiProblem::class.java) { WalletApi("test", server.url("/").toString()).catalog("accounts") }
            assertEquals(429, problem.code); assertEquals(120L, problem.delaySeconds)
        }
    }
    @Test fun `reconciliation queries exact unique marker`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"records":[{"id":"r","accountId":"a"}]}"""))
            assertEquals(1, WalletApi("test", server.url("/").toString()).findMarker("local").size)
            assertEquals("contains.wallet-sms-sync:local", server.takeRequest().requestUrl!!.queryParameter("note"))
        }
    }
    @Test fun `redirect does not forward token or write`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/elsewhere"))
            assertThrows(ApiProblem::class.java) { WalletApi("test", server.url("/").toString()).catalog("accounts") }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `disconnected create is not automatically retried`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val t = Transaction(Kind.EXPENSE, "10.00", accountKey = "IDFC:CARD:1234", merchant = "Synthetic", occurredAt = "2026-01-01T00:00:00Z", precision = Precision.SECOND)
            assertThrows(java.io.IOException::class.java) {
                WalletApi("test", server.url("/").toString()).create(Entry("local", "fp", Json.encode(t), sourceId = "synthetic", sourceAccount = "a"))
            }
            assertEquals(1, server.requestCount)
        }
    }
}
