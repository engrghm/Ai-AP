package bd.aiap.core

import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaTool
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Categories the UI knows how to show nicely; Claude may still invent others. */
val MEMORY_CATEGORIES = linkedMapOf(
    "profile" to "ব্যক্তিগত তথ্য",
    "family" to "পরিবার",
    "work" to "কাজ",
    "health" to "স্বাস্থ্য",
    "finance" to "টাকা-পয়সা",
    "goals" to "লক্ষ্য",
    "contacts" to "যোগাযোগ",
    "ideas" to "আইডিয়া",
    "preferences" to "পছন্দ-অপছন্দ",
    "notes" to "নোট",
)

private val STR = mapOf("type" to "string")
private val OPT_STR = mapOf("type" to listOf("string", "null"))
private val INT = mapOf("type" to "integer")

private fun described(base: Map<String, Any>, description: String) = base + ("description" to description)

private fun tool(name: String, description: String, properties: Map<String, Map<String, Any>>): BetaTool {
    val props = BetaTool.InputSchema.Properties.builder()
    properties.forEach { (key, schema) -> props.putAdditionalProperty(key, JsonValue.from(schema)) }
    return BetaTool.builder()
        .name(name)
        .description(description)
        .strict(true)
        .inputSchema(
            BetaTool.InputSchema.builder()
                .properties(props.build())
                .required(properties.keys.toList())
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build()
        )
        .build()
}

val TOOL_DEFINITIONS: List<BetaTool> = listOf(
    tool(
        "remember",
        "Save a durable fact about the owner to long-term memory. Use whenever the owner shares something " +
            "worth knowing later, even if they did not say 'remember'. Search first and use update_memory " +
            "instead if a matching fact already exists. Never store passwords, PINs or full card/account numbers.",
        mapOf(
            "category" to described(STR, "One of: ${MEMORY_CATEGORIES.keys.joinToString()} (or another short lowercase word if none fits)"),
            "content" to described(STR, "The fact as a clear standalone sentence, in the owner's language"),
        ),
    ),
    tool(
        "search_memory",
        "Search long-term memory. Pass null for both to list everything. Words in query are AND-matched.",
        mapOf("query" to OPT_STR, "category" to OPT_STR),
    ),
    tool("update_memory", "Replace the content of an existing memory by id.", mapOf("memory_id" to INT, "content" to STR)),
    tool("forget_memory", "Delete a memory by id (only when the owner asks or it is clearly wrong).", mapOf("memory_id" to INT)),
    tool(
        "add_item",
        "Add an item to one of the owner's lists (todo, shopping, goals, books, ideas, or any list name they use).",
        mapOf(
            "list_name" to described(STR, "Short lowercase list name, e.g. todo, shopping, books"),
            "text" to STR,
            "due_date" to described(OPT_STR, "Optional local date YYYY-MM-DD"),
            "priority" to described(OPT_STR, "high, medium, low or null"),
        ),
    ),
    tool(
        "get_items",
        "Get items from the owner's lists. list_name null means all lists.",
        mapOf("list_name" to OPT_STR, "include_done" to mapOf("type" to "boolean")),
    ),
    tool("complete_item", "Mark a list item as done.", mapOf("item_id" to INT)),
    tool("delete_item", "Delete a list item entirely.", mapOf("item_id" to INT)),
    tool(
        "set_reminder",
        "Schedule a reminder notification on the owner's phone. Write the text as the message they should " +
            "receive, in their language.",
        mapOf(
            "text" to STR,
            "when" to described(STR, "Local date-time YYYY-MM-DDTHH:MM"),
            "repeat" to mapOf("type" to "string", "enum" to Repeat.entries.map { it.key }),
        ),
    ),
    tool("get_reminders", "List all active reminders.", emptyMap()),
    tool("cancel_reminder", "Cancel an active reminder by id.", mapOf("reminder_id" to INT)),
)

class ToolExecutor(
    private val store: Store,
    private val alarms: AlarmScheduler,
    private val zone: () -> ZoneId,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = ObjectMapper()
    private val display = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEE)")

    fun formatLocal(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone()).format(display)

    /** Runs a tool and returns its JSON result; throws on bad input so the caller can report an error. */
    fun run(name: String, input: Map<*, *>): String {
        fun str(key: String): String = input[key] as? String ?: throw IllegalArgumentException("$key is required")
        fun optStr(key: String): String? = (input[key] as? String)?.takeIf { it.isNotBlank() }
        fun long(key: String): Long = (input[key] as? Number)?.toLong() ?: throw IllegalArgumentException("$key must be an integer")

        val result: Any = when (name) {
            "remember" -> mapOf("saved_memory_id" to store.addMemory(str("category").trim().lowercase(), str("content")))
            "search_memory" -> {
                val matches = store.searchMemories(optStr("query"), optStr("category")?.lowercase())
                if (matches.isEmpty()) {
                    mapOf("matches" to emptyList<Any>(), "categories_in_use" to store.searchMemories(null, null).map { it.category }.distinct())
                } else {
                    mapOf("matches" to matches.map { mapOf("id" to it.id, "category" to it.category, "content" to it.content) })
                }
            }
            "update_memory" -> mapOf("updated" to store.updateMemory(long("memory_id"), str("content")))
            "forget_memory" -> mapOf("deleted" to store.deleteMemory(long("memory_id")))
            "add_item" -> {
                val due = optStr("due_date")?.also { LocalDate.parse(it) }
                val priority = optStr("priority")?.lowercase()?.takeIf { it in setOf("high", "medium", "low") }
                mapOf("added_item_id" to store.addItem(str("list_name").trim().lowercase(), str("text"), due, priority))
            }
            "get_items" -> mapOf(
                "items" to store.getItems(optStr("list_name")?.lowercase(), input["include_done"] == true).map {
                    mapOf("id" to it.id, "list" to it.listName, "text" to it.text, "due_date" to it.dueDate, "priority" to it.priority, "done" to it.done)
                }
            )
            "complete_item" -> mapOf("completed" to store.setItemDone(long("item_id"), true))
            "delete_item" -> mapOf("deleted" to store.deleteItem(long("item_id")))
            "set_reminder" -> setReminder(str("text"), str("when"), Repeat.of(str("repeat")))
            "get_reminders" -> mapOf(
                "reminders" to store.activeReminders().map {
                    mapOf("id" to it.id, "text" to it.text, "when" to formatLocal(it.dueAtMillis), "repeat" to it.repeat.key)
                }
            )
            "cancel_reminder" -> {
                val id = long("reminder_id")
                alarms.cancel(id)
                mapOf("cancelled" to store.deactivateReminder(id))
            }
            else -> throw IllegalArgumentException("Unknown tool: $name")
        }
        return json.writeValueAsString(result)
    }

    private fun setReminder(text: String, `when`: String, repeat: Repeat): Map<String, Any> {
        var due = LocalDateTime.parse(`when`.take(16)).atZone(zone()).toInstant().toEpochMilli()
        if (due <= now()) {
            require(repeat != Repeat.NONE) { "That time is already in the past. Ask the owner for a future time." }
            due = nextFutureOccurrence(due, repeat, zone(), now())
        }
        val id = store.addReminder(text, due, repeat)
        alarms.schedule(Reminder(id, text, due, repeat))
        return mapOf("reminder_id" to id, "scheduled_for" to formatLocal(due), "repeat" to repeat.key)
    }
}
