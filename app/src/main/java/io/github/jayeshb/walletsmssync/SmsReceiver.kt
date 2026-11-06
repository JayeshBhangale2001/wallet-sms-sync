package io.github.jayeshb.walletsmssync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import io.github.jayeshb.walletsmssync.core.hash
import kotlinx.coroutines.*

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isEmpty()) return
        val body = parts.joinToString("") { it.messageBody.orEmpty() }
        val received = parts.first().timestampMillis
        val source = "broadcast:" + hash("${parts.first().originatingAddress}|$received")
        val candidate = context.wallet.repository.candidate(body, received, source) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { context.wallet.repository.ingest(candidate, historical = false) }
            catch (_: Exception) { context.wallet.settings.notice = "SMS processing failed; import inbox to recover" }
            finally { pending.finish() }
        }
    }
}
