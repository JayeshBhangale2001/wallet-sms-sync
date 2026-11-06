package io.github.jayeshb.walletsmssync.core

import org.junit.Assert.*
import org.junit.Test

class WalletContractTest {
    private val t = Transaction(Kind.EXPENSE, "123456.78", accountKey = "HDFC:CARD:5678", merchant = "Test Shop", occurredAt = "2026-01-01T00:00:00Z", precision = Precision.SECOND)
    @Test fun `expenses negative income positive and exact decimal JSON`() {
        val r = WalletPayload.create(t, "source", categoryId = "food", marker = "local-id")[0].asJsonObject
        assertEquals("-123456.78", r.getAsJsonObject("amount")["value"].asString)
        assertEquals("INR", r.getAsJsonObject("amount")["currencyCode"].asString)
        assertEquals("food", r["categoryId"].asString); assertFalse(r.has("transfer")); assertFalse(r.has("accountKey"))
        assertEquals("wallet-sms-sync:local-id", r["note"].asString)
        assertEquals("123456.78", WalletPayload.create(t.copy(kind = Kind.INCOME), "source", marker = "id")[0].asJsonObject.getAsJsonObject("amount")["value"].asString)
    }
    @Test fun `one transfer creates paired mirror`() {
        val payload = WalletPayload.create(t.copy(kind = Kind.TRANSFER), "source", "destination", marker = "id")
        assertEquals(1, payload.size())
        val transfer = payload[0].asJsonObject.getAsJsonObject("transfer")
        assertEquals("new", transfer["pairingMode"].asString); assertEquals("destination", transfer["accountId"].asString)
    }
    @Test fun `invalid approval rejected`() {
        listOf(t.copy(amount = "0"), t.copy(amount = "-1"), t.copy(amount = "1.001"), t.copy(kind = Kind.REPAYMENT)).forEach {
            assertThrows(IllegalArgumentException::class.java) { WalletPayload.create(it, "source", marker = "id") }
        }
        assertThrows(IllegalArgumentException::class.java) { WalletPayload.create(t.copy(kind = Kind.TRANSFER), "source", "source", marker = "id") }
    }
    @Test fun `success requires ID and transfer mirror`() {
        val body = """{"results":[{"inputIndex":0,"success":true,"id":"record-1"}]}"""
        assertEquals(WriteState.SYNCED, WriteResponse.interpret(200, body, false).state)
        assertEquals(WriteState.UNCERTAIN, WriteResponse.interpret(200, body, true).state)
        val paired = """{"results":[{"inputIndex":0,"success":true,"id":"record-1","createdMirrorRecordId":"record-2"}]}"""
        assertEquals("record-2", WriteResponse.interpret(200, paired, true).mirrorId)
    }
    @Test fun `partial failure examines input index`() {
        val body = """{"results":[{"inputIndex":1,"success":true,"id":"wrong"},{"inputIndex":0,"success":false,"errorType":"client_error"}]}"""
        assertEquals(WriteState.REVIEW, WriteResponse.interpret(207, body, false).state)
    }
    @Test fun `auth and throttling preserve queue`() {
        assertEquals(WriteState.AUTH, WriteResponse.interpret(401, "", false).state)
        assertEquals(WriteState.AUTH, WriteResponse.interpret(403, "", false).state)
        assertEquals(WriteState.RETRY, WriteResponse.interpret(429, "", false).state)
        assertEquals(WriteState.RETRY, WriteResponse.interpret(409, "", false).state)
    }
    @Test fun `server errors and malformed success never blindly retry`() {
        listOf(500, 502, 200).forEach { assertEquals(WriteState.UNCERTAIN, WriteResponse.interpret(it, "{}", false).state) }
        assertEquals(WriteState.UNCERTAIN, WriteResponse.interpret(500, """{"results":[{"inputIndex":0,"success":false,"errorType":"server_error","id":"maybe-written"}]}""", true).state)
    }
    @Test fun `structured JSON round trips without losing precision`() { assertEquals(t, Json.decode(Json.encode(t))) }
    @Test fun `SMS sender and heading fit notes without losing reconciliation marker`() {
        val title = SmsTitles.create("AD-HDFCBK","Transaction Successful! INR 10 spent", "BOTH")
        val note = WalletPayload.create(t,"source",marker = "uuid",smsTitle = title)[0].asJsonObject["note"].asString
        assertTrue(note.contains("AD-HDFCBK")); assertTrue(note.contains("Transaction Successful!")); assertTrue(note.endsWith("wallet-sms-sync:uuid"))
        val long = WalletPayload.create(t,"source",marker = "uuid",smsTitle = "title\n".repeat(100))[0].asJsonObject["note"].asString
        assertTrue(long.length <= 255); assertFalse(long.contains('\n'))
    }
    @Test fun `transfer evidence compares numeric amounts across decimal scales`() {
        assertTrue(sameAmount("10.00", "10")); assertFalse(sameAmount("10.01", "10")); assertFalse(sameAmount(null, null))
    }
}
