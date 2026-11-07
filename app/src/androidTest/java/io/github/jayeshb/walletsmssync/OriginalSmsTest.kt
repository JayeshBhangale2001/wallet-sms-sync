package io.github.jayeshb.walletsmssync

import android.content.Context
import android.database.MatrixCursor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jayeshb.walletsmssync.core.FinancialMessages
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OriginalSmsTest {
    @Test fun originalIsAvailableWithoutCloudAiAndRemovedWhenIgnored() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val settings = Settings(context, "original-sms-test").apply { aiEnabled = false }
            val repo = Repository(context, db, settings, enqueue = {})
            val sms = "ACME Bank: INR 42.00 debited\nfrom A/c XX1234 at TEST CAFE"
            repo.ingest(repo.candidate(sms, 1780000000000L, "fixture")!!, historical = true)
            val entry = db.store().entries().single()
            val evidence = db.store().evidence(entry.id)!!
            assertEquals("NONE", evidence.state)
            assertNotEquals(sms, evidence.bodyCipher)
            assertEquals(OriginalSms.Found(sms), repo.originalSms(entry))
            repo.ignore(entry.id)
            assertNull(db.store().evidence(entry.id)!!.bodyCipher)

            val manual = entry.copy(id = "manual", fingerprint = "manual", sourceId = "manual:fixture")
            db.store().insert(manual)
            assertEquals(OriginalSms.Missing.MANUAL, repo.originalSms(manual))
            val attached = sms.replace("42.00", "17.00")
            repo.attachOriginalSms(manual.id, attached)
            assertEquals(OriginalSms.Found(attached), repo.originalSms(manual))
            assertNull(db.store().nextAi())
        } finally { db.close() }
    }

    @Test fun inboxRecoveryChecksBothTimestampsAndPreservesTheFullBody() = runBlocking {
        val body = "ACME Bank:\nINR 42.00 debited from A/c XX1234"
        val other = "ACME Bank:\nINR 17.00 debited from A/c XX1234"
        val received = 1780000000000L
        val sent = received - 1000
        val expected = listOf(
            SmsEvidence("broadcast", FinancialMessages.identity(body, sent), sent, "{}"),
            SmsEvidence("imported", FinancialMessages.identity(other, received), received, "{}"),
            SmsEvidence("missing", FinancialMessages.identity("Unrelated", sent), sent, "{}")
        )
        MatrixCursor(arrayOf("body", "date", "date_sent")).use { rows ->
            rows.addRow(arrayOf(body, received - 8000, sent - 8000))
            rows.addRow(arrayOf(body, received, sent))
            rows.addRow(arrayOf(other, received, sent))
            assertEquals(mapOf("broadcast" to body, "imported" to other), recoverSmsRows(rows, expected))
        }
    }
}
