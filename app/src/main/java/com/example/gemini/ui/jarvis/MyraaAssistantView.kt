package com.shriram.cinecode.ui.jarvis

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.remote.GeminiLiveVoiceClient
import com.example.gemini.data.remote.LiveServerStatus
import com.example.gemini.data.remote.MyraaTranscriptItem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private val MyraaBgTop = Color(0xFF0E1628)
private val MyraaBgBottom = Color(0xFF070B14)
private val MyraaSurface = Color(0xFF141E33)
private val MyraaUserBubble = Color(0xFF13334C)
private val MyraaAiBubble = Color(0xFF161D31)

private val MYRAA_QUICK_ACTIONS = listOf(
    "हिंदी में समझाओ",
    "कोड चेक करो",
    "प्रोजेक्ट स्टेटस",
    "नया टास्क बनाओ",
    "चैट समरी दो"
)

/**
 * Clean dark-mode UI for मायरा (Myraa) Assistant with:
 * - Top header with Myraa branding, live server connection status, and real-time voice waveform
 * - Central scrollable LazyColumn for live User & Myraa transcript cards + Quick Action Chips
 * - Bottom control bar with Mic, Speaker/Interrupt (Barge-in), and Close/Minimize buttons
 */
@Composable
fun MyraaAssistantView(
    liveClient: GeminiLiveVoiceClient,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onQuickAgentAction: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isLiveActive by liveClient.isLiveActive.collectAsState()
    val liveStatus by liveClient.liveStatus.collectAsState()
    val audioLevel by liveClient.audioLevel.collectAsState()
    val transcripts by liveClient.transcripts.collectAsState()
    val errorMessage by liveClient.errorMessage.collectAsState()
    val isMicMuted by liveClient.isMicMuted.collectAsState()
    val isSpeakerEnabled by liveClient.isSpeakerEnabled.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            liveClient.clearError()
            liveClient.startRealtimeSession()
        }
    }

    LaunchedEffect(transcripts.size) {
        if (transcripts.isNotEmpty()) {
            listState.animateScrollToItem(transcripts.lastIndex)
        }
    }

    val statusColor by animateColorAsState(
        targetValue = when (liveStatus) {
            LiveServerStatus.CONNECTED -> Green
            LiveServerStatus.LISTENING -> Cyan
            LiveServerStatus.SPEAKING -> Violet
            LiveServerStatus.CONNECTING, LiveServerStatus.RECONNECTING -> Amber
            LiveServerStatus.OFFLINE -> Color(0xFF94A3B8)
        },
        label = "myraa_status_color"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MyraaBgTop, MyraaBgBottom)))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // ---- Top Header: मायरा (Myraa) + Connection Status + Close
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.16f))
                    .border(1.5.dp, statusColor.copy(alpha = 0.65f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "मायरा (Myraa)",
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    MyraaStatusPill(
                        label = liveStatus.displayLabel,
                        color = statusColor
                    )
                }
                Text(
                    text = when (liveStatus) {
                        LiveServerStatus.LISTENING -> "Google Live सुन रही है… बोलिए"
                        LiveServerStatus.SPEAKING -> "मायरा बोल रही है… रोकने के लिए Interrupt दबाएँ"
                        LiveServerStatus.RECONNECTING -> "लाइव सर्वर से दोबारा जुड़ रही है…"
                        LiveServerStatus.CONNECTING -> "Google Live सर्वर से कनेक्ट हो रहा है…"
                        LiveServerStatus.CONNECTED -> "Google Live तैयार है"
                        LiveServerStatus.OFFLINE -> "लाइव वॉइस शुरू करने के लिए माइक दबाएँ"
                    },
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onOpenSettings)
                    .testTag("myraa_settings_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "मायरा सेटिंग्स",
                    tint = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.size(21.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose)
                    .testTag("myraa_header_close_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "बंद करें",
                    tint = Color.White.copy(alpha = 0.78f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---- Real-Time Voice Waveform Card
        MyraaLiveWaveformBar(
            status = liveStatus,
            audioLevel = audioLevel,
            accent = statusColor,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MyraaSurface)
                .border(1.dp, statusColor.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )

        // ---- Non-blocking API Key / Error Notice
        if (!errorMessage.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Danger.copy(alpha = 0.14f))
                    .border(1.dp, Danger.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = errorMessage.orEmpty(),
                    color = Color(0xFFFCA5A5),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "सेटिंग्स",
                    color = Cyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            liveClient.clearError()
                            onOpenSettings()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---- Central Scrollable Transcript List
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.025f))
                .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(18.dp))
        ) {
            if (transcripts.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "नमस्ते! मैं मायरा (Myraa) हूँ",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "नीचे माइक बटन दबाएँ और हिंदी या Hinglish में सीधे Google Live वॉइस से बात करें — या क्विक एक्शन चुनें।",
                        color = Color.White.copy(alpha = 0.58f),
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("myraa_transcript_list")
                ) {
                    items(transcripts, key = { it.id }) { item ->
                        MyraaTranscriptCard(item = item)
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---- Quick Action Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MYRAA_QUICK_ACTIONS.forEach { actionText ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MyraaSurface)
                        .border(1.dp, Cyan.copy(alpha = 0.35f), RoundedCornerShape(50))
                        .clickable {
                            liveClient.sendTextTurn(actionText)
                            onQuickAgentAction?.invoke(actionText)
                        }
                        .padding(horizontal = 13.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = actionText,
                        color = Color.White.copy(alpha = 0.88f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---- Compact Text Input Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = {
                    Text(
                        "मायरा से कुछ पूछें…",
                        color = Color.White.copy(alpha = 0.42f),
                        fontSize = 13.5.sp
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("myraa_text_input"),
                maxLines = 3,
                shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (inputText.isNotBlank()) {
                        liveClient.sendTextTurn(inputText)
                        inputText = ""
                    }
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Cyan,
                    focusedBorderColor = Cyan.copy(alpha = 0.7f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
                    focusedContainerColor = Color.White.copy(alpha = 0.04f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.03f)
                )
            )
            if (inputText.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Cyan.copy(alpha = 0.22f))
                        .border(1.dp, Cyan.copy(alpha = 0.6f), CircleShape)
                        .clickable {
                            liveClient.sendTextTurn(inputText)
                            inputText = ""
                        }
                        .testTag("myraa_send_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "संदेश भेजें",
                        tint = Cyan,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- Bottom Control Bar: Speaker/Interrupt, Mic, Close/Minimize
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(MyraaSurface)
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(22.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Speaker / Interrupt (Barge-in) Button
            val isSpeaking = liveStatus == LiveServerStatus.SPEAKING
            val interruptTint = when {
                isSpeaking -> Amber
                isSpeakerEnabled -> Color.White.copy(alpha = 0.85f)
                else -> Color(0xFF94A3B8)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        if (isSpeaking) {
                            liveClient.interruptPlayback()
                        } else {
                            liveClient.toggleSpeakerEnabled()
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("myraa_interrupt_button")
                    .semantics {
                        contentDescription = if (isSpeaking) "मायरा को रोकें (Interrupt)" else "स्पीकर टॉगल करें"
                    }
            ) {
                Icon(
                    imageVector = when {
                        isSpeaking -> Icons.Filled.PauseCircle
                        isSpeakerEnabled -> Icons.AutoMirrored.Filled.VolumeUp
                        else -> Icons.AutoMirrored.Filled.VolumeOff
                    },
                    contentDescription = null,
                    tint = interruptTint,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when {
                        isSpeaking -> "Interrupt"
                        isSpeakerEnabled -> "Speaker"
                        else -> "Muted"
                    },
                    color = interruptTint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // 2. Primary Live Mic Toggle Button
            val micBg = when {
                isLiveActive && !isMicMuted -> Danger
                isLiveActive && isMicMuted -> Amber
                else -> Cyan
            }
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(micBg.copy(alpha = 0.22f))
                    .border(2.dp, micBg, CircleShape)
                    .clickable {
                        if (isLiveActive) {
                            liveClient.stopLiveSession()
                        } else if (!liveClient.hasMicPermission()) {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            liveClient.startRealtimeSession()
                        }
                    }
                    .testTag("myraa_mic_button")
                    .semantics {
                        contentDescription = if (isLiveActive) "लाइव माइक बंद करें" else "मायरा लाइव वॉइस शुरू करें"
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        isLiveActive && isMicMuted -> Icons.Filled.MicOff
                        isLiveActive -> Icons.Filled.Stop
                        else -> Icons.Filled.Mic
                    },
                    contentDescription = null,
                    tint = micBg,
                    modifier = Modifier.size(28.dp)
                )
            }

            // 3. Close / Minimize Button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        onClose()
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("myraa_close_button")
                    .semantics {
                        contentDescription = "मायरा पैनल मिनिमाइज़ या बंद करें"
                    }
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.78f),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Minimize",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun MyraaStatusPill(label: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            color = color,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MyraaTranscriptCard(item: MyraaTranscriptItem) {
    val isUser = item.isUser
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp
                    )
                )
                .background(if (isUser) MyraaUserBubble else MyraaAiBubble)
                .border(
                    width = 1.dp,
                    color = if (isUser) Cyan.copy(alpha = 0.35f) else Violet.copy(alpha = 0.32f),
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp
                    )
                )
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            Text(
                text = if (isUser) "आप" else "मायरा (Myraa)",
                color = if (isUser) Cyan else Violet,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = item.text,
                color = Color.White.copy(alpha = 0.93f),
                fontSize = 13.5.sp,
                lineHeight = 19.sp
            )
        }
    }
}

@Composable
private fun MyraaLiveWaveformBar(
    status: LiveServerStatus,
    audioLevel: Float,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val isAnimating = status == LiveServerStatus.LISTENING ||
        status == LiveServerStatus.SPEAKING ||
        status == LiveServerStatus.CONNECTING ||
        status == LiveServerStatus.RECONNECTING

    val phase = if (isAnimating) {
        val transition = rememberInfiniteTransition(label = "myraa_waveform")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "myraa_wave_phase"
        ).value
    } else {
        0f
    }

    Canvas(modifier = modifier) {
        val barCount = 24
        val gap = size.width / (barCount * 2f)
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        val centerY = size.height / 2f
        val energy = when {
            !isAnimating -> 0.14f
            status == LiveServerStatus.CONNECTING || status == LiveServerStatus.RECONNECTING -> 0.32f
            else -> (0.22f + audioLevel.coerceIn(0f, 1f) * 0.78f)
        }

        for (i in 0 until barCount) {
            val norm = (i.toFloat() / (barCount - 1)) * 2f - 1f
            val envelope = (1f - abs(norm) * 0.55f).coerceIn(0.25f, 1f)
            val wave = if (isAnimating) {
                (0.55f + 0.45f * sin(phase + i * 0.55f)).coerceIn(0.15f, 1f)
            } else {
                0.28f
            }
            val barH = (size.height * energy * envelope * wave).coerceIn(4f, size.height)
            val x = i * (barWidth + gap)
            drawRoundRect(
                color = accent.copy(alpha = if (isAnimating) 0.85f else 0.35f),
                topLeft = Offset(x, centerY - barH / 2f),
                size = Size(barWidth.coerceAtLeast(2f), barH),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
