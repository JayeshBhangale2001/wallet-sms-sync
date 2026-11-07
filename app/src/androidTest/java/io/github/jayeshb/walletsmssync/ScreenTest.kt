package io.github.jayeshb.walletsmssync

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun navigateCoreScreens() {
        compose.onNodeWithText("Your messages. Organized.").assertIsDisplayed()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val screenshot = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "history-smoke.png")
        screenshot.outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // UTP uninstalls the app after the suite; preserve this synthetic empty-state screenshot for visual QA.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cp ${screenshot.absolutePath} /sdcard/Download/wallet-sms-sync-smoke.png")).use { it.readBytes() }
        compose.onNodeWithText("Review", useUnmergedTree = true).performClick()
        compose.onNodeWithText("+ Add manual transaction").assertIsDisplayed()
        compose.onNodeWithText("Import", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Preview messages").assertIsDisplayed()
        compose.onNodeWithText("Setup", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Connect once. Parse on your phone.").assertIsDisplayed()
        compose.onNodeWithText("Learn", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("What your app remembers").assertIsDisplayed()
        compose.onNodeWithText("History", useUnmergedTree = true).performScrollTo().performClick()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.wallet
        val id = java.util.UUID.randomUUID().toString()
        val row = Entry(id, id, io.github.jayeshb.walletsmssync.core.Json.encode(
            io.github.jayeshb.walletsmssync.core.Transaction(io.github.jayeshb.walletsmssync.core.Kind.EXPENSE,
                "15", accountKey = null, merchant = "Notification fixture", occurredAt = "2026-06-01T00:00:00Z",
                precision = io.github.jayeshb.walletsmssync.core.Precision.DATE)), sourceId = "fixture")
        kotlinx.coroutines.runBlocking { app.db.store().insert(row) }
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        val settings = Settings(app, "notification-screen-test")
        settings.paymentAlerts = true
        ReviewNotifications.payment(app, settings, row.copy(status = "QUEUED"))
        org.junit.Assert.assertFalse(manager.activeNotifications.any { it.tag == id })
        ReviewNotifications.payment(app, settings, row)
        if (ReviewNotifications.allowed(app)) {
            compose.waitUntil(5_000) { manager.activeNotifications.any { it.tag == id } }
            manager.activeNotifications.single { it.tag == id }.notification.contentIntent.send()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Transaction details").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Transaction details").assertIsDisplayed()
        } else {
            org.junit.Assert.assertFalse(manager.activeNotifications.any { it.tag == id })
        }
        ReviewNotifications.cancelPayment(app, id)
        val day = java.util.UUID.randomUUID().toString()
        org.junit.Assert.assertTrue(settings.claimReviewReminder(day))
        org.junit.Assert.assertFalse(settings.claimReviewReminder(day))
    }
}
