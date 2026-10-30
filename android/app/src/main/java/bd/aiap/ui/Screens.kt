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
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import bd.aiap.VoiceListener
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NightsStay
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import bd.aiap.HabitRow
import bd.aiap.MainViewModel
import bd.aiap.splitKeys
import bd.aiap.core.EXPENSE_CATEGORIES
import bd.aiap.core.Expense
import bd.aiap.core.ListItem
import bd.aiap.core.PhoneAction
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
    TRACKER("হিসাব", "হিসাব ও অভ্যাস", Icons.Filled.AccountBalanceWallet),
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
                modifier = Modifier.background(
                    Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))
                ),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
                title = { Text(tab.title, fontWeight = FontWeight.Bold) },
                actions = {
                    if (tab == Tab.CHAT) {
                        IconButton(onClick = { vm.briefingNow("morning") }, enabled = vm.pending == null) {
                            Icon(Icons.Filled.WbSunny, "আজকের পরিকল্পনা")
                        }
                        IconButton(onClick = { vm.briefingNow("evening") }, enabled = vm.pending == null) {
                            Icon(Icons.Filled.NightsStay, "আজকের হিসাব")
                        }
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
            Crossfade(targetState = tab, animationSpec = tween(250), label = "tab") { current ->
                when (current) {
                    Tab.CHAT -> ChatScreen(vm)
                    Tab.MEMORY -> MemoryScreen(vm)
                    Tab.LISTS -> ListsScreen(vm)
                    Tab.REMINDERS -> RemindersScreen(vm)
                    Tab.TRACKER -> TrackerScreen(vm)
                }
            }
        }
    }

    if (showSettings) SettingsDialog(vm, onDismiss = { showSettings = false })

    // Opened from the "মুখে বলুন" shortcut: go to chat, where listening starts.
    LaunchedEffect(vm.listenRequested) {
        if (vm.listenRequested) tab = Tab.CHAT
    }

    vm.info?.let { message ->
        AlertDialog(
            onDismissRequest = { vm.info = null },
            confirmButton = { TextButton(onClick = { vm.info = null }) { Text("ঠিক আছে") } },
            text = { Text(message) },
        )
    }

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
    val context = LocalContext.current
    val pending = vm.pending

    // What to do with finished speech: send it, or put it in the box to edit.
    val onSpoken: (String) -> Unit = { spoken ->
        val text = if (input.isBlank()) spoken else "$input $spoken"
        if (vm.settings.autoSendVoice && vm.pending == null) {
            vm.send(text)
            // Keep the words if sending did not start (e.g. no API key yet).
            input = if (vm.pending != null) "" else text
        } else {
            input = text
        }
    }

    // In-app listening that keeps going through pauses; the system dialog is only a fallback.
    val voice = remember {
        if (VoiceListener.isAvailable(context)) VoiceListener(context, { onSpoken(it) }, { vm.error = it }) else null
    }
    DisposableEffect(voice) { onDispose { voice?.destroy() } }

    val systemSpeech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            ?.takeIf { it.isNotBlank() }?.let(onSpoken)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice?.start() else vm.error = "মুখে বলতে মাইক্রোফোনের অনুমতি দরকার।"
    }
    val listen: () -> Unit = {
        vm.stopSpeaking()
        if (voice != null) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (granted) voice.start() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            try {
                systemSpeech.launch(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "বলুন… আমি গুছিয়ে রাখবো")
                )
            } catch (e: ActivityNotFoundException) {
                vm.error = "এই ফোনে ভয়েস ইনপুট পাওয়া যায়নি। Google অ্যাপ ইনস্টল/আপডেট করে দেখুন।"
            }
        }
    }
    LaunchedEffect(vm.listenRequested) {
        if (vm.listenRequested) {
            vm.listenRequested = false
            listen()
        }
    }

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
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (vm.chats.isEmpty() && pending == null) {
                item(key = "welcome") {
                    WelcomeCard(vm.settings.ownerName, onExample = { vm.send(it) })
                }
            }
            items(vm.chats, key = { it.id }) { message ->
                Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Bubble(message.text, message.fromUser)
                    if (message.actions.isNotEmpty()) ActionButtons(message.actions, vm::runAction)
                }
            }
            if (pending != null) {
                item(key = "pending-question") { Box(Modifier.animateItem()) { Bubble(pending, fromUser = true) } }
                item(key = "pending-answer") { Box(Modifier.animateItem()) { ThinkingBubble() } }
            }
        }

        AnimatedContent(
            targetState = voice?.active == true,
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically { it / 2 }) togetherWith (fadeOut(tween(160)) + slideOutVertically { it / 2 })
            },
            label = "input",
        ) { listening ->
            if (listening && voice != null) {
                ListeningPanel(voice)
            } else {
                InputBar(
                    input = input,
                    onInput = { input = it },
                    busy = pending != null,
                    onMic = listen,
                    onSend = {
                        vm.send(input)
                        if (vm.pending != null) input = ""
                    },
                )
            }
        }
    }
}

@Composable
private fun InputBar(input: String, onInput: (String) -> Unit, busy: Boolean, onMic: () -> Unit, onSend: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.weight(1f),
                placeholder = { Text("লিখুন, অথবা মাইকে চেপে বলুন…") },
                maxLines = 5,
                shape = RoundedCornerShape(26.dp),
            )
            // One round button: mic when the box is empty, send when there is text (like popular chat apps).
            val showSend = input.isNotBlank()
            GradientCircleButton(
                onClick = { if (showSend) onSend() else onMic() },
                enabled = !busy,
            ) {
                Crossfade(targetState = showSend, label = "micOrSend") { send ->
                    Icon(
                        if (send) Icons.AutoMirrored.Filled.Send else Icons.Filled.Mic,
                        contentDescription = if (send) "পাঠান" else "মুখে বলুন",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun GradientCircleButton(onClick: () -> Unit, enabled: Boolean, size: Dp = 52.dp, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (enabled) Brush.linearGradient(listOf(colors.primary, colors.tertiary))
                else Brush.linearGradient(listOf(colors.outline, colors.outline))
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Shown while listening: a mic that pulses with your voice and the words as they are recognised. */
@Composable
private fun ListeningPanel(voice: VoiceListener) {
    val colors = MaterialTheme.colorScheme
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "ring",
    )
    val loud by animateFloatAsState(voice.level, tween(120), label = "level")

    Surface(tonalElevation = 6.dp, shadowElevation = 12.dp, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("শুনছি… থামলেও শুনতে থাকবো", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            val heard = voice.text
            Text(
                heard.ifBlank { "বলুন, যত লম্বা খুশি…" },
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp, max = 160.dp)
                    .verticalScroll(rememberScrollState(), reverseScrolling = true),
                style = MaterialTheme.typography.bodyLarge,
                color = if (heard.isBlank()) colors.outline else colors.onSurface,
                textAlign = TextAlign.Center,
            )
            Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                // Two expanding rings plus a glow that follows the loudness of the voice.
                listOf(ring, (ring + 0.5f) % 1f).forEach { t ->
                    Box(
                        Modifier
                            .size(120.dp)
                            .scale(0.55f + 0.45f * t)
                            .alpha(0.35f * (1f - t))
                            .clip(CircleShape)
                            .background(colors.primary)
                    )
                }
                Box(
                    Modifier
                        .size(80.dp)
                        .scale(1f + 0.25f * loud)
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.25f))
                )
                GradientCircleButton(onClick = { voice.stop() }, enabled = true, size = 68.dp) {
                    Icon(Icons.Filled.Mic, contentDescription = "শেষ করুন", tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { voice.cancel() }) { Text("বাতিল") }
                Button(onClick = { voice.stop() }) { Text("শেষ ✓") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WelcomeCard(name: String, onExample: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(
            Modifier
                .background(Brush.linearGradient(listOf(colors.primaryContainer, colors.tertiaryContainer)))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "আসসালামু আলাইকুম${if (name.isNotBlank()) " $name" else ""}! 👋",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text("🎤 মাইকে চেপে যা খুশি বলুন। আমি নিজেই বুঝে নোট, লিস্ট, রিমাইন্ডার, খরচ বা অভ্যাসে গুছিয়ে রাখবো।")
            Text("একটা চেপে দেখুন:", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    "আজকে আমার কী কী করা উচিত?",
                    "দুধ, ডিম আর চাল কিনতে হবে",
                    "আজ বাজারে ৮৫০ টাকা খরচ হলো",
                    "প্রতিদিন রাত ১০টায় ওষুধের কথা মনে করাবে",
                    "এই মাসে কোথায় বেশি খরচ হচ্ছে?",
                ).forEach { example ->
                    SuggestionChip(onClick = { onExample(example) }, label = { Text(example) })
                }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = if (fromUser) 20.dp else 6.dp,
        bottomEnd = if (fromUser) 6.dp else 20.dp,
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!fromUser) {
            Box(
                Modifier
                    .padding(end = 6.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(colors.primary, colors.tertiary))),
                contentAlignment = Alignment.Center,
            ) { Text("✨", style = MaterialTheme.typography.labelMedium) }
        }
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .shadow(if (fromUser) 2.dp else 0.dp, shape)
                .clip(shape)
                .background(
                    if (fromUser) Brush.linearGradient(listOf(colors.primary, colors.tertiary))
                    else Brush.linearGradient(listOf(colors.surfaceVariant, colors.surfaceVariant))
                )
        ) {
            SelectionContainer {
                Text(
                    text,
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = if (fromUser) Color.White else colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** Three dots that bounce one after another while the reply is being prepared. */
@Composable
private fun ThinkingBubble() {
    val transition = rememberInfiniteTransition(label = "dots")
    Row(
        Modifier
            .padding(start = 36.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val offset by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    keyframes {
                        durationMillis = 900
                        0f at 0 + index * 150
                        -6f at 150 + index * 150
                        0f at 300 + index * 150
                    }
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .offset(y = offset.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

// ---------------------------------------------------------------- memories

@Composable
private fun MemoryScreen(vm: MainViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Memory?>(null) }
    val filtered = vm.memories.filter { query.isBlank() || it.content.contains(query, ignoreCase = true) }
    // Known categories first, in a fixed order; anything the AI invented after them.
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
                            Repeat.YEARLY -> " · প্রতি বছর"
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionButtons(actions: List<PhoneAction>, onRun: (PhoneAction) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { action ->
            val icon = when (action.type) {
                "call" -> "📞"
                "sms" -> "💬"
                "whatsapp" -> "🟢"
                "email" -> "✉️"
                "map" -> "📍"
                "calendar" -> "📅"
                "alarm" -> "⏰"
                else -> "🔎"
            }
            FilledTonalButton(onClick = { onRun(action) }) { Text("$icon ${action.label}") }
        }
    }
}

// ---------------------------------------------------------------- tracker (money + habits)

@Composable
private fun TrackerScreen(vm: MainViewModel) {
    val spent = vm.monthExpenses.filter { it.amount > 0 }
    val received = -vm.monthExpenses.filter { it.amount < 0 }.sumOf { it.amount }
    val today = java.time.LocalDate.now().toString()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "habits") {
            SectionCard(title = "আজকের অভ্যাস", count = vm.habits.count { it.doneToday }) {
                if (vm.habits.isEmpty()) {
                    Text(
                        "চ্যাটে বলুন: \"প্রতিদিন নামাজ/হাঁটা/বই পড়ার অভ্যাস ট্র্যাক করো\"",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                vm.habits.forEach { HabitRowView(it, vm) }
            }
        }
        item(key = "month") {
            SectionCard(title = "এই মাসের খরচ", count = spent.size) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("মোট খরচ: ৳${money(spent.sumOf { it.amount })}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    if (received > 0) Text("আয়/প্রাপ্তি: ৳${money(received)}")
                    Text("আজ: ৳${money(spent.filter { it.date == today }.sumOf { it.amount })}")
                    spent.groupBy { it.category }
                        .mapValues { (_, v) -> v.sumOf { it.amount } }
                        .entries.sortedByDescending { it.value }
                        .forEach { (category, total) ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(EXPENSE_CATEGORIES[category] ?: category, Modifier.weight(1f))
                                Text("৳${money(total)}")
                            }
                        }
                }
            }
        }
        if (vm.monthExpenses.isEmpty()) {
            item { EmptyHint("খরচ লিখতে চ্যাটে বলুন: \"আজ রিকশা ভাড়া ৬০ টাকা\"") }
        }
        items(vm.monthExpenses, key = { "expense-${it.id}" }) { ExpenseRow(it, onDelete = { vm.deleteExpense(it) }) }
    }
}

@Composable
private fun HabitRowView(habit: HabitRow, vm: MainViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = habit.doneToday, onCheckedChange = { vm.toggleHabitToday(habit) })
        Column(Modifier.weight(1f)) {
            Text(habit.name)
            Text(
                "🔥 ${habit.streak} দিন টানা · গত ৭ দিনে ${habit.last7} বার",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        IconButton(onClick = { vm.deleteHabit(habit) }) {
            Icon(Icons.Filled.Delete, contentDescription = "মুছুন", tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun ExpenseRow(expense: Expense, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(expense.note)
                Text(
                    "${expense.date} · ${EXPENSE_CATEGORIES[expense.category] ?: expense.category}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                (if (expense.amount < 0) "+৳" else "৳") + money(kotlin.math.abs(expense.amount)),
                color = if (expense.amount < 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "মুছুন", tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

private fun money(value: Double): String =
    if (value % 1.0 == 0.0) "%,d".format(value.toLong()) else "%,.2f".format(value)

// ---------------------------------------------------------------- settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(vm.settings.ownerName) }
    var provider by rememberSaveable { mutableStateOf(vm.settings.provider) }
    var geminiKey by rememberSaveable { mutableStateOf(vm.settings.geminiKey) }
    var newGeminiKey by rememberSaveable { mutableStateOf("") }
    var geminiModel by rememberSaveable { mutableStateOf(vm.settings.geminiModel) }
    var apiKey by rememberSaveable { mutableStateOf(vm.settings.apiKey) }
    var briefing by rememberSaveable { mutableStateOf(vm.settings.briefingTime) }
    var evening by rememberSaveable { mutableStateOf(vm.settings.eveningTime) }
    var autoSend by rememberSaveable { mutableStateOf(vm.settings.autoSendVoice) }
    var speak by rememberSaveable { mutableStateOf(vm.settings.speakReplies) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportTo(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFrom(uri)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("সেটিংস") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("আপনার নাম") }, singleLine = true)
                Text("কোন AI ব্যবহার করবেন", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = provider == "gemini", onClick = { provider = "gemini" }, label = { Text("Google Gemini") })
                    FilterChip(selected = provider == "claude", onClick = { provider = "claude" }, label = { Text("Claude") })
                }
                if (provider == "gemini") {
                    GeminiKeysEditor(geminiKey, { geminiKey = it }, newGeminiKey, { newGeminiKey = it })
                    OutlinedTextField(
                        value = geminiModel,
                        onValueChange = { geminiModel = it },
                        label = { Text("Gemini মডেল") },
                        singleLine = true,
                    )
                    Text(
                        "aistudio.google.com/apikey থেকে API key নিন। এটি শুধু এই ফোনে থাকে।",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
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
                }
                OutlinedTextField(
                    value = briefing,
                    onValueChange = { briefing = it },
                    label = { Text("সকালের পরিকল্পনা (যেমন 8:00, ফাঁকা = বন্ধ)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = evening,
                    onValueChange = { evening = it },
                    label = { Text("রাতের হিসাব (যেমন 21:30, ফাঁকা = বন্ধ)") },
                    singleLine = true,
                )
                SwitchRow("মুখে বলা শেষ হলেই পাঠিয়ে দাও", autoSend) { autoSend = it }
                SwitchRow("উত্তর পড়ে শোনাও", speak) { speak = it }
                HorizontalDivider()
                Text("ব্যাকআপ (নতুন ফোনে নিতে বা নিরাপদে রাখতে)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        try {
                            exportLauncher.launch("amar-sohokari-backup-${java.time.LocalDate.now()}.json")
                        } catch (e: ActivityNotFoundException) {
                            vm.error = "ফাইল সংরক্ষণের অ্যাপ পাওয়া যায়নি।"
                        }
                    }) {
                        Text("সংরক্ষণ")
                    }
                    FilledTonalButton(onClick = {
                        try {
                            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        } catch (e: ActivityNotFoundException) {
                            vm.error = "ফাইল খোলার অ্যাপ পাওয়া যায়নি।"
                        }
                    }) {
                        Text("ফিরিয়ে আনুন")
                    }
                }
                TextButton(onClick = {
                    vm.clearChat()
                    onDismiss()
                }) { Text("চ্যাট ইতিহাস মুছুন (তথ্য থাকবে)") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // A key typed but not yet added with "যোগ" is kept too.
                val allGeminiKeys = (splitKeys(geminiKey) + splitKeys(newGeminiKey)).distinct().joinToString("\n")
                vm.saveSettings(name, provider, allGeminiKeys, geminiModel, apiKey, briefing, evening, autoSend, speak)
                onDismiss()
            }) { Text("সংরক্ষণ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } },
    )
}

/**
 * Gemini keys as a list: add as many as you like; when one stops working (limit reached, blocked,
 * server error) the app automatically uses the next. Keys are kept as newline-separated text so
 * the dialog state survives rotation.
 */
@Composable
private fun GeminiKeysEditor(keys: String, onChange: (String) -> Unit, newKey: String, onNewKey: (String) -> Unit) {
    val list = splitKeys(keys)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Gemini API key (${list.size}টি)", style = MaterialTheme.typography.labelLarge)
        list.forEachIndexed { index, key ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${index + 1}. ${key.take(6)}…${key.takeLast(4)}" + if (index == 0) "  (প্রথমে)" else "",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(onClick = { onChange((list - key).joinToString("\n")) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "key মুছুন", tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newKey,
                onValueChange = onNewKey,
                modifier = Modifier.weight(1f),
                label = { Text(if (list.isEmpty()) "Gemini API key" else "আরেকটি key যোগ করুন") },
                placeholder = { Text("AIza…") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            TextButton(
                onClick = {
                    onChange((list + splitKeys(newKey)).distinct().joinToString("\n"))
                    onNewKey("")
                },
                enabled = newKey.isNotBlank(),
            ) { Text("যোগ") }
        }
        if (list.size > 1) {
            Text(
                "একটি key কাজ না করলে (সীমা শেষ, বন্ধ, সার্ভার সমস্যা) অ্যাপ নিজে থেকেই পরেরটি ব্যবহার করবে।",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
