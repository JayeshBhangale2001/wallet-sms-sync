package io.github.jayeshb.walletsmssync

import android.content.Context
import androidx.work.*
import io.github.jayeshb.walletsmssync.core.*
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.wallet
        val store = app.db.store()
        val settings = app.settings
        store.recoverInterrupted()
        val e = store.next() ?: return Result.success()
        if (settings.authBlocked) return Result.success()
        val token = settings.token() ?: run { settings.notice = "Save a Wallet token to resume queued uploads"; return Result.success() }
        val wait = settings.notBefore - System.currentTimeMillis()
        if (wait > 0) { schedule(applicationContext, wait); return Result.success() }
        val t = Json.decode(e.parsed)
        val accounts = store.catalog("accounts")
        if (accounts.none { it.id == e.sourceAccount && it.currency == t.currency } ||
            (t.kind == Kind.TRANSFER && accounts.none { it.id == e.targetAccount && it.currency == t.currency })) {
            store.update(e.copy(status = "REVIEW", message = "Account missing or currency changed; refresh mapping"))
            schedule(applicationContext); return Result.success()
        }
        try { WalletPayload.create(t, e.sourceAccount.orEmpty(), e.targetAccount, e.categoryId, e.id) }
        catch (_: IllegalArgumentException) {
            store.update(e.copy(status = "REVIEW", message = "Invalid transaction fields")); schedule(applicationContext); return Result.success()
        }
        if (store.claim(e.id) != 1) return Result.success()
        // Persist a global minimum request interval before attempting any write.
        settings.notBefore = System.currentTimeMillis() + 20_000
        val reply = try { WalletApi(token).create(e) } catch (_: java.io.IOException) {
            store.update(e.copy(status = "UNCERTAIN", message = "Connection interrupted; Wallet may have received this record"))
            schedule(applicationContext, 20_000); return Result.success()
        }
        val outcome = runCatching { WriteResponse.interpret(reply.code, reply.body, t.kind == Kind.TRANSFER) }
            .getOrElse { WriteOutcome(WriteState.UNCERTAIN, message = "Unrecognized Wallet response; reconcile") }
        when (outcome.state) {
            WriteState.AUTH -> {
                store.update(e.copy(status = "QUEUED", message = outcome.message))
                if (settings.token() == token) {
                    settings.authBlocked = true
                    settings.notice = outcome.message
                } else schedule(applicationContext)
                return Result.success()
            }
            WriteState.RETRY -> {
                val delay = (reply.retrySeconds ?: 300).coerceAtLeast(20) * 1000
                settings.notBefore = System.currentTimeMillis() + delay
                store.update(e.copy(status = "QUEUED", message = outcome.message))
                schedule(applicationContext, delay); return Result.success()
            }
            else -> store.update(e.copy(status = outcome.state.name, remoteId = outcome.id, mirrorId = outcome.mirrorId, message = outcome.message))
        }
        schedule(applicationContext, 20_000)
        return Result.success()
    }
    companion object {
        fun schedule(context: Context, delay: Long = 0) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delay.coerceAtLeast(0), TimeUnit.MILLISECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("wallet-upload", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
