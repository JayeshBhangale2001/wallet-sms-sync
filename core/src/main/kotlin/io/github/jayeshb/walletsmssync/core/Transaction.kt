package io.github.jayeshb.walletsmssync.core

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant

enum class Kind { EXPENSE, INCOME, TRANSFER, REPAYMENT, UNKNOWN }
enum class Precision { SECOND, MINUTE, DATE, SMS_RECEIVED }
data class Transaction(
    val kind: Kind,
    val amount: String?,
    val currency: String = "INR",
    val accountKey: String?,
    val merchant: String?,
    val occurredAt: String,
    val precision: Precision,
    val reference: String? = null,
    val parserVersion: Int = 1,
    val reviewReason: String? = null
)
object Json {
    val gson = Gson()
    fun encode(t: Transaction): String = gson.toJson(t)
    fun decode(s: String): Transaction = gson.fromJson(s, Transaction::class.java)
}
fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
fun sameAmount(first: String?, second: String?): Boolean {
    val a = first?.toBigDecimalOrNull() ?: return false
    val b = second?.toBigDecimalOrNull() ?: return false
    return a.compareTo(b) == 0
}
fun fingerprint(t: Transaction, normalized: String, received: Long): String = hash(
    if (t.reference != null) "${t.accountKey}|${t.reference}|${t.kind}|${t.amount?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()}"
    // Supported templates already contain their bank-local date. Avoid timezone-dependent keys on re-import.
    else "${t.accountKey}|$normalized|${if (t.precision == Precision.SMS_RECEIVED) received.toString() else ""}"
)

object WalletPayload {
    fun create(t: Transaction, accountId: String, targetId: String? = null, categoryId: String? = null, marker: String): JsonArray {
        require(accountId.isNotBlank()) { "Select a source account" }
        val value = t.amount?.toBigDecimalOrNull() ?: error("Enter an amount")
        require(value > BigDecimal.ZERO && value.scale() <= 2) { "Amount must be positive with at most two decimals" }
        require(t.kind in setOf(Kind.EXPENSE, Kind.INCOME, Kind.TRANSFER)) { "Choose expense, income, or transfer" }
        Instant.parse(t.occurredAt)
        val record = JsonObject().apply {
            addProperty("accountId", accountId)
            add("amount", JsonObject().apply {
                addProperty("value", if (t.kind == Kind.INCOME) value else value.negate())
                addProperty("currencyCode", t.currency)
            })
            addProperty("recordDate", t.occurredAt)
            t.merchant?.let { addProperty("counterParty", it.take(255)) }
            addProperty("note", "wallet-sms-sync:$marker")
            if (t.kind == Kind.TRANSFER) {
                require(!targetId.isNullOrBlank() && targetId != accountId) { "Choose a different destination account" }
                add("transfer", JsonObject().apply { addProperty("pairingMode", "new"); addProperty("accountId", targetId) })
            } else if (!categoryId.isNullOrBlank()) addProperty("categoryId", categoryId)
        }
        return JsonArray().apply { add(record) }
    }
}

enum class WriteState { SYNCED, REVIEW, UNCERTAIN, AUTH, RETRY }
data class WriteOutcome(val state: WriteState, val id: String? = null, val mirrorId: String? = null, val message: String = "")
object WriteResponse {
    fun interpret(code: Int, body: String, transfer: Boolean): WriteOutcome {
        if (code == 401 || code == 403) return WriteOutcome(WriteState.AUTH, message = "Update token or check Wallet API entitlement")
        if (code == 429 || code == 409) return WriteOutcome(WriteState.RETRY, message = "Wallet requested a delay")
        val root = runCatching { Json.gson.fromJson(body, JsonObject::class.java) }.getOrNull()
        val results = runCatching { root?.getAsJsonArray("results") }.getOrNull()
        val r = results?.firstOrNull { it.asJsonObject.get("inputIndex")?.asInt == 0 }?.asJsonObject
        if (r?.get("success")?.asBoolean == true) {
            val id = r.get("id")?.asString
            val mirror = r.get("createdMirrorRecordId")?.asString
            if (id.isNullOrBlank() || (transfer && mirror.isNullOrBlank()))
                return WriteOutcome(WriteState.UNCERTAIN, id, mirror, "Verify remote record and transfer mirror before continuing")
            return WriteOutcome(WriteState.SYNCED, id, mirror)
        }
        // A failed create may have written a transfer source. Never retry it without reconciliation.
        if (r != null && r.get("errorType")?.asString == "client_error" && r.get("id") == null)
            return WriteOutcome(WriteState.REVIEW, message = "Wallet rejected fields; check account, category, amount and date")
        if (code == 400 && results == null) return WriteOutcome(WriteState.REVIEW, message = "Wallet rejected request fields")
        return WriteOutcome(WriteState.UNCERTAIN, r?.get("id")?.takeUnless { it.isJsonNull }?.asString, message = "Write outcome unknown; reconcile before retry")
    }
}
