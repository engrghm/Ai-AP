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
    override fun addChat(fromUser: Boolean, text: String) = nextId++.also { chats += ChatMessage(it, fromUser, text, 0) }
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
                {"type":"tool_use","id":"t2","name":"set_reminder","input":{"text":"ওষুধ খান","when":"2026-10-30T22:00","repeat":"daily"}}
            ]"""),
            message("end_turn", """[{"type":"text","text":"মনে রাখলাম ✅"}]"""),
        ))
        val assistant = Assistant(store, alarms, { requests += it; replies.removeFirst() }, "Masum", zone = { DHAKA }, now = { NOW })

        assertEquals("মনে রাখলাম ✅", assistant.chat("হ্যালো"))
        assertEquals("family", store.memories.single().category)
        assertEquals(LocalDateTime.of(2026, 10, 30, 22, 0).atZone(DHAKA).toInstant().toEpochMilli(), alarms.scheduled.single().dueAtMillis)
        assertEquals(listOf(true, false), store.chats.map { it.fromUser })

        val body = jsonMapper().writeValueAsString(requests[1]._body())
        assertTrue("\"role\":\"system\"" in body, body)
        assertTrue("\"tool_use_id\":\"t2\"" in body)
        assertTrue("\"fallbacks\":\"default\"" in body)
        assertTrue("\"strict\":true" in body)
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
}
