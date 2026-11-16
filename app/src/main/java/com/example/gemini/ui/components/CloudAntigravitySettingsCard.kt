package com.example.gemini.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.remote.CloudAntigravityEngine

@Composable
fun CloudAntigravitySettingsCard(
    cloudEngine: CloudAntigravityEngine,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentKey by cloudEngine.currentApiKey.collectAsState()
    val isCloudActive by cloudEngine.isCloudActive.collectAsState()

    var apiKeyInput by remember { mutableStateOf(currentKey) }
    var passwordVisible by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    // Keep input synced if loaded externally
    LaunchedEffect(currentKey) {
        if (apiKeyInput.isEmpty() && currentKey.isNotEmpty()) {
            apiKeyInput = currentKey
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = "Cloud Icon",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Google AI Studio Cloud Bridge",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Badge(containerColor = if (isCloudActive) Color(0xFF4CAF50) else Color(0xFFFF9800)) {
                    Text(
                        text = if (isCloudActive) "API Active 🟢 (Cloud-Native)" else "Offline Local Mode",
                        color = Color.White,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Enter your Google AI Studio API Key below to bypass local port 1235 and connect directly to Gemini 4 (Hydrogen/Carbon) & Flash models from mobile.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it },
                label = { Text("🔑 Google AI Studio API Key") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle key visibility"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = {
                        cloudEngine.saveApiKey(apiKeyInput)
                        statusMessage = "API Key saved successfully! Cloud Native Connected."
                    }
                ) {
                    Icon(imageVector = Icons.Default.Key, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save & Connect Direct")
                }
            }

            if (statusMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = statusMessage,
                    color = Color(0xFF4CAF50),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
