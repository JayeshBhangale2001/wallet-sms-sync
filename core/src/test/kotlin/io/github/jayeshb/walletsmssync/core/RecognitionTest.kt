package io.github.jayeshb.walletsmssync.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class RecognitionTest {
    private val parser = SmsParser(ZoneId.of("Asia/Kolkata"))
    private val received = 1780000000000L
    private val debit = "Spent Rs.199 From HDFC Bank Card x1234 At Demo Shop On 2026-08-28:23:29:55 Bal Rs.622.39"

    @Test fun completedMessageFamilies() {
        val messages = listOf(
            "Sent Rs.120.00\nFrom HDFC Bank A/C *1234\nTo Demo Cafe\nOn 07/09/26\nRef 100000000001" to Kind.EXPENSE,
            "Credit Alert!\nRs.1,200.00 credited to HDFC Bank A/c XX5678 on 07-09-26 from VPA demo@bank (UPI 100000000001)" to Kind.INCOME,
            debit to Kind.EXPENSE,
            debit.replace("Spent", "Withdrawn") to Kind.TRANSFER,
            "UPI Mandate:\nSent Rs.55.00\nfrom HDFC Bank A/c 1234\nTo DEMO FUNDS\n06/11/26\nRef 100000000002" to Kind.EXPENSE,
            "AutoPay (E-mandate) Success!\nFor DEMO STREAM\nTxn Amt:INR499.00\nDt:26/04/2026\nVia:HDFC Bank CC 1234\nSI Hub ID: DEMO" to Kind.EXPENSE,
            "Payment Successful! Rs. 15,000.56 from A/c **********1234 to ICICI BANK CREDIT CA via HDFC Bank NetBanking." to Kind.REPAYMENT,
            "Thank you for payment of INR 15,000.56 towards your FIRST Demo Credit Card XX5678 on 09 Aug 2026. IDFC FIRST Bank" to Kind.REPAYMENT
        )
        val parsed = messages.map { (sms, kind) -> parser.parse(sms, received)!!.also { assertEquals(kind, it.kind) } }
        assertEquals("1200.00", parsed[1].amount)
        assertEquals("100000000001", parsed[1].reference)
        assertEquals("HDFC:ACCOUNT:5678", parsed[1].toKey)
        assertEquals("199", parsed[2].amount)
        assertEquals(Precision.SECOND, parsed[2].precision)
        assertEquals("HDFC:ACCOUNT:1234", parsed[6].fromKey)
        assertEquals("ICICI", parsed[6].destinationIssuer)
        assertEquals(Precision.SMS_RECEIVED, parsed[6].precision)
        assertEquals("IDFC:CARD:5678", parsed[7].toKey)
    }

    @Test fun discardedMessagesCannotReachAiFallback() {
        listOf("E-Mandate! Rs.55 will be deducted for payment", "AutoPay Active! INR 50 payment enabled",
            "Payment Declined: Rs.10 from HDFC Bank", "UPI Mandate: Rs.10 payment cancelled",
            "Reward Points Credited: INR 10", "HDFC Bank OTP 123456 for payment INR 50",
            "Dear Customer, Txns through Aadhaar Auth are enabled", "Payment Due by 04-11-2026 INR 100")
            .forEach { assertNull(parser.parse(it, received)); assertFalse(FinancialMessages.eligible(it)) }
    }

    @Test fun promptIncludesChoicesBeyondOldCutoffAndValidatesAliases() {
        val t = parser.parse(debit, received)!!
        val prompt = AiPrompt.create(debit, t, (0..72).map { "c$it" to "Category $it" },
            listOf("a0" to "Demo Bank"), source = "a0", category = "c72")
        assertTrue(prompt.contains("Category 72"))
        assertTrue(prompt.contains("Demo Bank"))
        assertTrue(prompt.contains("\"sourceAccountAlias\":\"a0\""))
        assertTrue(prompt.contains("Demo Shop"))
        val accepted = AdviceValidator.parse("""{"sourceAccountAlias":"a0","categoryId":"c72"}""", debit, t, setOf("a0"), setOf("c72"))
        assertEquals("a0", accepted.sourceAccountId)
        assertThrows(IllegalArgumentException::class.java) {
            AdviceValidator.parse("""{"sourceAccountAlias":"wallet-uuid"}""", debit, t, setOf("a0"), setOf("c72"))
        }
    }
}
