package bd.aiap.core

data class Memory(
    val id: Long,
    val category: String,
    val content: String,
    val updatedAt: Long,
)

data class ListItem(
    val id: Long,
    val listName: String,
    val text: String,
    val dueDate: String?,
    val priority: String?,
    val done: Boolean,
)

data class Reminder(
    val id: Long,
    val text: String,
    val dueAtMillis: Long,
    val repeat: Repeat,
)

enum class Repeat(val key: String) {
    NONE("none"), DAILY("daily"), WEEKLY("weekly"), MONTHLY("monthly"), YEARLY("yearly");

    companion object {
        fun of(key: String): Repeat =
            entries.firstOrNull { it.key == key } ?: throw IllegalArgumentException("repeat must be one of ${entries.map { it.key }}")
    }
}

/** Money spent (or received, when [amount] is negative). [date] is a local YYYY-MM-DD. */
data class Expense(
    val id: Long,
    val amount: Double,
    val category: String,
    val note: String,
    val date: String,
)

data class Habit(val id: Long, val name: String)

/**
 * Something the assistant prepared for the owner to do on the phone (call, message, map, ...).
 * It is shown as a button under the reply and only runs when the owner taps it.
 */
data class PhoneAction(
    val type: String,
    val label: String,
    val target: String,
    val text: String?,
    val time: String?,
)

val PHONE_ACTION_TYPES = listOf("call", "sms", "whatsapp", "email", "map", "web", "calendar", "alarm")

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val createdAt: Long,
    val actions: List<PhoneAction> = emptyList(),
)

data class ChatReply(val text: String, val actions: List<PhoneAction>)

/** Everything the assistant knows about the owner. The Android app backs this with SQLite. */
interface Store {
    fun addMemory(category: String, content: String): Long
    fun searchMemories(query: String?, category: String?): List<Memory>
    fun updateMemory(id: Long, content: String): Boolean
    fun deleteMemory(id: Long): Boolean

    fun addItem(listName: String, text: String, dueDate: String?, priority: String?): Long
    fun getItems(listName: String?, includeDone: Boolean): List<ListItem>
    fun setItemDone(id: Long, done: Boolean): Boolean
    fun deleteItem(id: Long): Boolean

    fun addReminder(text: String, dueAtMillis: Long, repeat: Repeat): Long
    fun activeReminders(): List<Reminder>
    fun getReminder(id: Long): Reminder?
    fun rescheduleReminder(id: Long, dueAtMillis: Long)
    fun deactivateReminder(id: Long): Boolean

    fun addExpense(amount: Double, category: String, note: String, date: String): Long
    /** Expenses with from <= date <= to (inclusive, YYYY-MM-DD), newest first. */
    fun expenses(from: String, to: String): List<Expense>
    fun deleteExpense(id: Long): Boolean

    fun addHabit(name: String): Long
    fun habits(): List<Habit>
    fun deleteHabit(id: Long): Boolean
    /** Marks the habit done (or not) on a local date. */
    fun setHabitDone(habitId: Long, date: String, done: Boolean)
    /** Dates (YYYY-MM-DD) on or after [from] when the habit was done. */
    fun habitDoneDates(habitId: Long, from: String): Set<String>

    fun addChat(fromUser: Boolean, text: String, actions: List<PhoneAction> = emptyList()): Long
    fun recentChats(limit: Int): List<ChatMessage>
}

/** Delivers reminders at their due time (AlarmManager on Android). */
interface AlarmScheduler {
    fun schedule(reminder: Reminder)
    fun cancel(reminderId: Long)
}
