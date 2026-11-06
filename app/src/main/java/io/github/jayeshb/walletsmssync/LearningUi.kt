package io.github.jayeshb.walletsmssync

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jayeshb.walletsmssync.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable internal fun AiSetup(app: WalletApp, busy: Boolean, task: (suspend () -> String) -> Unit) {
    var enabled by remember { mutableStateOf(app.settings.aiEnabled) }
    var gpu by remember { mutableStateOf(app.settings.preferGpu) }
    var model by remember { mutableStateOf(app.localAi.modelFile.isFile) }
    var report by remember { mutableStateOf(app.settings.aiNotice) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) task { val result = app.localAi.importModel(uri); model = true; report = result; result }
    }
    Text("Local AI & learning", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Text("Known rules run first. AI suggests fields and categories for unfamiliar cases. Validated amounts on previously confirmed accounts can sync directly; uncertain fields and transfers remain in Review.", fontSize = 13.sp)
    Text("Recommended on your S24 Ultra: Gemma 3 1B INT4 in .litertlm format. Download from the model page, accept its terms there, then import the file. No model is bundled in this APK.", fontSize = 12.sp)
    Text("Inference runtime: LiteRT-LM 0.8.0 · ${if(app.localAi.supported) "64-bit device supported" else "AI unavailable on this 32-bit device; rules still work"}", fontSize = 12.sp)
    Row {
        TextButton(onClick = { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/litert-community/Gemma3-1B-IT")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Get model") }
        TextButton(enabled = !busy && app.localAi.supported, onClick = { importer.launch(arrayOf("*/*")) }) { Text("Import model") }
        if(model) TextButton(enabled = !busy, onClick = { task { app.localAi.removeModel(); model = false; enabled = false; "Model removed; learned knowledge retained" } }) { Text("Remove") }
    }
    Text(if(model) "Model installed · SHA-256 ${app.settings.modelHash.take(16)}…" else "No model installed", fontSize = 12.sp)
    Row {
        Switch(checked = enabled, enabled = !busy && app.localAi.supported, onCheckedChange = {
            enabled = it; app.settings.aiEnabled = it
            if(it) AiWorker.schedule(app) else task { app.localAi.unload(); "Local AI disabled. Saved rules remain available." }
        })
        Text("Enable local AI for uncertain messages", modifier = Modifier.padding(top = 12.dp, start = 8.dp))
    }
    Text("While enabled, financial SMS awaiting review is stored encrypted on this phone. Original SMS copies are deleted after approval/ignore. Inference is offline; only approved transaction fields go to Wallet.", fontSize = 12.sp)
    Row { Checkbox(gpu, onCheckedChange = { gpu = it; app.settings.preferGpu = it }); Text("Prefer GPU (automatically fall back to CPU)", modifier = Modifier.padding(top = 10.dp)) }
    OutlinedButton(enabled = !busy && model, onClick = { task {
        val body = "HDFC Bank: INR 123.45 debited from A/C XX9012 at DEMO SHOP on 06/11/2026. Avl bal INR 9000.00."
        val baseline = Transaction(Kind.UNKNOWN, null, accountKey = null, merchant = null, occurredAt = "2026-11-06T00:00:00Z", precision = Precision.SMS_RECEIVED)
        val result = app.localAi.infer(AiPrompt.create(body, baseline, emptyList(), emptyList()))
        val advice = AdviceValidator.parse(result, body, baseline, emptySet(), emptySet())
        require(sameAmount(advice.transaction.amount,"123.45") && advice.transaction.accountKey == "HDFC:ACCOUNT:9012") { "Model ran but missed the sample fields. Try another compatible model; review AI suggestions carefully." }
        report = "Sample extraction passed · ${app.settings.aiNotice}"
        report
    } }) { Text("Test model & measure speed") }
    if(report.isNotBlank()) Text(report, fontSize = 12.sp)
    TextButton(enabled = !busy, onClick = { AiWorker.schedule(app) }) { Text("Process pending AI suggestions") }
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
        OutlinedTextField(value = sms, onValueChange = { sms = it }, label = { Text("Financial SMS (local only, no OTPs)") }, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp), maxLines = 8)
        Button(enabled = !busy && app.settings.aiEnabled && sms.isNotBlank(), onClick = { val text = sms; task {
            app.repository.attachForAi(entry.id, text); "Message encrypted and queued for local AI"
        } }) { Text("Analyse this SMS locally") }
    }
    if(ev?.state == "FAILED" && ev.bodyCipher != null) TextButton(enabled = !busy, onClick = { task {
        app.db.store().evidence(ev.copy(state = "PENDING")); AiWorker.schedule(app); "AI retry queued"
    } }) { Text("Retry local AI") }
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
        Text("Enabled templates work without a loaded model. Clear amounts on confirmed INR accounts sync when automatic sync is on; transfers, repayments and uncertain fields require review. AI-proposed templates start disabled until you confirm them here.", fontSize = 12.sp)
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
