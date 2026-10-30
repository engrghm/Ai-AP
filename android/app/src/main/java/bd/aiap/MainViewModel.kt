package bd.aiap

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import bd.aiap.core.ChatMessage
import bd.aiap.core.ListItem
import bd.aiap.core.Memory
import bd.aiap.core.Reminder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AiApp
    val settings get() = app.settings

    var chats by mutableStateOf(emptyList<ChatMessage>())
        private set
    var memories by mutableStateOf(emptyList<Memory>())
        private set
    var items by mutableStateOf(emptyList<ListItem>())
        private set
    var reminders by mutableStateOf(emptyList<Reminder>())
        private set

    /** The message being answered right now, shown until the reply arrives. */
    var pending by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var hasApiKey by mutableStateOf(app.settings.apiKey.isNotEmpty())
        private set
    var canScheduleExact by mutableStateOf(app.alarms.canScheduleExact())
        private set

    init {
        refresh()
    }

    private class Snapshot(
        val chats: List<ChatMessage>,
        val memories: List<Memory>,
        val items: List<ListItem>,
        val reminders: List<Reminder>,
    )

    fun refresh() {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                Snapshot(
                    app.store.recentChats(300),
                    app.store.searchMemories(null, null),
                    app.store.getItems(null, includeDone = true),
                    app.store.activeReminders(),
                )
            }
            chats = data.chats
            memories = data.memories
            items = data.items
            reminders = data.reminders
            canScheduleExact = app.alarms.canScheduleExact()
        }
    }

    private fun background(block: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { block() }
            refresh()
        }
    }

    fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty() || pending != null) return
        if (!hasApiKey) {
            error = "প্রথমে সেটিংসে Claude API key দিন।"
            return
        }
        pending = message
        error = null
        viewModelScope.launch {
            try {
                // Building the HTTP client the first time is slow, so do it off the main thread too.
                withContext(Dispatchers.IO) { app.assistant()?.chat(message) }
            } catch (e: Exception) {
                error = "উত্তর আনা যায়নি: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                pending = null
                refresh()
            }
        }
    }

    fun briefingNow() {
        if (pending != null) return
        if (!hasApiKey) {
            error = "প্রথমে সেটিংসে Claude API key দিন।"
            return
        }
        pending = "আজকের পরিকল্পনা"
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val text = app.assistant()?.briefing().orEmpty()
                    if (text.isNotBlank()) app.store.addChat(fromUser = false, text = "🌅 $text")
                }
            } catch (e: Exception) {
                error = "ব্রিফিং আনা যায়নি: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                pending = null
                refresh()
            }
        }
    }

    fun saveSettings(name: String, apiKey: String, briefingTime: String) {
        app.settings.ownerName = name
        app.settings.apiKey = apiKey
        app.settings.briefingTime = normalizeTime(briefingTime)
        hasApiKey = app.settings.apiKey.isNotEmpty()
        app.alarms.scheduleBriefing(app.settings.briefingTime)
    }

    fun clearChat() = background {
        app.store.clearChats()
        app.assistant()?.reset()
    }

    fun setDone(item: ListItem, done: Boolean) = background { app.store.setItemDone(item.id, done) }
    fun deleteItem(item: ListItem) = background { app.store.deleteItem(item.id) }
    fun deleteMemory(memory: Memory) = background { app.store.deleteMemory(memory.id) }
    fun updateMemory(memory: Memory, content: String) = background { app.store.updateMemory(memory.id, content) }

    fun cancelReminder(reminder: Reminder) = background {
        app.alarms.cancel(reminder.id)
        app.store.deactivateReminder(reminder.id)
    }

}

/** Accepts "8:00", "08:00" or "8" and returns "08:00"; anything else turns the briefing off. */
fun normalizeTime(input: String): String {
    val match = Regex("""^\s*(\d{1,2})(?::(\d{2}))?\s*$""").find(input) ?: return ""
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
    return if (hour in 0..23 && minute in 0..59) "%02d:%02d".format(hour, minute) else ""
}
