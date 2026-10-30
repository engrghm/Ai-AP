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

class GeminiHttp(private val apiKey: String) : GeminiTransport {
    private val client = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val mapper = com.fasterxml.jackson.databind.ObjectMapper()

    override fun generate(model: String, body: String): String {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.isSuccessful) return text
            val detail = runCatching { mapper.readTree(text).path("error").path("message").asText() }.getOrDefault("")
            throw GeminiException(
                when {
                    response.code == 429 -> "Gemini-এর ব্যবহারের সীমা এই মুহূর্তে শেষ। কিছুক্ষণ পরে আবার চেষ্টা করুন।"
                    "API key" in detail || response.code == 401 || response.code == 403 -> "Gemini API key সঠিক নয়। সেটিংসে ঠিক key দিন।"
                    else -> "Gemini থেকে উত্তর আসেনি (${response.code}): $detail"
                }
            )
        }
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
