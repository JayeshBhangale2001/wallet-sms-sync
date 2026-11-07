package io.github.jayeshb.walletsmssync

import android.app.Application
import android.content.Context
import android.provider.Telephony
import androidx.room.Room
import androidx.room.withTransaction
import io.github.jayeshb.walletsmssync.core.*
import java.time.*
import java.util.Locale
import java.util.UUID

class WalletApp : Application() {
    val db by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "wallet.db").addMigrations(MIGRATION_1_2).build() }
    val settings by lazy { Settings(this) }
    val repository by lazy { Repository(this, db, settings) }
    override fun onCreate() { super.onCreate(); SyncWorker.schedule(this); AiWorker.schedule(this) }
}
val Context.wallet: WalletApp get() = applicationContext as WalletApp
data class SmsCandidate(val source: String, val received: Long, val fingerprint: String, val transaction: Transaction, val body: String? = null, val title: String? = null, val sent: Long = received) {
    val messageHash: String get() = body?.let { FinancialMessages.identity(it, sent) } ?: fingerprint
    val receiptHash: String get() = body?.let { FinancialMessages.identity(it, received) } ?: fingerprint
}
class Repository(private val context: Context, val db: AppDatabase, val settings: Settings, private val enqueue: (Context) -> Unit = { SyncWorker.schedule(it) }) {
    val store = db.store()
    private val parser = SmsParser()
    fun candidate(body: String, received: Long, source: String, sender: String? = null, sent: Long = received): SmsCandidate? {
        val parsed = parser.parse(body, received) ?: if (FinancialMessages.eligible(body)) Transaction(Kind.UNKNOWN, null, accountKey = null,
            merchant = null, occurredAt = Instant.ofEpochMilli(received).toString(), precision = Precision.SMS_RECEIVED,
            reviewReason = "Unfamiliar financial SMS format") else return null
        return SmsCandidate(source, received, fingerprint(parsed, parser.normalize(body), received), parsed, body, SmsTitles.create(sender,body,settings.noteTitleMode), sent)
    }
    private suspend fun knownEvidence(c: SmsCandidate): Boolean =
        store.evidenceByHash(c.messageHash) != null ||
            (c.receiptHash != c.messageHash && store.evidenceByHash(c.receiptHash) != null)

    suspend fun ingest(c: SmsCandidate, historical: Boolean): Boolean {
        val body = c.body
        if (knownEvidence(c)) return false
        val matching = if (body != null) store.templates().filter { it.enabled }.mapNotNull { row ->
            runCatching { TemplateLearning.apply(Json.gson.fromJson(row.spec, TemplateSpec::class.java), body)?.let { row to it } }.getOrNull()
        } else emptyList()
        val learned = matching.singleOrNull()
        // Conflicting learned formats never select a winner silently.
        val learnedTransaction = learned?.second?.let { if(c.transaction.kind == Kind.REPAYMENT) it.copy(kind = Kind.REPAYMENT) else it }
        val t = if (learnedTransaction != null && (c.transaction.kind == Kind.UNKNOWN || c.transaction.reviewReason != null)) learnedTransaction else c.transaction
        val referenceKey = FinancialMessages.referenceIdentity(if(c.transaction.kind != Kind.UNKNOWN) c.transaction else t)
        val account = store.mappings().find { it.key == t.accountKey }?.accountId
        val mapped = store.catalog("accounts").find { it.id == account && it.currency == t.currency }
        val merchantCategory = t.merchant?.let { store.categoryFor(it.lowercase(Locale.ROOT)) }
        val category = merchantCategory ?: settings.defaultCategory.takeIf { it.isNotBlank() }
        val aiNeeded = settings.aiEnabled && body != null && FinancialMessages.eligible(body) &&
            (matching.size > 1 || t.kind == Kind.UNKNOWN || (t.reviewReason != null && learned == null) || mapped == null || merchantCategory == null)
        val clear = DirectSyncPolicy.clear(t,c.transaction,body,mapped != null)
        val canCategorizeNow = aiNeeded && !settings.aiBlocked && settings.aiKey() != null
        val canAuto = !historical && settings.automatic && clear && !canCategorizeNow && matching.size <= 1
        val entry = Entry(UUID.randomUUID().toString(), c.fingerprint, Json.encode(t), if (canAuto) "QUEUED" else "REVIEW", c.source,
            sourceAccount = account, categoryId = category, message = if(canAuto) "Auto-queued: validated amount and confirmed account" else t.reviewReason ?: if (historical) "Historical import: approve upload" else if (account == null) "Map source account" else "Ready for review", smsTitle = c.title)
        val added = db.withTransaction {
            if (knownEvidence(c)) return@withTransaction false
            if (referenceKey != null && store.evidenceByReference(referenceKey) != null) return@withTransaction false
            val inserted = store.insert(entry) != -1L
            val savedId = if (inserted) entry.id else store.byFingerprint(c.fingerprint)?.id ?: return@withTransaction false
            if (!inserted && store.evidence(savedId) != null) return@withTransaction false
            val keepBody = inserted && settings.aiEnabled && !canAuto && body != null && FinancialMessages.eligible(body)
            store.evidence(SmsEvidence(savedId, c.messageHash, c.received, Json.encode(c.transaction),
                bodyCipher = if (keepBody) settings.sealEvidence(body!!) else null, state = if (inserted && aiNeeded && !canAuto) "PENDING" else "NONE",
                reason = if (matching.size > 1) "Conflicting learned templates" else entry.message, referenceKey = referenceKey, historical = historical))
            inserted
        }
        if (added && canAuto) enqueue(context)
        if (added && aiNeeded && !canAuto) AiWorker.schedule(context)
        return added
    }
    suspend fun preview(start: LocalDate, end: LocalDate): List<SmsCandidate> {
        require(!end.isBefore(start)) { "End date must follow start date" }
        val zone = ZoneId.systemDefault()
        val list = mutableListOf<SmsCandidate>()
        context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("_id", "body", "date", "address", "date_sent"), "date >= ? AND date < ?",
            arrayOf(start.atStartOfDay(zone).toInstant().toEpochMilli().toString(), end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().toString()), "date ASC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val received = cursor.getLong(2)
                val sent = cursor.getLong(4).takeIf { it > 0 } ?: received
                candidate(cursor.getString(1), received, "inbox:${cursor.getLong(0)}", cursor.getString(3), sent)?.let(list::add)
            }
        }
        return list.distinctBy { it.messageHash }.filter { c ->
            !knownEvidence(c) && store.byFingerprint(c.fingerprint) == null &&
                FinancialMessages.referenceIdentity(c.transaction)?.let { store.evidenceByReference(it) } == null
        }
    }
    suspend fun approve(id: String, t: Transaction, source: String, target: String?, category: String?, link: String?, remember: Boolean = false) {
        WalletPayload.create(t, source, target, category, id) // validates before touching the queue
        require(Instant.parse(t.occurredAt).isBefore(Instant.now().plusSeconds(86400))) { "Date is too far in the future" }
        val accounts = store.catalog("accounts")
        require(accounts.any { it.id == source && it.currency == t.currency }) { "Select a current ${t.currency} source account" }
        if (t.kind == Kind.TRANSFER) require(accounts.any { it.id == target && it.currency == t.currency }) { "Select a current same-currency target account" }
        if (!category.isNullOrBlank()) require(store.catalog("categories").any { it.id == category }) { "Refresh and select a valid category" }
        db.withTransaction {
            val e = store.get(id) ?: error("Transaction missing")
            require(e.status == "REVIEW") { "Only review items can be approved" }
            val evidence = store.evidence(id)
            val original = Json.decode(evidence?.baseline ?: e.parsed)
            val referenceKey = evidence?.referenceKey ?: FinancialMessages.referenceIdentity(if(original.reference != null) original else t)
            if(referenceKey != null) require(store.evidenceByReference(referenceKey)?.entryId.let { it == null || it == id }) { "This bank reference already belongs to another transaction; ignore the duplicate" }
            if (!link.isNullOrBlank()) {
                require(t.kind == Kind.TRANSFER) { "Evidence linking requires a transfer" }
                val other = store.get(link) ?: error("Linked transaction missing")
                require(other.id != id && other.status == "REVIEW") { "Evidence must be an unsubmitted review item" }
                val ot = Json.decode(other.parsed)
                require(sameAmount(ot.amount, t.amount)) { "Evidence amounts differ" }
                require(kotlin.math.abs(Duration.between(Instant.parse(ot.occurredAt), Instant.parse(t.occurredAt)).toHours()) <= 72) { "Evidence dates differ by more than three days" }
                store.update(other.copy(status = "LINKED", linkedId = id, message = "Evidence for confirmed transfer"))
                store.evidence(other.id)?.let { store.evidence(it.copy(bodyCipher = null, state = "LINKED")) }
            }
            store.update(e.copy(parsed = Json.encode(t.copy(reviewReason = null)), sourceAccount = source, targetAccount = target,
                categoryId = category, status = "QUEUED", linkedId = link, message = "Approved"))
            var templateId: String? = null
            if (remember) {
                val confirmedKey = t.accountKey
                val confirmedMerchant = t.merchant
                if (!confirmedKey.isNullOrBlank()) {
                    require(Regex("[A-Z][A-Z0-9]{1,23}:(?:CARD|ACCOUNT):[0-9]{4}").matches(confirmedKey)) { "Use BANK:CARD:last4 or BANK:ACCOUNT:last4" }
                    // A repayment's extracted key names its destination card, not the paying account.
                    val originalKind = Json.decode(evidence?.baseline ?: e.parsed).kind
                    val extractedKind = Json.decode(e.parsed).kind
                    val accountId = if ((originalKind == Kind.REPAYMENT || extractedKind == Kind.REPAYMENT) && t.kind == Kind.TRANSFER) requireNotNull(target) else source
                    store.mapping(AccountMapping(confirmedKey, accountId))
                }
                if (!confirmedMerchant.isNullOrBlank() && !category.isNullOrBlank() && t.kind != Kind.TRANSFER)
                    store.rule(MerchantRule(confirmedMerchant.lowercase(Locale.ROOT), category))
                val sms = evidence?.bodyCipher?.let { settings.openEvidence(it) }
                val spec = sms?.let { TemplateLearning.compile(it, t) }
                if (spec != null) {
                    templateId = hash(spec.expression)
                    val old = store.templates().find { it.id == templateId }
                    // Contradictory corrections retire the previous rule; user must explicitly re-trust it.
                    val sameKind = old?.let { Json.gson.fromJson(it.spec, TemplateSpec::class.java).kind == spec.kind } ?: true
                    store.template(LearnedTemplate(templateId, Json.gson.toJson(spec), spec.example, enabled = sameKind,
                        confirmations = (old?.confirmations ?: 0) + 1))
                }
            }
            store.decision(LearningDecision(id, evidence?.baseline ?: e.parsed, Json.gson.toJson(mapOf("transaction" to t, "sourceAccountId" to source,
                "targetAccountId" to target, "categoryId" to category)), evidence?.reason ?: e.message, templateId))
            evidence?.let { store.evidence(it.copy(bodyCipher = null, state = "REVIEWED", referenceKey = referenceKey)) }
        }
        enqueue(context)
    }
    suspend fun ignore(id: String) { db.withTransaction { store.get(id)?.let {
        require(it.status == "REVIEW") { "Only review items can be ignored" }; store.update(it.copy(status = "IGNORED"))
        store.evidence(id)?.let { evidence -> store.evidence(evidence.copy(bodyCipher = null, state = "IGNORED")) }
    } } }

    suspend fun attachForAi(id: String, body: String) {
        require(settings.aiEnabled) { "Enable cloud AI in Setup first" }
        require(FinancialMessages.eligible(body)) { "Use a financial SMS without OTPs (up to 2000 characters)" }
        db.withTransaction {
            val e = requireNotNull(store.get(id)); require(e.status == "REVIEW")
            val previous = store.evidence(id)
            val h = FinancialMessages.identity(body,previous?.received ?: e.createdAt)
            require(store.evidenceByHash(h)?.entryId.let { it == null || it == id }) { "This SMS already belongs to another transaction" }
            val messageHash = if (e.sourceId.startsWith("manual:")) h else previous?.messageHash ?: h
            store.evidence(SmsEvidence(id, messageHash, previous?.received ?: e.createdAt, previous?.baseline ?: e.parsed,
                settings.sealEvidence(body), "PENDING", reason = previous?.reason ?: e.message, referenceKey = previous?.referenceKey, historical = previous?.historical ?: true))
        }
        AiWorker.schedule(context)
    }
    suspend fun analyse(id: String, engine: SuggestionEngine): String {
        val evidence = requireNotNull(store.evidence(id)) { "Attach the original SMS first" }
        val body = requireNotNull(evidence.bodyCipher?.let(settings::openEvidence)) { "SMS unavailable; attach it again" }
        val e = requireNotNull(store.get(id)); require(e.status == "REVIEW")
        val accounts = store.catalog("accounts").filter { it.currency == "INR" }
        val categories = store.catalog("categories")
        val baseline = Json.decode(evidence.baseline)
        val categoryIds = categories.take(50).mapIndexed { index, category -> "c$index" to category }.toMap()
        require(FinancialMessages.eligible(body)) { "Only eligible financial SMS can be sent to cloud AI" }
        val output = engine.infer(AiPrompt.create(body, baseline, categoryIds.map { (alias, category) -> alias to category.name }))
        val extracted = AdviceValidator.parse(output, body, baseline, emptySet(), categoryIds.keys)
        val advice = extracted.copy(categoryId = categoryIds[extracted.categoryId]?.id)
        var autoQueued = false
        db.withTransaction {
            val current = requireNotNull(store.get(id))
            // Never overwrite a user's approval, ignore or newly attached message while inference was running.
            if (current.status == "REVIEW" && store.evidence(id)?.bodyCipher == evidence.bodyCipher) {
                val t = advice.transaction
                val mapping = store.mappings().find { it.key == t.accountKey }
                val account = accounts.find { it.id == mapping?.accountId && it.currency == t.currency }
                val clear = DirectSyncPolicy.clear(t,Json.decode(evidence.baseline),body,account != null)
                if(!evidence.historical && settings.aiEnabled && settings.automatic && clear && !evidence.reason.contains("Conflicting learned")) {
                    val ref = FinancialMessages.referenceIdentity(t)
                    require(ref == null || store.evidenceByReference(ref)?.entryId.let { it == null || it == id }) { "Duplicate bank reference; review this item" }
                    val category = t.merchant?.let { store.categoryFor(it.lowercase(Locale.ROOT)) } ?: advice.categoryId ?: current.categoryId
                    store.update(current.copy(parsed = Json.encode(t.copy(reviewReason = null)),sourceAccount = account!!.id,categoryId = category,status = "QUEUED",message = "Auto-queued: AI amount validated against SMS and account already confirmed"))
                    val merchant = t.merchant
                    val suggestedCategory = advice.categoryId
                    if(merchant != null && suggestedCategory != null && store.categoryFor(merchant.lowercase(Locale.ROOT)) == null)
                        store.rule(MerchantRule(merchant.lowercase(Locale.ROOT),suggestedCategory,"AI"))
                    val spec = TemplateLearning.compile(body,t)
                    if(spec != null) {
                        val ruleId = hash(spec.expression)
                        if(store.templates().none { it.id == ruleId }) store.template(LearnedTemplate(ruleId,Json.gson.toJson(spec),spec.example,enabled = false,confirmations = 0,origin = "AI"))
                    }
                    store.decision(LearningDecision(id,evidence.baseline,Json.gson.toJson(mapOf("transaction" to t,"sourceAccountId" to account.id,"categoryId" to category)),"Automatic sync: grounded amount and previously confirmed account; AI category cached",spec?.let { hash(it.expression) }))
                    store.evidence(evidence.copy(bodyCipher = null,state = "AUTO_QUEUED",suggestion = Json.gson.toJson(advice),referenceKey = ref))
                    autoQueued = true
                } else store.evidence(evidence.copy(state = "READY", suggestion = Json.gson.toJson(advice), reason = evidence.reason + "; AI suggestion ready"))
            }
        }
        if(autoQueued) { enqueue(context); return "Validated transaction sent to the Wallet queue; AI category remembered" }
        return "AI suggestion ready; review it before applying"
    }
    suspend fun applyAdvice(id: String) {
        db.withTransaction {
            val e = requireNotNull(store.get(id)); require(e.status == "REVIEW")
            val evidence = requireNotNull(store.evidence(id)); require(evidence.state == "READY")
            val advice = Json.gson.fromJson(requireNotNull(evidence.suggestion), AiAdvice::class.java)
            val confirmed = store.mappings().find { it.key == advice.transaction.accountKey }?.accountId
            val source = if(advice.transaction.kind in setOf(Kind.REPAYMENT,Kind.TRANSFER)) confirmed else e.sourceAccount ?: advice.sourceAccountId
            store.update(e.copy(parsed = Json.encode(advice.transaction), sourceAccount = source,
                categoryId = advice.categoryId ?: e.categoryId, message = "AI suggestions applied to form; verify and approve"))
            store.evidence(evidence.copy(state = "APPLIED"))
        }
    }
    suspend fun setSmsTitle(id: String, title: String) {
        db.withTransaction { val e = requireNotNull(store.get(id)); require(e.status == "REVIEW"); store.update(e.copy(smsTitle = title.trim().take(120).takeIf { it.isNotEmpty() })) }
    }
    suspend fun rememberedTransfer(key: String?): Pair<String,String>? {
        if(key == null || store.mappings().none { it.key == key }) return null
        val json = store.transferDecision("%\"accountKey\":\"$key\"%") ?: return null
        return runCatching {
            val fields = Json.gson.fromJson(json,com.google.gson.JsonObject::class.java)
            val source = fields["sourceAccountId"].asString; val target = fields["targetAccountId"].asString
            val accounts = store.catalog("accounts").filter { it.currency == "INR" }.map { it.id }
            require(source != target && source in accounts && target in accounts)
            source to target
        }.getOrNull()
    }
    suspend fun refreshCatalog() {
        require(System.currentTimeMillis() >= settings.notBefore) { "Wallet cooldown active; try later" }
        val api = WalletApi(settings.token() ?: error("Save a Wallet token first"))
        try {
            store.replaceCatalog("accounts", api.catalog("accounts"))
            store.replaceCatalog("categories", api.catalog("categories"))
            settings.notice = "Accounts and categories refreshed"
        } catch (e: ApiProblem) {
            if (e.code in setOf(429, 409)) settings.notBefore = System.currentTimeMillis() + e.delaySeconds * 1000
            throw e
        }
    }
    suspend fun reconcile(id: String): String {
        require(System.currentTimeMillis() >= settings.notBefore) { "Wallet cooldown active; try later" }
        val e = store.get(id) ?: error("Transaction missing")
        require(e.status == "UNCERTAIN")
        val records = try { WalletApi(settings.token() ?: error("Save a token first")).findMarker(id) }
        catch (problem: ApiProblem) {
            if (problem.code in setOf(429, 409)) settings.notBefore = System.currentTimeMillis() + problem.delaySeconds * 1000
            throw problem
        }
        val source = records.filter { it["accountId"]?.asString == e.sourceAccount }
        if (source.size == 1 && Json.decode(e.parsed).kind != Kind.TRANSFER) {
            store.update(e.copy(status = "SYNCED", remoteId = source.single()["id"].asString, message = "Reconciled by unique marker"))
            return "Remote record found and linked"
        }
        return if (records.isEmpty()) "Not visible yet. Wallet sync can lag; check Wallet before using manual resolution."
        else "${records.size} matching record(s). Verify source and destination in Wallet, then enter their IDs below."
    }
    suspend fun resolve(id: String, remote: String, mirror: String, confirmedAbsent: Boolean) {
        db.withTransaction {
            val e = store.get(id) ?: error("Transaction missing")
            require(e.status == "UNCERTAIN")
            if (confirmedAbsent) {
                require(e.remoteId == null) { "A known remote record exists; resolve it in Wallet first" }
                e.linkedId?.let { other -> store.get(other)?.let { store.update(it.copy(status = "REVIEW", linkedId = null)) } }
                store.update(e.copy(status = "REVIEW", linkedId = null, message = "User confirmed absence in Wallet; review before resubmitting"))
            } else {
                require(remote.isNotBlank()) { "Enter the verified source record ID" }
                if (Json.decode(e.parsed).kind == Kind.TRANSFER) require(mirror.isNotBlank()) { "Verify and enter destination record ID" }
                store.update(e.copy(status = "SYNCED", remoteId = remote.trim(), mirrorId = mirror.trim().takeIf { it.isNotEmpty() }, message = "Manually reconciled in Wallet"))
            }
        }
    }
}
