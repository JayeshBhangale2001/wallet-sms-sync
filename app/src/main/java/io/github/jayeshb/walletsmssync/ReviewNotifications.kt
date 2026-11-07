package io.github.jayeshb.walletsmssync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.work.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import io.github.jayeshb.walletsmssync.core.FinancialMessages.isOutgoing
import io.github.jayeshb.walletsmssync.core.Json
import io.github.jayeshb.walletsmssync.core.Kind
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

internal object ReviewNotifications {
    const val ROUTE = "io.github.jayeshb.walletsmssync.OPEN_REVIEW"
    private const val PAYMENT = "payment_review"
    private const val DAILY = "daily_review"

    fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun payment(context: Context, settings: Settings, entry: Entry) {
        val t = Json.decode(entry.parsed)
        if (settings.paymentAlerts && entry.status == "REVIEW" && t.kind != Kind.UNKNOWN && t.isOutgoing())
            post(context, PAYMENT, entry.id, "Payment needs review", "Open Wallet SMS Sync to check this payment.")
    }

    fun cancelPayment(context: Context, id: String) {
        context.getSystemService(NotificationManager::class.java).cancel(id, 1)
    }

    fun cancelPayments(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag != null }.forEach { manager.cancel(it.tag, it.id) }
    }

    fun daily(context: Context, count: Int) =
        post(context, DAILY, null, "Wallet SMS Sync review", "$count transactions need your attention.")

    private fun post(context: Context, channel: String, entryId: String?, title: String, message: String) {
        if (!allowed(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val payment = channel == PAYMENT
        manager.createNotificationChannel(NotificationChannel(channel, if (payment) "Payments to review" else "Daily review reminder",
            if (payment) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).setAction(ROUTE)
            .setData(Uri.Builder().scheme("wallet-sms-sync").authority("review").apply { entryId?.let(::appendPath) }.build())
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, channel).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(message).setContentIntent(tap).setAutoCancel(true)
            .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).build()
        try { manager.notify(entryId, if (payment) 1 else 2, notification) } catch (_: SecurityException) { }
    }
}

class DailyReviewWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.wallet
        if (!app.settings.dailyReview) return Result.success()
        val now = ZonedDateTime.now()
        val count = app.db.store().reviewCount()
        if (count > 0 && ReviewNotifications.allowed(app) &&
            now.hour * 60 + now.minute >= app.settings.dailyReviewMinute &&
            app.settings.claimReviewReminder(now.toLocalDate().toString())) ReviewNotifications.daily(app, count)
        schedule(app, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        private const val NAME = "daily-sms-review"
        internal fun schedule(app: WalletApp, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            val work = WorkManager.getInstance(app)
            if (!app.settings.dailyReview) {
                work.cancelUniqueWork(NAME)
                app.getSystemService(NotificationManager::class.java).cancel(2)
                return
            }
            val now = ZonedDateTime.now()
            val minute = app.settings.dailyReviewMinute
            val today = now.toLocalDate().atTime(minute / 60, minute % 60).atZone(now.zone)
            val next = if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(minute / 60, minute % 60).atZone(now.zone)
            val request = OneTimeWorkRequestBuilder<DailyReviewWorker>()
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS).build()
            work.enqueueUniqueWork(NAME, policy, request)
        }
    }
}

class ReviewClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED))
            DailyReviewWorker.schedule(context.wallet, ExistingWorkPolicy.REPLACE)
    }
}

@Composable internal fun ReviewNotificationSetup(app: WalletApp) {
    val context = LocalContext.current
    var alerts by remember { mutableStateOf(app.settings.paymentAlerts) }
    var daily by remember { mutableStateOf(app.settings.dailyReview) }
    var minute by remember { mutableIntStateOf(app.settings.dailyReviewMinute) }
    var allowed by remember { mutableStateOf(ReviewNotifications.allowed(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = ReviewNotifications.allowed(context)
    }
    fun requestPermission() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Text("Review notifications", fontWeight = FontWeight.Bold)
    Row {
        Switch(checked = alerts, onCheckedChange = {
            alerts = it
            app.settings.paymentAlerts = it
            if (it) requestPermission() else ReviewNotifications.cancelPayments(app)
        })
        Text("Notify me when a payment needs review")
    }
    Row {
        Switch(checked = daily, onCheckedChange = {
            daily = it
            app.settings.dailyReview = it
            DailyReviewWorker.schedule(app, ExistingWorkPolicy.REPLACE)
            if (it) requestPermission()
        })
        Text("Daily review reminder")
    }
    if (!allowed && (alerts || daily)) {
        Text("Allow notifications in Android settings to see review alerts.")
    }
    if (daily) {
        val time = LocalTime.of(minute / 60, minute % 60)
        OutlinedButton(onClick = {
            TimePickerDialog(context, { _, hour, minutes ->
                minute = hour * 60 + minutes
                app.settings.dailyReviewMinute = minute
                DailyReviewWorker.schedule(app, ExistingWorkPolicy.REPLACE)
            }, time.hour, time.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
        }) { Text("Reminder around ${time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}") }
    }
}
