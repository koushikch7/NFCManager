package com.example

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class NfcOperationMode {
    READ, WRITE, RESET, WRITE_PROFILE
}

data class ProfileData(
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val website: String = "",
    val instagram: String = "",
    val facebook: String = "",
    val customInfo: String = ""
)

data class LogEntry(val timestamp: Long, val message: String)

data class ChatMessage(val text: String, val isUser: Boolean)

data class NfcUiState(
    val isNfcEnabled: Boolean = false,
    val currentMode: NfcOperationMode = NfcOperationMode.READ,
    val tagData: String = "No tag detected yet.",
    val logs: List<LogEntry> = emptyList(),
    val chatMessages: List<ChatMessage> = listOf(ChatMessage("Hi! I'm your NFC Assistant. I can help you format data or switch modes. What do you need?", false)),
    val isChatLoading: Boolean = false,
    val profileData: ProfileData = ProfileData(),
    val history: List<NfcHistoryEntity> = emptyList()
)

class NfcViewModel(private val repository: NfcHistoryRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(NfcUiState())
    val uiState: StateFlow<NfcUiState> = combine(_uiState, repository.allHistory) { state, history ->
        state.copy(history = history)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = NfcUiState()
    )

    fun updateProfile(profile: ProfileData) {
        _uiState.update { it.copy(profileData = profile) }
    }

    fun setNfcStatus(enabled: Boolean) {
        _uiState.update { it.copy(isNfcEnabled = enabled) }
    }

    fun setMode(mode: NfcOperationMode) {
        _uiState.update { it.copy(currentMode = mode) }
        log("Switched to $mode mode. Ready to scan.")
    }

    fun updateTagData(data: String) {
        _uiState.update { it.copy(tagData = data) }
    }

    fun log(message: String) {
        val entry = LogEntry(System.currentTimeMillis(), message)
        _uiState.update { it.copy(logs = listOf(entry) + it.logs) }
    }

    fun logOperationToDb(tagId: String, data: String) {
        viewModelScope.launch {
            repository.insert(NfcHistoryEntity(
                operationMode = _uiState.value.currentMode.name,
                tagId = tagId,
                data = data
            ))
        }
    }

    fun sendChatMessage(message: String) {
        val userMsg = ChatMessage(message, true)
        _uiState.update { it.copy(
            chatMessages = it.chatMessages + userMsg,
            isChatLoading = true
        ) }

        viewModelScope.launch {
            val response = askGemini(message)
            _uiState.update { it.copy(isChatLoading = false) }
            
            if (response.contains("[COMMAND:SWITCH_MODE:READ]")) {
                setMode(NfcOperationMode.READ)
                addBotMessage(response.replace("[COMMAND:SWITCH_MODE:READ]", "").trim())
            } else if (response.contains("[COMMAND:SWITCH_MODE:WRITE]")) {
                setMode(NfcOperationMode.WRITE)
                addBotMessage(response.replace("[COMMAND:SWITCH_MODE:WRITE]", "").trim())
            } else if (response.contains("[COMMAND:SWITCH_MODE:RESET]")) {
                setMode(NfcOperationMode.RESET)
                addBotMessage(response.replace("[COMMAND:SWITCH_MODE:RESET]", "").trim())
            } else {
                addBotMessage(response)
            }
        }
    }

    private fun addBotMessage(message: String) {
        val botMsg = ChatMessage(message, false)
        _uiState.update { it.copy(chatMessages = it.chatMessages + botMsg) }
    }

    private suspend fun askGemini(prompt: String): String = withContext(Dispatchers.IO) {
        try {
            val apiKey = BuildConfig.GEMINI_API_KEY
            val request = GenerateContentRequest(
                contents = listOf(
                    Content(
                        parts = listOf(Part(text = prompt))
                    )
                ),
                systemInstruction = Content(
                    parts = listOf(Part(text = "You are an AI assistant for an NFC application. Help the user format data to write to NFC tags, or explain NFC concepts. You cannot bypass security or unformat readonly tags. If the user asks to switch to Read, Write, or Reset mode, output the exact phrase '[COMMAND:SWITCH_MODE:WRITE]' (or READ/RESET) in your response, and then explain the mode. Otherwise, just reply normally."))
                )
            )
            val response = RetrofitClient.service.generateContent(apiKey, request)
            response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "No response from Gemini."
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

class MainActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    
    private val viewModel: NfcViewModel by viewModels {
        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "nfc_db").build()
        val repo = NfcHistoryRepository(db.nfcHistoryDao())
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return NfcViewModel(repo) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        viewModel.setNfcStatus(nfcAdapter?.isEnabled == true)
        
        val intent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                NfcDashboardScreen(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.setNfcStatus(nfcAdapter?.isEnabled == true)
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action || 
            NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action || 
            NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {
            
            val tag: Tag? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }

            tag?.let {
                val tagId = it.id.joinToString(":") { byte -> "%02X".format(byte) }
                viewModel.log("Tag detected. ID: $tagId")
                
                when (viewModel.uiState.value.currentMode) {
                    NfcOperationMode.READ -> {
                        viewModel.logOperationToDb(tagId, "Read Tag Data")
                        handleReadTag(it)
                    }
                    NfcOperationMode.WRITE -> {
                        viewModel.logOperationToDb(tagId, "Simulated Write")
                        viewModel.log("Write operation simulated. Ready to write payload.")
                    }
                    NfcOperationMode.RESET -> {
                        viewModel.logOperationToDb(tagId, "Format/Reset Tag")
                        viewModel.log("Reset operation simulated. Format complete.")
                    }
                    NfcOperationMode.WRITE_PROFILE -> {
                        viewModel.logOperationToDb(tagId, "Write Digital Profile: ${viewModel.uiState.value.profileData.name}")
                        handleWriteProfile(it)
                    }
                }
            }
        }
    }

    private fun handleWriteProfile(tag: Tag) {
        val profile = viewModel.uiState.value.profileData
        val vCard = buildString {
            append("BEGIN:VCARD\n")
            append("VERSION:3.0\n")
            append("FN:${profile.name}\n")
            if (profile.phone.isNotBlank()) append("TEL:${profile.phone}\n")
            if (profile.email.isNotBlank()) append("EMAIL:${profile.email}\n")
            if (profile.website.isNotBlank()) append("URL:${profile.website}\n")
            val notes = mutableListOf<String>()
            if (profile.instagram.isNotBlank()) notes.add("Instagram: ${profile.instagram}")
            if (profile.facebook.isNotBlank()) notes.add("Facebook: ${profile.facebook}")
            if (profile.customInfo.isNotBlank()) notes.add(profile.customInfo)
            if (notes.isNotEmpty()) append("NOTE:${notes.joinToString(" | ")}\n")
            append("END:VCARD")
        }

        val ndefRecord = NdefRecord.createMime("text/vcard", vCard.toByteArray(Charsets.UTF_8))
        val ndefMessage = NdefMessage(arrayOf(ndefRecord))

        try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                if (!ndef.isWritable) {
                    viewModel.log("Tag is read-only. Cannot write profile.")
                    return
                }
                if (ndef.maxSize < ndefMessage.toByteArray().size) {
                    viewModel.log("Tag capacity is too small for this profile.")
                    return
                }
                ndef.writeNdefMessage(ndefMessage)
                viewModel.log("Successfully wrote Digital Profile to tag!")
            } else {
                val ndefFormatable = NdefFormatable.get(tag)
                if (ndefFormatable != null) {
                    ndefFormatable.connect()
                    ndefFormatable.format(ndefMessage)
                    viewModel.log("Successfully formatted and wrote profile data!")
                } else {
                    viewModel.log("Tag doesn't support NDEF.")
                }
            }
        } catch (e: Exception) {
            viewModel.log("Write failed: ${e.message}")
        }
    }

    private fun handleReadTag(tag: Tag) {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            viewModel.log("NDEF format detected.")
            try {
                ndef.connect()
                val ndefMessage = ndef.ndefMessage
                if (ndefMessage != null) {
                    val records = ndefMessage.records
                    val sb = StringBuilder()
                    for (record in records) {
                        sb.append(String(record.payload)).append("\n")
                    }
                    val data = sb.toString()
                    viewModel.updateTagData(data)
                    viewModel.log("Read successful.")
                } else {
                    viewModel.updateTagData("Tag is empty")
                    viewModel.log("Tag is empty.")
                }
            } catch (e: Exception) {
                viewModel.log("Error reading tag: ${e.message}")
            } finally {
                try { ndef.close() } catch (e: Exception) {}
            }
        } else {
            viewModel.log("Tag is not NDEF formatted.")
            viewModel.updateTagData("Raw tag. ID: ${tag.id.joinToString(":") { "%02X".format(it) }}")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcDashboardScreen(viewModel: NfcViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var showChatSheet by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NFC Manager") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showChatSheet = true }) {
                Icon(Icons.Default.Chat, contentDescription = "AI Assistant")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusCard(isNfcEnabled = uiState.isNfcEnabled)
            
            Text("Operations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OperationButton(
                        modifier = Modifier.weight(1f),
                        text = "Read",
                        icon = Icons.Default.Info,
                        isSelected = uiState.currentMode == NfcOperationMode.READ,
                        onClick = { viewModel.setMode(NfcOperationMode.READ) }
                    )
                    OperationButton(
                        modifier = Modifier.weight(1f),
                        text = "Write",
                        icon = Icons.Default.Create,
                        isSelected = uiState.currentMode == NfcOperationMode.WRITE,
                        onClick = { viewModel.setMode(NfcOperationMode.WRITE) }
                    )
                    OperationButton(
                        modifier = Modifier.weight(1f),
                        text = "Reset",
                        icon = Icons.Default.SettingsBackupRestore,
                        isSelected = uiState.currentMode == NfcOperationMode.RESET,
                        onClick = { viewModel.setMode(NfcOperationMode.RESET) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OperationButton(
                        modifier = Modifier.weight(1f),
                        text = "Digital Profile",
                        icon = Icons.Default.Contacts,
                        isSelected = uiState.currentMode == NfcOperationMode.WRITE_PROFILE,
                        onClick = { viewModel.setMode(NfcOperationMode.WRITE_PROFILE) }
                    )
                }
            }
            
            if (uiState.currentMode == NfcOperationMode.WRITE_PROFILE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Profile Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val context = LocalContext.current
                    IconButton(onClick = { 
                        val profile = uiState.profileData
                        val vCard = buildString {
                            append("BEGIN:VCARD\n")
                            append("VERSION:3.0\n")
                            append("FN:${profile.name}\n")
                            if (profile.phone.isNotBlank()) append("TEL:${profile.phone}\n")
                            if (profile.email.isNotBlank()) append("EMAIL:${profile.email}\n")
                            if (profile.website.isNotBlank()) append("URL:${profile.website}\n")
                            val notes = mutableListOf<String>()
                            if (profile.instagram.isNotBlank()) notes.add("Instagram: ${profile.instagram}")
                            if (profile.facebook.isNotBlank()) notes.add("Facebook: ${profile.facebook}")
                            if (profile.customInfo.isNotBlank()) notes.add(profile.customInfo)
                            if (notes.isNotEmpty()) append("NOTE:${notes.joinToString(" | ")}\n")
                            append("END:VCARD")
                        }
                        shareData(context, vCard)
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share Profile (vCard)")
                    }
                }
                ProfileEntryForm(profile = uiState.profileData, onProfileChange = { viewModel.updateProfile(it) })
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tag Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (uiState.tagData.isNotBlank() && uiState.tagData != "No tag detected yet." && uiState.tagData != "Tag is empty.") {
                        val context = LocalContext.current
                        IconButton(onClick = { 
                            val jsonFormat = "{\n  \"nfc_data\": \"${uiState.tagData.replace("\"", "\\\"").replace("\n", "\\n")}\"\n}"
                            shareData(context, jsonFormat) 
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Share as JSON")
                        }
                    }
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        val scroll = rememberScrollState()
                        Text(
                            text = uiState.tagData, 
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.verticalScroll(scroll)
                        )
                    }
                }
            }
            
            if (uiState.history.isNotEmpty()) {
                OperationStatsChart(uiState.history)
            }
            Text("Operation History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(uiState.history) { log ->
                    val format = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
                    val timeString = format.format(Date(log.timestamp))
                    Text(
                        text = "[$timeString] ${log.operationMode} - Tag ID: ${log.tagId}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = log.data,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        if (showChatSheet) {
            ModalBottomSheet(
                onDismissRequest = { showChatSheet = false },
                modifier = Modifier.fillMaxHeight(0.8f)
            ) {
                ChatSheetContent(uiState, viewModel)
            }
        }
    }
}

@Composable
fun ChatSheetContent(uiState: NfcUiState, viewModel: NfcViewModel) {
    var textInput by remember { mutableStateOf("") }
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("AI Assistant", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            reverseLayout = true
        ) {
            if (uiState.isChatLoading) {
                item {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                }
            }
            items(uiState.chatMessages.reversed()) { msg ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (msg.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp)
                            .fillMaxWidth(0.8f)
                    ) {
                        Text(
                            text = msg.text,
                            color = if (msg.isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask about NFC or type a command...") },
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            FloatingActionButton(
                onClick = {
                    if (textInput.isNotBlank()) {
                        viewModel.sendChatMessage(textInput)
                        textInput = ""
                    }
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun StatusCard(isNfcEnabled: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isNfcEnabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Nfc,
                contentDescription = "NFC Status",
                modifier = Modifier.size(32.dp),
                tint = if (isNfcEnabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
            )
            Column {
                Text(
                    text = if (isNfcEnabled) "NFC is Enabled" else "NFC is Disabled",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isNfcEnabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = if (isNfcEnabled) "Ready to interact with tags" else "Please enable NFC in settings",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isNfcEnabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
fun OperationButton(
    modifier: Modifier = Modifier,
    text: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = text, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

fun shareData(context: Context, data: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "NFC Data")
        putExtra(Intent.EXTRA_TEXT, data)
    }
    context.startActivity(Intent.createChooser(intent, "Share via"))
}

@Composable
fun ProfileEntryForm(profile: ProfileData, onProfileChange: (ProfileData) -> Unit) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = profile.name,
            onValueChange = { onProfileChange(profile.copy(name = it)) },
            label = { Text("Full Name (Required)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = profile.phone,
            onValueChange = { onProfileChange(profile.copy(phone = it)) },
            label = { Text("Phone Number") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = profile.email,
            onValueChange = { onProfileChange(profile.copy(email = it)) },
            label = { Text("Email Address") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = profile.website,
            onValueChange = { onProfileChange(profile.copy(website = it)) },
            label = { Text("Website") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = profile.instagram,
            onValueChange = { onProfileChange(profile.copy(instagram = it)) },
            label = { Text("Instagram Handle") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = profile.facebook,
            onValueChange = { onProfileChange(profile.copy(facebook = it)) },
            label = { Text("Facebook Username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = profile.customInfo,
            onValueChange = { onProfileChange(profile.copy(customInfo = it)) },
            label = { Text("Other Custom Info") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3
        )
    }
}
