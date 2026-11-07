package io.github.jayeshb.walletsmssync

import androidx.room.withTransaction
import io.github.jayeshb.walletsmssync.core.*

internal fun CustomRule.parserSpec(): CustomSmsRule =
    Json.gson.fromJson(value, CustomSmsRule::class.java)

internal fun categoryFor(
    merchant: String?, body: String?, active: Set<String>, custom: List<CustomRule>, remembered: List<MerchantRule>
): String? {
    val key = merchant?.let(FinancialMessages::merchantKey)
    val message = body?.let(FinancialMessages::merchantKey)
    val learned = remembered.filter { FinancialMessages.merchantKey(it.merchant) == key && it.categoryId in active }
    val eligible = custom.filter { it.enabled && it.categoryId in active }
    return eligible.firstOrNull { it.type == "MERCHANT" && it.value == key }?.categoryId
        ?: learned.filter { it.origin == "USER" }.map { it.categoryId }.distinct().singleOrNull()
        ?: eligible.firstOrNull { it.type == "SMS" && message?.contains(it.value) == true }?.categoryId
        ?: learned.filter { it.origin == "AI" }.map { it.categoryId }.distinct().singleOrNull()
}

internal class RuleRepository(private val db: AppDatabase) {
    private val store = db.store()

    suspend fun save(rule: CustomRule) = db.withTransaction {
        require(rule.name.isNotBlank() && rule.name.length <= 80) { "Enter a name up to 80 characters" }
        val rules = store.customRules()
        val old = rules.find { it.id == rule.id }
        val value = if (rule.type == "PARSER") {
            val original = rule.parserSpec()
            val spec = original.copy(pattern = CustomSmsRule.redactPattern(original.pattern))
            spec.compile()
            require(!rule.enabled || spec.builtIn == null || rules.none {
                it.id != rule.id && it.enabled && it.type == "PARSER" && it.parserSpec().builtIn == spec.builtIn
            }) { "Disable the other override for this built-in parser first" }
            Json.gson.toJson(spec)
        } else {
            require(rule.type in setOf("SMS", "MERCHANT")) { "Choose a category match type" }
            require(!rule.enabled || store.catalog("categories").any { it.id == rule.categoryId }) { "Select an active Wallet category" }
            FinancialMessages.merchantKey(rule.value).also {
                require(it.length in 1..120) { "Enter match text up to 120 characters" }
            }
        }
        store.saveCustomRule(rule.copy(value = value, position = old?.position ?: ((rules.lastOrNull()?.position ?: 0) + 1)))
    }

    suspend fun move(id: String, offset: Int) = db.withTransaction {
        val rules = store.customRules().filter { it.type != "PARSER" }
        val index = rules.indexOfFirst { it.id == id }
        val first = rules.getOrNull(index) ?: return@withTransaction
        val second = rules.getOrNull(index + offset) ?: return@withTransaction
        store.saveCustomRule(first.copy(position = second.position))
        store.saveCustomRule(second.copy(position = first.position))
    }
}
