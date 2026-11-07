package io.github.jayeshb.walletsmssync

import io.github.jayeshb.walletsmssync.core.*
import io.github.jayeshb.walletsmssync.core.FinancialMessages.isOutgoing
import java.time.Duration
import java.time.Instant

internal data class TransferRoute(val from: String?, val to: String?) {
    fun compatible(other: TransferRoute): Boolean =
        (from == null || other.from == null || from == other.from) &&
            (to == null || other.to == null || to == other.to)

    fun merge(other: TransferRoute) = TransferRoute(from ?: other.from, to ?: other.to)
}

internal fun counterpartyKey(name: String) = "PAYEE:" + FinancialMessages.merchantKey(name)

internal class TransferAccounts(mappings: List<AccountMapping>, private val accounts: List<Catalog>) {
    private val ids = mappings.associate { it.key to it.accountId }

    private fun account(key: String?, currency: String): String? =
        ids[key]?.takeIf { id -> accounts.any { it.id == id && it.currency == currency } }

    fun route(t: Transaction): TransferRoute {
        val primary = account(t.accountKey, t.currency)
        val counterparty = t.merchant?.let { account(counterpartyKey(it), t.currency) }
        val from = account(t.fromKey, t.currency) ?: if (t.isOutgoing()) primary else counterparty
        val to = account(t.toKey, t.currency) ?: if (t.isOutgoing()) counterparty else primary
        return TransferRoute(from, to)
    }

    fun issuerCard(t: Transaction): String? = t.destinationIssuer?.let { issuer ->
        ids.keys.filter { it.startsWith("$issuer:CARD:") }
            .mapNotNull { account(it, t.currency) }.distinct().singleOrNull()
    }

    fun ledger(t: Transaction): String? = if (t.isOutgoing()) route(t).from else route(t).to
}

internal fun sameTransferEvidence(a: Transaction, b: Transaction): Boolean =
    a.isOutgoing() != b.isOutgoing() && a.currency == b.currency && sameAmount(a.amount, b.amount) &&
        Duration.between(Instant.parse(a.occurredAt), Instant.parse(b.occurredAt)).abs() <= Duration.ofDays(3)

internal fun sharedReference(a: Transaction, b: Transaction): Boolean =
    a.reference != null && a.reference.equals(b.reference, ignoreCase = true)

internal data class TransferMatch(val entry: Entry, val route: TransferRoute, val exact: Boolean)
