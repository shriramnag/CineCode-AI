package com.shriram.cinecode.ui.browser.mcp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shriram.cinecode.ui.browser.BrowserSessionManager
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Browser MCP tool dispatcher + interactive CDP Inspector Dialog for CineCode-AI Browser.
 */
object BrowserMcp {
    fun isReady(tabId: String? = null): Boolean {
        return runCatching { BrowserSessionManager.instance.getTab(tabId) != null }.getOrDefault(false)
    }

    suspend fun executeTool(toolName: String, args: JSONObject, tabId: String? = null): Result<String> {
        return runCatching {
            val tab = BrowserSessionManager.instance.getTab(tabId)
            if (tab == null && toolName != "cdp_console") {
                return Result.failure(IllegalStateException("Browser MCP disconnected: no active browser tab available."))
            }
            when (toolName) {
                "cdp_snapshot" -> WebViewCdp.getPageSnapshot(tabId).map { it.toString(2) }.getOrThrow()
                "cdp_elements" -> WebViewCdp.getInteractiveElements(tabId).getOrThrow()
                "cdp_click" -> {
                    val selector = args.optString("selector").trim()
                    if (selector.isBlank()) throw IllegalArgumentException("CSS selector is required for cdp_click.")
                    WebViewCdp.clickSelector(selector, tabId).getOrThrow()
                }
                "cdp_fill" -> {
                    val selector = args.optString("selector").trim()
                    if (selector.isBlank()) throw IllegalArgumentException("CSS selector is required for cdp_fill.")
                    WebViewCdp.fillInput(selector, args.optString("value"), tabId).getOrThrow()
                }
                "cdp_eval" -> {
                    val script = args.optString("script").trim()
                    if (script.isBlank()) throw IllegalArgumentException("JavaScript script is required for cdp_eval.")
                    WebViewCdp.evalJs(script, tabId).getOrThrow()
                }
                "cdp_console" -> {
                    val logs = tab?.consoleLogs?.toList()?.takeLast(30)?.joinToString("\n") {
                        "[${it.level}] ${it.message}"
                    }.orEmpty()
                    logs.ifBlank { "No console logs captured on this tab." }
                }
                else -> throw IllegalArgumentException("Unknown Browser MCP tool: $toolName")
            }
        }
    }
}

@Composable
fun BrowserMcpPanelDialog(
    tabId: String?,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectorText by remember { mutableStateOf("button, a") }
    var jsText by remember { mutableStateOf("document.title + ' — ' + location.href") }
    var outputText by remember { mutableStateOf("Tap an MCP / CDP action below to inspect the live page.") }
    var isBusy by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("Browser MCP & CDP", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Live DOM, Click, JS & Console Inspector", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            isBusy = true
                            scope.launch {
                                outputText = BrowserMcp.executeTool("cdp_snapshot", JSONObject(), tabId)
                                    .getOrElse { "Error: ${it.message}" }
                                isBusy = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("DOM Snapshot", fontSize = 12.sp) }

                    OutlinedButton(
                        onClick = {
                            isBusy = true
                            scope.launch {
                                outputText = BrowserMcp.executeTool("cdp_elements", JSONObject(), tabId)
                                    .getOrElse { "Error: ${it.message}" }
                                isBusy = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Elements", fontSize = 12.sp) }

                    OutlinedButton(
                        onClick = {
                            isBusy = true
                            scope.launch {
                                outputText = BrowserMcp.executeTool("cdp_console", JSONObject(), tabId)
                                    .getOrElse { "Error: ${it.message}" }
                                isBusy = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Console", fontSize = 12.sp) }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = selectorText,
                        onValueChange = { selectorText = it },
                        label = { Text("CSS Selector") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            isBusy = true
                            scope.launch {
                                outputText = BrowserMcp.executeTool(
                                    "cdp_click",
                                    JSONObject().put("selector", selectorText),
                                    tabId
                                ).getOrElse { "Error: ${it.message}" }
                                isBusy = false
                            }
                        }
                    ) {
                        Icon(Icons.Outlined.TouchApp, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Click")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = jsText,
                        onValueChange = { jsText = it },
                        label = { Text("Evaluate JS") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            isBusy = true
                            scope.launch {
                                outputText = BrowserMcp.executeTool(
                                    "cdp_eval",
                                    JSONObject().put("script", jsText),
                                    tabId
                                ).getOrElse { "Error: ${it.message}" }
                                isBusy = false
                            }
                        }
                    ) {
                        Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Run")
                    }
                }

                if (isBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = outputText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}
