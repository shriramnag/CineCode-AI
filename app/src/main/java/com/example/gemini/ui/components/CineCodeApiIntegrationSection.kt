package com.shriram.cinecode.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.remote.GeminiLiveVoiceClient
import com.example.gemini.data.remote.GeminiSearchGroundingClient
import com.shriram.cinecode.data.remote.CloudBridgeStore
import com.shriram.cinecode.data.remote.EngineDiagnosticsReport
import kotlinx.coroutines.launch

/**
 * Compact status pill for ChatScreen top bar / banner area showing live Cloud vs Local Bridge state.
 * Tapping it opens Settings directly so the user can paste or update their Google AI Studio API Key.
 */
@Composable
fun CineCodeConnectionStatusPill(
    isLocalBridgeOnline: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { CloudBridgeStore.ensureInitialized(context) }

    val isCloudActive by CloudBridgeStore.isCloudActiveFlow.collectAsState()
    val isRateLimitRecovering by CloudBridgeStore.isRateLimitRecoveringFlow.collectAsState()
    val statusText = when {
        isRateLimitRecovering -> "📡 Connecting to Fallback/Local Hub 🟠"
        isCloudActive -> "📡 Connection: Cloud Native 🟢"
        isLocalBridgeOnline -> "📡 Connection: Local Bridge 🔵 (:1235 Connected)"
        else -> "📡 Connection: Local Bridge 🔵 (Searching Computer on :1235)"
    }
    val accentColor = when {
        isRateLimitRecovering -> Color(0xFFF59E0B)
        isCloudActive -> Color(0xFF2E7D32)
        else -> Color(0xFF1565C0)
    }
    val bgColor = when {
        isRateLimitRecovering -> Color(0xFFF59E0B).copy(alpha = 0.16f)
        isCloudActive -> Color(0xFF1B5E20).copy(alpha = 0.16f)
        else -> Color(0xFF0D47A1).copy(alpha = 0.14f)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("connection_status_pill"),
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = statusText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    isRateLimitRecovering -> "Fallback 🟠"
                    isCloudActive -> "API Key ✓"
                    else -> "Add Key →"
                },
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    isRateLimitRecovering -> Color(0xFFF59E0B)
                    isCloudActive -> Color(0xFF66BB6A)
                    else -> Color(0xFF64B5F6)
                }
            )
        }
    }
}

/**
 * Highly visible API Integration & Dual-Bridge Settings Section placed at the very top of Settings.
 * Displays the connection status banner, Google AI Studio API Key input with visibility toggle,
 * Save/Clear buttons, and connected Google Search Grounding + Gemini 3.8 Live Voice controls.
 */
@Composable
fun CineCodeApiIntegrationSection(
    isLocalBridgeOnline: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { CloudBridgeStore.ensureInitialized(context) }

    val savedApiKey by CloudBridgeStore.apiKeyFlow.collectAsState()
    val isCloudActive by CloudBridgeStore.isCloudActiveFlow.collectAsState()
    val isRateLimitRecovering by CloudBridgeStore.isRateLimitRecoveringFlow.collectAsState()
    val recoveryNotice by CloudBridgeStore.lastRecoveryNoticeFlow.collectAsState()
    val searchEnabled by CloudBridgeStore.searchGroundingEnabledFlow.collectAsState()
    val liveVoiceEnabled by CloudBridgeStore.liveVoiceEnabledFlow.collectAsState()
    val selectedModel by CloudBridgeStore.selectedModelFlow.collectAsState()

    var apiKeyInput by remember { mutableStateOf(savedApiKey) }
    var passwordVisible by remember { mutableStateOf(false) }
    var feedbackText by remember { mutableStateOf("") }
    var isDiagnosing by remember { mutableStateOf(false) }
    var diagnosticsReport by remember { mutableStateOf<EngineDiagnosticsReport?>(null) }

    // Keep text field synced if initialized asynchronously from DataStore
    LaunchedEffect(savedApiKey) {
        if (apiKeyInput != savedApiKey && apiKeyInput.isBlank()) {
            apiKeyInput = savedApiKey
        }
    }

    val searchClient = remember(context) { GeminiSearchGroundingClient(context.applicationContext) }
    val liveVoiceClient = remember(context) { GeminiLiveVoiceClient(context.applicationContext) }

    var showInteractiveTools by remember { mutableStateOf(false) }
    var quickSearchQuery by remember { mutableStateOf("") }
    var quickSearchResult by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    var quickVoiceTurn by remember { mutableStateOf("") }
    val liveLogs by liveVoiceClient.liveSessionLogs.collectAsState()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Top Connection Status Banner Card
        val bannerBrush = when {
            isRateLimitRecovering -> Brush.horizontalGradient(listOf(Color(0xFF4A2A0A), Color(0xFF6D3C11)))
            isCloudActive -> Brush.horizontalGradient(listOf(Color(0xFF10361A), Color(0xFF1A4D2A)))
            else -> Brush.horizontalGradient(listOf(Color(0xFF0E2542), Color(0xFF153860)))
        }
        val bannerBorder = when {
            isRateLimitRecovering -> Color(0xFFF59E0B)
            isCloudActive -> Color(0xFF4CAF50)
            else -> Color(0xFF42A5F5)
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_connection_banner"),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, bannerBorder.copy(alpha = 0.65f)),
            color = Color.Transparent
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bannerBrush)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(bannerBorder.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isCloudActive) Icons.Default.CloudDone else Icons.Default.Lan,
                        contentDescription = "Connection Status",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            isRateLimitRecovering -> "Connecting to Fallback/Local Hub 🟠"
                            isCloudActive -> "📡 Connection: Cloud Native 🟢"
                            isLocalBridgeOnline -> "📡 Connection: Local Bridge 🔵 (:1235 Connected)"
                            else -> "📡 Connection: Local Bridge 🔵 (Searching Computer on :1235)"
                        },
                        color = Color.White,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when {
                            isRateLimitRecovering -> recoveryNotice ?: "API 429 Autorecovery active — Local Hub :1235 isolated"
                            isCloudActive -> "Direct Google AI Studio Mode Active ($selectedModel)"
                            else -> "Antigravity Local Server Fallback (127.0.0.1:1235)"
                        },
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 11.5.sp
                    )
                }
            }
        }

        // 2. Main API Integration Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("api_integration_card"),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "API Key",
                            tint = Color(0xFFFFCA28),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "🔑 API Integration Card",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isCloudActive) Color(0xFF2E7D32) else Color(0xFF37474F)
                    ) {
                        Text(
                            text = if (isCloudActive) "Saved 🟢" else "Empty",
                            color = Color.White,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Text(
                    text = "गूगल एआई स्टूडियो कुंजी (Google AI Studio API Key)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("🔑 Enter Google API Key") },
                    placeholder = { Text("AIzaSy...") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(
                            onClick = { passwordVisible = !passwordVisible },
                            modifier = Modifier.testTag("toggle_api_key_visibility")
                        ) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Hide API Key" else "Show API Key"
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("google_api_key_input")
                )

                Text(
                    text = "यहाँ अपनी API की पेस्ट करें ताकि बिना कंप्यूटर के एआई चल सके (Paste API Key to run AI without computer)",
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Save & Clear Buttons (Min 48.dp height touch target)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            CloudBridgeStore.saveApiKey(context, apiKeyInput)
                            feedbackText = if (apiKeyInput.isNotBlank()) {
                                "✅ API Key सहेजी गई! Cloud Native Mode अब सक्रिय है।"
                            } else {
                                "ℹ️ खाली की सेव हुई — Local Bridge (:1235) सक्रिय है।"
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("save_api_key_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1976D2),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("💾 सहेजें / Save Key", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            apiKeyInput = ""
                            CloudBridgeStore.clearApiKey(context)
                            feedbackText = "❌ API Key मिटा दी गई। अब Local Bridge (:1235) सक्रिय है।"
                        },
                        modifier = Modifier
                            .weight(0.85f)
                            .heightIn(min = 48.dp)
                            .testTag("clear_api_key_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFE53935).copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF5350))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("❌ मिटाएं / Clear", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (feedbackText.isNotEmpty()) {
                    Text(
                        text = feedbackText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Cloud Model Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        CloudBridgeStore.MODEL_HYDROGEN to "4 Hydrogen",
                        CloudBridgeStore.MODEL_CARBON to "4 Carbon",
                        CloudBridgeStore.MODEL_FLASH to "3.8 Flash"
                    ).forEach { (modelId, label) ->
                        FilterChip(
                            selected = selectedModel == modelId,
                            onClick = { CloudBridgeStore.setSelectedModel(context, modelId) },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                // Toggle 1: Google Search Grounding
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { CloudBridgeStore.setSearchGroundingEnabled(context, !searchEnabled) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = "Google Search Grounding",
                            tint = Color(0xFF42A5F5),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Google Search Grounding",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "(गूगल खोज सक्रिय · gemini-4-hydrogen)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = searchEnabled,
                        onCheckedChange = { CloudBridgeStore.setSearchGroundingEnabled(context, it) }
                    )
                }

                // Toggle 2: Real-time Voice (Gemini 3.8 Live)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { CloudBridgeStore.setLiveVoiceEnabled(context, !liveVoiceEnabled) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Real-time Voice",
                            tint = Color(0xFF66BB6A),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Real-time Voice",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "(जेमिनी लाइव कन्वर्सेशन · gemini-3.8-live)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = liveVoiceEnabled,
                        onCheckedChange = { CloudBridgeStore.setLiveVoiceEnabled(context, it) }
                    )
                }

                // Expandable Live Voice & Google Search Grounding Test Console
                TextButton(
                    onClick = { showInteractiveTools = !showInteractiveTools },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = if (showInteractiveTools) "Hide Live Voice & Search Panel ▲" else "Open Live Voice & Search Test ▼",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                AnimatedVisibility(visible = showInteractiveTools) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = quickSearchQuery,
                            onValueChange = { quickSearchQuery = it },
                            label = { Text("Google Search Grounding Query") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                if (quickSearchQuery.isNotBlank()) {
                                    isSearching = true
                                    scope.launch {
                                        searchClient.queryWithSearchGrounding(quickSearchQuery)
                                            .onSuccess { quickSearchResult = it }
                                            .onFailure { quickSearchResult = "Error: ${it.localizedMessage}" }
                                        isSearching = false
                                    }
                                }
                            },
                            enabled = !isSearching,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isSearching) "Searching Web..." else "Run Google Search Grounding")
                        }
                        if (quickSearchResult.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Text(
                                    text = quickSearchResult,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = quickVoiceTurn,
                            onValueChange = { quickVoiceTurn = it },
                            label = { Text("Gemini 3.8 Live Voice Message") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (quickVoiceTurn.isNotBlank()) {
                                        scope.launch { liveVoiceClient.startLiveVoiceSession(quickVoiceTurn) }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Send Live Turn", fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { liveVoiceClient.stopLiveSession() },
                                modifier = Modifier.weight(0.7f)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Stop", fontSize = 12.sp)
                            }
                        }
                        if (liveLogs.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    liveLogs.takeLast(4).forEach { logLine ->
                                        Text(text = logLine, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                Text(
                    text = "AgyBridge Local Server Fallback (127.0.0.1:1235) is isolated from Cloud API 429 limits.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                // Dual-Engine Self-Diagnostics Button & Isolated Report
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        enabled = !isDiagnosing,
                        onClick = {
                            isDiagnosing = true
                            scope.launch {
                                diagnosticsReport = CloudBridgeStore.runEngineDiagnostics(context)
                                isDiagnosing = false
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("diagnose_engine_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        if (isDiagnosing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("जाँच हो रही है...", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(imageVector = Icons.Default.Build, contentDescription = "Diagnose Engine", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("⚡ Diagnose / Check Engine", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (isRateLimitRecovering) {
                        OutlinedButton(
                            onClick = { CloudBridgeStore.clearRecoveryState() },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("reset_429_recovery_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = "Reconnect", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reconnect", fontSize = 12.sp)
                        }
                    }
                }

                diagnosticsReport?.let { report ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth().testTag("engine_diagnostics_report")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "☁️ Cloud API: ${report.cloudStatusLabel}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (report.isRateLimit429) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "🖥️ Local Hub (:1235 / :1234): ${report.localStatusLabel}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = report.guidance,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
