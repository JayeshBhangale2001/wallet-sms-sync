package io.github.jayeshb.walletsmssync

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jayeshb.walletsmssync.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RulesRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun ruleEditsAndPriorityAffectNewExpenses() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val settings = Settings(context, "rules-priority-test").apply { automatic = false }
            val repo = Repository(context, db, settings, enqueue = { fail("Unexpected upload") })
            val rules = RuleRepository(db)
            val categories = listOf(Catalog("food", "categories", "Food"), Catalog("other", "categories", "Other"))
            db.store().catalog(categories)
            db.store().rule(MerchantRule("demo cafe", "other", "AI"))
            val first = CustomRule("one", "SMS", "Delivery", "ZOMATO   DELIVERY", "food", true, 0)
            val second = first.copy(id = "two", name = "Zomato", value = "zomato", categoryId = "other")
            rules.save(first)
            rules.save(second)
            val t = Transaction(Kind.EXPENSE, "120", accountKey = null, merchant = "Demo Cafe",
                occurredAt = "2026-01-01T00:00:00Z", precision = Precision.DATE)
            repo.ingest(SmsCandidate("fixture", 1, "one", t, "zomato\n delivery"), true)
            assertEquals("food", db.store().entries().single().categoryId)
            rules.move("two", -1)
            assertEquals("other", categoryFor(t.merchant, "Zomato delivery", setOf("food", "other"), db.store().customRules(), emptyList()))
            rules.save(second.copy(enabled = false))
            rules.save(first.copy(value = "ZOMATO"))
            db.store().rule(MerchantRule("demo cafe", "other", "USER"))
            assertEquals("other", categoryFor("DEMO   CAFE", "Zomato delivery", setOf("food", "other"), db.store().customRules(), db.store().merchantRules()))
            rules.save(first.copy(id = "exact", type = "MERCHANT", value = "DEMO CAFE"))
            assertEquals("food", categoryFor("demo cafe", null, setOf("food", "other"), db.store().customRules(), db.store().merchantRules()))
            db.store().deleteCustomRule("exact")
            assertEquals(listOf("two", "one"), db.store().customRules().map { it.id })
        } finally { db.close() }
    }

    @Test fun bulkCategoryChangesOnlySelectedEligibleReviewExpenses() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = Repository(context, db, Settings(context, "rules-bulk-test"), enqueue = { fail("Unexpected upload") })
            db.store().catalog(listOf(Catalog("food", "categories", "Food")))
            val t = Transaction(Kind.EXPENSE, "120", accountKey = null, merchant = "Demo Cafe",
                occurredAt = "2026-01-01T00:00:00Z", precision = Precision.DATE)
            fun item(id: String, status: String = "REVIEW", parsed: Transaction = t, category: String? = null) =
                Entry(id, id, Json.encode(parsed), status = status, sourceId = "fixture", categoryId = category)
            val target = item("target", parsed = t.copy(merchant = "DEMO  CAFE"))
            val rows = listOf(item("origin"), target, item("unselected"), item("changed", category = "existing"),
                item("queued", "QUEUED"), item("synced", "SYNCED"), item("different", parsed = t.copy(merchant = "Other")),
                item("income", parsed = t.copy(kind = Kind.INCOME)))
            rows.forEach { db.store().insert(it) }
            val selected = rows.map { it.id }.toSet() - "unselected"
            assertEquals(1, repo.applyCategoryToMatches("origin", "demo cafe", "food", selected))
            assertEquals(target.copy(categoryId = "food", categoryConfirmed = true), db.store().get("target"))
            rows.filter { it.id != "target" }.forEach { assertEquals(it, db.store().get(it.id)) }
            assertEquals(0, repo.applyCategoryToMatches("origin", "demo cafe", "food", selected))
        } finally { db.close() }
    }

    @Test fun walletOpensInstalledLauncherOrOfficialWebFallback() {
        val launches = mutableListOf<Intent>()
        val recorder = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { launches += intent }
        }
        assertTrue(openWallet(recorder, Intent(Intent.ACTION_MAIN).setPackage(WALLET_PACKAGE)))
        assertEquals(WALLET_PACKAGE, launches.single().`package`)
        launches.clear()
        assertTrue(openWallet(recorder, installed = null))
        assertEquals("https://web.budgetbakers.com/login", launches.single().dataString)
    }
}
