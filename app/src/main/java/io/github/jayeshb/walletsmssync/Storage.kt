package io.github.jayeshb.walletsmssync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
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
    val createdAt: Long = System.currentTimeMillis()
)
@Entity(tableName = "mappings") data class AccountMapping(@PrimaryKey val key: String, val accountId: String)
@Entity(tableName = "catalog", primaryKeys = ["id", "type"]) data class Catalog(val id: String, val type: String, val name: String, val currency: String = "INR")
@Entity(tableName = "merchant_rules") data class MerchantRule(@PrimaryKey val merchant: String, val categoryId: String)

@Dao interface Store {
    @Query("SELECT * FROM transactions ORDER BY createdAt DESC") fun observe(): Flow<List<Entry>>
    @Query("SELECT * FROM transactions ORDER BY createdAt DESC") suspend fun entries(): List<Entry>
    @Query("SELECT * FROM transactions WHERE id = :id") suspend fun get(id: String): Entry?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(e: Entry): Long
    @Update suspend fun update(e: Entry)
    @Query("SELECT * FROM transactions WHERE status = 'QUEUED' ORDER BY createdAt LIMIT 1") suspend fun next(): Entry?
    @Query("UPDATE transactions SET status = 'UNCERTAIN', message = 'Upload interrupted; reconcile remote outcome' WHERE status = 'SENDING'") suspend fun recoverInterrupted()
    @Query("UPDATE transactions SET status = 'SENDING' WHERE id = :id AND status = 'QUEUED'") suspend fun claim(id: String): Int
    @Query("SELECT * FROM mappings") suspend fun mappings(): List<AccountMapping>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun mapping(m: AccountMapping)
    @Query("SELECT * FROM catalog WHERE type = :type ORDER BY name") suspend fun catalog(type: String): List<Catalog>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun catalog(c: List<Catalog>)
    @Query("DELETE FROM catalog WHERE type = :type") suspend fun clearCatalog(type: String)
    @Transaction suspend fun replaceCatalog(type: String, items: List<Catalog>) { clearCatalog(type); catalog(items) }
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun rule(r: MerchantRule)
    @Query("SELECT categoryId FROM merchant_rules WHERE merchant = :merchant") suspend fun categoryFor(merchant: String): String?
}
@Database(entities = [Entry::class, AccountMapping::class, Catalog::class, MerchantRule::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() { abstract fun store(): Store }

class Settings(context: Context, name: String = "settings") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    var automatic: Boolean
        get() = prefs.getBoolean("automatic", false)
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
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("wallet-token", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("wallet-token", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
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
