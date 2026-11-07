package io.github.jayeshb.walletsmssync

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.jayeshb.walletsmssync.core.FinancialMessages
import io.github.jayeshb.walletsmssync.core.Json
import io.github.jayeshb.walletsmssync.core.Kind

internal const val WALLET_PACKAGE = "com.droid4you.application.wallet"
internal enum class ReviewSection { HISTORY, NEEDS_REVIEW, UPLOAD_PENDING }

internal fun reviewSection(status: String): ReviewSection? = when (status) {
    "SYNCED", "IGNORED", "LINKED" -> ReviewSection.HISTORY
    "REVIEW", "UNCERTAIN" -> ReviewSection.NEEDS_REVIEW
    "QUEUED", "SENDING" -> ReviewSection.UPLOAD_PENDING
    else -> null
}

internal fun Entry.matchesUncategorizedExpense(merchant: String): Boolean {
    if (status != "REVIEW" || !categoryId.isNullOrEmpty() || merchant.isBlank()) return false
    val parsed = Json.decode(this.parsed)
    return parsed.kind == Kind.EXPENSE &&
        parsed.merchant?.let(FinancialMessages::merchantKey) == FinancialMessages.merchantKey(merchant)
}

internal fun openWallet(context: Context, installed: Intent? = context.packageManager.getLaunchIntentForPackage(WALLET_PACKAGE)): Boolean {
    if (installed != null && launch(context, installed)) return true
    return launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://web.budgetbakers.com/login")))
}

private fun launch(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
