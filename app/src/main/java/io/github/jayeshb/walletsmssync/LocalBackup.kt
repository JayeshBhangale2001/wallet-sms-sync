package io.github.jayeshb.walletsmssync

import androidx.room.withTransaction
import io.github.jayeshb.walletsmssync.core.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal class LocalBackup(private val db: AppDatabase, private val settings: Settings) {
    private val store = db.store()

    suspend fun save(output: OutputStream, password: String): Int {
        require(password.length >= 12) { "Use a backup passphrase of at least 12 characters" }
        val snapshot = db.withTransaction {
            Snapshot(store.entries(), store.allEvidence().map { evidence ->
                evidence.copy(bodyCipher = null, state = when (evidence.state) {
                    "PENDING", "RUNNING" -> "NONE"
                    else -> evidence.state
                })
            }, store.mappings(), store.allCatalog(), store.merchantRules(), store.templates(),
                store.allDecisions(), settings.automatic, settings.defaultCategory, settings.noteTitleMode, store.customRules())
        }
        val plain = Json.gson.toJson(snapshot).toByteArray(Charsets.UTF_8)
        require(plain.size <= maxCipherBytes - 16) { "History is too large for this backup format" }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val encrypted = cipher(Cipher.ENCRYPT_MODE, password, salt, nonce).doFinal(plain)
        DataOutputStream(output).apply {
            writeInt(format)
            write(salt)
            write(nonce)
            writeInt(encrypted.size)
            write(encrypted)
            flush()
        }
        return snapshot.entries.size
    }

    suspend fun restore(input: InputStream, password: String): Int {
        val data = DataInputStream(input)
        require(data.readInt() == format) { "Choose a Wallet SMS Sync backup file" }
        val salt = ByteArray(16).also(data::readFully)
        val nonce = ByteArray(12).also(data::readFully)
        val size = data.readInt()
        require(size in 16..maxCipherBytes) { "Invalid backup size" }
        val encrypted = ByteArray(size).also(data::readFully)
        require(data.read() == -1) { "Invalid backup file" }
        val plain = try {
            cipher(Cipher.DECRYPT_MODE, password, salt, nonce).doFinal(encrypted)
        } catch (_: GeneralSecurityException) {
            throw IllegalArgumentException("Wrong backup passphrase or damaged file")
        }
        val snapshot = Json.gson.fromJson(plain.toString(Charsets.UTF_8), Snapshot::class.java)
        db.withTransaction {
            require(store.entries().isEmpty() && store.mappings().isEmpty() &&
                store.merchantRules().isEmpty() && store.templates().isEmpty() && store.customRules().isEmpty()) {
                "Restore on a fresh install before importing SMS or configuring mappings"
            }
            snapshot.entries.forEach { entry ->
                // An older backup can predate a successful upload.
                val restored = if (entry.status in setOf("SENDING", "QUEUED")) {
                    entry.copy(status = "UNCERTAIN", message = "Restored pending upload. Check Wallet before retrying.")
                } else entry
                require(store.insert(restored) != -1L) { "Backup contains duplicate transaction records" }
            }
            snapshot.evidence.forEach { store.evidence(it.copy(bodyCipher = null)) }
            snapshot.mappings.forEach { store.mapping(it) }
            store.catalog(snapshot.catalog)
            snapshot.rules.forEach { store.rule(it) }
            snapshot.templates.forEach { store.template(it) }
            snapshot.decisions.forEach { store.decision(it) }
            snapshot.customRules.orEmpty().forEach { store.saveCustomRule(it) }
        }
        settings.automatic = snapshot.automatic
        settings.defaultCategory = snapshot.category
        settings.noteTitleMode = snapshot.noteTitleMode
        return snapshot.entries.size
    }

    private data class Snapshot(
        val entries: List<Entry>,
        val evidence: List<SmsEvidence>,
        val mappings: List<AccountMapping>,
        val catalog: List<Catalog>,
        val rules: List<MerchantRule>,
        val templates: List<LearnedTemplate>,
        val decisions: List<LearningDecision>,
        val automatic: Boolean,
        val category: String,
        val noteTitleMode: String,
        val customRules: List<CustomRule>?
    )

    private companion object {
        const val format = 0x57534201
        const val maxCipherBytes = 32 * 1024 * 1024

        fun cipher(mode: Int, password: String, salt: ByteArray, nonce: ByteArray): Cipher {
            val spec = PBEKeySpec(password.toCharArray(), salt, 600_000, 256)
            val key = try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            }
        }
    }
}
