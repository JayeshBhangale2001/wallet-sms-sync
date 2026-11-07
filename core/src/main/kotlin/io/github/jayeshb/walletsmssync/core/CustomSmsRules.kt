package io.github.jayeshb.walletsmssync.core

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

data class CustomSmsRule(
    val pattern: String, val bank: String, val accountType: String, val kind: Kind,
    val dateFormat: String, val builtIn: String? = null
) {
    companion object {
        private val slot = Regex("\\{([a-z]+)\\}")
        private val privateLiteral = Regex("https?://[^\\s{}]+|[0-9]{4,}", RegexOption.IGNORE_CASE)

        fun redactPattern(pattern: String): String = privateLiteral.replace(FinancialMessages.normalize(pattern)) {
            if (it.value.startsWith("http", true)) "{link}" else "{number}"
        }
    }

    fun compile(zone: ZoneId = ZoneId.systemDefault()): (String, Long) -> Transaction? {
        require(pattern.length in 1..800) { "Keep the template between 1 and 800 characters" }
        require(Regex("[A-Z][A-Z0-9]{1,23}").matches(bank) && accountType in setOf("ACCOUNT", "CARD")) {
            "Choose a bank code and account type"
        }
        require(kind != Kind.UNKNOWN && (kind != Kind.REPAYMENT || accountType == "CARD")) { "A repayment template must identify its card" }
        require(builtIn == null || builtIn in SmsParser.builtIns) { "Choose a supported built-in parser" }
        val fields = mapOf(
            "amount" to "[0-9]{1,12}(?:,[0-9]{3}){0,4}(?:\\.[0-9]{1,2})?",
            "suffix" to "[0-9]{4}",
            "merchant" to ".{1,120}?",
            "date" to DateEvidence.formats[dateFormat],
            "reference" to "[A-Za-z0-9]{1,64}",
            "number" to "[0-9]{1,32}",
            "link" to "https?://[^ ]{1,256}"
        )
        val placeholders = slot.findAll(pattern).toList()
        require(placeholders.size <= 16) { "Use at most 16 placeholders" }
        val named = placeholders.map { it.groupValues[1] }.filterNot { it in setOf("number", "link") }
        require(named.distinct().size == named.size && "amount" in named && "suffix" in named) {
            "Mark the amount and account suffix once each"
        }
        require(placeholders.zipWithNext().none { (left, right) -> right.range.first == left.range.last + 1 }) {
            "Keep fixed text between placeholders"
        }
        require(slot.replace(pattern, "").any(Char::isLetter)) { "Include fixed wording that identifies the message format" }
        var at = 0
        val expression = buildString {
            for (token in placeholders) {
                append(Regex.escape(pattern.substring(at, token.range.first)))
                val name = token.groupValues[1]
                val field = requireNotNull(fields[name]) { "Unknown field or missing date format for {$name}" }
                if (name in named) append("(?<$name>$field)") else append("(?>$field)")
                at = token.range.last + 1
            }
            append(Regex.escape(pattern.substring(at)))
        }
        val regex = Regex(expression, RegexOption.IGNORE_CASE)
        return parse@{ body, received ->
            if (body.length > 2000 || FinancialMessages.ignored(body)) return@parse null
            val match = regex.matchEntire(FinancialMessages.normalize(body)) ?: return@parse null
            fun value(name: String) = if (name in named) match.groups[name]?.value else null
            runCatching {
                val amount = value("amount")!!.replace(",", "").toBigDecimal()
                require(amount.signum() > 0)
                val date = value("date")
                val key = "$bank:$accountType:${value("suffix")}"
                val outgoing = kind != Kind.INCOME && kind != Kind.REPAYMENT
                Transaction(kind, amount.toPlainString(), accountKey = key, merchant = value("merchant"),
                    occurredAt = date?.let { DateEvidence.parse(it, dateFormat, zone) } ?: Instant.ofEpochMilli(received).toString(),
                    precision = if (date == null) Precision.SMS_RECEIVED else DateEvidence.precision(dateFormat),
                    reference = value("reference"), parserVersion = 3, reviewReason = "Matched a custom parsing rule",
                    fromKey = key.takeIf { outgoing }, toKey = key.takeUnless { outgoing }, outgoing = outgoing)
            }.getOrNull()
        }
    }
}

class SmsRuleMatcher(private val builtIn: SmsParser, rules: List<CustomSmsRule>) {
    private val rules = rules.mapNotNull { rule ->
        runCatching { rule.builtIn to rule.compile() }.getOrNull()
    }

    fun parse(body: String, received: Long): Transaction? {
        if (FinancialMessages.ignored(body)) return null
        var matched: String? = null
        val standard = builtIn.parse(body, received) { matched = it }
        val candidates = rules.asSequence().filter { it.first == matched }
            .mapNotNull { (_, parse) -> parse(body, received) }.take(2).toList()
        if (candidates.size > 1 && matched == null) {
            return Transaction(Kind.UNKNOWN, null, accountKey = null, merchant = null,
                occurredAt = Instant.ofEpochMilli(received).toString(), precision = Precision.SMS_RECEIVED,
                reviewReason = "Conflicting custom parsing rules")
        }
        val custom = candidates.singleOrNull()
        if (standard?.kind in setOf(Kind.TRANSFER, Kind.REPAYMENT) && custom?.kind != standard?.kind) return standard
        return custom ?: standard
    }
}
