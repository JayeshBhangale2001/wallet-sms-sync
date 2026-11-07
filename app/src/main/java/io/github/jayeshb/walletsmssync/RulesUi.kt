package io.github.jayeshb.walletsmssync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.jayeshb.walletsmssync.core.*
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun RulesScreen(app: WalletApp, busy: Boolean, task: (suspend () -> String) -> Unit) {
    val store = app.db.store()
    val repo = remember { RuleRepository(app.db) }
    var rules by remember { mutableStateOf(emptyList<CustomRule>()) }
    var categories by remember { mutableStateOf(emptyList<Catalog>()) }
    var editing by remember { mutableStateOf<CustomRule?>(null) }
    var remove by remember { mutableStateOf<CustomRule?>(null) }
    suspend fun reload() {
        rules = store.customRules()
        categories = store.catalog("categories")
    }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { reload() } }
    val draft = editing
    if (draft != null) {
        key(draft.id) {
            RuleEditor(draft, categories, busy, close = { editing = null }) { updated ->
                task { repo.save(updated); reload(); editing = null; "Rule saved" }
            }
        }
        return
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Rules", style = MaterialTheme.typography.headlineSmall)
        Text("Category rules run in the displayed order within each match type. Parsing rules use a sample SMS.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { editing = newRule("SMS") }) { Text("Add category rule") }
            OutlinedButton(enabled = !busy, onClick = { editing = newRule("PARSER") }) { Text("Add parser") }
        }
        Text("Category rules", fontWeight = FontWeight.Bold)
        val categoryRules = rules.filter { it.type != "PARSER" }
        categoryRules.forEachIndexed { index, rule ->
            RuleRow(rule, if (rule.type == "SMS") "SMS contains" else "Merchant equals",
                "${rule.value} → ${categories.find { it.id == rule.categoryId }?.name ?: "Unavailable category"}", busy,
                edit = { editing = rule }, toggle = { task { repo.save(rule.copy(enabled = !rule.enabled)); reload(); "Rule updated" } },
                delete = { remove = rule })
            Row {
                TextButton(enabled = !busy && index > 0, onClick = { task { repo.move(rule.id, -1); reload(); "Priority updated" } }) { Text("Move up") }
                TextButton(enabled = !busy && index < categoryRules.lastIndex, onClick = { task { repo.move(rule.id, 1); reload(); "Priority updated" } }) { Text("Move down") }
            }
        }
        HorizontalDivider()
        Text("Custom bank parsers", fontWeight = FontWeight.Bold)
        val parsers = rules.filter { it.type == "PARSER" }
        parsers.forEach { rule ->
            val spec = rule.parserSpec()
            val label = spec.builtIn?.let { "Override · ${SmsParser.builtIns[it]}" } ?: "New message format"
            RuleRow(rule, label, spec.pattern, busy,
                edit = { editing = rule }, toggle = { task { repo.save(rule.copy(enabled = !rule.enabled)); reload(); "Parser updated" } },
                delete = { remove = rule })
        }
        HorizontalDivider()
        Text("Built-in bank parsers", fontWeight = FontWeight.Bold)
        SmsParser.builtIns.forEach { (id, label) ->
            val override = parsers.firstOrNull { it.enabled && it.parserSpec().builtIn == id }
            Text(if (override == null) label else "$label · override: ${override.name}")
        }
        Text("Remembered merchant categories, account mappings and learned templates are in Learn.")
    }
    remove?.let { rule ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text("Delete ${rule.name}?") },
            text = { Text("Future messages will use the remaining rules.") },
            confirmButton = { TextButton(enabled = !busy, onClick = { task {
                store.deleteCustomRule(rule.id); reload(); remove = null; "Rule deleted"
            } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } })
    }
}

private fun newRule(type: String) = CustomRule(UUID.randomUUID().toString(), type, "", "", null, true, 0)

@Composable private fun RuleRow(rule: CustomRule, label: String, description: String, busy: Boolean,
    edit: () -> Unit, toggle: () -> Unit, delete: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(rule.name, fontWeight = FontWeight.SemiBold)
            Text("$label · ${if (rule.enabled) "Enabled" else "Disabled"}")
            Text(description)
            Row {
                TextButton(enabled = !busy, onClick = edit) { Text("Edit") }
                TextButton(enabled = !busy, onClick = toggle) { Text(if (rule.enabled) "Disable" else "Enable") }
                TextButton(enabled = !busy, onClick = delete) { Text("Delete") }
            }
        }
    }
}

@Composable private fun RuleEditor(rule: CustomRule, categories: List<Catalog>, busy: Boolean,
    close: () -> Unit, save: (CustomRule) -> Unit) {
    var name by remember { mutableStateOf(rule.name) }
    var type by remember { mutableStateOf(rule.type) }
    var value by remember { mutableStateOf(rule.value) }
    var category by remember { mutableStateOf(rule.categoryId) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = !busy, onClick = close) { Text("Cancel") }
        Text(if (rule.type == "PARSER") "Bank parser" else "Category rule", style = MaterialTheme.typography.headlineSmall)
        Field("Rule name", name, !busy) { name = it }
        if (rule.type == "PARSER") {
            ParserEditor(rule, busy) { spec -> save(rule.copy(name = name.trim(), value = Json.gson.toJson(spec))) }
        } else {
            Picker("Match type", type, listOf("MERCHANT" to "Merchant equals", "SMS" to "SMS contains"), !busy) { type = it }
            Field("Match text", value, !busy) { value = it }
            Picker("Wallet category", category, categories.map { it.id to it.name }, !busy) { category = it }
            if (categories.isEmpty()) Text("Fetch Wallet accounts and categories in Setup to choose a category.")
            Button(enabled = !busy && category != null, onClick = {
                save(rule.copy(type = type, name = name.trim(), value = value, categoryId = category))
            }) { Text("Save category rule") }
        }
    }
}

@Composable private fun ParserEditor(rule: CustomRule, busy: Boolean, save: (CustomSmsRule) -> Unit) {
    val original = remember { rule.takeIf { it.value.isNotBlank() }?.parserSpec() }
    var sample by remember { mutableStateOf(TextFieldValue()) }
    var slots by remember { mutableStateOf(emptyMap<String, IntRange>()) }
    var pattern by remember { mutableStateOf(original?.pattern.orEmpty()) }
    var bank by remember { mutableStateOf(original?.bank.orEmpty()) }
    var accountType by remember { mutableStateOf(original?.accountType ?: "ACCOUNT") }
    var kind by remember { mutableStateOf(original?.kind ?: Kind.EXPENSE) }
    var dateFormat by remember { mutableStateOf(original?.dateFormat ?: "") }
    var builtIn by remember { mutableStateOf(original?.builtIn ?: "") }
    var result by remember { mutableStateOf("") }
    var verified by remember { mutableStateOf<Pair<CustomSmsRule, String>?>(null) }
    val spec = CustomSmsRule(CustomSmsRule.redactPattern(pattern), bank, accountType, kind, dateFormat, builtIn.ifEmpty { null })
    Text("Paste a sample, select a value in the sample, then tap its field. Mark the amount and last four digits. Date, merchant and reference are optional. Preview with the original sample before saving.")
    OutlinedTextField(sample, { next ->
        if (sample.text != next.text) { slots = emptyMap(); verified = null; result = "" }
        sample = next
    }, label = { Text("Sample SMS") }, enabled = !busy, modifier = Modifier.fillMaxWidth(), maxLines = 6)
    val selection = sample.selection
    val canMark = !busy && !selection.collapsed && selection.max <= sample.text.length
    listOf(listOf("amount", "suffix", "merchant"), listOf("date", "reference")).forEach { group ->
        Row {
            group.forEach { field ->
                TextButton(enabled = canMark, onClick = {
                    val range = selection.min until selection.max
                    val others = slots - field
                    if (others.values.any { it.first <= range.last && range.first <= it.last }) {
                        result = "Select text outside the other marked fields."
                    } else {
                        slots = others + (field to range)
                        var draft = sample.text
                        for ((name, marked) in slots.entries.sortedByDescending { it.value.first })
                            draft = draft.replaceRange(marked.first, marked.last + 1, "{$name}")
                        pattern = CustomSmsRule.redactPattern(draft)
                        verified = null
                        result = ""
                    }
                }) { Text(field.replaceFirstChar(Char::uppercaseChar)) }
            }
        }
    }
    OutlinedTextField(pattern, { pattern = it }, label = { Text("Template with placeholders") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
    Text("Fixed wording in the template is saved. Remove personal details from the template before saving.")
    Field("Bank code", bank, !busy) { bank = it.trim().uppercase(Locale.ROOT) }
    Picker("Account type", accountType, listOf("ACCOUNT" to "Bank account", "CARD" to "Card"), !busy) { accountType = it }
    Picker("Transaction kind", kind.name, Kind.entries.filter { it != Kind.UNKNOWN }.map { it.name to it.name }, !busy) { kind = Kind.valueOf(it) }
    Picker("Date format", dateFormat, listOf("" to "Use SMS receipt time") + DateEvidence.formats.keys.map { it to it }, !busy) { dateFormat = it }
    Picker("Built-in override", builtIn, listOf("" to "New message format") + SmsParser.builtIns.toList(), !busy) { builtIn = it }
    OutlinedButton(enabled = !busy && sample.text.isNotBlank(), onClick = {
        verified = null
        result = runCatching {
            var matched: String? = null
            val received = System.currentTimeMillis()
            val standard = if (spec.builtIn != null) SmsParser().parse(sample.text, received) { matched = it } else null
            require(spec.builtIn == null || spec.builtIn == matched) { "Use a sample recognized by the selected built-in parser" }
            require(standard?.kind !in setOf(Kind.TRANSFER, Kind.REPAYMENT) || standard?.kind == spec.kind) {
                "Keep the transfer or repayment type for this built-in parser"
            }
            val parsed = requireNotNull(spec.compile()(sample.text, received)) { "The template did not match the sample SMS" }
            verified = spec to sample.text
            "${parsed.kind} · ${parsed.currency} ${parsed.amount}\n${parsed.accountKey} · ${parsed.merchant ?: "No merchant"}\n${parsed.occurredAt} · ${parsed.precision}\nReference: ${parsed.reference ?: "—"}"
        }.getOrElse { it.message ?: "Could not preview this template" }
    }) { Text("Preview parser") }
    if (result.isNotBlank()) Text(result)
    Button(enabled = !busy && verified == (spec to sample.text), onClick = { save(spec) }) { Text("Save parser") }
}
