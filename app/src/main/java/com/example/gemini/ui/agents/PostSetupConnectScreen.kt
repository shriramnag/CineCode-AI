package com.shriram.cinecode.ui.agents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shriram.cinecode.data.agent.PostSetupPipelineState
import com.shriram.cinecode.data.agent.PostSetupStepStatus
import com.shriram.cinecode.data.agent.agy.AgyMcpManager
import com.shriram.cinecode.data.remote.CloudBridgeStore
import com.shriram.cinecode.theme.AppColors
import com.shriram.cinecode.theme.ClaudeTerracotta
import com.shriram.cinecode.theme.QuotaGreen
import com.shriram.cinecode.ui.browser.CineCodeBrowserManager
import com.shriram.cinecode.ui.chat.ChatViewModel
import kotlinx.coroutines.launch

/**
 * Post-Setup Sign Up / Connect Screen for CineCode-AI (100% Free).
 * Shown after initial setup completes so the user can sign up/connect Google & Antigravity,
 * configure a Custom MCP Server, and sequentially initialize all Plugins — or skip directly
 * with their free Google AI Studio API Key.
 */
@Composable
fun PostSetupConnectScreen(
    chatViewModel: ChatViewModel,
    onFinishAndEnterApp: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        CloudBridgeStore.ensureInitialized(context)
    }

    val storedApiKey by CloudBridgeStore.apiKeyFlow.collectAsState()
    val savedProfile = remember { chatViewModel.authPreferences.getPostSetupProfileSync() }
    val savedCustomMcp = remember { chatViewModel.authPreferences.getCustomMcpServerSync() }
    val pipelineState by chatViewModel.postSetupPipelineState.collectAsState()
    val customMcpStatus by chatViewModel.customMcpStatus.collectAsState()
    val customMcpDetail by chatViewModel.customMcpStatusDetail.collectAsState()

    var displayName by remember { mutableStateOf(savedProfile.first) }
    var email by remember { mutableStateOf(savedProfile.second) }
    var apiKeyInput by remember(storedApiKey) { mutableStateOf(storedApiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    var customMcpName by remember { mutableStateOf(savedCustomMcp.first) }
    var customMcpUrl by remember { mutableStateOf(savedCustomMcp.second) }
    var hasTriggeredConnect by remember { mutableStateOf(false) }

    val bgBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF0B0E14),
                Color(0xFF111622),
                Color(0xFF0A0D12)
            )
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0B0E14)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgBrush)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .align(Alignment.TopCenter)
            ) {
                // Top bar: Free badge + Skip / Use API Key
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = QuotaGreen.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, QuotaGreen.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(QuotaGreen)
                            )
                            Text(
                                text = "CineCode-AI • 100% Free",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = QuotaGreen
                            )
                        }
                    }

                    TextButton(
                        onClick = {
                            scope.launch {
                                chatViewModel.completePostSetupWithSkipOrApiKey(
                                    apiKey = apiKeyInput.trim(),
                                    customMcpName = customMcpName.trim(),
                                    customMcpUrl = customMcpUrl.trim()
                                )
                                onFinishAndEnterApp()
                            }
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("post_setup_skip_button")
                    ) {
                        Text(
                            text = "Skip / Use API Key",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF93C5FD)
                        )
                    }
                }

                // Scrollable Body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Spacer(modifier = Modifier.height(4.dp))

                    // Hero Title
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Sign Up & Connect",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = "सेटअप पूरा हो गया है! अब Google / Antigravity, Custom MCP Server और सभी Plugins को एक साथ (100% Free) कनेक्ट करें।",
                            fontSize = 13.5.sp,
                            lineHeight = 19.sp,
                            color = Color(0xFFB4BCD0)
                        )
                    }

                    // Card 1: Free Account + Google / API Key
                    ConnectSectionCard(
                        title = "1. Google, Antigravity & API Key",
                        subtitle = "फ़्री प्रोफ़ाइल, Google साइन-इन या Google AI Studio API Key से जुड़ें",
                        icon = Icons.Outlined.AccountCircle,
                        accent = Color(0xFF60A5FA)
                    ) {
                        OutlinedTextField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            label = { Text("आपका नाम (Optional)") },
                            placeholder = { Text("e.g. Shriram") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("post_setup_name_input"),
                            colors = connectFieldColors()
                        )
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Google Email (Optional)") },
                            placeholder = { Text("you@gmail.com") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("post_setup_email_input"),
                            colors = connectFieldColors()
                        )
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            label = { Text("Google AI Studio API Key (Free)") },
                            placeholder = { Text("AIza...") },
                            singleLine = true,
                            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showApiKey = !showApiKey }) {
                                    Icon(
                                        imageVector = if (showApiKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = "Toggle API Key visibility",
                                        tint = Color(0xFF94A3B8)
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("post_setup_apikey_input"),
                            colors = connectFieldColors()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val oauthUrl = chatViewModel.getGoogleOAuthUrl()
                                    CineCodeBrowserManager.launchRealBrowser(context, oauthUrl)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("post_setup_google_oauth_button"),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFF60A5FA).copy(alpha = 0.5f))
                            ) {
                                Icon(Icons.Outlined.Login, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF93C5FD))
                                Spacer(Modifier.width(6.dp))
                                Text("Google Sign-In", fontSize = 12.5.sp, color = Color(0xFFE2E8F0), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = { chatViewModel.loginToAgyHub(force = true) },
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("post_setup_agy_login_button"),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.55f))
                            ) {
                                Icon(Icons.Outlined.Hub, contentDescription = null, modifier = Modifier.size(16.dp), tint = ClaudeTerracotta)
                                Spacer(Modifier.width(6.dp))
                                Text("Antigravity Hub", fontSize = 12.5.sp, color = Color(0xFFE2E8F0), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    // Card 2: Custom MCP Server Configuration
                    ConnectSectionCard(
                        title = "2. Custom MCP Server",
                        subtitle = "अपना Custom MCP URL/पोर्ट जोड़ें (साइन-अप के बाद ऑटो-कनेक्ट होगा)",
                        icon = Icons.Outlined.Dns,
                        accent = Color(0xFFA78BFA),
                        badgeText = customMcpStatus.label,
                        badgeColor = when (customMcpStatus) {
                            AgyMcpManager.McpConnectionStatus.CONNECTED -> QuotaGreen
                            AgyMcpManager.McpConnectionStatus.RETRYING -> Color(0xFFF59E0B)
                            AgyMcpManager.McpConnectionStatus.DISCONNECTED -> Color(0xFF94A3B8)
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = customMcpName,
                                onValueChange = { customMcpName = it },
                                label = { Text("Server Name") },
                                placeholder = { Text("custom-mcp") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(0.42f)
                                    .testTag("post_setup_mcp_name_input"),
                                colors = connectFieldColors()
                            )
                            OutlinedTextField(
                                value = customMcpUrl,
                                onValueChange = { customMcpUrl = it },
                                label = { Text("URL / Port (Optional)") },
                                placeholder = { Text("http://127.0.0.1:8765/mcp") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(0.58f)
                                    .testTag("post_setup_mcp_url_input"),
                                colors = connectFieldColors()
                            )
                        }
                        if (!customMcpDetail.isNullOrBlank()) {
                            Text(
                                text = customMcpDetail.orEmpty(),
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }

                    // Card 3: Live 3-Step Auto-Connect Progress
                    ConnectPipelineStatusCard(state = pipelineState)

                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Bottom Pinned Actions
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (pipelineState.isCompleted && hasTriggeredConnect) {
                        Button(
                            onClick = onFinishAndEnterApp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("post_setup_enter_app_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = QuotaGreen)
                        ) {
                            Text(
                                text = "CineCode-AI में प्रवेश करें",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF04130A)
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Enter CineCode-AI",
                                tint = Color(0xFF04130A),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                hasTriggeredConnect = true
                                chatViewModel.runPostSetupConnectSequence(
                                    profileName = displayName.trim(),
                                    profileEmail = email.trim(),
                                    apiKey = apiKeyInput.trim(),
                                    customMcpName = customMcpName.trim(),
                                    customMcpUrl = customMcpUrl.trim()
                                )
                            },
                            enabled = !pipelineState.isRunning,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("post_setup_connect_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                        ) {
                            if (pipelineState.isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.2.dp,
                                    color = Color.White
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "कनेक्ट हो रहा है…",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            } else {
                                Icon(Icons.Outlined.RocketLaunch, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Sign Up & Connect All (100% Free)",
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = !pipelineState.isRunning) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    chatViewModel.completePostSetupWithSkipOrApiKey(
                                        apiKey = apiKeyInput.trim(),
                                        customMcpName = customMcpName.trim(),
                                        customMcpUrl = customMcpUrl.trim()
                                    )
                                    onFinishAndEnterApp()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("post_setup_use_api_key_button"),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFF334155))
                        ) {
                            Text(
                                text = if (apiKeyInput.isNotBlank()) "API Key के साथ सीधे आगे बढ़ें" else "अभी Skip करें और ऐप खोलें",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFCBD5E1)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectSectionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    badgeText: String? = null,
    badgeColor: Color = QuotaGreen,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF151B28),
        border = BorderStroke(1.dp, Color(0xFF253047))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
                    }
                    Column {
                        Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(subtitle, fontSize = 11.5.sp, color = Color(0xFF94A3B8), lineHeight = 15.sp)
                    }
                }
                if (!badgeText.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = badgeColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = badgeText,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = badgeColor,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun ConnectPipelineStatusCard(state: PostSetupPipelineState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF131926),
        border = BorderStroke(
            1.dp,
            if (state.isCompleted) QuotaGreen.copy(alpha = 0.45f) else Color(0xFF253047)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = if (state.isCompleted) QuotaGreen else Color(0xFF38BDF8),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "3. ऑटो-कनेक्शन स्टेटस (Google + MCP + Plugins)",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            PipelineStepRow(
                label = "Google & Antigravity Hub",
                detail = state.googleAgyDetail,
                status = state.googleAgyStatus
            )
            PipelineStepRow(
                label = "Custom MCP & Browser MCP",
                detail = state.mcpDetail,
                status = state.mcpStatus
            )
            PipelineStepRow(
                label = "All Plugins & Mini Apps",
                detail = state.pluginsDetail,
                status = state.pluginsStatus
            )
        }
    }
}

@Composable
private fun PipelineStepRow(
    label: String,
    detail: String,
    status: PostSetupStepStatus
) {
    val dotColor = when (status) {
        PostSetupStepStatus.CONNECTED -> QuotaGreen
        PostSetupStepStatus.RUNNING -> Color(0xFF38BDF8)
        PostSetupStepStatus.FALLBACK -> Color(0xFFF59E0B)
        PostSetupStepStatus.DISCONNECTED -> Color(0xFF94A3B8)
        PostSetupStepStatus.IDLE -> Color(0xFF475569)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp),
            contentAlignment = Alignment.Center
        ) {
            when (status) {
                PostSetupStepStatus.RUNNING -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = dotColor
                )
                PostSetupStepStatus.CONNECTED -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Connected",
                    tint = dotColor,
                    modifier = Modifier.size(18.dp)
                )
                else -> Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFF1F5F9)
            )
            Text(
                text = detail,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = Color(0xFF94A3B8)
            )
        }
    }
}

@Composable
private fun connectFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color(0xFFE2E8F0),
    focusedBorderColor = ClaudeTerracotta,
    unfocusedBorderColor = Color(0xFF334155),
    focusedLabelColor = ClaudeTerracotta,
    unfocusedLabelColor = Color(0xFF94A3B8),
    cursorColor = ClaudeTerracotta,
    focusedPlaceholderColor = Color(0xFF64748B),
    unfocusedPlaceholderColor = Color(0xFF64748B)
)
