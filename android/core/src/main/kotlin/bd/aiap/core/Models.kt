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
    NONE("none"), DAILY("daily"), WEEKLY("weekly"), MONTHLY("monthly");

    companion object {
        fun of(key: String): Repeat =
            entries.firstOrNull { it.key == key } ?: throw IllegalArgumentException("repeat must be one of ${entries.map { it.key }}")
    }
}

data class ChatMessage(val id: Long, val fromUser: Boolean, val text: String, val createdAt: Long)

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

    fun addChat(fromUser: Boolean, text: String): Long
    fun recentChats(limit: Int): List<ChatMessage>
}

/** Delivers reminders at their due time (AlarmManager on Android). */
interface AlarmScheduler {
    fun schedule(reminder: Reminder)
    fun cancel(reminderId: Long)
}
