package bd.aiap.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class GeminiException(message: String) : Exception(message)

/** Sends one generateContent request body and returns the response body; swapped out in tests. */
fun interface GeminiTransport {
    fun generate(model: String, body: String): String
}

/** Status code and body of one HTTP response. */
data class HttpResult(val code: Int, val body: String)

/**
 * Calls Gemini with a list of API keys. When a key fails in a way another key could fix (limit reached,
 * invalid or blocked key, server or network error), the next key is tried automatically, and the key
 * that worked is used first next time.
 */
class GeminiHttp(
    apiKeys: List<String>,
    private val post: (key: String, model: String, body: String) -> HttpResult = ::okHttpPost,
) : GeminiTransport {
    private val keys = apiKeys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    private val mapper = com.fasterxml.jackson.databind.ObjectMapper()

    @Volatile
    var currentKey = 0
        private set

    val keyCount get() = keys.size

    override fun generate(model: String, body: String): String {
        if (keys.isEmpty()) throw GeminiException("সেটিংসে অন্তত একটি Gemini API key দিন।")
        var lastProblem = ""
        for (attempt in keys.indices) {
            val index = (currentKey + attempt) % keys.size
            val result = try {
                post(keys[index], model, body)
            } catch (e: java.io.IOException) {
                lastProblem = "ইন্টারনেট সংযোগে সমস্যা (${e.message})"
                continue
            }
            if (result.code in 200..299) {
                currentKey = index
                return result.body
            }
            val detail = runCatching { mapper.readTree(result.body).path("error").path("message").asText() }.getOrDefault("")
            val keyProblem = result.code == 429 || result.code == 401 || result.code == 403 || result.code >= 500 ||
                (result.code == 400 && "API key" in detail)
            if (!keyProblem) {
                // Same answer from every key (bad request, unknown model): no point trying the others.
                throw GeminiException(
                    if (result.code == 404) "Gemini মডেল \"$model\" পাওয়া যায়নি। সেটিংসে সঠিক মডেলের নাম দিন।"
                    else "Gemini থেকে উত্তর আসেনি (${result.code}): $detail"
                )
            }
            lastProblem = when {
                result.code == 429 -> "ব্যবহারের সীমা শেষ"
                result.code >= 500 -> "Gemini সার্ভারে সমস্যা (${result.code})"
                else -> "key সঠিক নয় বা বন্ধ"
            }
        }
        throw GeminiException(
            if (keys.size == 1) "Gemini কাজ করছে না: $lastProblem। কিছুক্ষণ পরে চেষ্টা করুন বা সেটিংসে আরেকটি key যোগ করুন।"
            else "সবগুলো (${banglaDigits(keys.size)}টি) Gemini key চেষ্টা করা হয়েছে, কোনোটিই কাজ করেনি। শেষ সমস্যা: $lastProblem।"
        )
    }
}

private fun banglaDigits(n: Int) = n.toString().map { if (it.isDigit()) '০' + (it - '0') else it }.joinToString("")

private val sharedClient by lazy {
    OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
}

private fun okHttpPost(key: String, model: String, body: String): HttpResult {
    val request = Request.Builder()
        .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        .header("x-goog-api-key", key)
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()
    sharedClient.newCall(request).execute().use { response ->
        return HttpResult(response.code, response.body?.string().orEmpty())
    }
}

/** The assistant answered by Google Gemini, using the same tools through function calling. */
class GeminiAssistant(
    store: Store,
    alarms: AlarmScheduler,
    private val transport: GeminiTransport,
    ownerName: String,
    private val model: String = DEFAULT_GEMINI_MODEL,
    zone: () -> ZoneId = ZoneId::systemDefault,
    now: () -> Long = System::currentTimeMillis,
) : Assistant(store, alarms, ownerName, zone, now) {
    // Contents exactly as Gemini returned them, so thought signatures are sent back unchanged.
    private val transcript = mutableListOf<Any>()

    override fun clearTranscript() = transcript.clear()

    private fun request(contents: List<Any>, withTools: Boolean): JsonNode {
        val body = mutableMapOf<String, Any>(
            "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to systemPrompt))),
            "contents" to contents,
        )
        if (withTools) body["tools"] = listOf(mapOf("functionDeclarations" to GEMINI_FUNCTIONS))
        return json.readTree(transport.generate(model, json.writeValueAsString(body)))
    }

    private fun JsonNode.replyText(): String =
        path("parts").filter { it.has("text") && !it.path("thought").asBoolean(false) }
            .joinToString("") { it.path("text").asText() }
            .trim()

    override fun runTurn(userText: String, contextNote: String): String {
        if (transcript.size >= MAX_TRANSCRIPT_MESSAGES) transcript.clear()
        transcript += mapOf(
            "role" to "user",
            "parts" to listOf(mapOf("text" to "[Context]\n$contextNote"), mapOf("text" to userText)),
        )

        repeat(MAX_TOOL_ROUNDS) {
            val response = request(transcript, withTools = true)
            val candidate = response.path("candidates").firstOrNull()
                ?: return blocked().also { transcript += modelText(it) }
            val content = candidate.path("content")
            if (!content.path("parts").isArray || content.path("parts").isEmpty) {
                return blocked().also { transcript += modelText(it) }
            }
            if (content is ObjectNode && !content.has("role")) content.put("role", "model")
            transcript.add(content) // add(), not +=: a JsonNode is Iterable and += would add its children

            val calls = content.path("parts").filter { it.has("functionCall") }.map { it.path("functionCall") }
            if (calls.isEmpty()) return content.replyText().ifEmpty { "✅" }

            transcript += mapOf("role" to "user", "parts" to calls.map { call -> functionResponse(call) })
        }
        return "অনেকগুলো ধাপ হয়ে গেছে, আরেকটু সহজ করে বলবেন?"
    }

    private fun functionResponse(call: JsonNode): Map<String, Any> {
        val name = call.path("name").asText()
        @Suppress("UNCHECKED_CAST")
        val args = runCatching { json.convertValue(call.path("args"), Map::class.java) as Map<String, Any?> }.getOrNull() ?: emptyMap()
        val (output, isError) = runTool(name, args)
        val result: Any = if (isError) {
            mapOf("error" to output)
        } else {
            runCatching { json.readValue(output, Map::class.java) }.getOrNull() ?: mapOf("result" to output)
        }
        val response = mutableMapOf<String, Any>("name" to name, "response" to result)
        if (call.has("id")) response["id"] = call.path("id").asText()
        return mapOf("functionResponse" to response)
    }

    private fun blocked() = "দুঃখিত, এই অনুরোধে আমি সাহায্য করতে পারছি না।"

    private fun modelText(text: String) = mapOf("role" to "model", "parts" to listOf(mapOf("text" to text)))

    override fun complete(prompt: String): String {
        val response = request(listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to prompt)))), withTools = false)
        return response.path("candidates").firstOrNull()?.path("content")?.replyText().orEmpty()
    }
}
