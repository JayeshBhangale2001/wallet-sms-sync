package io.github.jayeshb.walletsmssync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jayeshb.walletsmssync.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable internal fun AiSetup(app: WalletApp, busy: Boolean, task: (suspend () -> String) -> Unit) {
    val settings = app.settings
    var provider by remember { mutableStateOf(settings.aiProvider) }
    var enabled by remember { mutableStateOf(settings.aiEnabled) }
    var hasKey by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var report by remember { mutableStateOf(settings.aiNotice) }
    LaunchedEffect(provider) {
        hasKey = withContext(Dispatchers.IO) { settings.aiKey(provider) != null }
    }

    Text("Cloud AI & learning", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AiProvider.entries.forEach { option ->
            FilterChip(selected = option == provider, enabled = !busy, onClick = {
                if (option != provider) task {
                    settings.selectAiProvider(option)
                    keyInput = ""
                    enabled = false
                    report = ""
                    hasKey = false
                    provider = option
                    "${option.label} selected. Test the connection and enable cloud AI."
                }
            }, label = { Text(option.label) })
        }
    }
    Text("If enabled, eligible financial SMS text and category choices are sent to ${provider.label} (${provider.model}), including pending messages. Account mappings and Wallet sync are handled on your phone.", fontSize = 13.sp)
    OutlinedTextField(
        value = keyInput,
        onValueChange = { keyInput = it },
        label = { Text("${provider.label} API key") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth()
    )
    Row {
        TextButton(enabled = !busy && keyInput.isNotBlank(), onClick = {
            val key = keyInput
            task {
                settings.saveAiKey(provider, key)
                keyInput = ""
                hasKey = true
                enabled = false
                report = ""
                "Key saved. Test the connection and enable cloud AI."
            }
        }) { Text("Save key") }
        TextButton(enabled = !busy && hasKey, onClick = {
            task {
                settings.saveAiKey(provider, "")
                hasKey = false
                enabled = false
                report = ""
                "Key removed"
            }
        }) { Text("Remove key") }
    }
    Text(if (hasKey) "Key saved securely" else "Enter the key issued by ${provider.label}", fontSize = 12.sp)
    OutlinedButton(enabled = !busy && hasKey, onClick = { task {
        val key = requireNotNull(settings.aiKey(provider)) { "Save an API key first" }
        val body = "HDFC Bank: INR 123.45 debited from A/C XX9012 at DEMO SHOP on 06/11/2026. Avl bal INR 9000.00."
        val baseline = Transaction(Kind.UNKNOWN, null, accountKey = null, merchant = null,
            occurredAt = "2026-11-06T00:00:00Z", precision = Precision.SMS_RECEIVED)
        val result = CloudAi(provider, key).infer(AiPrompt.create(body, baseline, emptyList()))
        settings.aiBlocked = false
        if (settings.aiEnabled) AiWorker.schedule(app)
        val advice = AdviceValidator.parse(result, body, baseline, emptySet(), emptySet())
        require(sameAmount(advice.transaction.amount, "123.45") && advice.transaction.accountKey == "HDFC:ACCOUNT:9012") {
            "${provider.label} connected. The sample extraction needs review."
        }
        report = "${provider.label} connection and sample extraction passed."
        settings.aiNotice = report
        report
    } }) { Text("Test with sample SMS") }
    Row {
        Switch(checked = enabled, enabled = !busy && hasKey, onCheckedChange = {
            enabled = it
            app.settings.aiEnabled = it
            if (it) AiWorker.schedule(app)
        })
        Text("Enable cloud AI for financial SMS", modifier = Modifier.padding(top = 12.dp, start = 8.dp))
    }
    if (report.isNotBlank()) Text(report, fontSize = 12.sp)
    HorizontalDivider()
}

@Composable internal fun AiReview(app: WalletApp, entry: Entry, busy: Boolean, task: (suspend () -> String) -> Unit) {
    var evidence by remember(entry.id) { mutableStateOf<SmsEvidence?>(null) }
    var sms by remember(entry.id) { mutableStateOf("") }
    var showSms by remember { mutableStateOf(false) }
    LaunchedEffect(entry.id) {
        while(true) {
            evidence = withContext(Dispatchers.IO) { app.db.store().evidence(entry.id) }
            delay(1500)
        }
    }
    Text("AI assistant", fontWeight = FontWeight.Bold)
    val ev = evidence
    Text("${ev?.state ?: "No SMS attached"} · ${ev?.reason ?: "Older records can be analysed by pasting their original financial SMS below."}", fontSize = 12.sp)
    if(ev?.modelInfo?.isNotEmpty() == true) Text(ev.modelInfo, fontSize = 12.sp)
    if(ev?.state == "READY" && ev.suggestion != null) {
        val advice = Json.gson.fromJson(ev.suggestion, AiAdvice::class.java)
        SelectionContainer { Text("${advice.transaction.kind} · ${advice.transaction.currency} ${advice.transaction.amount ?: "?"}\n${advice.transaction.merchant ?: "Unknown merchant"} · ${advice.transaction.accountKey ?: "Unknown account"}\nSuggested Wallet account: ${advice.sourceAccountId ?: "choose manually"}\nSuggested category: ${advice.categoryId ?: "choose manually"}", fontSize = 13.sp) }
        Button(enabled = !busy, onClick = { task { app.repository.applyAdvice(entry.id); "Suggestions applied. Check the fields and approve below." } }) { Text("Apply suggestions to review form") }
    }
    TextButton(enabled = !busy, onClick = { showSms = !showSms }) { Text(if(showSms) "Hide message" else "View / attach original SMS") }
    if(showSms) {
        LaunchedEffect(ev?.bodyCipher) { if(sms.isEmpty()) sms = withContext(Dispatchers.IO) { ev?.bodyCipher?.let(app.settings::openEvidence).orEmpty() } }
        OutlinedTextField(value = sms, onValueChange = { sms = it }, label = { Text("Financial SMS for cloud AI (exclude OTPs)") }, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp), maxLines = 8)
        Button(enabled = !busy && app.settings.aiEnabled && sms.isNotBlank(), onClick = { val text = sms; task {
            app.repository.attachForAi(entry.id, text); "Message encrypted and queued for AI"
        } }) { Text("Analyse with cloud AI") }
    }
    if(ev?.state == "FAILED" && ev.bodyCipher != null) TextButton(enabled = !busy, onClick = { task {
        require(app.db.store().retryAi(entry.id) == 1) { "This item is no longer awaiting a retry" }
        AiWorker.schedule(app)
        "AI retry queued"
    } }) { Text("Retry cloud AI") }
    HorizontalDivider()
}

@Composable internal fun LearningScreen(app: WalletApp, busy: Boolean, task: (suspend () -> String) -> Unit) {
    var templates by remember { mutableStateOf(emptyList<LearnedTemplate>()) }
    var decisions by remember { mutableStateOf(emptyList<LearningDecision>()) }
    var merchants by remember { mutableStateOf(emptyList<MerchantRule>()) }
    var mappings by remember { mutableStateOf(emptyList<AccountMapping>()) }
    var catalog by remember { mutableStateOf(emptyList<Catalog>()) }
    suspend fun load() {
        val store = app.db.store(); templates = store.templates(); decisions = store.decisions(); merchants = store.merchantRules(); mappings = store.mappings()
        catalog = store.catalog("accounts") + store.catalog("categories")
    }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { load() } }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("What your app remembers", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("${templates.size} templates · ${mappings.size} account mappings · ${merchants.size} merchant categories", fontSize = 13.sp)
        Text("Enabled templates run on your phone. Clear amounts on confirmed INR accounts sync when automatic sync is on; transfers, repayments and uncertain fields require review. AI-proposed templates start disabled until you confirm them here.", fontSize = 12.sp)
        if(templates.isEmpty()) Text("Approve a reviewed SMS with ‘Remember these corrections’ to start learning.")
        templates.forEach { row ->
            val spec = Json.gson.fromJson(row.spec, TemplateSpec::class.java)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${spec.bank} · ${spec.kind} · ${row.origin} · ${row.confirmations} confirmation(s)", fontWeight = FontWeight.Bold)
                    SelectionContainer { Text(row.example, fontSize = 12.sp) }
                    Row { Switch(row.enabled, enabled = !busy, onCheckedChange = { enabled -> task { app.db.store().template(row.copy(enabled = enabled, origin = if(enabled) "USER" else row.origin, confirmations = if(enabled) row.confirmations.coerceAtLeast(1) else row.confirmations)); load(); "Template updated" } }); Text("Confirm and use this template", modifier = Modifier.padding(top = 12.dp, start = 8.dp)) }
                    TextButton(enabled = !busy, onClick = { task { app.db.store().deleteTemplate(row.id); load(); "Template forgotten" } }) { Text("Forget template") }
                }
            }
        }
        HorizontalDivider(); Text("Account mappings", fontWeight = FontWeight.Bold)
        mappings.forEach { row ->
            Text("${row.key} → ${catalog.find { it.id == row.accountId }?.name ?: row.accountId}", fontSize = 12.sp)
            TextButton(enabled = !busy, onClick = { task { app.db.store().deleteMapping(row.key); load(); "Account mapping forgotten" } }) { Text("Forget mapping") }
        }
        Text("Merchant categories", fontWeight = FontWeight.Bold)
        merchants.forEach { row ->
            Text("${row.merchant} → ${catalog.find { it.id == row.categoryId }?.name ?: row.categoryId} (${row.origin})", fontSize = 12.sp)
            TextButton(enabled = !busy, onClick = { task { app.db.store().deleteMerchantRule(row.merchant); load(); "Merchant category forgotten" } }) { Text("Forget merchant category") }
        }
        HorizontalDivider(); Text("Reviewed uncertainties (latest 100)", fontWeight = FontWeight.Bold)
        decisions.forEach { decision ->
            var expanded by remember(decision.entryId) { mutableStateOf(false) }
            Text(decision.reason, fontSize = 12.sp)
            TextButton(onClick = { expanded = !expanded }) { Text(if(expanded) "Hide correction" else "Show correction") }
            if(expanded) SelectionContainer { Text("Before: ${decision.beforeJson}\nReviewed: ${decision.afterJson}", fontSize = 11.sp) }
        }
    }
}
