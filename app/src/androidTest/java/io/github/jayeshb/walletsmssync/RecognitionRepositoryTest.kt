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
class RecognitionRepositoryTest {
    private val debit = "Sent Rs.120.00 From HDFC Bank A/C *1234 To Demo Payee On 07/09/26 Ref 100000000001"
    private val credit = "Credit Alert! Rs.120.00 credited to HDFC Bank A/c XX5678 on 07-09-26 from VPA demo@bank (UPI 100000000001)"
    private val received = 1789000000000L
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private suspend fun fixture(block: suspend (Repository) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val settings = Settings(context, "recognition-${java.util.UUID.randomUUID()}")
            val repo = Repository(context, db, settings, enqueue = {})
            db.store().catalog(listOf(Catalog("bank-a", "accounts", "Demo Bank A"), Catalog("bank-b", "accounts", "Demo Bank B"),
                Catalog("food", "categories", "Food & Drink")))
            db.store().mapping(AccountMapping("HDFC:ACCOUNT:1234", "bank-a"))
            db.store().mapping(AccountMapping("HDFC:ACCOUNT:5678", "bank-b"))
            block(repo)
        } finally { db.close() }
    }

    @Test fun bothArrivalOrdersProduceOneQueuedTransfer() = runBlocking {
        for (order in listOf(listOf(debit, credit), listOf(credit, debit))) fixture { repo ->
            order.forEach { assertTrue(repo.ingest(repo.candidate(it, received, "fixture")!!, false)) }
            val outgoing = repo.store.entries().single { Json.decode(it.parsed).outgoing == true }
            val parsed = Json.decode(outgoing.parsed)
            val match = repo.transferMatch(outgoing.id, parsed)!!
            assertTrue(match.exact)
            assertEquals(TransferRoute("bank-a", "bank-b"), match.route)
            repo.approve(outgoing.id, parsed.copy(kind = Kind.TRANSFER), "bank-a", "bank-b", null, match.entry.id)
            assertEquals(1, repo.store.entries().count { it.status == "QUEUED" })
            assertEquals("LINKED", repo.store.get(match.entry.id)!!.status)
        }
    }

    @Test fun lateMirrorLinksWithoutQueueingAndResultPreservesLink() = runBlocking {
        fixture { repo ->
            val outgoing = repo.candidate(debit, received, "fixture")!!
            repo.ingest(outgoing, true)
            val row = repo.store.entries().single()
            repo.approve(row.id, outgoing.transaction.copy(kind = Kind.TRANSFER), "bank-a", "bank-b", null, null)
            repo.store.claim(row.id)
            val incoming = repo.candidate(credit, received, "fixture")!!
            assertTrue(repo.ingest(incoming, false))
            assertFalse(repo.ingest(incoming, true))
            repo.store.uploadResult(row.id, "SYNCED", "Created", "remote-source", "remote-destination")
            val other = repo.store.entries().single { it.id != row.id }
            assertEquals("LINKED", other.status)
            assertEquals(other.id, repo.store.get(row.id)!!.linkedId)
            assertNull(repo.store.next())
        }
    }

    @Test fun missingMappingAndMultipleCandidateCreditsStayUnpaired() = runBlocking {
        fixture { repo ->
            val outgoing = repo.candidate(debit, received, "fixture")!!
            repo.ingest(outgoing, true)
            repo.ingest(repo.candidate(credit, received, "fixture")!!, true)
            val row = repo.store.entries().single { Json.decode(it.parsed).outgoing == true }
            repo.store.deleteMapping("HDFC:ACCOUNT:5678")
            assertNull(repo.transferMatch(row.id, outgoing.transaction))
            repo.store.mapping(AccountMapping("HDFC:ACCOUNT:5678", "bank-b"))
            repo.store.catalog(listOf(Catalog("bank-c", "accounts", "Demo Bank C")))
            repo.store.mapping(AccountMapping("HDFC:ACCOUNT:9876", "bank-c"))
            repo.ingest(repo.candidate(credit.replace("5678", "9876"), received, "fixture")!!, true)
            assertNull(repo.transferMatch(row.id, outgoing.transaction))
            assertEquals(3, repo.store.entries().count { it.status == "REVIEW" })
        }
    }

    @Test fun merchantRuleAndAiCatalogUseLocalPrecedence() = runBlocking {
        fixture { repo ->
            val first = repo.candidate(debit.replace("Demo Payee", "Sai Chat"), received, "fixture")!!
            repo.ingest(first, true)
            val row = repo.store.entries().single()
            repo.approve(row.id, first.transaction, "bank-a", null, "food", null, rememberCategory = true)
            repo.settings.aiEnabled = true
            val next = repo.candidate(debit.replace("Demo Payee", "SAI   CHAT").replace("100000000001", "100000000002"), received, "fixture")!!
            repo.ingest(next, false)
            val saved = repo.store.entries().single { it.id != row.id }
            assertEquals("food", saved.categoryId)
            assertEquals("QUEUED", saved.status)
            assertEquals("NONE", repo.store.evidence(saved.id)!!.state)
            assertNull(repo.candidate("E-Mandate! Rs.100 payment will be deducted", received, "ignored"))

            val unknown = repo.candidate(debit.replace("100000000001", "100000000003"), received, "fixture")!!
            repo.ingest(unknown, true)
            val review = repo.store.entries().single { it.status == "REVIEW" }
            repo.store.catalog((0..70).map { Catalog("private-$it", "categories", "Synthetic category %02d".format(it)) })
            repo.analyse(review.id, SuggestionEngine { prompt ->
                assertTrue(prompt.contains("Synthetic category 70"))
                assertTrue(prompt.contains("Demo Bank A"))
                assertFalse(prompt.contains("bank-a"))
                """{"sourceAccountAlias":"a1","categoryId":"c0"}"""
            })
            val suggestion = Json.gson.fromJson(repo.store.evidence(review.id)!!.suggestion, AiAdvice::class.java)
            assertEquals("bank-a", suggestion.sourceAccountId)
            assertEquals("REVIEW", repo.store.get(review.id)!!.status)
        }
    }
}
