package bd.aiap.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import bd.aiap.MainViewModel
import bd.aiap.core.ListItem
import bd.aiap.core.MEMORY_CATEGORIES
import bd.aiap.core.Memory
import bd.aiap.core.Reminder
import bd.aiap.core.Repeat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Tab(val label: String, val title: String, val icon: ImageVector) {
    CHAT("চ্যাট", "আমার সহকারী", Icons.Filled.Forum),
    MEMORY("তথ্য", "যা মনে রেখেছি", Icons.Filled.Psychology),
    LISTS("লিস্ট", "আমার লিস্ট", Icons.Filled.TaskAlt),
    REMINDERS("রিমাইন্ডার", "রিমাইন্ডার", Icons.Filled.Alarm),
}

private val LIST_NAMES = mapOf(
    "todo" to "করণীয়",
    "shopping" to "কেনাকাটা",
    "goals" to "লক্ষ্য",
    "books" to "বই",
    "ideas" to "আইডিয়া",
)

private val BANGLA = Locale.forLanguageTag("bn-BD")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("d MMMM, EEEE · h:mm a", BANGLA)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppRoot(vm: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    var showSettings by rememberSaveable { mutableStateOf(!vm.hasApiKey) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tab.title) },
                actions = {
                    if (tab == Tab.CHAT) {
                        IconButton(onClick = vm::briefingNow, enabled = vm.pending == null) { Icon(Icons.Filled.WbSunny, "আজকের পরিকল্পনা") }
                    }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "সেটিংস") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            when (tab) {
                Tab.CHAT -> ChatScreen(vm)
                Tab.MEMORY -> MemoryScreen(vm)
                Tab.LISTS -> ListsScreen(vm)
                Tab.REMINDERS -> RemindersScreen(vm)
            }
        }
    }

    if (showSettings) SettingsDialog(vm, onDismiss = { showSettings = false })

    vm.error?.let { message ->
        AlertDialog(
            onDismissRequest = { vm.error = null },
            confirmButton = { TextButton(onClick = { vm.error = null }) { Text("ঠিক আছে") } },
            text = { Text(message) },
        )
    }
}

// ---------------------------------------------------------------- chat

@Composable
private fun ChatScreen(vm: MainViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) input = if (input.isBlank()) spoken else "$input $spoken"
    }

    val pending = vm.pending
    val rowCount = vm.chats.size + (if (pending != null) 2 else 0)
    LaunchedEffect(rowCount) {
        if (rowCount > 0) listState.animateScrollToItem(rowCount - 1)
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (vm.chats.isEmpty() && pending == null) {
                item(key = "welcome") { WelcomeCard(vm.settings.ownerName) }
            }
            items(vm.chats, key = { it.id }) { Bubble(it.text, it.fromUser) }
            if (pending != null) {
                item(key = "pending-question") { Bubble(pending, fromUser = true) }
                item(key = "pending-answer") { ThinkingBubble() }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "বলুন…")
                try {
                    speech.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    vm.error = "এই ফোনে ভয়েস ইনপুট পাওয়া যায়নি। Google অ্যাপ ইনস্টল/আপডেট করে দেখুন।"
                }
            }) {
                Icon(Icons.Filled.Mic, contentDescription = "মুখে বলুন")
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("লিখুন বা মাইকে বলুন…") },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
            )
            IconButton(
                onClick = {
                    vm.send(input)
                    if (vm.pending != null) input = ""
                },
                enabled = input.isNotBlank() && pending == null,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "পাঠান")
            }
        }
    }
}

@Composable
private fun WelcomeCard(name: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("আসসালামু আলাইকুম${if (name.isNotBlank()) " $name" else ""}! 👋", style = MaterialTheme.typography.titleMedium)
            Text("আমাকে যা বলবেন, আমি গুছিয়ে মনে রাখবো আর সময়মতো মনে করিয়ে দেবো। যেমন:")
            listOf(
                "আমার স্ত্রীর জন্মদিন ১২ মার্চ",
                "শপিং লিস্টে দুধ, ডিম আর চাল যোগ করো",
                "প্রতিদিন রাত ১০টায় ওষুধের কথা মনে করাবে",
                "কাল সকাল ১১টায় মিটিং, ১ ঘণ্টা আগে মনে করিও",
                "আজকে আমার কী কী করা উচিত?",
            ).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (fromUser) 18.dp else 4.dp,
                bottomEnd = if (fromUser) 4.dp else 18.dp,
            ),
            color = if (fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 310.dp),
        ) {
            SelectionContainer {
                Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun ThinkingBubble() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(8.dp))
        Text("ভাবছি…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- memories

@Composable
private fun MemoryScreen(vm: MainViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Memory?>(null) }
    val filtered = vm.memories.filter { query.isBlank() || it.content.contains(query, ignoreCase = true) }
    // Known categories first, in a fixed order; anything Claude invented after them.
    val order = MEMORY_CATEGORIES.keys.toList()
    val groups = filtered.groupBy { it.category }.toList()
        .sortedBy { (category, _) -> order.indexOf(category).let { if (it < 0) order.size else it } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("খুঁজুন…") },
                singleLine = true,
            )
        }
        if (groups.isEmpty()) {
            item { EmptyHint("এখনো কিছু মনে রাখিনি। চ্যাটে আপনার সম্পর্কে বলুন — আমি নিজেই গুছিয়ে রাখবো।") }
        }
        groups.forEach { (category, memories) ->
            item(key = "cat-$category") {
                SectionCard(title = MEMORY_CATEGORIES[category] ?: category.replaceFirstChar { it.uppercase() }, count = memories.size) {
                    memories.forEachIndexed { index, memory ->
                        if (index > 0) HorizontalDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { editing = memory }
                                .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(memory.content, Modifier.weight(1f))
                            IconButton(onClick = { vm.deleteMemory(memory) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "মুছুন", tint = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { memory ->
        var text by remember(memory.id) { mutableStateOf(memory.content) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("তথ্য সম্পাদনা") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank()) vm.updateMemory(memory, text.trim())
                    editing = null
                }) { Text("সংরক্ষণ") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("বাতিল") } },
        )
    }
}

// ---------------------------------------------------------------- lists

@Composable
private fun ListsScreen(vm: MainViewModel) {
    val groups = vm.items.groupBy { it.listName }.toList()
        .sortedBy { (name, _) -> LIST_NAMES.keys.indexOf(name).let { if (it < 0) LIST_NAMES.size else it } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (groups.isEmpty()) {
            item { EmptyHint("কোনো লিস্ট নেই। চ্যাটে বলুন: \"টুডু লিস্টে বিদ্যুৎ বিল দেওয়া যোগ করো\"") }
        }
        groups.forEach { (name, items) ->
            item(key = "list-$name") {
                SectionCard(
                    title = LIST_NAMES[name] ?: name.replaceFirstChar { it.uppercase() },
                    count = items.count { !it.done },
                ) {
                    items.forEach { ListRow(it, vm) }
                }
            }
        }
    }
}

@Composable
private fun ListRow(item: ListItem, vm: MainViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = item.done, onCheckedChange = { vm.setDone(item, it) })
        Column(Modifier.weight(1f)) {
            Text(
                item.text,
                textDecoration = if (item.done) TextDecoration.LineThrough else null,
                color = if (item.done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            )
            val details = listOfNotNull(
                item.dueDate?.let { "📅 $it" },
                when (item.priority) {
                    "high" -> "🔴 জরুরি"
                    "medium" -> "🟡 মাঝারি"
                    "low" -> "🟢 কম জরুরি"
                    else -> null
                },
            )
            if (details.isNotEmpty() && !item.done) {
                Text(details.joinToString("  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        IconButton(onClick = { vm.deleteItem(item) }) {
            Icon(Icons.Filled.Delete, contentDescription = "মুছুন", tint = MaterialTheme.colorScheme.outline)
        }
    }
}

// ---------------------------------------------------------------- reminders

@Composable
private fun RemindersScreen(vm: MainViewModel) {
    val context = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!vm.canScheduleExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("রিমাইন্ডার ঠিক সময়ে পেতে \"Alarms & reminders\" অনুমতি দিন। না দিলে কয়েক মিনিট দেরি হতে পারে।")
                        Button(onClick = {
                            try {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                        .setData(android.net.Uri.parse("package:${context.packageName}"))
                                )
                            } catch (e: ActivityNotFoundException) {
                                vm.error = "ফোনের Settings → Apps → আমার সহকারী → Alarms & reminders থেকে অনুমতি দিন।"
                            }
                        }) { Text("অনুমতি দিন") }
                    }
                }
            }
        }
        if (vm.reminders.isEmpty()) {
            item { EmptyHint("কোনো রিমাইন্ডার নেই। চ্যাটে বলুন: \"প্রতিদিন সকাল ৭টায় হাঁটার কথা মনে করাবে\"") }
        }
        items(vm.reminders, key = { it.id }) { ReminderCard(it, onCancel = { vm.cancelReminder(it) }) }
    }
}

@Composable
private fun ReminderCard(reminder: Reminder, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(reminder.text, style = MaterialTheme.typography.titleSmall)
                Text(
                    Instant.ofEpochMilli(reminder.dueAtMillis).atZone(ZoneId.systemDefault()).format(TIME_FORMAT) +
                        when (reminder.repeat) {
                            Repeat.NONE -> ""
                            Repeat.DAILY -> " · প্রতিদিন"
                            Repeat.WEEKLY -> " · প্রতি সপ্তাহে"
                            Repeat.MONTHLY -> " · প্রতি মাসে"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "বাতিল") }
        }
    }
}

// ---------------------------------------------------------------- shared

@Composable
private fun SectionCard(title: String, count: Int, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("$count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        content()
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
        color = MaterialTheme.colorScheme.outline,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun SettingsDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(vm.settings.ownerName) }
    var apiKey by rememberSaveable { mutableStateOf(vm.settings.apiKey) }
    var briefing by rememberSaveable { mutableStateOf(vm.settings.briefingTime) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("সেটিংস") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("আপনার নাম") }, singleLine = true)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Claude API key") },
                    placeholder = { Text("sk-ant-…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text(
                    "console.anthropic.com থেকে API key নিন। এটি শুধু এই ফোনে থাকে।",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = briefing,
                    onValueChange = { briefing = it },
                    label = { Text("সকালের ব্রিফিং (HH:MM, ফাঁকা = বন্ধ)") },
                    singleLine = true,
                )
                TextButton(onClick = {
                    vm.clearChat()
                    onDismiss()
                }) { Text("চ্যাট ইতিহাস মুছুন (তথ্য থাকবে)") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.saveSettings(name, apiKey, briefing)
                onDismiss()
            }) { Text("সংরক্ষণ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } },
    )
}
