package io.github.jayeshb.walletsmssync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalContext
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
    private var reviewIntent by mutableStateOf<Intent?>(null)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ReviewNotifications.ROUTE) reviewIntent = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reviewIntent = intent.takeIf { it.action == ReviewNotifications.ROUTE }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF12654D), secondary = Color(0xFF48675B), background = Color(0xFFF6F8F5))) {
                Surface(Modifier.fillMaxSize()) { WalletScreen(wallet, reviewIntent) }
            }
        }
    }
}

@Composable private fun WalletScreen(app: WalletApp, reviewIntent: Intent?) {
    val entries by app.repository.store.observe().collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf("History") }
    var selected by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reviewIntent) {
        if (reviewIntent != null) {
            tab = "Review"
            selected = reviewIntent.data?.lastPathSegment
        }
    }
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
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("History", "Review", "Import", "Rules", "Learn", "Setup").forEach { name ->
                FilterChip(selected = tab == name, onClick = { tab = name; selected = null }, label = { Text(name) })
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (notice.isNotBlank()) Text(notice, modifier = Modifier.fillMaxWidth().background(Color(0xFFE5EEE7)).padding(10.dp), fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        val entry = entries.find { it.id == selected }
        if (entry != null) {
            TextButton(onClick = { selected = null }) { Text("← Back") }
            Box(Modifier.weight(1f)) {
                key(entry.id, entry.status, entry.parsed, entry.sourceAccount, entry.targetAccount, entry.categoryId) {
                    Detail(app, entry, entries, busy, ::runTask)
                }
            }
        } else when(tab) {
            "Setup" -> Box(Modifier.weight(1f)) { Setup(app, busy, ::runTask) }
            "Import" -> Box(Modifier.weight(1f)) { Import(app, busy, ::runTask) }
            "Rules" -> Box(Modifier.weight(1f)) { RulesScreen(app, busy, ::runTask) }
            "Learn" -> Box(Modifier.weight(1f)) { LearningScreen(app, busy, ::runTask) }
            else -> {
                if (tab == "Review") TextButton(enabled = !busy, onClick = { runTask {
                    val id = UUID.randomUUID().toString()
                    app.repository.ingest(SmsCandidate("manual:$id", System.currentTimeMillis(), hash(id), Transaction(Kind.UNKNOWN, null,
                        accountKey = null, merchant = null, occurredAt = Instant.now().toString(), precision = Precision.SMS_RECEIVED, reviewReason = "Manual transaction")), true)
                    "Manual item added; open it to enter income, expense, or transfer details"
                } }) { Text("+ Add manual transaction") }
                if (tab == "Review" && entries.any { it.status == "REVIEW" }) {
                    TextButton(enabled = !busy, onClick = { runTask { app.repository.recheckPending() } }) {
                        Text("Recheck pending items")
                    }
                }
                val sections = entries.groupBy { reviewSection(it.status) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val groups = if (tab == "Review") listOf(
                        ReviewSection.NEEDS_REVIEW to "Needs review", ReviewSection.UPLOAD_PENDING to "Upload pending"
                    ) else listOf(ReviewSection.HISTORY to "")
                    groups.forEach { (section, title) ->
                        if (title.isNotEmpty()) item { Text(title, fontWeight = FontWeight.Bold) }
                        val rows = sections[section].orEmpty()
                        if (rows.isEmpty()) item {
                            Text(when (section) {
                                ReviewSection.HISTORY -> "No processed transactions yet."
                                ReviewSection.NEEDS_REVIEW -> "Nothing needs review."
                                ReviewSection.UPLOAD_PENDING -> "No uploads pending."
                            })
                        }
                        items(rows, key = { it.id }) { e ->
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
                }
                if (tab == "Review" && !sections[ReviewSection.UPLOAD_PENDING].isNullOrEmpty()) {
                    TextButton(enabled = !busy, onClick = { app.settings.authBlocked = false; SyncWorker.schedule(app); notice = "Queued uploads scheduled. " + app.settings.notice }) { Text("Resume queued sync") }
                }
            }
        }
    }
}

@Composable internal fun Picker(label: String, value: String?, options: List<Pair<String, String>>, enabled: Boolean = true, onSelect: (String) -> Unit) {
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
@Composable internal fun Field(label: String, value: String, enabled: Boolean = true, onChange: (String) -> Unit) {
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
    var noteMode by remember { mutableStateOf(app.settings.noteTitleMode) }
    var backupPassphrase by remember { mutableStateOf("") }
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            val passphrase = backupPassphrase
            backupPassphrase = ""
            task {
                val output = requireNotNull(app.contentResolver.openOutputStream(uri, "wt")) { "Could not open the backup file for writing" }
                val count = output.use { LocalBackup(app.db, app.settings).save(it, passphrase) }
                "Encrypted backup saved with $count history items"
            }
        }
    }
    val restoreBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val passphrase = backupPassphrase
            backupPassphrase = ""
            task {
                val input = requireNotNull(app.contentResolver.openInputStream(uri)) { "Could not open the backup file" }
                val count = input.use { LocalBackup(app.db, app.settings).restore(it, passphrase) }
                load()
                auto = app.settings.automatic
                defaultCategory = app.settings.defaultCategory
                noteMode = app.settings.noteTitleMode
                "Restored $count history items and their duplicate checks"
            }
        }
    }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { load() } }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Connect once. Parse on your phone.", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Rules parse known messages on your phone. If you enable cloud AI, the app sends eligible financial SMS to the provider you select. Original SMS awaiting review is stored encrypted.", fontSize = 13.sp)
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
        Row { Checkbox(checked = auto, onCheckedChange = { auto = it; app.settings.automatic = it }); Text("Send directly when amount and confirmed account are clear", modifier = Modifier.padding(top = 10.dp)) }
        Text("Ambiguous amounts, unmapped accounts, transfers, repayments and historical imports require review. AI can choose a category; its category mappings are labeled AI in Learn.", fontSize = 12.sp)
        Picker("SMS title in Wallet notes",noteMode,listOf("SENDER" to "Sender label", "BOTH" to "Sender label and heading", "HEADING" to "Message heading / first line"),!busy) { noteMode = it; app.settings.noteTitleMode = it }
        HorizontalDivider()
        AiSetup(app, busy, task)
        HorizontalDivider()
        ReviewNotificationSetup(app)
        HorizontalDivider()
        Text("Local backup", fontWeight = FontWeight.Bold)
        Text("Save an encrypted copy to internal storage before uninstalling. After reinstalling, restore it here before granting SMS permissions or importing messages. Enter your Wallet token and cloud AI key again in Setup.", fontSize = 12.sp)
        OutlinedTextField(value = backupPassphrase, onValueChange = { backupPassphrase = it },
            label = { Text("Backup passphrase (12 or more characters)") }, enabled = !busy,
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy && backupPassphrase.length >= 12, onClick = {
                saveBackup.launch("wallet-sms-sync-${LocalDate.now()}.wsbackup")
            }) { Text("Save backup") }
            OutlinedButton(enabled = !busy && backupPassphrase.length >= 12, onClick = {
                restoreBackup.launch(arrayOf("application/octet-stream"))
            }) { Text("Restore backup") }
        }
        HorizontalDivider()
        Text("Account mapping", fontWeight = FontWeight.Bold)
        Text("Use BANK:TYPE:last4, e.g. IDFC:CARD:1234, HDFC:CARD:5678, HDFC:ACCOUNT:9012. Map only accounts you own. Prefer Wallet accounts without bank sync to avoid importing the same transaction twice.", fontSize = 12.sp)
        mappings.forEach { Text("${it.key} → ${accounts.find { a -> a.id == it.accountId }?.name ?: "Refresh accounts"}", fontSize = 12.sp) }
        Field("Bank account key", key, !busy) { key = it.uppercase(Locale.ROOT).trim() }
        Picker("Wallet account (INR)", account, accounts.filter { it.currency == "INR" }.map { it.id to it.name }, !busy) { account = it }
        Button(enabled = !busy, onClick = { task {
            require(Regex("[A-Z][A-Z0-9]{1,23}:(?:CARD|ACCOUNT):\\d{4}").matches(key)) { "Enter BANK:CARD:last4 or BANK:ACCOUNT:last4" }
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
            repo.store.rule(MerchantRule(FinancialMessages.merchantKey(merchant), override)); "Merchant category saved for new transactions"
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
            "${preview?.size ?: 0} new financial candidates found; nothing uploaded"
        } }) { Text("Preview messages") }
        preview?.let { list ->
            Text("${list.size} candidates", fontWeight = FontWeight.Bold)
            list.take(50).forEach { c -> Text("${c.transaction.kind} · INR ${c.transaction.amount ?: "?"} · ${c.transaction.merchant ?: "Needs details"}", fontSize = 12.sp) }
            if (list.size > 50) Text("First 50 shown; all ${list.size} will be available in Review.")
            Row { Checkbox(checked = accepted, onCheckedChange = { accepted = it }); Text("I reviewed this preview. Add these to Review and use cloud AI if enabled.", modifier = Modifier.padding(top = 10.dp)) }
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
    val legacyRepayment = parsed.kind == Kind.REPAYMENT && parsed.fromKey == null && parsed.toKey == null
    var source by remember { mutableStateOf(if(legacyRepayment && e.targetAccount == null) "" else e.sourceAccount.orEmpty()) }
    var target by remember { mutableStateOf((e.targetAccount ?: e.sourceAccount.takeIf { legacyRepayment }).orEmpty()) }
    var category by remember { mutableStateOf(e.categoryId.orEmpty()) }
    var link by remember { mutableStateOf("") }
    var accounts by remember { mutableStateOf(emptyList<Catalog>()) }
    var categories by remember { mutableStateOf(emptyList<Catalog>()) }
    var remote by remember { mutableStateOf(e.remoteId.orEmpty()) }
    var mirror by remember { mutableStateOf(e.mirrorId.orEmpty()) }
    var absent by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    var rememberCorrections by remember { mutableStateOf(true) }
    var rememberCategory by remember { mutableStateOf(true) }
    var rememberCounterparty by remember { mutableStateOf(false) }
    var match by remember { mutableStateOf<TransferMatch?>(null) }
    var submitted by remember { mutableStateOf<TransferMatch?>(null) }
    var accountKey by remember { mutableStateOf(parsed.accountKey.orEmpty()) }
    var smsTitle by remember { mutableStateOf(e.smsTitle.orEmpty()) }
    LaunchedEffect(e.id, entries.map { it.id to it.status }) { withContext(Dispatchers.IO) {
        accounts = app.repository.store.catalog("accounts"); categories = app.repository.store.catalog("categories")
        val mappings = app.repository.transferAccounts()
        val route = mappings.route(parsed)
        if (e.status == "REVIEW") {
            submitted = app.repository.transferMatch(e.id, parsed, submitted = true)
            match = app.repository.transferMatch(e.id, parsed)
            if (match != null || submitted != null || kind == Kind.TRANSFER) {
                val pair = (submitted ?: match)?.route ?: route
                kind = Kind.TRANSFER
                if (source.isEmpty() || parsed.kind == Kind.INCOME) source = pair.from.orEmpty()
                if (target.isEmpty()) target = (pair.to ?: mappings.issuerCard(parsed)).orEmpty()
                match?.takeIf { it.exact }?.let { link = it.entry.id }
            } else if (source.isEmpty()) source = mappings.ledger(parsed).orEmpty()
        }
    } }
    val editable = e.status == "REVIEW" && !busy
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Transaction details", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("${e.status} · ${e.message}")
        Text("${parsed.accountKey ?: "Unidentified account"} · Time precision: ${parsed.precision}", fontSize = 12.sp)
        parsed.reference?.let { Text("Bank reference: $it", fontSize = 12.sp) }
        if(e.status == "REVIEW") {
            AiReview(app, e, accounts, categories, busy, task)
            Picker("Transaction type", kind.name, listOf(Kind.EXPENSE, Kind.INCOME, Kind.TRANSFER).map { it.name to it.name }, editable) { kind = Kind.valueOf(it) }
            Field("Bank/card identifier (BANK:CARD:last4)", accountKey, editable) { accountKey = it.uppercase(Locale.ROOT).trim() }
            Field("SMS title for Wallet notes",smsTitle,editable) { smsTitle = it }
            Field("Amount (INR, positive)", amount, editable) { amount = it }
            Field("Merchant / payee", merchant, editable) { merchant = it }
            Field("Timestamp (ISO 8601 with timezone)", date, editable) { date = it }
            Picker(if (kind == Kind.INCOME) "Receiving account" else "From account", source, accounts.filter { it.currency == parsed.currency }.map { it.id to it.name }, editable) { source = it }
            if(kind == Kind.TRANSFER) {
                Picker("To account", target, accounts.filter { it.id != source && it.currency == parsed.currency }.map { it.id to it.name }, editable) { target = it }
                match?.let { evidence ->
                    val candidate = Json.decode(evidence.entry.parsed)
                    Picker("Matching SMS evidence", link, listOf("" to "None", evidence.entry.id to
                        "${candidate.accountKey} · ${if (evidence.exact) "Matching reference" else "Possible repayment match"}"), editable) { link = it }
                }
                submitted?.let { existing ->
                    Text("This SMS matches a transfer already submitted or awaiting Wallet.", fontSize = 12.sp)
                    if (existing.entry.linkedId == null) Button(enabled = editable, onClick = { task {
                        app.repository.linkExistingTransfer(e.id); "Evidence linked to the existing Wallet transfer"
                    } }) { Text("Link to existing transfer") }
                }
                if (!parsed.merchant.isNullOrBlank() && parsed.merchant != "Credit card repayment" && parsed.destinationIssuer == null) {
                    Row { Checkbox(rememberCounterparty, enabled = editable, onCheckedChange = { rememberCounterparty = it })
                        Text("Remember this counterparty as my other account", modifier = Modifier.padding(top = 10.dp)) }
                }
            } else Picker("Category", category, listOf("" to "Wallet default / Unknown") + categories.map { it.id to it.name }, editable) { category = it }
            if (kind == Kind.EXPENSE) {
                if (category.isNotBlank() && category != e.categoryId.orEmpty()) {
                    BulkCategoryReview(app, e.id, merchant, category, entries, editable, task)
                }
                Row { Checkbox(rememberCategory, enabled = editable, onCheckedChange = { rememberCategory = it })
                    Text("Remember category for this merchant", modifier = Modifier.padding(top = 10.dp)) }
            }
            Row { Checkbox(rememberCorrections, enabled = editable, onCheckedChange = { rememberCorrections = it }); Text("Remember account and learnable message format", modifier = Modifier.padding(top = 10.dp)) }
            Text("Reviewed patterns can be reused. Clear amounts on confirmed accounts can sync directly; repayments and transfers require review.", fontSize = 12.sp)
            Button(enabled = editable && submitted == null, onClick = { task {
                app.repository.setSmsTitle(e.id,smsTitle)
                app.repository.approve(e.id, parsed.copy(kind = kind, amount = amount.trim(), merchant = merchant.trim().takeIf { it.isNotEmpty() }, accountKey = accountKey.takeIf { it.isNotEmpty() },
                    occurredAt = Instant.parse(date.trim()).toString(), precision = if (date != parsed.occurredAt) Precision.SECOND else parsed.precision),
                    source, target.takeIf { kind == Kind.TRANSFER }, category.takeIf { it.isNotEmpty() && kind != Kind.TRANSFER }, link.takeIf { it.isNotEmpty() && kind == Kind.TRANSFER },
                    remember = rememberCorrections, rememberCategory = rememberCategory, rememberCounterparty = rememberCounterparty)
                "Approved and queued for Wallet${if(rememberCorrections) "; confirmed mappings and learnable format remembered" else ""}"
            } }) { Text("Approve & send to Wallet") }
            TextButton(enabled = editable, onClick = { task { app.repository.ignore(e.id); "Transaction ignored" } }) { Text("Ignore transaction") }
        }
        if(e.status != "REVIEW" && !e.smsTitle.isNullOrBlank()) Text("SMS title in Wallet note: ${e.smsTitle}",fontSize = 12.sp)
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
        if (e.status == "SYNCED" && e.remoteId != null) {
            val context = LocalContext.current
            var launchFailed by remember { mutableStateOf(false) }
            Text("${parsed.currency} ${parsed.amount ?: "—"} · ${accounts.find { it.id == e.sourceAccount }?.name ?: parsed.accountKey ?: "Wallet account"}")
            Text(Instant.parse(parsed.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate().toString())
            OutlinedButton(onClick = { launchFailed = !openWallet(context) }) { Text("Open Wallet") }
            if (launchFailed) Text("Wallet and a web browser are unavailable.")
        }
        if(e.linkedId != null) Text("Linked local evidence: ${e.linkedId}", fontSize = 12.sp)
        HorizontalDivider()
        Text("Extracted JSON", fontWeight = FontWeight.Bold)
        SelectionContainer { Text(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(parsed), fontSize = 12.sp) }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable private fun BulkCategoryReview(app: WalletApp, id: String, merchant: String, category: String,
    entries: List<Entry>, editable: Boolean, task: (suspend () -> String) -> Unit) {
    val matches = entries.filter { it.id != id && it.matchesUncategorizedExpense(merchant) }
    var selected by remember(merchant, category) { mutableStateOf<Set<String>?>(null) }
    if (matches.isNotEmpty()) TextButton(enabled = editable, onClick = { selected = emptySet() }) {
        Text("Apply to other uncategorized purchases from this merchant (${matches.size})")
    }
    val selection = selected ?: return
    AlertDialog(onDismissRequest = { selected = null }, title = { Text("Choose purchases to categorize") },
        text = {
            LazyColumn(Modifier.heightIn(max = 330.dp)) {
                items(matches, key = { it.id }) { entry ->
                    val t = Json.decode(entry.parsed)
                    Row {
                        Checkbox(entry.id in selection, enabled = editable, onCheckedChange = {
                            selected = if (it) selection + entry.id else selection - entry.id
                        })
                        Column(Modifier.padding(top = 8.dp)) {
                            Text(t.merchant.orEmpty())
                            Text("${Instant.parse(t.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate()} · ${t.currency} ${t.amount ?: "—"}")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = editable && matches.any { it.id in selection }, onClick = { task {
                val count = app.repository.applyCategoryToMatches(id, merchant, category, selection)
                selected = null
                "Updated $count purchases. They are still in Review."
            } }) { Text("Apply category") }
        },
        dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } })
}
