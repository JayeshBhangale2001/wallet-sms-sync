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
        val sender = parts.first().originatingAddress
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = context.wallet.repository
                repo.candidate(body, received, source, sender)?.let { repo.ingest(it, historical = false) }
            }
            catch (_: Exception) { context.wallet.settings.notice = "SMS processing failed; import inbox to recover" }
            finally { pending.finish() }
        }
    }
}
