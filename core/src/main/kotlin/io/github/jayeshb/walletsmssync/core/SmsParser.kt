package io.github.jayeshb.walletsmssync.core

import java.math.BigDecimal
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale

class SmsParser(private val zone: ZoneId = ZoneId.systemDefault()) {
    private val number = "([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)"
    private fun regex(s: String) = Regex(s, RegexOption.IGNORE_CASE)
    private val idfc = regex("INR\\s+$number spent on your IDFC FIRST Bank Credit Card ending XX(\\d{4}) at (.+?) on (\\d{2} [A-Z]{3} \\d{4}) at (\\d{2}:\\d{2} [AP]M)(?: |$)")
    private val upi = regex("^Sent Rs\\.\\s*$number From HDFC Bank A/C \\*(\\d{4}) To (.+?) On (\\d{2}/\\d{2}/\\d{2}) Ref (\\d+)(?: |$)")
    private val hdfc = regex("^Spent Rs\\.\\s*$number On HDFC Bank Card (\\d{4}) At (.+?) On (\\d{4}-\\d{2}-\\d{2}:\\d{2}:\\d{2}:\\d{2})(?:\\.| |$)")
    private val repayment = regex("^Thank you for payment of INR\\s+$number towards your .+? Credit Card XX(\\d{4}) on (\\d{2} [A-Z]{3} \\d{4})\\. IDFC FIRST Bank")
    private val credit = regex("^Credit Alert! Rs\\.\\s*$number credited to HDFC Bank A/c [X*]*(\\d{4}) on (\\d{2}-\\d{2}-\\d{2}) from VPA (.+?) \\(UPI (\\d+)\\)")
    private val debitCard = regex("^(Spent|Withdrawn) Rs\\.\\s*$number From HDFC Bank Card [X*]*(\\d{4}) At (.+?) On (\\d{4}-\\d{2}-\\d{2}:\\d{2}:\\d{2}:\\d{2})(?:\\.| |$)")
    private val mandate = regex("^UPI Mandate: Sent Rs\\.\\s*$number from HDFC Bank A/c [X*]*(\\d{4}) To (.+?) (\\d{2}/\\d{2}/\\d{2}) Ref (\\d+)(?: |$)")
    private val autoPay = regex("^AutoPay \\(E-mandate\\) Success! For (.+?) Txn Amt:\\s*INR\\s*$number Dt:\\s*(\\d{2}/\\d{2}/\\d{4}) Via:\\s*HDFC Bank CC [X*]*(\\d{4})(?: |$)")
    private val netPayment = regex("^Payment Successful! Rs\\.\\s*$number from A/c [X*]*(\\d{4}) to (.+?) via HDFC Bank NetBanking(?:\\.| |$)")
    fun normalize(body: String): String = body.replace(Regex("\\s+"), " ").trim()
    private fun date(text: String, pattern: String): LocalDateTime = LocalDateTime.parse(text,
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT))
    private fun day(text: String, pattern: String): LocalDate = LocalDate.parse(text,
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT))
    private fun amount(s: String): String = BigDecimal(s.replace(",", "")).also { require(it > BigDecimal.ZERO) }.toPlainString()
    fun parse(body: String, receivedMillis: Long): Transaction? {
        val s = normalize(body)
        if (FinancialMessages.ignored(s)) return null
        try {
            idfc.find(s)?.destructured?.let { (a, suffix, merchant, d, time) ->
                return Transaction(Kind.EXPENSE, amount(a), accountKey = "IDFC:CARD:$suffix", merchant = merchant,
                    occurredAt = date("$d $time", "dd MMM uuuu hh:mm a").atZone(zone).toInstant().toString(), precision = Precision.MINUTE)
            }
            upi.find(s)?.destructured?.let { (a, suffix, merchant, d, ref) ->
                return Transaction(Kind.EXPENSE, amount(a), accountKey = "HDFC:ACCOUNT:$suffix", merchant = merchant,
                    occurredAt = day(d, "dd/MM/uu").atStartOfDay(zone).toInstant().toString(), precision = Precision.DATE, reference = ref,
                    reviewReason = "Confirm expense or transfer to an owned account", fromKey = "HDFC:ACCOUNT:$suffix", outgoing = true)
            }
            hdfc.find(s)?.destructured?.let { (a, suffix, merchant, d) ->
                return Transaction(Kind.EXPENSE, amount(a), accountKey = "HDFC:CARD:$suffix", merchant = merchant.trim(),
                    occurredAt = date(d, "uuuu-MM-dd:HH:mm:ss").atZone(zone).toInstant().toString(), precision = Precision.SECOND)
            }
            repayment.find(s)?.destructured?.let { (a, suffix, d) ->
                return Transaction(Kind.REPAYMENT, amount(a), accountKey = "IDFC:CARD:$suffix", merchant = "Credit card repayment",
                    occurredAt = day(d, "dd MMM uuuu").atStartOfDay(zone).toInstant().toString(), precision = Precision.DATE,
                    reviewReason = "Select paying account and destination card; confirm transfer", toKey = "IDFC:CARD:$suffix", outgoing = false)
            }
            credit.find(s)?.destructured?.let { (a, suffix, d, vpa, ref) ->
                val key = "HDFC:ACCOUNT:$suffix"
                return Transaction(Kind.INCOME, amount(a), accountKey = key, merchant = vpa,
                    occurredAt = day(d, "dd-MM-uu").atStartOfDay(zone).toInstant().toString(), precision = Precision.DATE,
                    reference = ref, reviewReason = "Confirm incoming payment or transfer from an owned account", toKey = key, outgoing = false)
            }
            debitCard.find(s)?.destructured?.let { (verb, a, suffix, merchant, d) ->
                val key = "HDFC:CARD:$suffix"
                val withdrawal = verb.equals("Withdrawn", true)
                return Transaction(if (withdrawal) Kind.TRANSFER else Kind.EXPENSE, amount(a), accountKey = key, merchant = merchant,
                    occurredAt = date(d, "uuuu-MM-dd:HH:mm:ss").atZone(zone).toInstant().toString(), precision = Precision.SECOND,
                    reviewReason = if (withdrawal) "Confirm the destination cash account" else null, fromKey = key, outgoing = true)
            }
            mandate.find(s)?.destructured?.let { (a, suffix, merchant, d, ref) ->
                val key = "HDFC:ACCOUNT:$suffix"
                return Transaction(Kind.EXPENSE, amount(a), accountKey = key, merchant = merchant,
                    occurredAt = day(d, "dd/MM/uu").atStartOfDay(zone).toInstant().toString(), precision = Precision.DATE,
                    reference = ref, reviewReason = "Confirm the mandate payment category or owned destination", fromKey = key, outgoing = true)
            }
            autoPay.find(s)?.destructured?.let { (merchant, a, d, suffix) ->
                val key = "HDFC:CARD:$suffix"
                return Transaction(Kind.EXPENSE, amount(a), accountKey = key, merchant = merchant,
                    occurredAt = day(d, "dd/MM/uuuu").atStartOfDay(zone).toInstant().toString(), precision = Precision.DATE, fromKey = key, outgoing = true)
            }
            netPayment.find(s)?.destructured?.let { (a, suffix, payee) ->
                val key = "HDFC:ACCOUNT:$suffix"
                val cardPayment = regex("\\bCREDIT CA(?:RD)?\\b").containsMatchIn(payee)
                val issuer = if (cardPayment) regex("^([A-Z0-9]+)(?: FIRST)?(?: BANK)? CREDIT CA(?:RD)?\\b").find(payee)?.groupValues?.get(1)?.uppercase(Locale.ROOT) else null
                val cardSuffix = regex("\\bCREDIT CARD\\s+[X*]*(\\d{4})\\b").find(payee)?.groupValues?.get(1)
                return Transaction(if (cardPayment) Kind.REPAYMENT else Kind.EXPENSE, amount(a), accountKey = key, merchant = payee,
                    occurredAt = Instant.ofEpochMilli(receivedMillis).toString(), precision = Precision.SMS_RECEIVED,
                    reviewReason = if (cardPayment) "Confirm the paying bank and destination credit card" else "Confirm net-banking payment",
                    fromKey = key, toKey = if (issuer != null && cardSuffix != null) "$issuer:CARD:$cardSuffix" else null,
                    destinationIssuer = issuer, outgoing = true)
            }
        } catch (_: IllegalArgumentException) { /* malformed financial messages stay local for review */ }
        catch (_: java.time.DateTimeException) { }
        // Do not collect unrelated texts or OTPs. Retain structured, incomplete financial candidates only.
        if (regex("\\b(?:IDFC|HDFC)\\b").containsMatchIn(s) && regex("(?:INR|Rs\\.)\\s*\\d").containsMatchIn(s) &&
            regex("\\b(?:spent|sent|payment|credited|debited|received|refund)\\b").containsMatchIn(s) && !regex("\\bOTP\\b").containsMatchIn(s))
            return Transaction(Kind.UNKNOWN, null, accountKey = null, merchant = null,
                occurredAt = Instant.ofEpochMilli(receivedMillis).toString(), precision = Precision.SMS_RECEIVED,
                reviewReason = "Unsupported or malformed format; enter details manually")
        return null
    }
}
