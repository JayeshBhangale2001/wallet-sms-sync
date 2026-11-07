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

internal class GroqFailure(
    message: String,
    val authFailed: Boolean = false,
    val retryAfterSeconds: Long? = null
) : IllegalArgumentException(message)

internal class GroqApi(
    private val apiKey: String,
    private val endpoint: String = "https://api.groq.com/openai/v1/chat/completions"
) : SuggestionEngine {
    override suspend fun infer(prompt: String): String = withContext(Dispatchers.IO) {
        val payload = JsonObject().apply {
            addProperty("model", MODEL)
            addProperty("reasoning_effort", "low")
            addProperty("max_completion_tokens", 600)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", prompt)
                })
            })
            add("response_format", responseFormat)
        }
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    401, 403 -> throw GroqFailure("Groq rejected the API key or model access. Check the key in Setup.", authFailed = true)
                    429 -> {
                        val retryAfter = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 86_400) ?: 60L
                        throw GroqFailure("Groq rate limit reached. Processing will resume after the cooldown.", retryAfterSeconds = retryAfter)
                    }
                }
                if (!response.isSuccessful) throw GroqFailure("Groq request failed (HTTP ${response.code}). Try again from Review.")
                parseContent(response.body?.string().orEmpty())
            }
        } catch (_: IOException) {
            throw GroqFailure("Could not reach Groq. Check your connection and retry.")
        }
    }

    private fun parseContent(body: String): String {
        val content = runCatching {
            val choice = JsonParser.parseString(body).asJsonObject.getAsJsonArray("choices")[0].asJsonObject
            if (choice["finish_reason"]?.asString != "stop") return@runCatching null
            choice.getAsJsonObject("message")["content"]?.asString?.takeIf { it.isNotBlank() }
        }.getOrNull()
        return content ?: throw GroqFailure("Groq returned an incomplete extraction. Try again from Review.")
    }

    companion object {
        const val MODEL = "openai/gpt-oss-20b"
        private val client = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

        private val responseFormat = JsonObject().apply {
            addProperty("type", "json_schema")
            add("json_schema", JsonObject().apply {
                addProperty("name", "sms_transaction")
                addProperty("strict", true)
                add("schema", JsonObject().apply {
                    addProperty("type", "object")
                    addProperty("additionalProperties", false)
                    val fields = listOf("kind", "amount", "accountKey", "merchant", "dateText", "dateFormat", "reference", "categoryId", "reason")
                    add("required", JsonArray().apply { fields.forEach { add(it) } })
                    add("properties", JsonObject().apply {
                        for (field in fields) {
                            add(field, JsonObject().apply {
                                add("type", JsonArray().apply { add("string"); add("null") })
                            })
                        }
                    })
                })
            })
        }
    }
}

class AiWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.wallet
        val settings = app.settings
        if (!settings.aiEnabled || settings.groqBlocked) return Result.success()
        val key = settings.groqKey() ?: return Result.success()
        if (System.currentTimeMillis() < settings.groqNotBefore) return Result.retry()

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
                if (!settings.aiEnabled || settings.groqKey() != key) {
                    throw GroqFailure("Groq settings changed. Retry once setup is complete.")
                }
                val output = GroqApi(key).infer(prompt)
                if (!settings.aiEnabled || settings.groqKey() != key) {
                    throw GroqFailure("Groq settings changed. Retry once setup is complete.")
                }
                output
            })
            app.db.withTransaction {
                val result = store.evidence(evidence.entryId)
                if (result != null && result.state in setOf("READY", "AUTO_QUEUED")) {
                    store.evidence(result.copy(modelInfo = "Groq GPT-OSS 20B", elapsedMs = SystemClock.elapsedRealtime() - startedAt))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val groqFailure = failure as? GroqFailure
            val retryAfter = groqFailure?.retryAfterSeconds
            val sameKey = settings.groqKey() == key
            if (sameKey) {
                if (groqFailure?.authFailed == true) settings.groqBlocked = true
                if (retryAfter != null) settings.groqNotBefore = System.currentTimeMillis() + retryAfter * 1000
                settings.aiNotice = groqFailure?.message ?: "Groq extraction needs manual review."
            }
            app.db.withTransaction {
                val result = store.evidence(evidence.entryId)
                if (result?.state == "RUNNING" && result.bodyCipher == evidence.bodyCipher) {
                    val state = if (retryAfter != null || groqFailure?.authFailed == true) "PENDING" else "FAILED"
                    store.evidence(result.copy(state = state, reason = groqFailure?.message ?: "AI fields could not be validated. Review manually or retry."))
                }
            }
            if (sameKey && retryAfter != null) return Result.retry()
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
