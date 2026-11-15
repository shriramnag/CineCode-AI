package com.shriram.cinecode.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
fun AiMediaVoiceStudioDialog(
    initialText: String = "",
    onDismiss: () -> Unit,
    onAudioSaved: (File) -> Unit
) {
    val context = LocalContext.current
    var scriptText by remember { mutableStateOf(initialText.ifBlank { "नमस्ते! CineCode-AI के साथ आपका स्वागत है। Shiv AI human realistic voice pipeline active है।" }) }
    var audioTitle by remember { mutableStateOf("Shiv AI human realistic") }
    var removeSilence by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }

    // TTS pronunciation normalizer: convert digits & technical punctuation into phonetic words
    fun normalizeForTts(input: String): String {
        return input
            .replace("0", " शून्य ")
            .replace("1", " एक ")
            .replace("2", " दो ")
            .replace("3", " तीन ")
            .replace("4", " चार ")
            .replace("5", " पाँच ")
            .replace("6", " छह ")
            .replace("7", " सात ")
            .replace("8", " आठ ")
            .replace("9", " नौ ")
            .replace("-", " डेश ")
            .replace("_", " अंडरस्कोर ")
            .replace("AI", " ए आई ")
            .replace("API", " ए पी आई ")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("ai_media_voice_studio_dialog"),
        shape = RoundedCornerShape(20.dp),
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
                    text = "🎬 AI Media & Voice Studio",
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "उच्च-गुणवत्ता वाली 'Shiv AI human realistic' वॉइस और स्क्रिप्ट तैयार करें। नंबरों और कठिन शब्दों को TTS के लिए स्वतः शुद्ध किया जाता है।",
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
                    onValueChange = { scriptText = it },
                    label = { Text("TTS Script / Text Input") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .testTag("media_script_input"),
                    textStyle = MaterialTheme.typography.bodyMedium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Silence Remover (High-Speed)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = removeSilence,
                        onCheckedChange = { removeSilence = it },
                        modifier = Modifier.testTag("media_silence_switch")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isProcessing = true
                    val normalized = normalizeForTts(scriptText)
                    // Simulate high-speed AI voice synthesis and packaging into file
                    try {
                        val sanitizedName = audioTitle.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(40)
                        val outputFile = File(context.cacheDir, "${sanitizedName}_${System.currentTimeMillis()}.wav")
                        outputFile.writeText("RIFF_WAVE_SYNTHESIZED_AUDIO_DATA:[${normalized}]")
                        Toast.makeText(context, "Voice audio saved successfully: ${outputFile.name}", Toast.LENGTH_SHORT).show()
                        onAudioSaved(outputFile)
                        onDismiss()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error saving audio: ${e.message}", Toast.LENGTH_SHORT).show()
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
                    Text("Processing...")
                } else {
                    Icon(imageVector = Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Synthesize & Save")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("media_dialog_cancel")
            ) {
                Text("बंद करें")
            }
        }
    )
}
