package io.github.jayeshb.walletsmssync.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class SmsParserTest {
    private val parser = SmsParser(ZoneId.of("Asia/Kolkata"))
    private val received = 1780000000000L
    private fun parse(s: String) = parser.parse(s, received)!!
    private val purchase = "Happy Shopping! INR 125.40 spent on your IDFC FIRST Bank Credit Card ending XX1234 at SAMPLE CAFE on 05 JUN 2026 at 12:37 PM Avbl Limit: INR 98765.43 If not done by you, call support"
    @Test fun `idfc extracts purchase rather than balance`() {
        val t = parse(purchase)
        assertEquals("125.40", t.amount); assertEquals("SAMPLE CAFE", t.merchant)
        assertEquals("IDFC:CARD:1234", t.accountKey); assertEquals("2026-06-05T07:07:00Z", t.occurredAt)
        assertEquals(Precision.MINUTE, t.precision); assertNull(t.reviewReason)
    }
    @Test fun `all idfc lead-ins use same rule`() {
        listOf("Transaction Successful!", "Delicious Purchase!", "All Stocked Up!").forEach {
            assertEquals("125.40", parse(purchase.replace("Happy Shopping!", it)).amount)
        }
    }
    @Test fun `midnight and noon are interpreted correctly`() {
        assertEquals("2026-06-04T18:30:00Z", parse(purchase.replace("12:37 PM", "12:00 AM")).occurredAt)
        assertEquals("2026-06-05T06:30:00Z", parse(purchase.replace("12:37 PM", "12:00 PM")).occurredAt)
    }
    @Test fun `upi multiline and bank reference`() {
        val t = parse("Sent Rs.1,234.56\nFrom HDFC Bank A/C *9012\nTo Sample Grocery Private\nOn 06/11/26\nRef 100000000001\nNot You? Call support")
        assertEquals("1234.56", t.amount); assertEquals("Sample Grocery Private", t.merchant)
        assertEquals("HDFC:ACCOUNT:9012", t.accountKey); assertEquals("100000000001", t.reference)
        assertEquals("2026-11-05T18:30:00Z", t.occurredAt); assertEquals(Precision.DATE, t.precision)
        assertNotNull(t.reviewReason)
    }
    @Test fun `hdfc card timestamp preserves seconds and merchant punctuation`() {
        val t = parse("Spent Rs.219.55 On HDFC Bank Card 5678 At _SAMPLE FUEL. On 2025-08-18:17:13:42.Not You? To Block call support")
        assertEquals("_SAMPLE FUEL.", t.merchant); assertEquals("2025-08-18T11:43:42Z", t.occurredAt)
        assertEquals("HDFC:CARD:5678", t.accountKey); assertEquals(Precision.SECOND, t.precision)
    }
    @Test fun `repayment is not income or purchase`() {
        val t = parse("Thank you for payment of INR 12,345.67 towards your FIRST Millennia Credit Card XX4321 on 30 Sep 2026. IDFC FIRST Bank")
        assertEquals(Kind.REPAYMENT, t.kind); assertEquals("12345.67", t.amount)
        assertEquals("IDFC:CARD:4321", t.accountKey); assertNotNull(t.reviewReason)
    }
    @Test fun `alerts OTP and unrelated texts ignored`() {
        listOf("Dear Cardmember, you've used 80% of the limit on your IDFC FIRST Bank Credit Card XX1234. Enable overlimit here: https://example.invalid",
            "IDFC OTP 123456 for payment INR 100", "Meet me at the cafe", "Your balance INR 500 at HDFC").forEach { assertNull(parser.parse(it, received)) }
    }
    @Test fun `unsupported financial messages enter manual review`() {
        val t = parse("HDFC Bank credited INR 500 to your account")
        assertEquals(Kind.UNKNOWN, t.kind); assertNull(t.amount); assertNotNull(t.reviewReason)
    }
    @Test fun `invalid dates and zero amounts never automatically upload`() {
        listOf(purchase.replace("05 JUN", "31 JUN"), purchase.replace("125.40", "0.00"), purchase.replace("125.40", "12.345")).forEach {
            val t = parse(it); assertEquals(Kind.UNKNOWN, t.kind); assertNotNull(t.reviewReason)
        }
    }
    @Test fun `whitespace and case variations preserve payee spelling`() {
        assertEquals("SAMPLE CAFE", parse(purchase.replace("INR ", "inr\n ")).merchant)
    }
    @Test fun `stable fingerprints deduplicate normalized messages`() {
        val t = parse(purchase)
        assertEquals(fingerprint(t, parser.normalize(purchase), received), fingerprint(t, parser.normalize(purchase.replace(" ", "\n")), received + 100))
        assertEquals(fingerprint(t, parser.normalize(purchase), received), fingerprint(t.copy(occurredAt = "2026-06-05T12:37:00Z"), parser.normalize(purchase), received))
        assertNotEquals(fingerprint(t, parser.normalize(purchase), received), fingerprint(t, parser.normalize(purchase.replace("125.40", "125.41")), received))
    }
    @Test fun `reference fingerprints ignore appended support text`() {
        val body = "Sent Rs.15.00 From HDFC Bank A/C *9012 To Sample On 06/11/26 Ref 100000000002"
        assertEquals(fingerprint(parse(body), body, received), fingerprint(parse(body), "$body Not You?", received + 1))
        assertEquals(fingerprint(parse(body), body, received), fingerprint(parse(body).copy(amount = "15"), body, received))
    }
}
