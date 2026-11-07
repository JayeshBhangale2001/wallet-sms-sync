package io.github.jayeshb.walletsmssync

import android.app.Application
import android.content.Context
import android.provider.Telephony
import androidx.room.Room
import androidx.room.withTransaction
import io.github.jayeshb.walletsmssync.core.*
import io.github.jayeshb.walletsmssync.core.FinancialMessages.isOutgoing
import java.time.*
import java.util.Locale
import java.util.UUID

class WalletApp : Application() {
    val db by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "wallet.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build() }
    val settings by lazy { Settings(this) }
    val repository by lazy { Repository(this, db, settings) }
    override fun onCreate() { super.onCreate(); SyncWorker.schedule(this); AiWorker.schedule(this); DailyReviewWorker.schedule(this) }
}
val Context.wallet: WalletApp get() = applicationContext as WalletApp
data class SmsCandidate(val source: String, val received: Long, val fingerprint: String, val transaction: Transaction, val body: String? = null, val title: String? = null, val sent: Long = received) {
    val messageHash: String get() = body?.let { FinancialMessages.identity(it, sent) } ?: fingerprint
    val receiptHash: String get() = body?.let { FinancialMessages.identity(it, received) } ?: fingerprint
}
class Repository(private val context: Context, val db: AppDatabase, val settings: Settings, private val enqueue: (Context) -> Unit = { SyncWorker.schedule(it) }) {
    val store = db.store()
    private val parser = SmsParser()
    private suspend fun learnedMatches(body: String): List<Transaction> =
        store.templates().filter { it.enabled }.mapNotNull { row ->
            runCatching { TemplateLearning.apply(Json.gson.fromJson(row.spec, TemplateSpec::class.java), body) }.getOrNull()
        }
    internal suspend fun transferAccounts() = TransferAccounts(store.mappings(), store.catalog("accounts"))
    private suspend fun merchantCategory(merchant: String?, categories: List<Catalog>, body: String? = null): String? =
        categoryFor(merchant, body, categories.map { it.id }.toSet(), store.customRules(), store.merchantRules())

    private suspend fun smsMatcher() = SmsRuleMatcher(parser, store.customRules()
        .filter { it.enabled && it.type == "PARSER" }.mapNotNull { runCatching { it.parserSpec() }.getOrNull() })

    internal suspend fun transferMatch(id: String, t: Transaction, submitted: Boolean = false, reciprocal: Boolean = true): TransferMatch? {
        if (t.amount == null || (t.reference == null && t.kind != Kind.REPAYMENT)) return null
        val mappings = transferAccounts()
        if (mappings.ledger(t) == null) return null
        val ownRoute = mappings.route(t)
        val matches = store.transferCandidates(id).mapNotNull { other ->
            val parsed = Json.decode(other.parsed)
            val inReview = other.status == "REVIEW"
            if (inReview == submitted || (!inReview && parsed.kind != Kind.TRANSFER)) return@mapNotNull null
            val baseline = store.evidence(other.id)?.baseline?.let(Json::decode) ?: parsed
            if (!sameTransferEvidence(t, baseline)) return@mapNotNull null
            if (inReview && mappings.ledger(baseline) == null) return@mapNotNull null
            val route = if (inReview) mappings.route(baseline) else TransferRoute(other.sourceAccount, other.targetAccount)
            if (!ownRoute.compatible(route)) return@mapNotNull null
            val merged = ownRoute.merge(route)
            if (merged.from == null || merged.to == null || merged.from == merged.to) return@mapNotNull null
            val exact = sharedReference(t, baseline)
            val payment = if (t.isOutgoing()) t else baseline
            val receipt = if (t.isOutgoing()) baseline else t
            val issuer = receipt.toKey?.substringBefore(':') ?: receipt.accountKey?.substringBefore(':')
            val cardMatch = payment.kind == Kind.REPAYMENT && receipt.kind == Kind.REPAYMENT &&
                (payment.toKey != null || (payment.destinationIssuer != null && payment.destinationIssuer == issuer))
            if (exact || (cardMatch && (t.reference == null || baseline.reference == null))) TransferMatch(other, merged, exact) else null
        }
        val match = matches.singleOrNull() ?: return null
        if (!submitted && reciprocal && store.get(id) != null) {
            val baseline = store.evidence(match.entry.id)?.baseline?.let(Json::decode) ?: Json.decode(match.entry.parsed)
            if (transferMatch(match.entry.id, baseline, reciprocal = false)?.entry?.id != id) return null
        }
        return match
    }
    suspend fun candidate(body: String, received: Long, source: String, sender: String? = null, sent: Long = received,
        matcher: SmsRuleMatcher? = null): SmsCandidate? {
        val parsed = (matcher ?: smsMatcher()).parse(body, received) ?: if (FinancialMessages.eligible(body)) Transaction(Kind.UNKNOWN, null, accountKey = null,
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
        val matching = body?.let { learnedMatches(it) }.orEmpty()
        val learned = matching.singleOrNull()
        // Conflicting learned formats never select a winner silently.
        val customConflict = c.transaction.reviewReason == "Conflicting custom parsing rules"
        val parsed = learned?.takeIf { c.transaction.kind == Kind.UNKNOWN && !customConflict } ?: c.transaction
        val resolved = transferAccounts()
        val route = resolved.route(parsed)
        val t = if (parsed.reference != null && route.from != null && route.to != null && route.from != route.to)
            parsed.copy(kind = Kind.TRANSFER, reviewReason = "Confirm transfer between owned accounts") else parsed
        val referenceKey = FinancialMessages.referenceIdentity(if(c.transaction.kind != Kind.UNKNOWN) c.transaction else t)
        val transfer = t.kind in setOf(Kind.TRANSFER, Kind.REPAYMENT)
        val account = if (transfer) route.from else resolved.ledger(t)
        val mapped = account != null
        val merchantCategory = if (t.kind in setOf(Kind.EXPENSE, Kind.UNKNOWN))
            merchantCategory(t.merchant, store.catalog("categories"), body) else null
        val category = merchantCategory ?: settings.defaultCategory.takeIf { it.isNotBlank() }
        val aiNeeded = settings.aiEnabled && body != null && FinancialMessages.eligible(body) &&
            (matching.size > 1 || t.kind == Kind.UNKNOWN || (t.reviewReason != null && learned == null && merchantCategory == null) || !mapped ||
                (if (transfer) route.to == null else merchantCategory == null))
        val clear = t.parserVersion != 3 && DirectSyncPolicy.clear(t,c.transaction,body,mapped) &&
            (c.transaction.reviewReason != "Confirm expense or transfer to an owned account" || merchantCategory != null)
        val canCategorizeNow = aiNeeded && !settings.aiBlocked && settings.aiKey() != null
        val canAuto = !historical && settings.automatic && clear && !canCategorizeNow && matching.size <= 1
        val entry = Entry(UUID.randomUUID().toString(), c.fingerprint, Json.encode(t), if (canAuto) "QUEUED" else "REVIEW", c.source,
            sourceAccount = account, targetAccount = route.to.takeIf { transfer }, categoryId = category, message = if(canAuto) "Auto-queued: validated amount and confirmed account" else t.reviewReason ?: if (historical) "Historical import: approve upload" else if (account == null) "Map source account" else "Ready for review", smsTitle = c.title)
        val added = db.withTransaction {
            if (knownEvidence(c)) return@withTransaction false
            if (referenceKey != null && store.evidenceByReference(referenceKey) != null) return@withTransaction false
            val late = transferMatch(entry.id, t, submitted = true)?.takeIf { it.exact && it.entry.linkedId == null }
            val needsTransferReview = canAuto && transferMatch(entry.id, t) != null
            val stored = when {
                late != null -> entry.copy(status = "LINKED", linkedId = late.entry.id, message = "Evidence for an existing transfer")
                needsTransferReview -> entry.copy(status = "REVIEW", message = "Confirm matching transfer evidence")
                else -> entry
            }
            val inserted = store.insert(stored) != -1L
            val savedId = if (inserted) entry.id else store.byFingerprint(c.fingerprint)?.id ?: return@withTransaction false
            if (!inserted && store.evidence(savedId) != null) return@withTransaction false
            if (inserted && late != null) store.update(late.entry.copy(linkedId = entry.id))
            val keepBody = inserted && late == null && settings.aiEnabled && !canAuto && body != null && FinancialMessages.eligible(body)
            store.evidence(SmsEvidence(savedId, c.messageHash, c.received, Json.encode(c.transaction),
                bodyCipher = if (keepBody) settings.sealEvidence(body!!) else null, state = if (late != null) "LINKED" else if (inserted && aiNeeded && !canAuto) "PENDING" else "NONE",
                reason = if (matching.size > 1) "Conflicting learned templates" else entry.message, referenceKey = referenceKey, historical = historical))
            inserted
        }
        if (added) when (store.get(entry.id)?.status) {
            "QUEUED" -> enqueue(context)
            "REVIEW" -> if (aiNeeded && canCategorizeNow && !canAuto) AiWorker.schedule(context)
                else if (!historical) ReviewNotifications.payment(context, settings, entry)
        }
        return added
    }
    suspend fun preview(start: LocalDate, end: LocalDate): List<SmsCandidate> {
        require(!end.isBefore(start)) { "End date must follow start date" }
        val zone = ZoneId.systemDefault()
        val matcher = smsMatcher()
        val list = mutableListOf<SmsCandidate>()
        context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("_id", "body", "date", "address", "date_sent"), "date >= ? AND date < ?",
            arrayOf(start.atStartOfDay(zone).toInstant().toEpochMilli().toString(), end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().toString()), "date ASC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val received = cursor.getLong(2)
                val sent = cursor.getLong(4).takeIf { it > 0 } ?: received
                candidate(cursor.getString(1), received, "inbox:${cursor.getLong(0)}", cursor.getString(3), sent, matcher)?.let(list::add)
            }
        }
        return list.distinctBy { it.messageHash }.filter { c ->
            !knownEvidence(c) && store.byFingerprint(c.fingerprint) == null &&
                FinancialMessages.referenceIdentity(c.transaction)?.let { store.evidenceByReference(it) } == null
        }
    }
    suspend fun approve(id: String, t: Transaction, source: String, target: String?, category: String?, link: String?,
        remember: Boolean = false, rememberCategory: Boolean = remember, rememberCounterparty: Boolean = false) {
        WalletPayload.create(t, source, target, category, id) // validates before touching the queue
        require(Instant.parse(t.occurredAt).isBefore(Instant.now().plusSeconds(86400))) { "Date is too far in the future" }
        val accounts = store.catalog("accounts")
        require(accounts.any { it.id == source && it.currency == t.currency }) { "Select a current ${t.currency} source account" }
        if (t.kind == Kind.TRANSFER) require(accounts.any { it.id == target && it.currency == t.currency }) { "Select a current same-currency target account" }
        if (!category.isNullOrBlank()) require(store.catalog("categories").any { it.id == category }) { "Refresh and select a valid category" }
        db.withTransaction {
            val e = store.get(id) ?: error("Transaction missing")
            require(e.status == "REVIEW") { "Only review items can be approved" }
            val selectedLink = link ?: e.linkedId
            require(e.linkedId == null || e.linkedId == selectedLink) { "Resolve the existing linked evidence first" }
            val evidence = store.evidence(id)
            val original = Json.decode(evidence?.baseline ?: e.parsed)
            val pairingEvidence = Json.decode(e.parsed).takeIf { it.reference != null || it.kind == Kind.REPAYMENT } ?: original
            require(transferMatch(id, pairingEvidence, submitted = true) == null) { "Link this SMS to the existing transfer instead" }
            transferMatch(id, pairingEvidence)?.takeIf { it.exact }?.let { match ->
                require(t.kind == Kind.TRANSFER && selectedLink == match.entry.id && source == match.route.from && target == match.route.to) {
                    "Confirm the matching SMS and account route before sending this transfer"
                }
            }
            val referenceKey = evidence?.referenceKey ?: FinancialMessages.referenceIdentity(if(original.reference != null) original else t)
            if(referenceKey != null) require(store.evidenceByReference(referenceKey)?.entryId.let { it == null || it == id }) { "This bank reference already belongs to another transaction; ignore the duplicate" }
            if (!selectedLink.isNullOrBlank()) {
                require(t.kind == Kind.TRANSFER) { "Evidence linking requires a transfer" }
                val other = store.get(selectedLink) ?: error("Linked transaction missing")
                require(other.id != id && (other.status == "REVIEW" || other.status == "LINKED" && other.linkedId == id)) {
                    "Evidence must be an unsubmitted review item or already linked here"
                }
                val ot = Json.decode(other.parsed)
                require(sameTransferEvidence(t.copy(outgoing = original.isOutgoing()), ot)) {
                    "Evidence must match the currency and amount, have an opposite direction, and be within three days"
                }
                require(original.reference == null || ot.reference == null || sharedReference(original, ot)) { "Evidence references differ" }
                require(transferAccounts().route(ot).compatible(TransferRoute(source, target))) { "Evidence belongs to a different account" }
                store.update(other.copy(status = "LINKED", linkedId = id, message = "Evidence for confirmed transfer"))
                store.evidence(other.id)?.let { store.evidence(it.copy(bodyCipher = null, state = "LINKED")) }
            }
            store.update(e.copy(parsed = Json.encode(t.copy(reviewReason = null)), sourceAccount = source, targetAccount = target,
                categoryId = category, status = "QUEUED", linkedId = selectedLink, message = "Approved"))
            val confirmedMerchant = t.merchant
            if (rememberCategory && t.kind == Kind.EXPENSE && !confirmedMerchant.isNullOrBlank() && !category.isNullOrBlank()) {
                store.rule(MerchantRule(FinancialMessages.merchantKey(confirmedMerchant), category))
            }
            val counterparty = original.merchant
            if (rememberCounterparty && t.kind == Kind.TRANSFER && !counterparty.isNullOrBlank() &&
                counterparty != "Credit card repayment" && original.destinationIssuer == null) {
                store.mapping(AccountMapping(counterpartyKey(counterparty), if (original.isOutgoing()) requireNotNull(target) else source))
            }
            var templateId: String? = null
            if (remember) {
                val confirmedKey = t.accountKey
                if (!confirmedKey.isNullOrBlank()) {
                    require(Regex("[A-Z][A-Z0-9]{1,23}:(?:CARD|ACCOUNT):[0-9]{4}").matches(confirmedKey)) { "Use BANK:CARD:last4 or BANK:ACCOUNT:last4" }
                    // A repayment's extracted key names its destination card, not the paying account.
                    val accountId = if (!original.isOutgoing() && t.kind == Kind.TRANSFER) requireNotNull(target) else source
                    store.mapping(AccountMapping(confirmedKey, accountId))
                }
                if (t.kind == Kind.TRANSFER) {
                    original.fromKey?.let { store.mapping(AccountMapping(it, source)) }
                    original.toKey?.let { store.mapping(AccountMapping(it, requireNotNull(target))) }
                    selectedLink?.let { store.evidence(it) }?.let { Json.decode(it.baseline) }?.let { other ->
                        other.accountKey?.let { store.mapping(AccountMapping(it, if (other.isOutgoing()) source else requireNotNull(target))) }
                    }
                }
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
        ReviewNotifications.cancelPayment(context, id)
        link?.let { ReviewNotifications.cancelPayment(context, it) }
        enqueue(context)
    }
    suspend fun linkExistingTransfer(id: String) {
        db.withTransaction {
            val entry = requireNotNull(store.get(id))
            require(entry.status == "REVIEW") { "Only unsubmitted evidence can be linked" }
            val evidence = store.evidence(id)
            val t = Json.decode(entry.parsed).takeIf { it.reference != null || it.kind == Kind.REPAYMENT }
                ?: evidence?.baseline?.let(Json::decode) ?: Json.decode(entry.parsed)
            val match = transferMatch(id, t, submitted = true)?.entry
            require(match != null && match.linkedId == null) { "No unique unlinked transfer matches this evidence" }
            store.update(entry.copy(status = "LINKED", linkedId = match.id, message = "Evidence for an existing transfer"))
            store.update(match.copy(linkedId = id))
            evidence?.let { store.evidence(it.copy(state = "LINKED", bodyCipher = null)) }
        }
        ReviewNotifications.cancelPayment(context, id)
    }
    suspend fun ignore(id: String) { db.withTransaction { store.get(id)?.let {
        require(it.status == "REVIEW") { "Only review items can be ignored" }; store.update(it.copy(status = "IGNORED"))
        store.evidence(id)?.let { evidence -> store.evidence(evidence.copy(bodyCipher = null, state = "IGNORED")) }
    } }; ReviewNotifications.cancelPayment(context, id) }

    suspend fun recheckPending(): String {
        val matcher = smsMatcher()
        val pending = store.allEvidence().filter { store.get(it.entryId)?.status == "REVIEW" && it.state != "RUNNING" }
        val missing = pending.filter { it.bodyCipher?.let(settings::openEvidence) == null }.associateBy { it.messageHash }.toMutableMap()
        val recovered = mutableMapOf<String, String>()
        if (missing.isNotEmpty() && context.checkSelfPermission(android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("body", "date", "date_sent"), null, null, null)?.use { rows ->
                while (missing.isNotEmpty() && rows.moveToNext()) {
                    val body = rows.getString(0) ?: continue
                    val received = rows.getLong(1)
                    val sent = rows.getLong(2).takeIf { it > 0 } ?: received
                    val found = missing.remove(FinancialMessages.identity(body, sent))
                        ?: missing.remove(FinancialMessages.identity(body, received))
                    if (found != null) recovered[found.entryId] = body
                }
            }
        }
        val mappings = transferAccounts()
        val categories = store.catalog("categories")
        var refreshed = 0
        var cloud = false
        for (evidence in pending) {
            val body = evidence.bodyCipher?.let(settings::openEvidence) ?: recovered[evidence.entryId] ?: continue
            if (FinancialMessages.ignored(body)) {
                db.withTransaction {
                    if (store.get(evidence.entryId)?.status == "REVIEW" && store.evidence(evidence.entryId) == evidence)
                        store.evidence(evidence.copy(state = "NONE", bodyCipher = null, suggestion = null, reason = "Non-transaction alert. Review or ignore this item."))
                }
                continue
            }
            val baseline = matcher.parse(body, evidence.received) ?: Json.decode(evidence.baseline)
            val local = if (baseline.kind == Kind.UNKNOWN) learnedMatches(body).singleOrNull() ?: baseline else baseline
            val ownedRoute = mappings.route(local)
            val parsed = if (local.reference != null && ownedRoute.from != null && ownedRoute.to != null && ownedRoute.from != ownedRoute.to)
                local.copy(kind = Kind.TRANSFER, reviewReason = "Confirm transfer between owned accounts") else local
            val route = mappings.route(parsed)
            val transfer = parsed.kind in setOf(Kind.REPAYMENT, Kind.TRANSFER)
            val source = if (transfer) route.from else mappings.ledger(parsed)
            val existing = store.get(evidence.entryId)
            val category = existing?.categoryId?.takeIf { existing.categoryConfirmed }
                ?: merchantCategory(parsed.merchant, categories, body)
            val useCloud = settings.aiEnabled && settings.aiKey() != null && FinancialMessages.eligible(body) &&
                (parsed.kind == Kind.UNKNOWN || source == null || if (transfer) route.to == null else category == null)
            val advice = AiAdvice(parsed, source, category, "Rechecked with current parser and saved rules",
                route.to.takeIf { transfer }, local = true)
            db.withTransaction {
                if (store.get(evidence.entryId)?.status != "REVIEW" || store.evidence(evidence.entryId) != evidence) return@withTransaction
                store.evidence(evidence.copy(state = if (useCloud) "PENDING" else "READY",
                    bodyCipher = if (useCloud) settings.sealEvidence(body) else evidence.bodyCipher,
                    suggestion = Json.gson.toJson(advice), reason = advice.reason, modelInfo = "", historical = true))
                refreshed++
                cloud = cloud || useCloud
            }
        }
        if (cloud) AiWorker.schedule(context)
        return "Rechecked $refreshed review items. Open a transaction to inspect updated suggestions."
    }

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
        val baseline = Json.decode(evidence.baseline)
        val parsed = if (evidence.historical && evidence.suggestion != null)
            Json.gson.fromJson(evidence.suggestion, AiAdvice::class.java).takeIf { it.local }?.transaction ?: Json.decode(e.parsed)
        else Json.decode(e.parsed)
        val accounts = store.catalog("accounts").filter { it.currency == parsed.currency }
        val categories = store.catalog("categories")
        if (accounts.isEmpty() || categories.isEmpty()) throw AiFailure("Fetch Wallet accounts and categories in Setup, then retry AI.")
        val categoryIds = categories.mapIndexed { index, category -> "c$index" to category }.toMap()
        val accountIds = accounts.mapIndexed { index, account -> "a$index" to account }.toMap()
        val mappings = TransferAccounts(store.mappings(), accounts)
        val transfer = parsed.kind in setOf(Kind.TRANSFER, Kind.REPAYMENT)
        val route = mappings.route(parsed)
        val source = if (transfer) route.from else mappings.ledger(parsed)
        val target = route.to.takeIf { transfer }
        val knownCategory = e.categoryId.takeIf { e.categoryConfirmed } ?: merchantCategory(parsed.merchant, categories, body)
        fun alias(choices: Map<String, Catalog>, id: String?) = choices.entries.find { it.value.id == id }?.key
        require(FinancialMessages.eligible(body)) { "Only eligible financial SMS can be sent to cloud AI" }
        val prompt = AiPrompt.create(body, parsed, categoryIds.map { it.key to it.value.name }, accountIds.map { it.key to it.value.name },
            alias(accountIds, source), alias(accountIds, target), alias(categoryIds, knownCategory), buildList {
                parsed.reviewReason?.let(::add)
                if (source == null) add("Source or receiving Wallet ledger")
                if (transfer && target == null) add("Destination Wallet account")
                if (!transfer && knownCategory == null) add("Category")
            })
        if (prompt.toByteArray().size > 48_000) throw AiFailure("Wallet catalog is too large for cloud AI. Review this transaction manually.")
        val output = engine.infer(prompt)
        val extracted = AdviceValidator.parse(output, body, parsed, accountIds.keys, categoryIds.keys)
        val advice = extracted.copy(sourceAccountId = source ?: accountIds[extracted.sourceAccountId]?.id,
            targetAccountId = target ?: accountIds[extracted.targetAccountId]?.id,
            categoryId = knownCategory ?: categoryIds[extracted.categoryId]?.id)
        var autoQueued = false
        db.withTransaction {
            val current = requireNotNull(store.get(id))
            // Never overwrite a user's approval, ignore or newly attached message while inference was running.
            if (current.status == "REVIEW" && store.evidence(id)?.bodyCipher == evidence.bodyCipher) {
                val t = advice.transaction
                val account = transferAccounts().ledger(t)
                val savedCategory = current.categoryId.takeIf { current.categoryConfirmed }
                    ?: merchantCategory(t.merchant, store.catalog("categories"), body)
                val clear = !current.categoryConfirmed && t.parserVersion != 3 && DirectSyncPolicy.clear(t,baseline,body,account != null) &&
                    (baseline.reviewReason != "Confirm expense or transfer to an owned account" || savedCategory != null) &&
                    transferMatch(id, baseline) == null
                if(!evidence.historical && settings.aiEnabled && settings.automatic && clear &&
                    !evidence.reason.startsWith("Conflicting")) {
                    val ref = FinancialMessages.referenceIdentity(t)
                    require(ref == null || store.evidenceByReference(ref)?.entryId.let { it == null || it == id }) { "Duplicate bank reference; review this item" }
                    val category = savedCategory ?: advice.categoryId ?: current.categoryId
                    store.update(current.copy(parsed = Json.encode(t.copy(reviewReason = null)),sourceAccount = account!!,categoryId = category,status = "QUEUED",message = "Auto-queued: AI amount validated against SMS and account already confirmed"))
                    val merchant = t.merchant
                    val suggestedCategory = advice.categoryId
                    if(t.kind == Kind.EXPENSE && merchant != null && suggestedCategory != null && savedCategory == null)
                        store.rule(MerchantRule(FinancialMessages.merchantKey(merchant),suggestedCategory,"AI"))
                    val spec = TemplateLearning.compile(body,t)
                    if(spec != null) {
                        val ruleId = hash(spec.expression)
                        if(store.templates().none { it.id == ruleId }) store.template(LearnedTemplate(ruleId,Json.gson.toJson(spec),spec.example,enabled = false,confirmations = 0,origin = "AI"))
                    }
                    store.decision(LearningDecision(id,evidence.baseline,Json.gson.toJson(mapOf("transaction" to t,"sourceAccountId" to account,"categoryId" to category)),"Automatic sync: grounded amount and previously confirmed account; AI category cached",spec?.let { hash(it.expression) }))
                    store.evidence(evidence.copy(bodyCipher = null,state = "AUTO_QUEUED",suggestion = Json.gson.toJson(advice),referenceKey = ref))
                    autoQueued = true
                } else store.evidence(evidence.copy(state = "READY", suggestion = Json.gson.toJson(advice.copy(
                    categoryId = savedCategory ?: advice.categoryId)), reason = "AI suggestion ready"))
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
            val accounts = transferAccounts()
            val route = accounts.route(advice.transaction)
            val transfer = advice.transaction.kind in setOf(Kind.REPAYMENT, Kind.TRANSFER)
            val source = if (transfer) route.from else accounts.ledger(advice.transaction)
            val prior = Json.decode(e.parsed)
            val legacyTarget = e.sourceAccount.takeIf { prior.kind == Kind.REPAYMENT && prior.fromKey == null && prior.toKey == null && e.targetAccount == null }
            val target = if (transfer) route.to ?: e.targetAccount ?: legacyTarget ?: advice.targetAccountId else null
            val knownCategory = e.categoryId.takeIf { e.categoryConfirmed } ?: merchantCategory(advice.transaction.merchant,
                store.catalog("categories"), evidence.bodyCipher?.let(settings::openEvidence))
            store.update(e.copy(parsed = Json.encode(advice.transaction),
                sourceAccount = source ?: e.sourceAccount.takeUnless { legacyTarget != null } ?: advice.sourceAccountId, targetAccount = target,
                categoryId = knownCategory ?: advice.categoryId ?: e.categoryId, message = "Suggestions applied to form; verify and approve"))
            store.evidence(evidence.copy(state = "APPLIED"))
        }
    }
    suspend fun setSmsTitle(id: String, title: String) {
        db.withTransaction { val e = requireNotNull(store.get(id)); require(e.status == "REVIEW"); store.update(e.copy(smsTitle = title.trim().take(120).takeIf { it.isNotEmpty() })) }
    }

    internal suspend fun applyCategoryToMatches(id: String, merchant: String, category: String, selected: Set<String>): Int =
        db.withTransaction {
            require(store.get(id)?.status == "REVIEW") { "The original purchase is no longer in Review" }
            require(store.catalog("categories").any { it.id == category }) { "Choose an active Wallet category" }
            store.uncategorizedReview(id).filter { it.id in selected && it.matchesUncategorizedExpense(merchant) }
                .sumOf { store.setEmptyReviewCategory(it.id, category) }
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
                e.linkedId?.let { other ->
                    store.get(other)?.let { store.update(it.copy(status = "REVIEW", linkedId = null)) }
                    store.evidence(other)?.let { store.evidence(it.copy(state = "NONE")) }
                }
                store.update(e.copy(status = "REVIEW", linkedId = null, message = "User confirmed absence in Wallet; review before resubmitting"))
            } else {
                require(remote.isNotBlank()) { "Enter the verified source record ID" }
                if (Json.decode(e.parsed).kind == Kind.TRANSFER) require(mirror.isNotBlank()) { "Verify and enter destination record ID" }
                store.update(e.copy(status = "SYNCED", remoteId = remote.trim(), mirrorId = mirror.trim().takeIf { it.isNotEmpty() }, message = "Manually reconciled in Wallet"))
            }
        }
    }
}
