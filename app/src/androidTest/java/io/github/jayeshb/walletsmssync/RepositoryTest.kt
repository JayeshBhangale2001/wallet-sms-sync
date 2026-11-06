package io.github.jayeshb.walletsmssync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jayeshb.walletsmssync.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    @Test fun duplicateImportApprovalLinkingAndRecovery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = Repository(context, db, Settings(context, "test-settings"), enqueue = {})
            val t = Transaction(Kind.EXPENSE, "10.00", accountKey = "HDFC:ACCOUNT:9012", merchant = "Synthetic", occurredAt = "2026-01-01T00:00:00Z", precision = Precision.DATE)
            val a = SmsCandidate("synthetic", 0, "fixture-one", t)
            assertTrue(repo.ingest(a, historical = true)); assertFalse(repo.ingest(a, historical = true))
            val first = db.store().entries().single()
            assertEquals("REVIEW", first.status)
            db.store().catalog(listOf(Catalog("a", "accounts", "Source"), Catalog("b", "accounts", "Target")))
            repo.ingest(a.copy(fingerprint = "fixture-two", transaction = t.copy(kind = Kind.REPAYMENT)), historical = true)
            val other = db.store().entries().first { it.id != first.id }
            repo.approve(first.id, t.copy(kind = Kind.TRANSFER), "a", "b", null, other.id)
            assertEquals("QUEUED", db.store().get(first.id)!!.status)
            assertEquals("LINKED", db.store().get(other.id)!!.status)
            assertEquals(1, db.store().claim(first.id)); assertEquals(0, db.store().claim(first.id))
            db.store().recoverInterrupted()
            assertEquals("UNCERTAIN", db.store().get(first.id)!!.status)
            repo.resolve(first.id, "", "", confirmedAbsent = true)
            assertEquals("REVIEW", db.store().get(other.id)!!.status)
        } finally { db.close() }
    }
    @Test fun keystoreRoundTrip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = Settings(context, "test-token-settings")
        settings.saveToken("synthetic-token")
        assertEquals("synthetic-token", settings.token())
        settings.authBlocked = true
        assertTrue(settings.authBlocked)
        settings.saveToken("synthetic-replacement")
        assertFalse(settings.authBlocked)
        settings.saveToken("")
        assertNull(settings.token())
    }
    @Test fun automaticMappingAndReviewGates() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = Settings(context, "test-auto-settings")
        settings.automatic = true
        try {
            var scheduled = 0
            val repo = Repository(context, db, settings, enqueue = { scheduled++ })
            db.store().catalog(listOf(Catalog("a", "accounts", "Source")))
            db.store().mapping(AccountMapping("IDFC:CARD:1234", "a"))
            val t = Transaction(Kind.EXPENSE, "10.00", accountKey = "IDFC:CARD:1234", merchant = "Test", occurredAt = "2026-01-01T00:00:00Z", precision = Precision.MINUTE)
            repo.ingest(SmsCandidate("synthetic", 0, "auto", t), false)
            repo.ingest(SmsCandidate("synthetic", 0, "unmapped", t.copy(accountKey = "IDFC:CARD:4321")), false)
            repo.ingest(SmsCandidate("synthetic", 0, "unclear", t.copy(reviewReason = "Possible transfer")), false)
            repo.ingest(SmsCandidate("synthetic", 0, "historical", t), true)
            assertEquals(1, scheduled)
            assertEquals(1, db.store().entries().count { it.status == "QUEUED" })
            assertEquals(3, db.store().entries().count { it.status == "REVIEW" })
        } finally { settings.automatic = false; db.close() }
    }
}
