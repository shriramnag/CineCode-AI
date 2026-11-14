package com.shriram.cinecode.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shriram.cinecode.data.local.LocalServerStatus
import com.shriram.cinecode.data.remote.SystemConnectionState
import com.shriram.cinecode.data.remote.SystemStatus
import com.shriram.cinecode.theme.AppColors
import com.shriram.cinecode.theme.ClaudeTerracotta
import com.shriram.cinecode.theme.GeminiBlue

private enum class DashboardVisualState {
    ONLINE,
    STARTING,
    OFFLINE
}

/**
 * अल्ट्रा-मॉडर्न और सरल होम व सर्वर स्टेटस डैशबोर्ड।
 * कम RAM (4GB) पर भी तेज़ परफॉर्मेंस के लिए हल्के स्टेट और MaterialTheme टोकन का उपयोग करता है।
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeStatusDashboard(
    systemConnectionState: SystemConnectionState,
    serverStatus: LocalServerStatus,
    isBridgeOnline: Boolean,
    isLocalToolsInstalled: Boolean,
    isInitialGracePeriod: Boolean,
    activeProjectName: String,
    onStartOrRestartEngine: () -> Unit,
    onOpenServerLogs: () -> Unit,
    onOpenIde: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenSettings: () -> Unit,
    onInsertStarterPrompt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val visualState by remember(systemConnectionState.status, serverStatus, isBridgeOnline, isInitialGracePeriod) {
        derivedStateOf {
            when {
                serverStatus is LocalServerStatus.Starting ||
                    serverStatus is LocalServerStatus.Stopping ||
                    systemConnectionState.status == SystemStatus.STARTING ||
                    systemConnectionState.status == SystemStatus.ACQUIRING_CSRF ||
                    systemConnectionState.status == SystemStatus.CHECKING_AUTH ||
                    (systemConnectionState.status == SystemStatus.OFFLINE && isInitialGracePeriod) -> DashboardVisualState.STARTING

                systemConnectionState.status == SystemStatus.READY ||
                    (serverStatus is LocalServerStatus.Running && isBridgeOnline) -> DashboardVisualState.ONLINE

                else -> DashboardVisualState.OFFLINE
            }
        }
    }

    val statusColor = when (visualState) {
        DashboardVisualState.ONLINE -> AppColors.success
        DashboardVisualState.STARTING -> AppColors.warning
        DashboardVisualState.OFFLINE -> AppColors.danger
    }

    val statusBadgeText = when (visualState) {
        DashboardVisualState.ONLINE -> "ऑनलाइन · Online"
        DashboardVisualState.STARTING -> "तैयार हो रहा है · Starting"
        DashboardVisualState.OFFLINE -> "ऑफलाइन · Offline"
    }

    val friendlyTitle = when (visualState) {
        DashboardVisualState.ONLINE -> "CineCode Studio तैयार है"
        DashboardVisualState.STARTING -> "AI इंजन शुरू हो रहा है…"
        DashboardVisualState.OFFLINE -> "लोकल AI इंजन अभी बंद है"
    }

    val friendlySubtitle = when (visualState) {
        DashboardVisualState.ONLINE -> "प्रोजेक्ट \"$activeProjectName\" के साथ कोडिंग, टर्मिनल या AI चैट शुरू करें।"
        DashboardVisualState.STARTING -> "Go Bridge और वर्कस्पेस कनेक्ट हो रहे हैं, कृपया कुछ सेकंड प्रतीक्षा करें।"
        DashboardVisualState.OFFLINE -> "कोडिंग और AI असिस्टेंट चलाने के लिए नीचे 'इंजन शुरू करें' बटन दबाएं।"
    }

    val infiniteTransition = rememberInfiniteTransition(label = "dashboard_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (visualState == DashboardVisualState.STARTING) 0.3f else 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (visualState == DashboardVisualState.STARTING) 650 else 1400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 600.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Live Server & Bridge Status Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("dashboard_status_card"),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f)),
            tonalElevation = 2.dp
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                statusColor.copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Top Row: Brand + Live Dot Badge
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
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(ClaudeTerracotta.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = "CineCode Studio",
                                    tint = ClaudeTerracotta,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = friendlyTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "CineCode-AI Workspace",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }

                        // Status Pill (🟢/🟡/🔴)
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = statusColor.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .graphicsLayer { alpha = if (visualState != DashboardVisualState.OFFLINE) pulseAlpha else 1f }
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Text(
                                    text = statusBadgeText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = statusColor,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Text(
                        text = friendlySubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )

                    // Smooth startup progress bar
                    AnimatedVisibility(
                        visible = visualState == DashboardVisualState.STARTING,
                        enter = fadeIn() + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = statusColor,
                            trackColor = statusColor.copy(alpha = 0.18f)
                        )
                    }

                    // 3 Clean Subsystem Indicators (Zero technical jargon)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SubsystemStatusChip(
                            title = "AI Hub",
                            isReady = systemConnectionState.isHubOnline,
                            isPending = visualState == DashboardVisualState.STARTING,
                            modifier = Modifier.weight(1f)
                        )
                        SubsystemStatusChip(
                            title = "Go Bridge",
                            isReady = isBridgeOnline,
                            isPending = visualState == DashboardVisualState.STARTING && !isBridgeOnline,
                            modifier = Modifier.weight(1f)
                        )
                        SubsystemStatusChip(
                            title = "Linux Shell",
                            isReady = isLocalToolsInstalled,
                            isPending = false,
                            pendingLabel = "Setup",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Action Buttons Row (48.dp touch target)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onStartOrRestartEngine,
                            enabled = visualState != DashboardVisualState.STARTING,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (visualState == DashboardVisualState.ONLINE) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    ClaudeTerracotta
                                },
                                contentColor = if (visualState == DashboardVisualState.ONLINE) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    Color.White
                                }
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("dashboard_engine_action_btn")
                        ) {
                            Icon(
                                imageVector = if (visualState == DashboardVisualState.ONLINE) Icons.Outlined.Refresh else Icons.Outlined.PlayArrow,
                                contentDescription = if (visualState == DashboardVisualState.ONLINE) "Restart Engine" else "Start Engine",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when (visualState) {
                                    DashboardVisualState.ONLINE -> "रीस्टार्ट (Restart)"
                                    DashboardVisualState.STARTING -> "शुरू हो रहा है…"
                                    DashboardVisualState.OFFLINE -> "इंजन शुरू करें (Start)"
                                },
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedButton(
                            onClick = onOpenServerLogs,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("dashboard_logs_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Description,
                                contentDescription = "View Server Logs",
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "लॉग्स",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        // 2. Quick Action Grid (2x2 Self-Explanatory Cards with Hindi + English Tooltips)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DashboardQuickActionCard(
                    icon = Icons.Outlined.Code,
                    title = "Code IDE",
                    subtitle = "कोड लिखें और चलाएं",
                    accent = ClaudeTerracotta,
                    onClick = onOpenIde,
                    testTag = "dashboard_ide_btn",
                    modifier = Modifier.weight(1f)
                )
                DashboardQuickActionCard(
                    icon = Icons.Outlined.Terminal,
                    title = "Terminal",
                    subtitle = "लिनक्स शेल और कमांड",
                    accent = AppColors.success,
                    onClick = onOpenTerminal,
                    testTag = "dashboard_terminal_btn",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DashboardQuickActionCard(
                    icon = Icons.Outlined.Language,
                    title = "Web Preview",
                    subtitle = "वेब ऐप और एक्सटेंशन",
                    accent = GeminiBlue,
                    onClick = onOpenBrowser,
                    testTag = "dashboard_browser_btn",
                    modifier = Modifier.weight(1f)
                )
                DashboardQuickActionCard(
                    icon = Icons.Outlined.Settings,
                    title = "Settings",
                    subtitle = "मॉडल, थीम व टूल्स",
                    accent = AppColors.warning,
                    onClick = onOpenSettings,
                    testTag = "dashboard_settings_btn",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 3. Quick Prompt Starters (Visible when Engine is Online so users can jump straight in)
        AnimatedVisibility(
            visible = visualState == DashboardVisualState.ONLINE,
            enter = fadeIn() + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut() + shrinkVertically()
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StarterPromptChip(
                    label = "✨ नया प्रोजेक्ट बनाएं",
                    onClick = { onInsertStarterPrompt("मेरे लिए एक नया साफ-सुथरा प्रोजेक्ट स्ट्रक्चर बनाओ और बताओ कि कैसे रन करना है।") }
                )
                StarterPromptChip(
                    label = "🛠️ कोड एरर ठीक करें",
                    onClick = { onInsertStarterPrompt("मेरे वर्तमान प्रोजेक्ट की फाइल्स चेक करो और जो भी एरर या बग हैं उन्हें ठीक करो।") }
                )
                StarterPromptChip(
                    label = "⚡ परफॉर्मेंस तेज़ करें",
                    onClick = { onInsertStarterPrompt("इस प्रोजेक्ट की स्पीड और मेमोरी उपयोग को कम RAM डिवाइस के लिए ऑप्टिमाइज़ करो।") }
                )
            }
        }
    }
}

@Composable
private fun SubsystemStatusChip(
    title: String,
    isReady: Boolean,
    isPending: Boolean,
    pendingLabel: String = "Wait",
    modifier: Modifier = Modifier
) {
    val dotColor = when {
        isReady -> AppColors.success
        isPending -> AppColors.warning
        else -> AppColors.danger
    }
    val stateText = when {
        isReady -> "Ready"
        isPending -> pendingLabel
        else -> "Off"
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stateText,
                    fontSize = 10.sp,
                    color = dotColor,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun DashboardQuickActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        border = BorderStroke(0.9.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accent,
                    modifier = Modifier.size(19.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun StarterPromptChip(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}
