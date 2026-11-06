package io.github.jayeshb.walletsmssync

import com.google.gson.JsonObject
import io.github.jayeshb.walletsmssync.core.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class ApiReply(val code: Int, val body: String, val retrySeconds: Long?)
class ApiProblem(val code: Int, val delaySeconds: Long = 60) : Exception(when(code) {
    401 -> "Token invalid or expired"
    403 -> "Wallet API access not permitted; check Premium and token type"
    409 -> "Wallet initial sync in progress; retry later"
    429 -> "Wallet rate limit; retry later"
    else -> "Wallet request failed (HTTP $code)"
})
class WalletApi(private val token: String, private val base: String = "https://rest.budgetbakers.com/wallet/v1/api/", private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()) {
    private fun request(path: String, parameters: Map<String, String> = emptyMap()): Request.Builder {
        val url = (base + path).toHttpUrl().newBuilder().apply { parameters.forEach { (k,v) -> addQueryParameter(k,v) } }.build()
        return Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/json")
    }
    private fun execute(request: Request): ApiReply = client.newCall(request).execute().use { response ->
        val seconds = response.header("Retry-After")?.let { raw -> raw.toLongOrNull() ?: runCatching {
            (ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()) / 1000
        }.getOrNull() }
        ApiReply(response.code, response.body?.string().orEmpty(), seconds?.coerceAtLeast(1))
    }
    fun catalog(type: String): List<Catalog> {
        val items = mutableListOf<Catalog>()
        var offset = 0
        repeat(100) {
            val r = execute(request(type, mapOf("limit" to "200", "offset" to "$offset")).get().build())
            if (r.code !in 200..299) throw ApiProblem(r.code, r.retrySeconds ?: 300)
            val root = Json.gson.fromJson(r.body, JsonObject::class.java)
            val list = root.getAsJsonArray(type) ?: error("Invalid Wallet catalog response")
            list.forEach { el -> val o = el.asJsonObject
                if (o.get("archived")?.asBoolean != true && o.get("enabled")?.asBoolean != false)
                    items += Catalog(o["id"].asString, type, o["name"]?.asString ?: "Unnamed", o["currencyCode"]?.asString ?: "INR")
            }
            val next = root["nextOffset"]
            if (next == null || next.isJsonNull) return items
            require(next.asInt > offset) { "Invalid pagination offset" }
            offset = next.asInt
        }
        error("Wallet catalog exceeded pagination limit")
    }
    fun create(e: Entry): ApiReply = execute(request("records", mapOf("agentHints" to "true", "returnData" to "false"))
            .post(WalletPayload.create(Json.decode(e.parsed), e.sourceAccount.orEmpty(), e.targetAccount, e.categoryId, e.id, e.smsTitle)
            .toString().toRequestBody("application/json".toMediaType())).build())

    fun findMarker(marker: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var offset = 0
        repeat(100) {
            val r = execute(request("records", mapOf("note" to "contains.wallet-sms-sync:$marker", "limit" to "200", "offset" to "$offset")).get().build())
            if (r.code !in 200..299) throw ApiProblem(r.code, r.retrySeconds ?: 300)
            val root = Json.gson.fromJson(r.body, JsonObject::class.java)
            root.getAsJsonArray("records")?.forEach { out += it.asJsonObject }
            val next = root["nextOffset"]
            if (next == null || next.isJsonNull) return out
            require(next.asInt > offset)
            offset = next.asInt
        }
        error("Reconciliation exceeded pagination limit")
    }
}
