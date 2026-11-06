package io.github.jayeshb.walletsmssync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jayeshb.walletsmssync.core.*
import kotlinx.coroutines.*
import java.time.*
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF12654D), secondary = Color(0xFF48675B), background = Color(0xFFF6F8F5))) {
                Surface(Modifier.fillMaxSize()) { WalletScreen(wallet) }
            }
        }
    }
}

@Composable private fun WalletScreen(app: WalletApp) {
    val entries by app.repository.store.observe().collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf("History") }
    var selected by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf(app.settings.notice) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun runTask(task: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            notice = try { withContext(Dispatchers.IO) { task() } }
            catch (e: Exception) { when(e) {
                is CancellationException -> throw e
                is ApiProblem -> e.message.orEmpty()
                is IllegalArgumentException -> e.message ?: "Check the entered fields"
                is IllegalStateException -> "Action unavailable; check setup and selected transaction"
                is SecurityException -> "Grant SMS permission in Setup"
                else -> "Action failed. Check connection and setup, then try again."
            } }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Text("WALLET / SMS SYNC", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("Your messages. Organized.", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("${entries.count { it.status == "SYNCED" }} synced · ${entries.count { it.status == "REVIEW" }} to review · ${entries.count { it.status == "QUEUED" }} queued", modifier = Modifier.padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("History", "Review", "Import", "Setup").forEach { name ->
                FilterChip(selected = tab == name, onClick = { tab = name; selected = null }, label = { Text(name) })
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (notice.isNotBlank()) Text(notice, modifier = Modifier.fillMaxWidth().background(Color(0xFFE5EEE7)).padding(10.dp), fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        val entry = entries.find { it.id == selected }
        if (entry != null) {
            TextButton(onClick = { selected = null }) { Text("← Back") }
            Box(Modifier.weight(1f)) { key(entry.id, entry.status) { Detail(app, entry, entries, busy, ::runTask) } }
        } else when(tab) {
            "Setup" -> Box(Modifier.weight(1f)) { Setup(app, busy, ::runTask) }
            "Import" -> Box(Modifier.weight(1f)) { Import(app, busy, ::runTask) }
            else -> {
                if (tab == "Review") TextButton(enabled = !busy, onClick = { runTask {
                    val id = UUID.randomUUID().toString()
                    app.repository.ingest(SmsCandidate("manual:$id", System.currentTimeMillis(), hash(id), Transaction(Kind.UNKNOWN, null,
                        accountKey = null, merchant = null, occurredAt = Instant.now().toString(), precision = Precision.SMS_RECEIVED, reviewReason = "Manual transaction")), true)
                    "Manual item added; open it to enter income, expense, or transfer details"
                } }) { Text("+ Add manual transaction") }
                val visible = entries.filter { tab != "Review" || it.status in setOf("REVIEW", "UNCERTAIN") }
                if (visible.isEmpty()) Text(if(tab == "Review") "Nothing needs review." else "Start in Setup, then import a date range or receive a new financial SMS.", modifier = Modifier.padding(top = 24.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible, key = { it.id }) { e ->
                        val t = Json.decode(e.parsed)
                        OutlinedCard(onClick = { selected = e.id }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(t.merchant ?: "Needs details", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Text("${t.currency} ${t.amount ?: "—"}", fontWeight = FontWeight.Bold)
                                }
                                Text("${t.kind} · ${e.status}", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                                Text("${t.accountKey ?: "No account"} · ${Instant.parse(t.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate()}", fontSize = 12.sp)
                                if (e.message.isNotBlank()) Text(e.message, fontSize = 12.sp)
                            }
                        }
                    }
                }
                TextButton(enabled = !busy, onClick = { app.settings.authBlocked = false; SyncWorker.schedule(app); notice = "Queued uploads scheduled. " + app.settings.notice }) { Text("Resume queued sync") }
            }
        }
    }
}

@Composable private fun Picker(label: String, value: String?, options: List<Pair<String, String>>, enabled: Boolean = true, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp)
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(options.find { it.first == value }?.second ?: "Choose…")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); open = false }) }
        }
    }
}
@Composable private fun Field(label: String, value: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable private fun Setup(app: WalletApp, busy: Boolean, task: (suspend () -> String) -> Unit) {
    val repo = app.repository
    var token by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf(app.settings.token() != null) }
    var auto by remember { mutableStateOf(app.settings.automatic) }
    var accounts by remember { mutableStateOf(emptyList<Catalog>()) }
    var categories by remember { mutableStateOf(emptyList<Catalog>()) }
    var mappings by remember { mutableStateOf(emptyList<AccountMapping>()) }
    var key by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var defaultCategory by remember { mutableStateOf(app.settings.defaultCategory) }
    var merchant by remember { mutableStateOf("") }
    var override by remember { mutableStateOf("") }
    var permission by remember { mutableStateOf(app.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED && app.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { values ->
        permission = listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS).all {
            values[it] == true || app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }
    suspend fun load() { accounts = repo.store.catalog("accounts"); categories = repo.store.catalog("categories"); mappings = repo.store.mappings() }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { load() } }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Connect once. Parse on your phone.", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Financial SMS is processed locally. Unrelated messages and OTPs are ignored. Only approved transaction fields are sent to Wallet; SMS bodies are not stored.", fontSize = 13.sp)
        Button(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)) }) { Text(if(permission) "SMS permissions granted" else "Grant SMS permissions") }
        Text(if(hasToken) "Wallet token saved securely" else "No Wallet token saved")
        OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("New Wallet API token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && token.isNotBlank(), onClick = { val replacement = token; token = ""; task {
                app.settings.saveToken(replacement); hasToken = true; SyncWorker.schedule(app); "Token saved; queued sync resumed"
            } }) { Text("Save token") }
            TextButton(enabled = !busy && hasToken, onClick = { task { app.settings.saveToken(""); hasToken = false; "Token removed; pending uploads paused" } }) { Text("Remove") }
        }
        OutlinedButton(enabled = !busy && hasToken, onClick = { task { repo.refreshCatalog(); load(); "Accounts and categories refreshed" } }) { Text("Fetch Wallet accounts & categories") }
        Row { Checkbox(checked = auto, onCheckedChange = { auto = it; app.settings.automatic = it }); Text("Automatically send new recognized purchases from mapped accounts", modifier = Modifier.padding(top = 10.dp)) }
        Text("UPI, repayments, unknown formats, and historical imports always require review.", fontSize = 12.sp)
        HorizontalDivider()
        Text("Account mapping", fontWeight = FontWeight.Bold)
        Text("Use BANK:TYPE:last4, e.g. IDFC:CARD:1234, HDFC:CARD:5678, HDFC:ACCOUNT:9012. Map only accounts you own. Prefer Wallet accounts without bank sync to avoid importing the same transaction twice.", fontSize = 12.sp)
        mappings.forEach { Text("${it.key} → ${accounts.find { a -> a.id == it.accountId }?.name ?: "Refresh accounts"}", fontSize = 12.sp) }
        Field("Bank account key", key, !busy) { key = it.uppercase(Locale.ROOT).trim() }
        Picker("Wallet account (INR)", account, accounts.filter { it.currency == "INR" }.map { it.id to it.name }, !busy) { account = it }
        Button(enabled = !busy, onClick = { task {
            require(Regex("(?:IDFC|HDFC):(?:CARD|ACCOUNT):\\d{4}").matches(key)) { "Enter BANK:CARD:last4 or BANK:ACCOUNT:last4" }
            require(accounts.any { it.id == account && it.currency == "INR" }) { "Choose an INR account" }
            repo.store.mapping(AccountMapping(key, account)); load(); "Mapping saved; approve existing review items individually"
        } }) { Text("Save mapping") }
        HorizontalDivider()
        Picker("Default category", defaultCategory, listOf("" to "Wallet default / Unknown") + categories.map { it.id to it.name }, !busy) {
            defaultCategory = it; app.settings.defaultCategory = it
        }
        Field("Merchant override (exact name, case insensitive)", merchant, !busy) { merchant = it }
        Picker("Override category", override, categories.map { it.id to it.name }, !busy) { override = it }
        Button(enabled = !busy, onClick = { task {
            require(merchant.isNotBlank() && categories.any { it.id == override }) { "Enter merchant and choose a category" }
            repo.store.rule(MerchantRule(merchant.trim().lowercase(Locale.ROOT), override)); "Merchant category saved for new transactions"
        } }) { Text("Save merchant rule") }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable private fun Import(app: WalletApp, busy: Boolean, task: (suspend () -> String) -> Unit) {
    var start by remember { mutableStateOf(LocalDate.now().minusDays(30).toString()) }
    var end by remember { mutableStateOf(LocalDate.now().toString()) }
    var preview by remember { mutableStateOf<List<SmsCandidate>?>(null) }
    var accepted by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Import your inbox", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Choose SMS receipt dates. Previewing never uploads. Imported items enter Review, where you approve each upload.", fontSize = 13.sp)
        Field("From (YYYY-MM-DD)", start, !busy) { start = it; preview = null; accepted = false }
        Field("Through (YYYY-MM-DD)", end, !busy) { end = it; preview = null; accepted = false }
        Button(enabled = !busy, onClick = { task {
            preview = app.repository.preview(LocalDate.parse(start), LocalDate.parse(end)); accepted = false
            "${preview?.size ?: 0} financial candidates found; nothing uploaded"
        } }) { Text("Preview messages") }
        preview?.let { list ->
            Text("${list.size} candidates", fontWeight = FontWeight.Bold)
            list.take(50).forEach { c -> Text("${c.transaction.kind} · INR ${c.transaction.amount ?: "?"} · ${c.transaction.merchant ?: "Needs details"}", fontSize = 12.sp) }
            if (list.size > 50) Text("First 50 shown; all ${list.size} will be available in Review.")
            Row { Checkbox(checked = accepted, onCheckedChange = { accepted = it }); Text("I reviewed this preview. Add these to the local review queue.", modifier = Modifier.padding(top = 10.dp)) }
            Button(enabled = accepted && !busy && list.isNotEmpty(), onClick = { task {
                var added = 0
                list.forEach { if(app.repository.ingest(it, historical = true)) added++ }
                preview = null; accepted = false
                "$added new items added to Review; ${list.size - added} duplicates skipped. Approve items there to upload."
            } }) { Text("Import to Review") }
        }
    }
}

@Composable private fun Detail(app: WalletApp, e: Entry, entries: List<Entry>, busy: Boolean, task: (suspend () -> String) -> Unit) {
    val parsed = remember(e.parsed) { Json.decode(e.parsed) }
    var kind by remember { mutableStateOf(if(parsed.kind == Kind.REPAYMENT) Kind.TRANSFER else parsed.kind) }
    var amount by remember { mutableStateOf(parsed.amount.orEmpty()) }
    var merchant by remember { mutableStateOf(parsed.merchant.orEmpty()) }
    var date by remember { mutableStateOf(parsed.occurredAt) }
    var source by remember { mutableStateOf(if(parsed.kind == Kind.REPAYMENT) "" else e.sourceAccount.orEmpty()) }
    var target by remember { mutableStateOf(if(parsed.kind == Kind.REPAYMENT) e.sourceAccount.orEmpty() else e.targetAccount.orEmpty()) }
    var category by remember { mutableStateOf(e.categoryId.orEmpty()) }
    var link by remember { mutableStateOf("") }
    var accounts by remember { mutableStateOf(emptyList<Catalog>()) }
    var categories by remember { mutableStateOf(emptyList<Catalog>()) }
    var remote by remember { mutableStateOf(e.remoteId.orEmpty()) }
    var mirror by remember { mutableStateOf(e.mirrorId.orEmpty()) }
    var absent by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    LaunchedEffect(e.id) { withContext(Dispatchers.IO) {
        accounts = app.repository.store.catalog("accounts"); categories = app.repository.store.catalog("categories")
        val mapped = app.repository.store.mappings().find { it.key == parsed.accountKey }?.accountId.orEmpty()
        if (parsed.kind == Kind.REPAYMENT && target.isEmpty()) target = mapped
        else if (source.isEmpty()) source = mapped
    } }
    val editable = e.status == "REVIEW" && !busy
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Transaction details", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("${e.status} · ${e.message}")
        Text("${parsed.accountKey ?: "Unidentified account"} · Time precision: ${parsed.precision}", fontSize = 12.sp)
        parsed.reference?.let { Text("Bank reference: $it", fontSize = 12.sp) }
        if(e.status == "REVIEW") {
            Picker("Transaction type", kind.name, listOf(Kind.EXPENSE, Kind.INCOME, Kind.TRANSFER).map { it.name to it.name }, editable) { kind = Kind.valueOf(it) }
            Field("Amount (INR, positive)", amount, editable) { amount = it }
            Field("Merchant / payee", merchant, editable) { merchant = it }
            Field("Timestamp (ISO 8601 with timezone)", date, editable) { date = it }
            Picker("Source account", source, accounts.filter { it.currency == parsed.currency }.map { it.id to it.name }, editable) { source = it }
            if(kind == Kind.TRANSFER) {
                Picker("Destination account", target, accounts.filter { it.id != source && it.currency == parsed.currency }.map { it.id to it.name }, editable) { target = it }
                Text("One Wallet transfer creates both sides. Link a matching unsubmitted SMS below to avoid uploading it separately.", fontSize = 12.sp)
                val eligible = entries.filter { other -> other.id != e.id && other.status == "REVIEW" && runCatching {
                    val t = Json.decode(other.parsed)
                    sameAmount(t.amount, amount) &&
                        kotlin.math.abs(Duration.between(Instant.parse(t.occurredAt), Instant.parse(date)).toHours()) <= 72
                }.getOrDefault(false) }
                Picker("Other SMS evidence (optional)", link, listOf("" to "None") + eligible.map { it.id to "${Json.decode(it.parsed).accountKey} · ${Json.decode(it.parsed).merchant}" }, editable) { link = it }
            } else Picker("Category", category, listOf("" to "Wallet default / Unknown") + categories.map { it.id to it.name }, editable) { category = it }
            Button(enabled = editable, onClick = { task {
                app.repository.approve(e.id, parsed.copy(kind = kind, amount = amount.trim(), merchant = merchant.trim().takeIf { it.isNotEmpty() },
                    occurredAt = Instant.parse(date.trim()).toString(), precision = if (date != parsed.occurredAt) Precision.SECOND else parsed.precision),
                    source, target.takeIf { kind == Kind.TRANSFER }, category.takeIf { it.isNotEmpty() && kind != Kind.TRANSFER }, link.takeIf { it.isNotEmpty() && kind == Kind.TRANSFER })
                "Approved and queued for Wallet"
            } }) { Text("Approve & send to Wallet") }
            TextButton(enabled = editable, onClick = { task { app.repository.ignore(e.id); "Transaction ignored" } }) { Text("Ignore transaction") }
        }
        if (e.status == "UNCERTAIN") {
            Text("Wallet may already contain this transaction. Reconciliation searches a unique marker; no result is not proof of absence because Wallet sync can lag.", fontSize = 13.sp)
            Button(enabled = !busy, onClick = { task { app.repository.reconcile(e.id) } }) { Text("Check Wallet for this record") }
            Field("Verified source record ID", remote, !busy) { remote = it }
            if(parsed.kind == Kind.TRANSFER) Field("Verified destination record ID", mirror, !busy) { mirror = it }
            Row { Checkbox(confirmed, onCheckedChange = { confirmed = it }); Text("I checked this transaction in Wallet", modifier = Modifier.padding(top = 10.dp)) }
            Button(enabled = confirmed && !busy, onClick = { task { app.repository.resolve(e.id, remote, mirror, false); "Remote IDs linked" } }) { Text("Save verified IDs") }
            if(e.remoteId == null) {
                Row { Checkbox(absent, onCheckedChange = { absent = it }); Text("I verified in Wallet that neither side exists", modifier = Modifier.padding(top = 10.dp)) }
                OutlinedButton(enabled = absent && confirmed && !busy, onClick = { task { app.repository.resolve(e.id, "", "", true); "Returned to Review; inspect before resubmitting" } }) { Text("Return confirmed-absent item to Review") }
            }
        }
        if(e.remoteId != null) SelectionContainer { Text("Wallet record: ${e.remoteId}\nDestination record: ${e.mirrorId ?: "—"}", fontSize = 12.sp) }
        if(e.linkedId != null) Text("Linked local evidence: ${e.linkedId}", fontSize = 12.sp)
        HorizontalDivider()
        Text("Extracted JSON", fontWeight = FontWeight.Bold)
        SelectionContainer { Text(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(parsed), fontSize = 12.sp) }
        Spacer(Modifier.height(20.dp))
    }
}
