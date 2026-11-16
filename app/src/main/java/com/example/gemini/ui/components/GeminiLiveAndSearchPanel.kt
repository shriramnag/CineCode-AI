package com.example.gemini.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.remote.GeminiLiveVoiceClient
import com.example.gemini.data.remote.GeminiSearchGroundingClient
import kotlinx.coroutines.launch

@Composable
fun GeminiLiveAndSearchPanel(
    liveVoiceClient: GeminiLiveVoiceClient,
    searchGroundingClient: GeminiSearchGroundingClient,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var searchResult by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    var voicePrompt by remember { mutableStateOf("Hello Gemini Live, let's build an app!") }
    val isLiveActive by liveVoiceClient.isLiveActive.collectAsState()
    val liveLogs by liveVoiceClient.liveSessionLogs.collectAsState()

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
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = "Search & Live Icon",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Gemini Live (3.8) & Search Grounding (4)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
                Badge(containerColor = Color(0xFF2196F3)) {
                    Text(text = "Active 🟢", color = Color.White, fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Google Search Grounding Section
            Text(text = "1. Google Search Grounding (gemini-4-hydrogen)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Ask anything with Live Web Search...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = {
                    if (searchQuery.isNotBlank()) {
                        isSearching = true
                        scope.launch {
                            searchGroundingClient.queryWithSearchGrounding(searchQuery)
                                .onSuccess { searchResult = it }
                                .onFailure { searchResult = "Error: ${it.localizedMessage}" }
                            isSearching = false
                        }
                    }
                },
                enabled = !isSearching,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isSearching) "Searching Web with Grounding..." else "Query with Google Search Grounding")
            }

            if (searchResult.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Text(
                        text = searchResult,
                        modifier = Modifier.padding(10.dp),
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Gemini Live API Voice Conversation Section
            Text(text = "2. Real-Time Voice Conversation (gemini-3.8-live)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = voicePrompt,
                onValueChange = { voicePrompt = it },
                label = { Text("Speak or type voice turn...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            liveVoiceClient.startLiveVoiceSession(voicePrompt)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Icon(imageVector = Icons.Default.Mic, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Live Turn")
                }

                Button(
                    onClick = { liveVoiceClient.stopLiveSession() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                ) {
                    Icon(imageVector = Icons.Default.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("End Session")
                }
            }

            if (liveLogs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        Text(text = "Live Conversation Transcript:", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        liveLogs.takeLast(5).forEach { log ->
                            Text(text = log, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
