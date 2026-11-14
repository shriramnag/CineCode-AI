package com.shriram.cinecode.ui.ide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shriram.cinecode.data.ai.SelfHealingCoordinator
import com.shriram.cinecode.data.ai.SelfHealingRunnerState
import com.shriram.cinecode.data.local.LocalPtySession
import com.shriram.cinecode.theme.AppColors
import com.shriram.cinecode.theme.ClaudeTerracotta
import kotlinx.coroutines.delay

/**
 * IDE कोड रनर टर्मिनल के ऊपर दिखने वाला Self-Healing बैनर।
 * कमांड समाप्त होते ही एग्जिट कोड और अंतिम 80 लाइनों की जांच करता है और एरर आने पर
 * तुरंत 1-टैप AI Auto-Fix और Re-Run विकल्प देता है।
 */
@Composable
fun SelfHealingRunnerBanner(
    session: LocalPtySession,
    command: String,
    activeFilePath: String?,
    activeFileContent: String?,
    healAttemptCount: Int,
    onAutoFixInChat: (prompt: String) -> Unit,
    onReRun: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isExited by session.isExited.collectAsState()
    val exitCode by session.exitCode.collectAsState()

    var runnerState by remember(session.id) { mutableStateOf<SelfHealingRunnerState>(SelfHealingRunnerState.Idle) }

    LaunchedEffect(session.id, isExited, exitCode) {
        if (!isExited) {
            runnerState = SelfHealingRunnerState.Idle
            return@LaunchedEffect
        }
        // Let final emulator output flush on main thread before inspecting tail
        delay(180)
        val tail = session.readCleanTranscriptTail(maxLines = 80)
        val detected = SelfHealingCoordinator.detectFailure(exitCode, tail)
        runnerState = when {
            detected == null -> SelfHealingRunnerState.Succeeded
            healAttemptCount >= SelfHealingRunnerState.MAX_HEAL_ATTEMPTS ->
                SelfHealingRunnerState.Exhausted(detected)
            else -> SelfHealingRunnerState.ErrorDetected(
                error = detected,
                attemptCount = healAttemptCount + 1
            )
        }
    }

    AnimatedVisibility(
        visible = runnerState !is SelfHealingRunnerState.Idle,
        enter = fadeIn() + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier.fillMaxWidth()
    ) {
        when (val state = runnerState) {
            is SelfHealingRunnerState.Succeeded -> {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("self_heal_success_banner"),
                    shape = RoundedCornerShape(12.dp),
                    color = AppColors.success.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, AppColors.success.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = "Success",
                                tint = AppColors.success,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "कोड सफलतापूर्वक रन हुआ (Exit 0)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = onReRun,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("self_heal_rerun_success_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Re-Run",
                                tint = AppColors.success,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("फिर चलाएं", fontSize = 11.5.sp, color = Color.White)
                        }
                    }
                }
            }

            is SelfHealingRunnerState.ErrorDetected,
            is SelfHealingRunnerState.Exhausted -> {
                val detected = if (state is SelfHealingRunnerState.ErrorDetected) state.error else (state as SelfHealingRunnerState.Exhausted).error
                val attemptText = if (state is SelfHealingRunnerState.ErrorDetected) {
                    "प्रयास ${state.attemptCount}/${state.maxAttempts}"
                } else {
                    "अधिकतम ${SelfHealingRunnerState.MAX_HEAL_ATTEMPTS} प्रयास पूरे"
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("self_heal_error_banner"),
                    shape = RoundedCornerShape(14.dp),
                    color = AppColors.danger.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, AppColors.danger.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ErrorOutline,
                                    contentDescription = "Error Detected",
                                    tint = AppColors.danger,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = "कोड में एरर मिला${detected.exitCode?.let { " (Exit $it)" } ?: ""}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = attemptText,
                                        fontSize = 11.sp,
                                        color = AppColors.warning,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(ClipData.newPlainText("Error Log", detected.errorSnippet))
                                    Toast.makeText(context, "एरर लॉग कॉपी हो गया", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("self_heal_copy_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = "Copy Error",
                                    tint = Color.LightGray,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Text(
                            text = detected.headline,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val nextAttempt = (healAttemptCount + 1).coerceAtMost(SelfHealingRunnerState.MAX_HEAL_ATTEMPTS)
                                    val prompt = SelfHealingCoordinator.buildAutoFixPrompt(
                                        filePath = activeFilePath,
                                        fileContent = activeFileContent,
                                        command = command,
                                        error = detected,
                                        attemptCount = nextAttempt
                                    )
                                    runnerState = SelfHealingRunnerState.HealingDispatched(nextAttempt)
                                    onAutoFixInChat(prompt)
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ClaudeTerracotta,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("self_heal_autofix_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AutoFixHigh,
                                    contentDescription = "Auto-Fix with AI",
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "AI से ठीक कराएं (Auto-Fix)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            OutlinedButton(
                                onClick = onReRun,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("self_heal_rerun_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Refresh,
                                    contentDescription = "Re-Run",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "री-रन",
                                    fontSize = 12.sp,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            else -> Unit
        }
    }
}
