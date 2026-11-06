package io.github.jayeshb.walletsmssync

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.work.*
import io.github.jayeshb.walletsmssync.inference.NativeModel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

fun interface SuggestionEngine { suspend fun infer(prompt: String): String }

class LocalAi(private val app: WalletApp) : SuggestionEngine {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var idle: Job? = null
    private var engine: NativeModel? = null
    private var configuration = ""
    val modelFile get() = File(app.noBackupFilesDir, "models/gemma.litertlm")
    val available get() = modelFile.isFile && supported
    val supported get() = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

    suspend fun importModel(uri: Uri): String = withContext(Dispatchers.IO) {
        require(supported) { "Local AI requires a 64-bit Android device; standard parsing still works" }
        val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if(cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty()
        require(name.endsWith(".litertlm", true)) { "Select a compatible .litertlm model file" }
        mutex.withLock {
            try { NativeModel.probe() } catch (_: LinkageError) { throw IllegalArgumentException("Native AI runtime cannot load on this device") }
            val folder = modelFile.parentFile!!; folder.mkdirs()
            val temp = File(folder, "import-${java.util.UUID.randomUUID()}.part")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                requireNotNull(app.contentResolver.openInputStream(uri)).use { input -> temp.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        count += n
                        require(count <= 3_000_000_000L) { "Model must be smaller than 3 GB" }
                        digest.update(buffer,0,n); output.write(buffer,0,n)
                    }
                } }
                require(count >= 1_000_000) { "File is too small to be a language model" }
                releaseLocked()
                // POSIX rename replaces the previous file only after a complete import.
                android.system.Os.rename(temp.absolutePath, modelFile.absolutePath)
                app.settings.modelHash = digest.digest().joinToString("") { "%02x".format(it) }
                app.settings.aiNotice = "Model imported (${count / 1_000_000} MB). Run the sample test to check compatibility."
                AiWorker.schedule(app)
                app.settings.aiNotice
            } finally { temp.delete() }
        }
    }
    override suspend fun infer(prompt: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(available) { "Import a compatible Gemma 3 1B .litertlm model in Setup" }
            idle?.cancel()
            val config = "${app.settings.modelHash}:${app.settings.preferGpu}"
            val start = android.os.SystemClock.elapsedRealtime()
            try {
                if (engine == null || configuration != config) {
                    releaseLocked()
                    engine = NativeModel(modelFile.absolutePath, File(app.cacheDir,"llm").apply { mkdirs() }.absolutePath, app.settings.preferGpu)
                    configuration = config
                }
                val response = engine!!.generate(prompt)
                app.settings.aiNotice = "${engine!!.backend}: ${(android.os.SystemClock.elapsedRealtime() - start) / 1000.0}s (includes loading when cold)"
                response
            } catch (_: LinkageError) {
                releaseLocked(); throw IllegalArgumentException("AI runtime unavailable on this device; standard parsing remains available")
            } catch (_: Exception) {
                releaseLocked(); throw IllegalArgumentException("Local model failed. Check its format or choose CPU in Setup; the item remains in Review.")
            } finally {
                idle = scope.launch { delay(60_000); mutex.withLock { releaseLocked() } }
            }
        }
    }
    private fun releaseLocked() { runCatching { engine?.close() }; engine = null; configuration = "" }
    suspend fun unload() = mutex.withLock { idle?.cancel(); releaseLocked() }
    suspend fun removeModel() = mutex.withLock {
        idle?.cancel(); releaseLocked(); modelFile.delete(); app.settings.modelHash = ""; app.settings.aiEnabled = false
        app.settings.aiNotice = "Model removed. Saved rules and mappings are retained."
    }
}

class AiWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.wallet
        if (!app.settings.aiEnabled || !app.localAi.available) return Result.success()
        val store = app.db.store()
        store.recoverAi()
        val evidence = store.nextAi() ?: return Result.success()
        if (store.get(evidence.entryId)?.status != "REVIEW") {
            store.evidence(evidence.copy(state = "NONE", bodyCipher = null)); schedule(app); return Result.success()
        }
        store.evidence(evidence.copy(state = "RUNNING"))
        val start = android.os.SystemClock.elapsedRealtime()
        try {
            app.repository.analyse(evidence.entryId, app.localAi)
            val current = store.evidence(evidence.entryId)
            if (current?.state == "READY") store.evidence(current.copy(modelInfo = app.settings.aiNotice,
                elapsedMs = android.os.SystemClock.elapsedRealtime() - start))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            val current = store.evidence(evidence.entryId)
            if (current?.state == "RUNNING") store.evidence(current.copy(state = "FAILED", reason = "AI could not produce validated fields. Review manually or retry with another model."))
        }
        schedule(app)
        return Result.success()
    }
    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("local-ai", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<AiWorker>().setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build())
        }
    }
}
