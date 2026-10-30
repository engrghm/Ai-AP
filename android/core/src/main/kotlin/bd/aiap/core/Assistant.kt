package bd.aiap.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val DEFAULT_MODEL = "claude-opus-5-5"
private const val MAX_TOOL_ROUNDS = 12

// Long-term knowledge lives in the Store, so the API transcript starts fresh once it gets long
// instead of being trimmed (trimming would rewrite history).
private const val MAX_TRANSCRIPT_MESSAGES = 60
private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

private val SYSTEM_PROMPT = """
You are the personal AI assistant of {owner}, living in an Android app on their phone. You are their \
second brain and their guide: you remember everything they tell you, keep it neatly organised, keep \
their lists, remind them of things at the right time, and help them plan and stay on track.

How you work:
- Your memory is the database behind your tools, not this chat. Whenever {owner} shares anything worth \
knowing later (people, dates, plans, health, money, preferences, goals, habits, ideas), save it with \
`remember` in the best-fitting category without being asked. Search first so you update instead of \
duplicating. Split a message with several facts into several memories.
- Before answering anything about {owner}'s life, check memory and lists with the tools rather than \
guessing. If you don't know, say so and ask.
- Lists: todo, shopping, goals, books or any list they name go in `add_item`. Mark things done when \
they say so.
- Reminders: when they ask to be reminded, or mention something time-bound (a bill, a meeting, medicine, \
a birthday), set a reminder with `set_reminder`. Resolve relative times ("kal sokale", "in 2 hours") \
against the current local time you are given. If the time is ambiguous, pick a sensible one and say so.
- Guidance: be a proactive, practical coach. Point out what is due or overdue, what matters most today, \
and suggest a next step. Keep it short and concrete.
- Never store passwords, PINs or full card/account numbers; suggest a password manager instead.
- Reply in the language {owner} writes in (Bangla by default). Messages are read on a phone: keep them \
brief and friendly, use short lists, no tables. After saving or scheduling, confirm briefly what you did.
""".trimIndent().replace("\\\n", "")

private val BRIEFING_PROMPT = """
Write {owner}'s briefing for today. Using the data below, give:
1. A one-line greeting.
2. Today's reminders and anything due today or overdue.
3. The top 3 things to focus on today, with a short reason each.
4. One short piece of encouragement or advice tied to their goals.
Keep it under 120 words, in Bangla, as a phone notification-style message. Data:

""".trimIndent()

/** Sends one request to Claude; swapped out in tests. */
fun interface MessageSender {
    fun send(params: MessageCreateParams): BetaMessage
}

class ClaudeSender(private val client: AnthropicClient) : MessageSender {
    constructor(apiKey: String) : this(AnthropicOkHttpClient.builder().apiKey(apiKey).build())

    override fun send(params: MessageCreateParams): BetaMessage = client.beta().messages().create(params)
}

class Assistant(
    private val store: Store,
    alarms: AlarmScheduler,
    private val sender: MessageSender,
    private val ownerName: String,
    private val model: String = DEFAULT_MODEL,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val tools = ToolExecutor(store, alarms, zone, now)
    private val transcript = mutableListOf<BetaMessageParam>()
    private val json = ObjectMapper()
    private val system = listOf(
        BetaTextBlockParam.builder()
            .text(SYSTEM_PROMPT.replace("{owner}", ownerName))
            .cacheControl(BetaCacheControlEphemeral.builder().build())
            .build()
    )

    private fun request(messages: List<BetaMessageParam>, effort: BetaOutputConfig.Effort, withTools: Boolean): BetaMessage {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .systemOfBetaTextBlockParams(system)
            .messages(messages)
            .outputConfig(BetaOutputConfig.builder().effort(effort).build())
            .addBeta(FALLBACK_BETA)
            .fallbacks(BetaFallbacksParam.ofDefault())
        if (withTools) TOOL_DEFINITIONS.forEach { builder.addTool(it) }
        return sender.send(builder.build())
    }

    private fun BetaMessage.text(): String =
        content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("\n").trim()

    /** Per-turn context: current time plus what is due today, so guidance stays timely. */
    private fun contextNote(): String {
        val nowLocal = Instant.ofEpochMilli(now()).atZone(zone())
        val today = nowLocal.toLocalDate().toString()
        val due = store.getItems(null, includeDone = false).filter { it.dueDate != null && it.dueDate <= today }
        val endOfDay = LocalDate.parse(today).plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()
        val reminders = store.activeReminders().filter { it.dueAtMillis < endOfDay }
        return buildString {
            append("Current local time: ")
            append(nowLocal.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm, EEEE")))
            append(" (").append(zone().id).append(").")
            if (due.isNotEmpty()) {
                append("\nList items due today or overdue: ")
                append(json.writeValueAsString(due.map { mapOf("id" to it.id, "list" to it.listName, "text" to it.text, "due" to it.dueDate) }))
            }
            if (reminders.isNotEmpty()) {
                append("\nReminders still to come today: ")
                append(json.writeValueAsString(reminders.map { mapOf("id" to it.id, "text" to it.text, "when" to tools.formatLocal(it.dueAtMillis)) }))
            }
        }
    }

    /** One conversation turn. Blocking: call it off the main thread. */
    @Synchronized
    fun chat(userText: String): String {
        store.addChat(fromUser = true, text = userText)
        if (transcript.size >= MAX_TRANSCRIPT_MESSAGES) transcript.clear()
        transcript += BetaMessageParam.ofUser(userText)
        transcript += BetaMessageParam.ofSystem(contextNote())

        val reply = try {
            runToolLoop()
        } catch (e: Exception) {
            // A failed turn may leave a dangling tool call; start a clean transcript next time.
            transcript.clear()
            throw e
        }
        store.addChat(fromUser = false, text = reply)
        return reply
    }

    private fun runToolLoop(): String {
        repeat(MAX_TOOL_ROUNDS) {
            val response = request(transcript, BetaOutputConfig.Effort.MEDIUM, withTools = true)
            val stop = response.stopReason().orElse(null)

            if (stop == BetaStopReason.REFUSAL) {
                val reply = "দুঃখিত, এই অনুরোধে আমি সাহায্য করতে পারছি না।"
                transcript += BetaMessageParam.ofAssistant(reply)
                return reply
            }
            transcript += response.toParam()
            if (stop != BetaStopReason.TOOL_USE) return response.text().ifEmpty { "✅" }

            val results = response.content().mapNotNull { it.toolUse().orElse(null) }.map { call ->
                val result = BetaToolResultBlockParam.builder().toolUseId(call.id())
                try {
                    val input = call._input().convert(Map::class.java) ?: emptyMap<String, Any>()
                    result.content(tools.run(call.name(), input))
                } catch (e: Exception) {
                    result.content(e.message ?: e.toString()).isError(true)
                }
                BetaContentBlockParam.ofToolResult(result.build())
            }
            transcript += BetaMessageParam.ofUserOfContentBlockParams(results)
        }
        return "অনেকগুলো ধাপ হয়ে গেছে, আরেকটু সহজ করে বলবেন?"
    }

    /** A fresh conversation; saved memories, lists and reminders are untouched. */
    @Synchronized
    fun reset() = transcript.clear()

    /** Today's plan and guidance, generated from everything saved. */
    fun briefing(): String {
        val data = mapOf(
            "now" to tools.formatLocal(now()),
            "open_list_items" to store.getItems(null, includeDone = false).map { mapOf("list" to it.listName, "text" to it.text, "due" to it.dueDate, "priority" to it.priority) },
            "active_reminders" to store.activeReminders().map { mapOf("text" to it.text, "when" to tools.formatLocal(it.dueAtMillis), "repeat" to it.repeat.key) },
            "goals_and_profile" to (store.searchMemories(null, "goals") + store.searchMemories(null, "profile")).map { it.content },
        )
        val prompt = BRIEFING_PROMPT.replace("{owner}", ownerName) + json.writerWithDefaultPrettyPrinter().writeValueAsString(data)
        val response = request(listOf(BetaMessageParam.ofUser(prompt)), BetaOutputConfig.Effort.LOW, withTools = false)
        if (response.stopReason().orElse(null) == BetaStopReason.REFUSAL) return "আজকের ব্রিফিং তৈরি করা যায়নি।"
        return response.text()
    }
}
