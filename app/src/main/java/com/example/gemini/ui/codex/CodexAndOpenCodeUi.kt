package com.shriram.cinecode.ui.codex

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shriram.cinecode.data.agent.codex.CodexPreferences
import com.shriram.cinecode.data.agent.opencode.OpenCodeModelsCatalog
import com.shriram.cinecode.data.agent.opencode.OpenCodePreferences
import com.shriram.cinecode.domain.model.AiModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenCodeAndCodexModelSheet(
    title: String,
    selectedModelId: String,
    models: List<AiModel>,
    onSelectModel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    val filters = listOf("All", "Coder", "Reasoning", "4GB RAM", "Free")

    val sourceModels = remember(models) {
        models.ifEmpty { OpenCodeModelsCatalog.models }
    }

    val filtered = remember(sourceModels, query, filter) {
        sourceModels.filter { m ->
            val matchQuery = query.isBlank() ||
                m.displayName.contains(query, true) ||
                m.id.contains(query, true) ||
                m.description.contains(query, true)
            val matchFilter = when (filter) {
                "Coder" -> m.id.contains("coder", true) || m.id.contains("code", true) || m.id.contains("devstral", true)
                "Reasoning" -> m.supportsThinking
                "4GB RAM" -> m.description.contains("4GB RAM", true) || m.id.contains("3b", true) || m.id.contains("4b", true) || m.id.contains("1.5b", true)
                "Free" -> m.id.contains("free", true) || m.description.contains("Free", true)
                else -> true
            }
            matchQuery && matchFilter
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "$title (${sourceModels.size} Models)",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "100% Free & Open-Source • बिना किसी पेड पेवॉल के",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Search Qwen, DeepSeek, Llama, Gemma…") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                filters.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f, fontSize = 12.sp) }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filtered, key = { it.id }) { m ->
                    val isSelected = m.id == selectedModelId
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onSelectModel(m.id)
                                onDismiss()
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = m.displayName,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${m.id} • ${m.description}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isSelected) {
                                Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CodexAndOpenCodeSettingsScreen(
    codexPrefs: CodexPreferences,
    openCodePrefs: OpenCodePreferences,
    cardBg: Color,
    cardBorder: BorderStroke
) {
    val context = LocalContext.current
    val codexUrl by codexPrefs.baseUrl.collectAsState()
    val codexKey by codexPrefs.apiKey.collectAsState()
    val codexModel by codexPrefs.selectedModel.collectAsState()

    val ocUrl by openCodePrefs.baseUrl.collectAsState()
    val ocKey by openCodePrefs.apiKey.collectAsState()
    val ocModel by openCodePrefs.selectedModel.collectAsState()

    var codexUrlInput by remember(codexUrl) { mutableStateOf(codexUrl) }
    var codexKeyInput by remember(codexKey) { mutableStateOf(codexKey) }
    var ocUrlInput by remember(ocUrl) { mutableStateOf(ocUrl) }
    var ocKeyInput by remember(ocKey) { mutableStateOf(ocKey) }
    var showOpenCodeCatalog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Codex Card
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Terminal, contentDescription = null, tint = Color(0xFF10B981))
                    Column {
                        Text("Codex Agent (100% Free Presets)", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("Active Model: $codexModel", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CodexPreferences.PRESETS.forEach { preset ->
                        AssistChip(
                            onClick = {
                                codexUrlInput = preset.baseUrl
                                codexPrefs.setBaseUrl(preset.baseUrl)
                                codexPrefs.setSelectedModel(preset.defaultModel)
                                Toast.makeText(context, "Preset: ${preset.name}", Toast.LENGTH_SHORT).show()
                            },
                            label = { Text(preset.name, fontSize = 11.sp) }
                        )
                    }
                }

                OutlinedTextField(
                    value = codexUrlInput,
                    onValueChange = { codexUrlInput = it },
                    label = { Text("Codex Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = codexKeyInput,
                    onValueChange = { codexKeyInput = it },
                    label = { Text("Free API Key (Optional for Ollama / AI Studio Fallback)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        codexPrefs.setBaseUrl(codexUrlInput)
                        codexPrefs.setApiKey(codexKeyInput)
                        Toast.makeText(context, "Codex settings saved!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Save Codex Config")
                }
            }
        }

        // OpenCode Card (200+ Free Models)
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = Color(0xFF8B5CF6))
                    Column {
                        Text("OpenCode Agent (200+ Free Models)", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("Active: $ocModel", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                OutlinedTextField(
                    value = ocUrlInput,
                    onValueChange = { ocUrlInput = it },
                    label = { Text("OpenCode Endpoint URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ocKeyInput,
                    onValueChange = { ocKeyInput = it },
                    label = { Text("API Key (Optional for Local Ollama / AI Studio Fallback)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { showOpenCodeCatalog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Browse 200+ Models")
                    }
                    Button(
                        onClick = {
                            openCodePrefs.setBaseUrl(ocUrlInput)
                            openCodePrefs.setApiKey(ocKeyInput)
                            Toast.makeText(context, "OpenCode settings saved!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Save OpenCode")
                    }
                }
            }
        }
    }

    if (showOpenCodeCatalog) {
        OpenCodeAndCodexModelSheet(
            title = "OpenCode Free Models",
            selectedModelId = ocModel,
            models = OpenCodeModelsCatalog.models,
            onSelectModel = { openCodePrefs.setSelectedModel(it) },
            onDismiss = { showOpenCodeCatalog = false }
        )
    }
}
