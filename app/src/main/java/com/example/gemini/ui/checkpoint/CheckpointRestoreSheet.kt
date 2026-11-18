package com.shriram.cinecode.ui.checkpoint

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shriram.cinecode.data.checkpoint.ProjectCheckpoint
import com.shriram.cinecode.data.checkpoint.ProjectCheckpoints
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckpointRestoreSheet(
    projectPath: String,
    projectName: String,
    onRestored: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checkpoints by ProjectCheckpoints.checkpoints.collectAsState()
    var labelInput by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var confirmTarget by remember { mutableStateOf<ProjectCheckpoint?>(null) }

    LaunchedEffect(projectPath) {
        ProjectCheckpoints.refresh(context, projectPath.takeIf { it.isNotBlank() })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Project Checkpoints", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (projectName.isNotBlank()) "$projectName · एक टैप में सुरक्षित वर्जन रीस्टोर करें" else "कोई प्रोजेक्ट चुनें",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (projectPath.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { labelInput = it },
                        placeholder = { Text("Snapshot नाम (e.g. Before refactor)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (!isBusy) {
                                isBusy = true
                                scope.launch {
                                    val res = ProjectCheckpoints.createCheckpoint(
                                        context,
                                        projectPath,
                                        labelInput.ifBlank { "Safe Snapshot" }
                                    )
                                    isBusy = false
                                    res.onSuccess {
                                        labelInput = ""
                                        Toast.makeText(context, "Checkpoint saved (${it.fileCount} files)", Toast.LENGTH_SHORT).show()
                                    }.onFailure {
                                        Toast.makeText(context, "Snapshot failed: ${it.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        enabled = !isBusy,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Outlined.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save")
                    }
                }
            }

            if (checkpoints.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "अभी कोई Checkpoint सेव नहीं है। कोड बदलने से पहले 'Save' दबाएँ।",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(checkpoints, key = { it.id }) { cp ->
                        val dateText = remember(cp.createdAt) {
                            SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(cp.createdAt))
                        }
                        val sizeKb = (cp.archiveSizeBytes / 1024L).coerceAtLeast(1L)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = cp.label,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "$dateText • ${cp.fileCount} files • ${sizeKb}KB",
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = {
                                    scope.launch { ProjectCheckpoints.deleteCheckpoint(context, cp.id) }
                                }) {
                                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                                FilledTonalButton(
                                    onClick = { confirmTarget = cp },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Restore", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirmTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = { Text("Restore \"${target.label}\"?") },
            text = { Text("यह ${target.fileCount} फाइलों को इस सुरक्षित स्नैपशॉट पर वापस लाएगा।") },
            confirmButton = {
                Button(onClick = {
                    val cp = target
                    confirmTarget = null
                    isBusy = true
                    scope.launch {
                        val res = ProjectCheckpoints.restoreCheckpoint(context, cp)
                        isBusy = false
                        res.onSuccess { count ->
                            Toast.makeText(context, "Restored $count files!", Toast.LENGTH_SHORT).show()
                            onRestored()
                            onDismiss()
                        }.onFailure {
                            Toast.makeText(context, "Restore failed: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Restore Now") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTarget = null }) { Text("Cancel") }
            }
        )
    }
}
