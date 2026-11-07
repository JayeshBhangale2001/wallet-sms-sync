package io.github.jayeshb.walletsmssync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jayeshb.walletsmssync.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class LearningRepositoryTest {
    @Test fun clearAiTransactionsSyncDirectlyButNewOwnershipAndBalanceMistakesStayInReview() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val settings = Settings(context,"ai-direct-test"); settings.aiEnabled = true; settings.automatic = true
        try {
            var queued = 0
            val repo = Repository(context,db,settings,enqueue = { queued++ })
            db.store().catalog(listOf(Catalog("a","accounts","Confirmed bank"),Catalog("food","categories","Food")))
            db.store().mapping(AccountMapping("ACME:ACCOUNT:9012","a"))
            repo.ingest(repo.candidate(sms,1780000000000,"fixture","AD-ACMEBK")!!,false)
            val id = db.store().entries().single().id
            repo.analyse(id,SuggestionEngine { output })
            assertEquals("QUEUED",db.store().get(id)!!.status); assertEquals(1,queued)
            assertTrue(db.store().get(id)!!.smsTitle!!.contains("AD-ACMEBK"))
            assertTrue(db.store().get(id)!!.smsTitle!!.contains("ACME Bank:"))
            assertNull(db.store().evidence(id)!!.bodyCipher)
            assertEquals("AI",db.store().merchantRules().single().origin)
            assertFalse(db.store().templates().single().enabled)
            db.store().deleteMapping("ACME:ACCOUNT:9012")
            val newMessage = sms.replace("ABC123456","ABC654321")
            repo.ingest(repo.candidate(newMessage,1780000001000,"fixture2")!!,false)
            val unknown = db.store().entries().first { it.id != id }
            repo.analyse(unknown.id,SuggestionEngine { output.replace("ABC123456","ABC654321") })
            assertEquals("REVIEW",db.store().get(unknown.id)!!.status) // Extracting the account key does not confirm ownership.
            db.store().mapping(AccountMapping("ACME:ACCOUNT:9012","a"))
            repo.analyse(unknown.id,SuggestionEngine { output.replace("ABC123456","ABC654321").replace("123.45","9000.00") })
            assertEquals("REVIEW",db.store().get(unknown.id)!!.status) // Balance appears in SMS but is not the debit amount.
        } finally { db.close(); settings.aiEnabled = false; settings.automatic = false }
    }
    private val sms = "ACME Bank: INR 123.45 debited from A/C XX9012 at DEMO SHOP on 06/11/2026. Avl bal INR 9000.00. Ref ABC123456."
    private val output = """{"kind":"EXPENSE","amount":"123.45","accountKey":"ACME:ACCOUNT:9012","merchant":"DEMO SHOP","dateText":"06/11/2026","dateFormat":"dd/MM/uuuu","reference":"ABC123456","categoryId":"c0"}"""
    @Test fun suggestionsBecomeRulesOnlyAfterReviewedApprovalAndSurviveReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val filename = "learning-${java.util.UUID.randomUUID()}.db"
        val settings = Settings(context,"learning-test")
        settings.aiEnabled = true; settings.automatic = false
        var db = Room.databaseBuilder(context,AppDatabase::class.java,filename).build()
        try {
            var repo = Repository(context,db,settings,enqueue = {})
            db.store().catalog(listOf(Catalog("a","accounts","ACME Savings"),Catalog("b","accounts","Own second account"),Catalog("food","categories","Food")))
            val c = repo.candidate(sms,1780000000000,"synthetic")!!
            assertTrue(repo.ingest(c,false))
            val row = db.store().entries().single()
            val evidence = db.store().evidence(row.id)!!
            assertNotEquals(sms,evidence.bodyCipher); assertEquals(sms,settings.openEvidence(evidence.bodyCipher!!))
            repo.analyse(row.id,SuggestionEngine { output })
            assertEquals(Kind.UNKNOWN,Json.decode(db.store().get(row.id)!!.parsed).kind)
            assertTrue(db.store().templates().isEmpty()); assertTrue(db.store().mappings().isEmpty())
            repo.applyAdvice(row.id)
            val suggestion = Json.decode(db.store().get(row.id)!!.parsed)
            assertEquals("REVIEW",db.store().get(row.id)!!.status)
            repo.approve(row.id,suggestion,"a",null,"food",null,remember = true)
            assertNull(db.store().evidence(row.id)!!.bodyCipher)
            assertEquals(1,db.store().templates().size); assertTrue(db.store().templates().single().enabled)
            assertEquals("a",db.store().mappings().single().accountId)
            assertEquals("food",db.store().merchantRules().single().categoryId)
            assertEquals(1,db.store().decisions().size)
            db.close()
            db = Room.databaseBuilder(context,AppDatabase::class.java,filename).build()
            repo = Repository(context,db,settings,enqueue = {})
            // Parser identity changes after learning; message-hash ledger still prevents re-imports.
            assertFalse(repo.ingest(repo.candidate(sms,1780000001000,"inbox:1")!!,true))
            val next = sms.replace("123.45","222.00").replace("06/11/2026","07/11/2026").replace("ABC123456","ABC654321")
            repo.ingest(repo.candidate(next,1780000002000,"synthetic2")!!,false)
            val reused = db.store().entries().first { it.id != row.id }
            assertEquals("222.00",Json.decode(reused.parsed).amount)
            assertEquals("REVIEW",reused.status)
            assertEquals("NONE",db.store().evidence(reused.id)!!.state) // no second LLM call needed
            val rule = db.store().templates().single()
            settings.automatic = true
            val third = next.replace("222.00","333.00").replace("ABC654321","ABC111222")
            repo.ingest(repo.candidate(third,1780000003000,"synthetic3")!!,false)
            assertEquals(2,db.store().entries().count { it.status == "QUEUED" })
            val originalSpec = Json.gson.fromJson(rule.spec,TemplateSpec::class.java)
            db.store().template(rule.copy(id = "conflicting",spec = Json.gson.toJson(originalSpec.copy(kind = Kind.INCOME))))
            val fourth = next.replace("222.00","444.00").replace("ABC654321","ABC333444")
            repo.ingest(repo.candidate(fourth,1780000004000,"synthetic4")!!,false)
            val conflicted = db.store().entries().first { Json.decode(it.parsed).kind == Kind.UNKNOWN }
            assertEquals("REVIEW",conflicted.status); assertEquals("PENDING",db.store().evidence(conflicted.id)!!.state)
            db.store().deleteTemplate("conflicting")
            val correction = TemplateLearning.apply(originalSpec,fourth)!!.copy(kind = Kind.TRANSFER)
            repo.approve(conflicted.id,correction,"a","b",null,null,remember = true)
            assertFalse(db.store().templates().single().enabled)
            assertEquals(Kind.TRANSFER,Json.gson.fromJson(db.store().templates().single().spec,TemplateSpec::class.java).kind)
        } finally { db.close(); context.deleteDatabase(filename); settings.aiEnabled = false; settings.automatic = false }
    }
    @Test fun invalidAiOutputAndIgnoreNeverCreateTrustedKnowledge() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val settings = Settings(context,"learning-invalid-test"); settings.aiEnabled = true
        try {
            val repo = Repository(context,db,settings,enqueue = {})
            repo.ingest(repo.candidate(sms,1780000000000,"synthetic")!!,false)
            val row = db.store().entries().single()
            try { repo.analyse(row.id,SuggestionEngine { output.replace("123.45","987.65") }); fail("Hallucinated amount accepted") }
            catch (_: IllegalArgumentException) { }
            assertTrue(db.store().templates().isEmpty()); assertEquals("REVIEW",db.store().get(row.id)!!.status)
            repo.ignore(row.id); assertNull(db.store().evidence(row.id)!!.bodyCipher)
        } finally { db.close(); settings.aiEnabled = false }
    }
    @Test fun repaymentRemembersDestinationCardRatherThanPayingAccount() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            val repo = Repository(context,db,Settings(context,"repayment-learning"),enqueue = {})
            db.store().catalog(listOf(Catalog("payer","accounts","Bank"),Catalog("card","accounts","Credit Card")))
            val t = Transaction(Kind.REPAYMENT,"10.00",accountKey = "IDFC:CARD:4321",merchant = "Credit card repayment",occurredAt = "2026-01-01T00:00:00Z",precision = Precision.DATE)
            repo.ingest(SmsCandidate("fixture",0,"repayment",t),true)
            val e = db.store().entries().single()
            repo.approve(e.id,t.copy(kind = Kind.TRANSFER),"payer","card",null,null,remember = true)
            assertEquals("card",db.store().mappings().single().accountId)
            assertEquals(TransferRoute(null, "card"), repo.transferAccounts().route(t))
            db.store().deleteMapping("IDFC:CARD:4321")
            assertEquals(TransferRoute(null, null), repo.transferAccounts().route(t))
            assertTrue(db.store().merchantRules().isEmpty())
        } finally { db.close() }
    }
    @Test fun migrationPreservesVersionOneHistoryMappingsAndCategories() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val filename = "migration-${java.util.UUID.randomUUID()}.db"
        val file = context.getDatabasePath(filename); file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file,null).use { old ->
            old.execSQL("CREATE TABLE transactions (id TEXT NOT NULL PRIMARY KEY, fingerprint TEXT NOT NULL, parsed TEXT NOT NULL, status TEXT NOT NULL, sourceId TEXT NOT NULL, sourceAccount TEXT, targetAccount TEXT, categoryId TEXT, remoteId TEXT, mirrorId TEXT, linkedId TEXT, message TEXT NOT NULL, createdAt INTEGER NOT NULL)")
            old.execSQL("CREATE UNIQUE INDEX index_transactions_fingerprint ON transactions(fingerprint)")
            old.execSQL("CREATE TABLE mappings (`key` TEXT NOT NULL PRIMARY KEY, accountId TEXT NOT NULL)")
            old.execSQL("CREATE TABLE catalog (id TEXT NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL, currency TEXT NOT NULL, PRIMARY KEY(id,type))")
            old.execSQL("CREATE TABLE merchant_rules (merchant TEXT NOT NULL PRIMARY KEY, categoryId TEXT NOT NULL)")
            old.execSQL("INSERT INTO transactions VALUES ('old','fp','{}','SYNCED','fixture',NULL,NULL,NULL,'remote',NULL,NULL,'preserve',1)")
            old.execSQL("INSERT INTO mappings VALUES ('ACME:ACCOUNT:9012','a')")
            old.execSQL("INSERT INTO catalog VALUES ('food','categories','Food','INR')")
            old.version = 1
        }
        val db = Room.databaseBuilder(context,AppDatabase::class.java,filename).addMigrations(MIGRATION_1_2).build()
        try {
            assertEquals("remote",db.store().get("old")!!.remoteId)
            assertEquals("a",db.store().mappings().single().accountId)
            assertEquals("Food",db.store().catalog("categories").single().name)
            assertTrue(db.store().templates().isEmpty())
        } finally { db.close(); context.deleteDatabase(filename) }
    }
}
