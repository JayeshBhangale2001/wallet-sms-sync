package io.github.jayeshb.walletsmssync

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import androidx.work.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

fun interface SuggestionEngine {
    suspend fun infer(prompt: String): String
}

internal enum class AiProvider(val label: String, val model: String, val endpoint: String, val keyPreference: String, val keyAlias: String) {
    GROQ("Groq", "openai/gpt-oss-20b", "https://api.groq.com/openai/v1/chat/completions", "groqKey", "groq-key"),
    XAI("Grok (xAI)", "grok-4.3", "https://api.x.ai/v1/responses", "xaiKey", "xai-key")
}

internal class AiFailure(
    message: String,
    val authFailed: Boolean = false,
    val retryAfterSeconds: Long? = null
) : IllegalArgumentException(message)

internal class CloudAi(
    private val provider: AiProvider,
    private val apiKey: String,
    private val endpoint: String = provider.endpoint
) : SuggestionEngine {
    override suspend fun infer(prompt: String): String = withContext(Dispatchers.IO) {
        val payload = JsonObject().apply {
            addProperty("model", provider.model)
            if (provider == AiProvider.XAI) {
                addProperty("input", prompt)
                addProperty("store", false)
                addProperty("max_output_tokens", 600)
                add("reasoning", JsonObject().apply { addProperty("effort", "none") })
                add("text", JsonObject().apply {
                    add("format", schema.deepCopy().apply { addProperty("type", "json_schema") })
                })
            } else {
                addProperty("reasoning_effort", "low")
                addProperty("max_completion_tokens", 600)
                add("messages", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", prompt)
                    })
                })
                add("response_format", JsonObject().apply {
                    addProperty("type", "json_schema")
                    add("json_schema", schema)
                })
            }
        }
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    401 -> throw AiFailure("${provider.label} rejected the key (HTTP 401). Save a key issued by that provider.", authFailed = true)
                    403 -> throw AiFailure("${provider.label} denied access (HTTP 403). Check model permissions for ${provider.model}.", authFailed = true)
                    404 -> if (provider == AiProvider.XAI) {
                        throw AiFailure("xAI could not access ${provider.model} (HTTP 404). Check the key's team and model access.", authFailed = true)
                    }
                    429 -> {
                        val retryAfter = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 86_400) ?: 60L
                        throw AiFailure("${provider.label} rate limit reached. Processing will resume after the cooldown.", retryAfterSeconds = retryAfter)
                    }
                }
                if (!response.isSuccessful) throw AiFailure("${provider.label} request failed (HTTP ${response.code}).")
                parseContent(response.body?.string().orEmpty())
            }
        } catch (_: IOException) {
            throw AiFailure("Could not reach ${provider.label}. Check your connection and retry.")
        }
    }

    private fun parseContent(body: String): String {
        val content = runCatching {
            val root = JsonParser.parseString(body).asJsonObject
            if (provider == AiProvider.XAI) {
                if (root["status"]?.asString != "completed") return@runCatching null
                return@runCatching root.getAsJsonArray("output").map { it.asJsonObject }
                    .filter { it["type"]?.asString == "message" }
                    .flatMap { it.getAsJsonArray("content").map { part -> part.asJsonObject } }
                    .filter { it["type"]?.asString == "output_text" }
                    .joinToString("") { it["text"].asString }.takeIf { it.isNotBlank() }
            }
            val choice = root.getAsJsonArray("choices")[0].asJsonObject
            if (choice["finish_reason"]?.asString != "stop") return@runCatching null
            choice.getAsJsonObject("message")["content"]?.asString?.takeIf { it.isNotBlank() }
        }.getOrNull()
        return content ?: throw AiFailure("${provider.label} returned an incomplete extraction. Try again from Review.")
    }

    companion object {
        private val client = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

        private val schema = JsonObject().apply {
            addProperty("name", "sms_transaction")
            addProperty("strict", true)
            add("schema", JsonObject().apply {
                addProperty("type", "object")
                addProperty("additionalProperties", false)
                val fields = listOf("kind", "amount", "accountKey", "merchant", "dateText", "dateFormat", "reference",
                    "sourceAccountAlias", "targetAccountAlias", "categoryId", "reason")
                add("required", JsonArray().apply { fields.forEach { add(it) } })
                add("properties", JsonObject().apply {
                    for (field in fields) {
                        add(field, JsonObject().apply {
                            add("type", JsonArray().apply { add("string"); add("null") })
                        })
                    }
                })
            })
        }
    }
}

class AiWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.wallet
        val settings = app.settings
        if (!settings.aiEnabled || settings.aiBlocked) return Result.success()
        val provider = settings.aiProvider
        val key = settings.aiKey(provider) ?: return Result.success()
        fun sameCredentials() = settings.aiProvider == provider && settings.aiKey(provider) == key
        if (System.currentTimeMillis() < settings.aiNotBefore) return Result.retry()

        val store = app.db.store()
        val evidence = app.db.withTransaction {
            store.recoverAi()
            val pending = store.nextAi() ?: return@withTransaction null
            val active = store.get(pending.entryId)?.status == "REVIEW"
            store.evidence(if (active) pending.copy(state = "RUNNING") else pending.copy(state = "NONE", bodyCipher = null))
            pending
        } ?: return Result.success()

        val current = store.evidence(evidence.entryId)
        if (current?.state != "RUNNING") {
            schedule(app)
            return Result.success()
        }
        val startedAt = SystemClock.elapsedRealtime()
        try {
            app.repository.analyse(evidence.entryId, SuggestionEngine { prompt ->
                if (!settings.aiEnabled || !sameCredentials()) {
                    throw AiFailure("AI settings changed. Retry once setup is complete.")
                }
                val output = CloudAi(provider, key).infer(prompt)
                if (!settings.aiEnabled || !sameCredentials()) {
                    throw AiFailure("AI settings changed. Retry once setup is complete.")
                }
                output
            })
            app.db.withTransaction {
                val result = store.evidence(evidence.entryId)
                if (result != null && result.state in setOf("READY", "AUTO_QUEUED")) {
                    store.evidence(result.copy(modelInfo = "${provider.label} ${provider.model}", elapsedMs = SystemClock.elapsedRealtime() - startedAt))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val aiFailure = failure as? AiFailure
            val retryAfter = aiFailure?.retryAfterSeconds
            val sameKey = sameCredentials()
            if (sameKey) {
                if (aiFailure?.authFailed == true) settings.aiBlocked = true
                if (retryAfter != null) settings.aiNotBefore = System.currentTimeMillis() + retryAfter * 1000
                settings.aiNotice = aiFailure?.message ?: "AI extraction needs manual review."
            }
            app.db.withTransaction {
                val result = store.evidence(evidence.entryId)
                if (result?.state == "RUNNING" && result.bodyCipher == evidence.bodyCipher) {
                    val state = if (!sameKey || retryAfter != null || aiFailure?.authFailed == true) "PENDING" else "FAILED"
                    store.evidence(result.copy(state = state, reason = aiFailure?.message ?: "AI fields could not be validated. Review manually or retry."))
                }
            }
            if (sameKey && retryAfter != null) return Result.retry()
        }
        if (!evidence.historical && sameCredentials()) {
            val entry = store.get(evidence.entryId)
            if (entry?.status == "QUEUED") ReviewNotifications.cancelPayment(app, entry.id)
            if (entry?.status == "REVIEW") {
                val suggestion = store.evidence(entry.id)?.suggestion
                val preview = suggestion?.let { io.github.jayeshb.walletsmssync.core.Json.gson.fromJson(it, io.github.jayeshb.walletsmssync.core.AiAdvice::class.java).transaction }
                ReviewNotifications.payment(app, settings, if (preview == null) entry else entry.copy(parsed = io.github.jayeshb.walletsmssync.core.Json.encode(preview)))
            }
        }
        schedule(app)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            // Keep the existing chain name so upgraded installs process pending evidence in order.
            val request = OneTimeWorkRequestBuilder<AiWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("local-ai", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
