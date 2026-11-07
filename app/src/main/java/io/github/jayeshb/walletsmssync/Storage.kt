package io.github.jayeshb.walletsmssync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Entity(tableName = "transactions", indices = [Index(value = ["fingerprint"], unique = true)])
data class Entry(
    @PrimaryKey val id: String,
    val fingerprint: String,
    val parsed: String,
    val status: String = "REVIEW",
    val sourceId: String,
    val sourceAccount: String? = null,
    val targetAccount: String? = null,
    val categoryId: String? = null,
    val remoteId: String? = null,
    val mirrorId: String? = null,
    val linkedId: String? = null,
    val message: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val smsTitle: String? = null
)
@Entity(tableName = "mappings") data class AccountMapping(@PrimaryKey val key: String, val accountId: String)
@Entity(tableName = "catalog", primaryKeys = ["id", "type"]) data class Catalog(val id: String, val type: String, val name: String, val currency: String = "INR")
@Entity(tableName = "merchant_rules") data class MerchantRule(@PrimaryKey val merchant: String, val categoryId: String,
    @ColumnInfo(defaultValue = "'USER'") val origin: String = "USER")
@Entity(tableName = "sms_evidence", indices = [Index(value = ["messageHash"], unique = true), Index(value = ["referenceKey"], unique = true)])
data class SmsEvidence(@PrimaryKey val entryId: String, val messageHash: String, val received: Long, val baseline: String,
    val bodyCipher: String? = null, val state: String = "NONE", val suggestion: String? = null,
    val reason: String = "", val modelInfo: String = "", val elapsedMs: Long = 0, val referenceKey: String? = null, val historical: Boolean = false)
@Entity(tableName = "learned_templates")
data class LearnedTemplate(@PrimaryKey val id: String, val spec: String, val example: String, val enabled: Boolean = true,
    val confirmations: Int = 1, val createdAt: Long = System.currentTimeMillis(), val origin: String = "USER")
@Entity(tableName = "learning_decisions")
data class LearningDecision(@PrimaryKey val entryId: String, val beforeJson: String, val afterJson: String, val reason: String,
    val templateId: String?, val createdAt: Long = System.currentTimeMillis())

@Dao interface Store {
    @Query("SELECT * FROM transactions ORDER BY createdAt DESC") fun observe(): Flow<List<Entry>>
    @Query("SELECT * FROM transactions ORDER BY createdAt DESC") suspend fun entries(): List<Entry>
    @Query("SELECT * FROM transactions WHERE id = :id") suspend fun get(id: String): Entry?
    @Query("SELECT * FROM transactions WHERE fingerprint = :value") suspend fun byFingerprint(value: String): Entry?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(e: Entry): Long
    @Update suspend fun update(e: Entry)
    @Query("SELECT * FROM transactions WHERE status = 'QUEUED' ORDER BY createdAt LIMIT 1") suspend fun next(): Entry?
    @Query("UPDATE transactions SET status = 'UNCERTAIN', message = 'Upload interrupted; reconcile remote outcome' WHERE status = 'SENDING'") suspend fun recoverInterrupted()
    @Query("UPDATE transactions SET status = 'SENDING' WHERE id = :id AND status = 'QUEUED'") suspend fun claim(id: String): Int
    @Query("SELECT * FROM mappings") suspend fun mappings(): List<AccountMapping>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun mapping(m: AccountMapping)
    @Query("SELECT * FROM catalog WHERE type = :type ORDER BY name") suspend fun catalog(type: String): List<Catalog>
    @Query("SELECT * FROM catalog") suspend fun allCatalog(): List<Catalog>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun catalog(c: List<Catalog>)
    @Query("DELETE FROM catalog WHERE type = :type") suspend fun clearCatalog(type: String)
    @Transaction suspend fun replaceCatalog(type: String, items: List<Catalog>) { clearCatalog(type); catalog(items) }
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun rule(r: MerchantRule)
    @Query("SELECT categoryId FROM merchant_rules WHERE merchant = :merchant") suspend fun categoryFor(merchant: String): String?
    @Query("SELECT * FROM merchant_rules ORDER BY merchant") suspend fun merchantRules(): List<MerchantRule>
    @Query("SELECT * FROM sms_evidence WHERE entryId = :id") suspend fun evidence(id: String): SmsEvidence?
    @Query("SELECT * FROM sms_evidence") suspend fun allEvidence(): List<SmsEvidence>
    @Query("SELECT * FROM sms_evidence WHERE messageHash = :value") suspend fun evidenceByHash(value: String): SmsEvidence?
    @Query("SELECT * FROM sms_evidence WHERE referenceKey = :value") suspend fun evidenceByReference(value: String): SmsEvidence?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun evidence(e: SmsEvidence)
    @Query("UPDATE sms_evidence SET state = 'PENDING' WHERE entryId = :id AND state = 'FAILED' AND bodyCipher IS NOT NULL")
    suspend fun retryAi(id: String): Int
    @Query("SELECT * FROM sms_evidence WHERE state = 'PENDING' ORDER BY received LIMIT 1") suspend fun nextAi(): SmsEvidence?
    @Query("UPDATE sms_evidence SET state = 'FAILED', reason = 'AI processing interrupted; retry from Review' WHERE state = 'RUNNING'") suspend fun recoverAi()
    @Query("SELECT * FROM learned_templates ORDER BY createdAt DESC") suspend fun templates(): List<LearnedTemplate>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun template(t: LearnedTemplate)
    @Query("DELETE FROM learned_templates WHERE id = :id") suspend fun deleteTemplate(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun decision(d: LearningDecision)
    @Query("SELECT * FROM learning_decisions ORDER BY createdAt DESC LIMIT 100") suspend fun decisions(): List<LearningDecision>
    @Query("SELECT * FROM learning_decisions") suspend fun allDecisions(): List<LearningDecision>
    @Query("SELECT afterJson FROM learning_decisions WHERE afterJson LIKE :keyPattern AND afterJson LIKE '%\"kind\":\"TRANSFER\"%' ORDER BY createdAt DESC LIMIT 1") suspend fun transferDecision(keyPattern: String): String?
    @Query("DELETE FROM merchant_rules WHERE merchant = :value") suspend fun deleteMerchantRule(value: String)
    @Query("DELETE FROM mappings WHERE `key` = :value") suspend fun deleteMapping(value: String)
}
@Database(entities = [Entry::class, AccountMapping::class, Catalog::class, MerchantRule::class, SmsEvidence::class, LearnedTemplate::class, LearningDecision::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() { abstract fun store(): Store }

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transactions ADD COLUMN smsTitle TEXT")
        db.execSQL("ALTER TABLE merchant_rules ADD COLUMN origin TEXT NOT NULL DEFAULT 'USER'")
        db.execSQL("CREATE TABLE IF NOT EXISTS sms_evidence (entryId TEXT NOT NULL PRIMARY KEY, messageHash TEXT NOT NULL, received INTEGER NOT NULL, baseline TEXT NOT NULL, bodyCipher TEXT, state TEXT NOT NULL, suggestion TEXT, reason TEXT NOT NULL, modelInfo TEXT NOT NULL, elapsedMs INTEGER NOT NULL, referenceKey TEXT, historical INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sms_evidence_messageHash ON sms_evidence(messageHash)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sms_evidence_referenceKey ON sms_evidence(referenceKey)")
        db.execSQL("CREATE TABLE IF NOT EXISTS learned_templates (id TEXT NOT NULL PRIMARY KEY, spec TEXT NOT NULL, example TEXT NOT NULL, enabled INTEGER NOT NULL, confirmations INTEGER NOT NULL, createdAt INTEGER NOT NULL, origin TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS learning_decisions (entryId TEXT NOT NULL PRIMARY KEY, beforeJson TEXT NOT NULL, afterJson TEXT NOT NULL, reason TEXT NOT NULL, templateId TEXT, createdAt INTEGER NOT NULL)")
    }
}

class Settings(context: Context, name: String = "settings") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(value) { prefs.edit().putBoolean("automatic", value).commit() }
    var defaultCategory: String
        get() = prefs.getString("category", "") ?: ""
        set(value) { prefs.edit().putString("category", value).commit() }
    var notBefore: Long
        get() = prefs.getLong("notBefore", 0)
        set(value) { prefs.edit().putLong("notBefore", value).commit() }
    var notice: String
        get() = prefs.getString("notice", "") ?: ""
        set(value) { prefs.edit().putString("notice", value).commit() }
    var authBlocked: Boolean
        get() = prefs.getBoolean("authBlocked", false)
        set(value) { prefs.edit().putBoolean("authBlocked", value).commit() }
    var aiEnabled: Boolean
        get() = prefs.getBoolean("cloudAiEnabled", false)
        set(value) { prefs.edit().putBoolean("cloudAiEnabled", value).commit() }
    var groqBlocked: Boolean
        get() = prefs.getBoolean("groqBlocked", false)
        set(value) { prefs.edit().putBoolean("groqBlocked", value).commit() }
    var groqNotBefore: Long
        get() = prefs.getLong("groqNotBefore", 0)
        set(value) { prefs.edit().putLong("groqNotBefore", value).commit() }

    @Synchronized fun saveGroqKey(value: String) {
        val key = value.trim()
        val editor = prefs.edit().putBoolean("cloudAiEnabled", false)
            .putBoolean("groqBlocked", false).remove("groqNotBefore").remove("aiNotice")
        if (key.isEmpty()) {
            editor.remove("groqKey")
        } else {
            require(key.all { it in '!'..'~' }) { "Enter a valid Groq API key" }
            editor.putString("groqKey", encrypt(key, "groq-key"))
        }
        check(editor.commit()) { "Could not save Groq settings" }
    }

    @Synchronized fun groqKey(): String? =
        prefs.getString("groqKey", null)?.let { decrypt(it, "groq-key") }

    var aiNotice: String
        get() = prefs.getString("aiNotice", "") ?: ""
        set(value) { prefs.edit().putString("aiNotice", value).commit() }
    var noteTitleMode: String
        get() = prefs.getString("noteTitleMode", "BOTH") ?: "BOTH"
        set(value) { prefs.edit().putString("noteTitleMode", value).commit() }
    private fun key(alias: String = "wallet-token"): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun sealEvidence(text: String): String = encrypt(text, "sms-evidence")

    @Synchronized fun openEvidence(text: String): String? = decrypt(text, "sms-evidence")

    private fun encrypt(text: String, alias: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(alias)) }
        val result = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + result, Base64.NO_WRAP)
    }
    private fun decrypt(text: String, alias: String): String? = runCatching {
        val bytes = Base64.decode(text, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(128, bytes.copyOfRange(0,12))) }
        String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)), Charsets.UTF_8)
    }.getOrNull()
    @Synchronized fun saveToken(value: String) {
        if (value.isBlank()) { prefs.edit().remove("token").putBoolean("authBlocked", false).putString("notice", "").commit(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        prefs.edit().putString("token", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).putBoolean("authBlocked", false).putString("notice", "").commit()
    }
    @Synchronized fun token(): String? {
        val stored = prefs.getString("token", null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }.getOrNull()
    }
}
