package io.github.jayeshb.walletsmssync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalBackupTest {
    @Test fun restoreRetainsProcessedSmsAndNeverRequeuesPendingUploads() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val fresh = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val settings = Settings(context, "backup-source").apply { aiEnabled = false }
            val repo = Repository(context, original, settings, enqueue = {})
            val sms = "ACME Bank: INR 123.45 debited from A/C XX9012 at DEMO SHOP"
            val sent = 1780000000000L
            val delivered = repo.candidate(sms, sent, "broadcast")!!
            val imported = repo.candidate(sms, sent + 2000, "inbox:12", sent = sent)!!
            assertTrue(repo.ingest(delivered, false))
            assertFalse(repo.ingest(imported, true))
            val row = original.store().entries().single()
            original.store().update(row.copy(status = "SYNCED", remoteId = "remote-demo"))
            val evidence = original.store().evidence(row.id)!!
            original.store().evidence(evidence.copy(bodyCipher = "raw-sms-test-sentinel", state = "PENDING"))
            assertTrue(repo.ingest(repo.candidate(sms.replace("123.45", "234.56"), sent + 5000, "inbox:13")!!, true))
            val pending = original.store().entries().first { it.id != row.id }
            original.store().update(pending.copy(status = "QUEUED"))
            original.store().mapping(AccountMapping("ACME:ACCOUNT:9012", "account-demo"))
            val customRule = CustomRule("rule-demo", "SMS", "Cafe", "demo shop", "food", true, 1)
            original.store().saveCustomRule(customRule)

            val password = "demo backup passphrase"
            val output = ByteArrayOutputStream()
            assertEquals(2, LocalBackup(original, settings).save(output, password))
            val restoredSettings = Settings(context, "backup-target")
            val backup = LocalBackup(fresh, restoredSettings)
            val bytes = output.toByteArray()
            assertTrue(runCatching { backup.restore(bytes.inputStream(), "wrong backup passphrase") }.isFailure)
            assertTrue(fresh.store().entries().isEmpty())
            assertEquals(2, backup.restore(bytes.inputStream(), password))
            assertEquals("remote-demo", fresh.store().get(row.id)!!.remoteId)
            assertEquals("SYNCED", fresh.store().get(row.id)!!.status)
            assertEquals("UNCERTAIN", fresh.store().get(pending.id)!!.status)
            assertNull(fresh.store().next())
            assertNull(fresh.store().evidence(row.id)!!.bodyCipher)
            assertNull(fresh.store().nextAi())
            assertEquals("account-demo", fresh.store().mappings().single().accountId)
            assertEquals(customRule, fresh.store().customRules().single())
            val restored = Repository(context, fresh, restoredSettings, enqueue = { fail("Duplicate queued") })
            assertFalse(restored.ingest(imported, true))
            assertEquals(2, fresh.store().entries().size)
        } finally {
            original.close()
            fresh.close()
        }
    }
}
