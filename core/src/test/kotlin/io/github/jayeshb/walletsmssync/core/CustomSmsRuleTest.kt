package io.github.jayeshb.walletsmssync.core

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CustomSmsRuleTest {
    private val sample = "ACME debit INR 12.35 from XX1234 at Demo Cafe on 2026-10-04 ref TXN45"
    private val template = CustomSmsRule("ACME debit INR {amount} from XX{suffix} at {merchant} on {date} ref {reference}",
        "ACME", "ACCOUNT", Kind.EXPENSE, "uuuu-MM-dd")

    @Test fun customTemplateParsesEditedFormatsAndKeepsFieldTypes() {
        val parsed = template.compile(ZoneId.of("Asia/Kolkata"))(sample, 0)!!
        assertEquals("12.35", parsed.amount)
        assertEquals("ACME:ACCOUNT:1234", parsed.fromKey)
        assertEquals("Demo Cafe", parsed.merchant)
        assertEquals("TXN45", parsed.reference)
        assertEquals("2026-10-03T18:30:00Z", parsed.occurredAt)
        val edited = template.copy(pattern = template.pattern.replace(" debit ", " payment "))
        assertNull(edited.compile()(sample, 0))
        assertNotNull(edited.compile()(sample.replace(" debit ", " payment "), 0))
        assertNull(template.compile()(sample.replace("12.35", "0.00"), 0))
        assertThrows(IllegalArgumentException::class.java) { template.copy(pattern = "ACME {amount}{suffix}").compile() }
    }

    @Test fun overrideRunsOnlyForTargetAndFallsBackWhenItDoesNotMatch() {
        val text = "Sent Rs.12.35 From HDFC Bank A/C *1234 To Demo Cafe On 04/10/26 Ref 123456"
        val pattern = "Sent Rs.{amount} From HDFC Bank A/C *{suffix} To {merchant} On {date} Ref {reference}"
        val custom = template.copy(pattern = pattern, bank = "HDFC", dateFormat = "dd/MM/uu")
        val parser = SmsParser()
        assertEquals(1, SmsRuleMatcher(parser, listOf(custom)).parse(text, 0)!!.parserVersion)
        assertEquals(3, SmsRuleMatcher(parser, listOf(custom.copy(builtIn = "hdfc_upi"))).parse(text, 0)!!.parserVersion)
        val wrong = custom.copy(pattern = "$pattern confirmed", builtIn = "hdfc_upi")
        assertEquals(1, SmsRuleMatcher(parser, listOf(wrong)).parse(text, 0)!!.parserVersion)
        assertEquals(3, SmsRuleMatcher(parser, listOf(template)).parse(sample, 0)!!.parserVersion)
        assertEquals(Kind.UNKNOWN, SmsRuleMatcher(parser, listOf(template, template.copy(kind = Kind.INCOME))).parse(sample, 0)?.kind)
    }

    @Test fun filtersIgnoredAlertsBeforeCustomParsing() {
        val alert = template.copy(pattern = "Payment Declined: Rs.{amount} from ACME A/c {suffix}", dateFormat = "")
        val sms = "Payment Declined: Rs.12.35 from ACME A/c 1234"
        assertNull(alert.compile()(sms, 0))
        assertNull(SmsRuleMatcher(SmsParser(), listOf(alert)).parse(sms, 0))
        assertEquals("ACME {number} {link}", CustomSmsRule.redactPattern("ACME 12345678 https://example.invalid/ref"))
    }
}
