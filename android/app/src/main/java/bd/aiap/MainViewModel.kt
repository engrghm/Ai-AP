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
        val assistant = app.assistant()
        if (assistant == null) {
            error = "প্রথমে সেটিংসে Claude API key দিন।"
            return
        }
        pending = message
        error = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { assistant.chat(message) }
            } catch (e: Exception) {
                error = "উত্তর আনা যায়নি: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                pending = null
                refresh()
            }
        }
    }

    fun briefingNow() {
        val assistant = app.assistant()
        if (assistant == null) {
            error = "প্রথমে সেটিংসে Claude API key দিন।"
            return
        }
        pending = "আজকের পরিকল্পনা"
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { assistant.briefing() }
                withContext(Dispatchers.IO) { app.store.addChat(fromUser = false, text = "🌅 $text") }
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
        app.settings.briefingTime = briefingTime
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

    fun canScheduleExact() = app.alarms.canScheduleExact()
}
