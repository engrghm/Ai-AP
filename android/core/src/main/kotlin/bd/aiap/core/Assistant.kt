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
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val DEFAULT_MODEL = "claude-opus-5-5"
const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"
internal const val MAX_TOOL_ROUNDS = 12

// Long-term knowledge lives in the Store, so the API transcript starts fresh once it gets long
// instead of being trimmed (trimming would rewrite history).
internal const val MAX_TRANSCRIPT_MESSAGES = 60
private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

private val SYSTEM_PROMPT = """
You are the personal AI assistant of {owner}, living in an Android app on their phone. You are their \
second brain and life manager: they talk to you (often by voice, in Bangla or Banglish), and you quietly \
capture and organise everything, remind them at the right time, track their money and habits, and help \
them plan so their whole life gets easier.

Capture everything, automatically:
- Treat every message as something to file. Never ask "should I save this?" - just save it and say \
briefly where it went. One message often holds several things; handle each one.
- Facts about their life (people, birthdays, addresses, phone numbers, health, preferences, plans, \
ideas, decisions) go to `remember` in the best-fitting category. Search first and update instead of \
duplicating.
- Anything they need or want to do goes to a list with `add_item` (todo by default; shopping items to \
shopping; books, goals, ideas to their own lists), with a due date when one is mentioned.
- Anything with a time goes to `set_reminder`. Also add sensible reminders they did not ask for when \
clearly useful: a day-before reminder for bills and appointments, yearly reminders for birthdays and \
anniversaries. Resolve relative times against the current local time you are given; if a time is \
vague ("bikele", "sokale"), pick a sensible one and say so.
- Money spent or received goes to `add_expense` (taka). Mention the running monthly total when useful.
- Recurring personal routines (prayer, exercise, reading, medicine, water) are habits: `add_habit`, and \
`log_habit` when they say they did one.
- When they want to call, message, email, find a place, search, or schedule something on the phone, \
use `prepare_phone_action` so a tap-to-run button appears. Find numbers/emails in memory first.
- Use web search, when available, for current information (prices, news, prayer times, weather, how-to).

Answering and guiding:
- Before answering anything about {owner}'s life, check your tools rather than guessing.
- Be a proactive, practical coach: notice what is due, overdue, or slipping (habits, budget), and \
suggest a concrete next step. Never preachy.
- Never store passwords, PINs or full card/account numbers; suggest a password manager instead.
- Reply in the language {owner} uses (Bangla by default). Replies are read on a phone and may be read \
aloud: short, warm, plain sentences, short lists, no tables or markdown headings. End with a brief \
summary of what you saved or scheduled (e.g. "✅ লিস্টে যোগ: ... ⏰ রিমাইন্ডার: ...").
""".trimIndent().replace("\\\n", "")

private val BRIEFING_PROMPTS = mapOf(
    "morning" to """
        Write {owner}'s morning briefing for today. Using the data below, give:
        1. A one-line greeting.
        2. Today's reminders and anything due today or overdue.
        3. The top 3 things to focus on today, with a short reason each.
        4. Habits to do today, and one short piece of encouragement tied to their goals.
        Keep it under 120 words, in Bangla, as a phone notification-style message. Data:
    """.trimIndent(),
    "evening" to """
        Write {owner}'s short evening review. Using the data below, give:
        1. What got done today (lists, habits) - praise briefly.
        2. What is still pending, and what is coming up tomorrow.
        3. Today's spending total and anything notable about this month's budget.
        4. One gentle suggestion for tomorrow.
        Keep it under 120 words, in Bangla, as a phone notification-style message. Data:
    """.trimIndent(),
)

/** Sends one request to Claude; swapped out in tests. */
fun interface MessageSender {
    fun send(params: MessageCreateParams): BetaMessage
}

class ClaudeSender(private val client: AnthropicClient) : MessageSender {
    constructor(apiKey: String) : this(AnthropicOkHttpClient.builder().apiKey(apiKey).build())

    override fun send(params: MessageCreateParams): BetaMessage = client.beta().messages().create(params)
}

/**
 * The assistant, independent of which AI service answers: it keeps the chat log, the tools and the
 * per-turn context. [ClaudeAssistant] and [GeminiAssistant] supply the conversation with the model.
 */
abstract class Assistant(
    protected val store: Store,
    alarms: AlarmScheduler,
    protected val ownerName: String,
    protected val zone: () -> ZoneId,
    protected val now: () -> Long,
) {
    protected val tools = ToolExecutor(store, alarms, zone, now)
    protected val json = ObjectMapper()
    protected val systemPrompt = SYSTEM_PROMPT.replace("{owner}", ownerName)

    /** Runs one turn (with tool calls) and returns the reply text. */
    protected abstract fun runTurn(userText: String, contextNote: String): String

    /** A single prompt without tools, for briefings. */
    protected abstract fun complete(prompt: String): String

    /** Forgets the model-side transcript. */
    protected abstract fun clearTranscript()

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
    fun chat(userText: String): ChatReply {
        store.addChat(fromUser = true, text = userText)
        tools.takeActions() // drop anything left over from a failed turn
        val reply = try {
            runTurn(userText, contextNote())
        } catch (e: Exception) {
            // A failed turn may leave a dangling tool call; start a clean transcript next time.
            clearTranscript()
            throw e
        }
        val actions = tools.takeActions()
        store.addChat(fromUser = false, text = reply, actions = actions)
        return ChatReply(reply, actions)
    }

    /** A fresh conversation; saved memories, lists and reminders are untouched. */
    @Synchronized
    fun reset() = clearTranscript()

    /** A morning plan or evening review ([kind] = "morning" / "evening"), generated from everything saved. */
    fun briefing(kind: String = "morning"): String {
        val today = tools.today()
        val data = mapOf(
            "now" to tools.formatLocal(now()),
            "open_list_items" to store.getItems(null, includeDone = false).map { mapOf("list" to it.listName, "text" to it.text, "due" to it.dueDate, "priority" to it.priority) },
            "active_reminders" to store.activeReminders().take(30).map { mapOf("text" to it.text, "when" to tools.formatLocal(it.dueAtMillis), "repeat" to it.repeat.key) },
            "habits" to tools.habitStatus(),
            "spent_today" to tools.expenseSummary(today.toString(), today.toString())["total_spent"],
            "month_spending" to tools.expenseSummary(today.withDayOfMonth(1).toString(), today.toString()).filterKeys { it != "items" },
            "goals_and_profile" to (store.searchMemories(null, "goals") + store.searchMemories(null, "profile")).map { it.content },
        )
        val prompt = BRIEFING_PROMPTS.getValue(kind).replace("{owner}", ownerName) + "\n\n" +
            json.writerWithDefaultPrettyPrinter().writeValueAsString(data)
        return complete(prompt)
    }

    /** Runs a tool call, turning failures into an error message for the model. */
    protected fun runTool(name: String, input: Map<*, *>): Pair<String, Boolean> = try {
        tools.run(name, input) to false
    } catch (e: Exception) {
        (e.message ?: e.toString()) to true
    }
}

class ClaudeAssistant(
    store: Store,
    alarms: AlarmScheduler,
    private val sender: MessageSender,
    ownerName: String,
    private val model: String = DEFAULT_MODEL,
    zone: () -> ZoneId = ZoneId::systemDefault,
    now: () -> Long = System::currentTimeMillis,
) : Assistant(store, alarms, ownerName, zone, now) {
    private val transcript = mutableListOf<BetaMessageParam>()
    private val system = listOf(
        BetaTextBlockParam.builder()
            .text(systemPrompt)
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
        if (withTools) {
            TOOL_DEFINITIONS.forEach { builder.addTool(it) }
            builder.addTool(BetaWebSearchTool20260209.builder().maxUses(3L).build())
        }
        return sender.send(builder.build())
    }

    private fun BetaMessage.text(): String =
        content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("\n").trim()

    override fun clearTranscript() = transcript.clear()

    override fun runTurn(userText: String, contextNote: String): String {
        if (transcript.size >= MAX_TRANSCRIPT_MESSAGES) transcript.clear()
        transcript += BetaMessageParam.ofUser(userText)
        transcript += BetaMessageParam.ofSystem(contextNote)

        repeat(MAX_TOOL_ROUNDS) {
            val response = request(transcript, BetaOutputConfig.Effort.MEDIUM, withTools = true)
            val stop = response.stopReason().orElse(null)

            if (stop == BetaStopReason.REFUSAL) {
                val reply = "দুঃখিত, এই অনুরোধে আমি সাহায্য করতে পারছি না।"
                transcript += BetaMessageParam.ofAssistant(reply)
                return reply
            }
            transcript += response.toParam()
            // A long server-side web search can pause; sending the turn back lets it continue.
            if (stop == BetaStopReason.PAUSE_TURN) return@repeat
            if (stop != BetaStopReason.TOOL_USE) return response.text().ifEmpty { "✅" }

            val results = response.content().mapNotNull { it.toolUse().orElse(null) }.map { call ->
                val input = runCatching { call._input().convert(Map::class.java) }.getOrNull() ?: emptyMap<String, Any>()
                val (output, isError) = runTool(call.name(), input)
                BetaContentBlockParam.ofToolResult(
                    BetaToolResultBlockParam.builder().toolUseId(call.id()).content(output).isError(isError).build()
                )
            }
            transcript += BetaMessageParam.ofUserOfContentBlockParams(results)
        }
        return "অনেকগুলো ধাপ হয়ে গেছে, আরেকটু সহজ করে বলবেন?"
    }

    override fun complete(prompt: String): String {
        val response = request(listOf(BetaMessageParam.ofUser(prompt)), BetaOutputConfig.Effort.LOW, withTools = false)
        if (response.stopReason().orElse(null) == BetaStopReason.REFUSAL) return ""
        return response.text()
    }
}
