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
    val db by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "wallet.db").build() }
    val settings by lazy { Settings(this) }
    val repository by lazy { Repository(this, db, settings) }
    override fun onCreate() { super.onCreate(); SyncWorker.schedule(this) }
}
val Context.wallet: WalletApp get() = applicationContext as WalletApp
data class SmsCandidate(val source: String, val received: Long, val fingerprint: String, val transaction: Transaction)
class Repository(private val context: Context, val db: AppDatabase, val settings: Settings, private val enqueue: (Context) -> Unit = { SyncWorker.schedule(it) }) {
    val store = db.store()
    private val parser = SmsParser()
    fun candidate(body: String, received: Long, source: String): SmsCandidate? = parser.parse(body, received)?.let {
        SmsCandidate(source, received, fingerprint(it, parser.normalize(body), received), it)
    }
    suspend fun ingest(c: SmsCandidate, historical: Boolean): Boolean {
        val t = c.transaction
        val account = store.mappings().find { it.key == t.accountKey }?.accountId
        val mapped = store.catalog("accounts").find { it.id == account && it.currency == t.currency }
        val category = t.merchant?.let { store.categoryFor(it.lowercase(Locale.ROOT)) } ?: settings.defaultCategory.takeIf { it.isNotBlank() }
        val canAuto = !historical && settings.automatic && t.kind == Kind.EXPENSE && t.reviewReason == null && mapped != null
        val entry = Entry(UUID.randomUUID().toString(), c.fingerprint, Json.encode(t), if (canAuto) "QUEUED" else "REVIEW", c.source,
            sourceAccount = account, categoryId = category, message = t.reviewReason ?: if (historical) "Historical import: approve upload" else if (account == null) "Map source account" else "Ready for review")
        val added = store.insert(entry) != -1L
        if (added && canAuto) enqueue(context)
        return added
    }
    fun preview(start: LocalDate, end: LocalDate): List<SmsCandidate> {
        require(!end.isBefore(start)) { "End date must follow start date" }
        val zone = ZoneId.systemDefault()
        val list = mutableListOf<SmsCandidate>()
        context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("_id", "body", "date"), "date >= ? AND date < ?",
            arrayOf(start.atStartOfDay(zone).toInstant().toEpochMilli().toString(), end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().toString()), "date ASC")?.use { cursor ->
            while (cursor.moveToNext()) candidate(cursor.getString(1), cursor.getLong(2), "inbox:${cursor.getLong(0)}")?.let(list::add)
        }
        return list.distinctBy { it.fingerprint }
    }
    suspend fun approve(id: String, t: Transaction, source: String, target: String?, category: String?, link: String?) {
        WalletPayload.create(t, source, target, category, id) // validates before touching the queue
        require(Instant.parse(t.occurredAt).isBefore(Instant.now().plusSeconds(86400))) { "Date is too far in the future" }
        val accounts = store.catalog("accounts")
        require(accounts.any { it.id == source && it.currency == t.currency }) { "Select a current ${t.currency} source account" }
        if (t.kind == Kind.TRANSFER) require(accounts.any { it.id == target && it.currency == t.currency }) { "Select a current same-currency target account" }
        if (!category.isNullOrBlank()) require(store.catalog("categories").any { it.id == category }) { "Refresh and select a valid category" }
        db.withTransaction {
            val e = store.get(id) ?: error("Transaction missing")
            require(e.status == "REVIEW") { "Only review items can be approved" }
            if (!link.isNullOrBlank()) {
                require(t.kind == Kind.TRANSFER) { "Evidence linking requires a transfer" }
                val other = store.get(link) ?: error("Linked transaction missing")
                require(other.id != id && other.status == "REVIEW") { "Evidence must be an unsubmitted review item" }
                val ot = Json.decode(other.parsed)
                require(sameAmount(ot.amount, t.amount)) { "Evidence amounts differ" }
                require(kotlin.math.abs(Duration.between(Instant.parse(ot.occurredAt), Instant.parse(t.occurredAt)).toHours()) <= 72) { "Evidence dates differ by more than three days" }
                store.update(other.copy(status = "LINKED", linkedId = id, message = "Evidence for confirmed transfer"))
            }
            store.update(e.copy(parsed = Json.encode(t.copy(reviewReason = null)), sourceAccount = source, targetAccount = target,
                categoryId = category, status = "QUEUED", linkedId = link, message = "Approved"))
        }
        enqueue(context)
    }
    suspend fun ignore(id: String) { db.withTransaction { store.get(id)?.let {
        require(it.status == "REVIEW") { "Only review items can be ignored" }; store.update(it.copy(status = "IGNORED"))
    } } }
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
