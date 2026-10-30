package bd.aiap.core

import com.anthropic.core.jsonMapper
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.MessageCreateParams
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val DHAKA = ZoneId.of("Asia/Dhaka")
private val NOW = LocalDateTime.of(2026, 10, 30, 18, 0).atZone(DHAKA).toInstant().toEpochMilli()

class InMemoryStore : Store {
    private var nextId = 1L
    val memories = mutableListOf<Memory>()
    val items = mutableListOf<ListItem>()
    val reminders = mutableListOf<Reminder>()
    val chats = mutableListOf<ChatMessage>()

    override fun addMemory(category: String, content: String) = nextId++.also { memories += Memory(it, category, content, 0) }
    override fun searchMemories(query: String?, category: String?) = memories.filter { m ->
        (category == null || m.category == category) &&
            (query == null || query.split(" ").all { m.content.contains(it, ignoreCase = true) })
    }
    override fun updateMemory(id: Long, content: String) = memories.replaceAll { if (it.id == id) it.copy(content = content) else it }.let { true }
    override fun deleteMemory(id: Long) = memories.removeIf { it.id == id }
    override fun addItem(listName: String, text: String, dueDate: String?, priority: String?) =
        nextId++.also { items += ListItem(it, listName, text, dueDate, priority, false) }
    override fun getItems(listName: String?, includeDone: Boolean) =
        items.filter { (listName == null || it.listName == listName) && (includeDone || !it.done) }
    override fun setItemDone(id: Long, done: Boolean) = items.replaceAll { if (it.id == id) it.copy(done = done) else it }.let { true }
    override fun deleteItem(id: Long) = items.removeIf { it.id == id }
    override fun addReminder(text: String, dueAtMillis: Long, repeat: Repeat) = nextId++.also { reminders += Reminder(it, text, dueAtMillis, repeat) }
    override fun activeReminders() = reminders.sortedBy { it.dueAtMillis }
    override fun getReminder(id: Long) = reminders.firstOrNull { it.id == id }
    override fun rescheduleReminder(id: Long, dueAtMillis: Long) = reminders.replaceAll { if (it.id == id) it.copy(dueAtMillis = dueAtMillis) else it }
    override fun deactivateReminder(id: Long) = reminders.removeIf { it.id == id }
    val expenseList = mutableListOf<Expense>()
    val habitList = mutableListOf<Habit>()
    val habitDone = mutableSetOf<Pair<Long, String>>()
    override fun addExpense(amount: Double, category: String, note: String, date: String) =
        nextId++.also { expenseList += Expense(it, amount, category, note, date) }
    override fun expenses(from: String, to: String) = expenseList.filter { it.date in from..to }.sortedByDescending { it.date }
    override fun deleteExpense(id: Long) = expenseList.removeIf { it.id == id }
    override fun addHabit(name: String) = nextId++.also { habitList += Habit(it, name) }
    override fun habits() = habitList.toList()
    override fun deleteHabit(id: Long) = habitList.removeIf { it.id == id }
    override fun setHabitDone(habitId: Long, date: String, done: Boolean) {
        if (done) habitDone += habitId to date else habitDone -= habitId to date
    }
    override fun habitDoneDates(habitId: Long, from: String) =
        habitDone.filter { it.first == habitId && it.second >= from }.map { it.second }.toSet()
    override fun addChat(fromUser: Boolean, text: String, actions: List<PhoneAction>) =
        nextId++.also { chats += ChatMessage(it, fromUser, text, 0, actions) }
    override fun recentChats(limit: Int) = chats.takeLast(limit)
}

class RecordingAlarms : AlarmScheduler {
    val scheduled = mutableListOf<Reminder>()
    override fun schedule(reminder: Reminder) { scheduled += reminder }
    override fun cancel(reminderId: Long) { scheduled.removeIf { it.id == reminderId } }
}

private fun message(stopReason: String, contentJson: String): BetaMessage = jsonMapper().readValue(
    """{"id":"msg_1","type":"message","role":"assistant","model":"$DEFAULT_MODEL","content":$contentJson,
       "stop_reason":"$stopReason","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}""",
    BetaMessage::class.java,
)

class AssistantTest {
    @Test
    fun `tool loop saves memory, schedules reminder and replies`() {
        val store = InMemoryStore()
        val alarms = RecordingAlarms()
        val requests = mutableListOf<MessageCreateParams>()
        val replies = ArrayDeque(listOf(
            message("tool_use", """[
                {"type":"tool_use","id":"t1","name":"remember","input":{"category":"Family","content":"স্ত্রীর জন্মদিন ১২ মার্চ"}},
                {"type":"tool_use","id":"t2","name":"set_reminder","input":{"text":"ওষুধ খান","when":"2026-10-30T22:00","repeat":"daily"}},
                {"type":"tool_use","id":"t3","name":"prepare_phone_action","input":{"type":"call","label":"আম্মুকে কল","target":"01700000000","text":null,"time":null}}
            ]"""),
            message("end_turn", """[{"type":"text","text":"মনে রাখলাম ✅"}]"""),
        ))
        val assistant = ClaudeAssistant(store, alarms, { requests += it; replies.removeFirst() }, "Masum", zone = { DHAKA }, now = { NOW })

        val reply = assistant.chat("হ্যালো")
        assertEquals("মনে রাখলাম ✅", reply.text)
        assertEquals("01700000000", reply.actions.single().target)
        assertEquals(reply.actions, store.chats.last().actions)
        assertEquals(reply.actions, ActionsJson.decode(ActionsJson.encode(reply.actions)))
        assertEquals("family", store.memories.single().category)
        assertEquals(LocalDateTime.of(2026, 10, 30, 22, 0).atZone(DHAKA).toInstant().toEpochMilli(), alarms.scheduled.single().dueAtMillis)
        assertEquals(listOf(true, false), store.chats.map { it.fromUser })

        val body = jsonMapper().writeValueAsString(requests[1]._body())
        assertTrue("\"role\":\"system\"" in body, body)
        assertTrue("\"tool_use_id\":\"t2\"" in body)
        assertTrue("\"fallbacks\":\"default\"" in body)
        assertTrue("\"strict\":true" in body)
        assertTrue("web_search_20260209" in body)
    }

    @Test
    fun `tool errors are reported back instead of crashing`() {
        val tools = ToolExecutor(InMemoryStore(), RecordingAlarms(), { DHAKA }, { NOW })
        val error = runCatching { tools.run("set_reminder", mapOf("text" to "x", "when" to "2026-10-29T09:00", "repeat" to "none")) }
        assertTrue(error.isFailure)
    }

    @Test
    fun `monthly reminder keeps wall clock and clamps day`() {
        val jan31 = LocalDateTime.of(2026, 1, 31, 9, 0).atZone(DHAKA).toInstant().toEpochMilli()
        val next = java.time.Instant.ofEpochMilli(nextOccurrence(jan31, Repeat.MONTHLY, DHAKA)).atZone(DHAKA)
        assertEquals(Triple(2, 28, 9), Triple(next.monthValue, next.dayOfMonth, next.hour))
    }

    @Test
    fun `expenses habits and backup round trip`() {
        val store = InMemoryStore()
        val alarms = RecordingAlarms()
        val tools = ToolExecutor(store, alarms, { DHAKA }, { NOW })
        tools.run("add_expense", mapOf("amount" to 500, "category" to "food", "note" to "বাজার", "date" to null))
        tools.run("add_expense", mapOf("amount" to -40000, "category" to "other", "note" to "বেতন", "date" to "2026-10-01"))
        val summary = tools.expenseSummary("2026-10-01", "2026-10-30")
        assertEquals(500.0, summary["total_spent"])
        assertEquals(40000.0, summary["total_received"])
        assertEquals("income", store.expenseList.last().category)

        tools.run("add_habit", mapOf("name" to "হাঁটা", "reminder_time" to "06:30"))
        val habitId = store.habitList.single().id
        assertEquals(Repeat.DAILY, alarms.scheduled.single().repeat)
        tools.run("log_habit", mapOf("habit_id" to habitId, "date" to "2026-10-29", "done" to true))
        tools.run("log_habit", mapOf("habit_id" to habitId, "date" to null, "done" to true))
        assertEquals(2, tools.habitStatus().single()["streak"])

        val restored = InMemoryStore()
        val count = Backup.import(Backup.export(store), restored, RecordingAlarms())
        assertEquals(4, count) // 2 expenses, 1 habit, 1 habit reminder
        assertEquals(setOf("2026-10-29", "2026-10-30"), restored.habitDoneDates(restored.habitList.single().id, "2000-01-01"))
    }

    @Test
    fun `yearly reminder and streak helpers`() {
        val bday = LocalDateTime.of(2026, 3, 12, 9, 0).atZone(DHAKA).toInstant().toEpochMilli()
        val next = java.time.Instant.ofEpochMilli(nextFutureOccurrence(bday, Repeat.YEARLY, DHAKA, NOW)).atZone(DHAKA)
        assertEquals(Triple(2027, 3, 12), Triple(next.year, next.monthValue, next.dayOfMonth))
        val today = java.time.LocalDate.of(2026, 10, 30)
        assertEquals(2, streak(setOf("2026-10-28", "2026-10-29"), today))
        assertEquals(0, streak(setOf("2026-10-27"), today))
    }

    @Test
    fun `gemini runs function calls and echoes model content back`() {
        val store = InMemoryStore()
        val bodies = mutableListOf<String>()
        val replies = ArrayDeque(listOf(
            """{"candidates":[{"content":{"role":"model","parts":[
                {"functionCall":{"name":"add_item","args":{"list_name":"shopping","text":"দুধ"}},"thoughtSignature":"sig1"},
                {"functionCall":{"name":"add_expense","args":{"amount":850,"category":"food","note":"বাজার"}}}
            ]},"finishReason":"STOP"}]}""",
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"✅ শপিং লিস্টে দুধ, খরচ ৮৫০ টাকা"}]},"finishReason":"STOP"}]}""",
        ))
        val assistant = GeminiAssistant(store, RecordingAlarms(), { _, body -> bodies += body; replies.removeFirst() }, "Masum", zone = { DHAKA }, now = { NOW })

        val reply = assistant.chat("দুধ কিনতে হবে, আজ বাজারে ৮৫০ টাকা খরচ")
        assertEquals("✅ শপিং লিস্টে দুধ, খরচ ৮৫০ টাকা", reply.text)
        assertEquals("দুধ", store.items.single().text)
        assertEquals(850.0, store.expenseList.single().amount)

        val first = jsonMapper().readTree(bodies[0])
        val decls = first.path("tools")[0].path("functionDeclarations")
        val setReminder = decls.first { it.path("name").asText() == "set_reminder" }.path("parameters")
        assertEquals("STRING", setReminder.path("properties").path("when").path("type").asText())
        val addItem = decls.first { it.path("name").asText() == "add_item" }.path("parameters")
        assertTrue(addItem.path("properties").path("due_date").path("nullable").asBoolean())
        assertEquals(listOf("list_name", "text"), addItem.path("required").map { it.asText() })

        val second = jsonMapper().readTree(bodies[1]).path("contents")
        assertEquals(listOf("user", "model", "user"), second.map { it.path("role").asText() })
        assertEquals("sig1", second[1].path("parts")[0].path("thoughtSignature").asText())
        assertEquals("add_expense", second[2].path("parts")[1].path("functionResponse").path("name").asText())
    }

    @Test
    fun `gemini switches to the next key when one fails`() {
        val tried = mutableListOf<String>()
        val status = mutableMapOf("k1" to 429, "k2" to 200, "k3" to 200)
        val http = GeminiHttp(listOf("k1", " k2 ", "", "k3", "k1")) { key, _, _ ->
            tried += key
            HttpResult(status.getValue(key), if (status[key] == 200) "ok-$key" else """{"error":{"message":"quota"}}""")
        }
        assertEquals(3, http.keyCount) // blanks and duplicates dropped
        assertEquals("ok-k2", http.generate("m", "{}"))
        assertEquals(listOf("k1", "k2"), tried)

        // The working key is tried first next time.
        tried.clear()
        assertEquals("ok-k2", http.generate("m", "{}"))
        assertEquals(listOf("k2"), tried)

        // Network errors and server errors also move on; it wraps around to the start.
        status["k2"] = 503
        tried.clear()
        status["k3"] = 403
        status["k1"] = 200
        assertEquals("ok-k1", http.generate("m", "{}"))
        assertEquals(listOf("k2", "k3", "k1"), tried)

        // Every key failing gives one clear message.
        status["k1"] = 429
        val all = runCatching { http.generate("m", "{}") }.exceptionOrNull()
        assertTrue(all is GeminiException && "৩টি" in all.message!!, all.toString())

        // A wrong model name fails the same on every key, so it is not retried.
        tried.clear()
        status["k1"] = 404
        val notFound = runCatching { http.generate("bad-model", "{}") }.exceptionOrNull()
        assertTrue(notFound is GeminiException && "bad-model" in notFound.message!!)
        assertEquals(1, tried.size)

        val offline = GeminiHttp(listOf("a", "b")) { key, _, _ -> if (key == "a") throw java.io.IOException("timeout") else HttpResult(200, "ok") }
        assertEquals("ok", offline.generate("m", "{}"))
    }
}
