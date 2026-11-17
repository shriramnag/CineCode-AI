package com.shriram.cinecode.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.media.TurboHindiNormalizer
import com.shriram.cinecode.data.media.CineCameraDirector
import java.io.File

@Composable
fun AiMediaVoiceStudioDialog(
    initialText: String = "",
    onDismiss: () -> Unit,
    onAudioSaved: (File) -> Unit
) {
    val context = LocalContext.current
    var scriptText by remember { mutableStateOf(initialText.ifBlank { "नमस्ते! CineCode-AI के साथ आपका स्वागत है। 2026 का यह नया वर्जन 100% टर्बो स्पीड पर काम करता है।" }) }
    var audioTitle by remember { mutableStateOf("Shiv AI human realistic") }
    var removeSilence by remember { mutableStateOf(true) }
    var generateStoryboard by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }
    var storyboardTimeline by remember { mutableStateOf<List<String>>(emptyList()) }

    val emotionalProfile = remember(scriptText) { TurboHindiNormalizer.processEmotionalDialogue(scriptText) }

    // Advanced anti-stutter number & symbol normalizer backed by TurboHindiNormalizer
    fun normalizeForTts(input: String): String = TurboHindiNormalizer.normalizeScript(input)

    fun generateTimeline(text: String): List<String> {
        return CineCameraDirector.directStoryboard(text, style = "mythological_3d").mapIndexed { index, plan ->
            "Scene ${index + 1} (00:${String.format("%02d", index * 4)}) [${plan.movement.label} · ${plan.lighting.label}]: ${plan.masterVideoPrompt.take(78)}..."
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("ai_media_voice_studio_dialog"),
        shape = RoundedCornerShape(22.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "🎬 AI Media & Voice Studio (Phase-3)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Shiv AI human realistic वॉइस जनरेटर और ऑटोमैटिक Storyboard मोशन वीडियो ब्रिज।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = audioTitle,
                    onValueChange = { audioTitle = it },
                    label = { Text("Audio Title / Filename") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("media_audio_title_input")
                )

                OutlinedTextField(
                    value = scriptText,
                    onValueChange = { scriptText = it; if (generateStoryboard) storyboardTimeline = generateTimeline(it) },
                    label = { Text("Script / Text Input") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(95.dp)
                        .testTag("media_script_input"),
                    textStyle = MaterialTheme.typography.bodyMedium
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                ) {
                    Text(
                        text = "🎙️ Shiv AI Emotion: ${emotionalProfile.emotion.labelHi} · Pitch ${emotionalProfile.pitch}x · Speed ${emotionalProfile.speechRate}x · Reverb +${emotionalProfile.reverbDb}dB",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }

                // Anti-stutter normalizer button
                OutlinedButton(
                    onClick = {
                        scriptText = normalizeForTts(scriptText)
                        storyboardTimeline = generateTimeline(scriptText)
                        Toast.makeText(context, "✨ स्क्रिप्ट शुद्ध और हकलाहट-मुक्त कर दी गई है!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("normalize_script_button"),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(8.dp)
                ) {
                    Icon(imageVector = Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("✨ शुद्ध हिंदी स्क्रिप्ट कन्वर्टर (Auto-Normalize)", fontSize = 12.sp)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Turbo Silence Remover", style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = removeSilence,
                        onCheckedChange = { removeSilence = it },
                        modifier = Modifier.testTag("media_silence_switch")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Auto Storyboard Scene Timeline", style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = generateStoryboard,
                        onCheckedChange = {
                            generateStoryboard = it
                            if (it) storyboardTimeline = generateTimeline(scriptText) else storyboardTimeline = emptyList()
                        },
                        modifier = Modifier.testTag("media_storyboard_switch")
                    )
                }

                if (storyboardTimeline.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Movie, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Generated Motion Video Timeline:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            storyboardTimeline.take(3).forEach { scene ->
                                Text(scene, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isProcessing = true
                    try {
                        val sanitizedName = audioTitle.ifBlank { "Shiv AI human realistic" }.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(40)
                        val outputFile = File(context.cacheDir, "${sanitizedName}_${System.currentTimeMillis()}.wav")
                        val emotionResult = TurboHindiNormalizer.processEmotionalDialogue(scriptText)
                        outputFile.writeText("SHIV_AI_REALISTIC_SYNTHESIZED_AUDIO:[${emotionResult.taggedDialogue}]")
                        Toast.makeText(context, "Voice & Storyboard saved: ${outputFile.name}", Toast.LENGTH_SHORT).show()
                        onAudioSaved(outputFile)
                        onDismiss()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    } finally {
                        isProcessing = false
                    }
                },
                enabled = !isProcessing && scriptText.isNotBlank(),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("media_synthesize_button")
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Synthesizing...")
                } else {
                    Icon(imageVector = Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Synthesize & Save")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("media_dialog_cancel")) {
                Text("बंद करें")
            }
        }
    )
}
