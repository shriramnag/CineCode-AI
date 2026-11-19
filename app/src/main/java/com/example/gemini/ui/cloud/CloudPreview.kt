package com.shriram.cinecode.ui.cloud

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shriram.cinecode.data.androiddev.AndroidDevManager
import com.shriram.cinecode.data.androiddev.AndroidToolchainStatus
import com.shriram.cinecode.data.github.GitHubManager
import com.shriram.cinecode.data.github.GitHubRepoItem
import com.shriram.cinecode.data.local.LocalTerminalManager
import com.shriram.cinecode.data.pc.PcConnect
import com.shriram.cinecode.data.preferences.AuthPreferences
import com.shriram.cinecode.data.rea.ApkAnalysisReport
import com.shriram.cinecode.data.rea.ReaManager
import com.shriram.cinecode.ui.browser.BrowserSessionManager
import kotlinx.coroutines.launch
import java.text.DecimalFormat

@Composable
fun CloudPreviewDialog(
    initialTab: Int = 0,
    activeProjectPath: String = "",
    onOpenInBrowser: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            CloudPreviewScreen(
                initialTab = initialTab,
                activeProjectPath = activeProjectPath,
                onOpenInBrowser = onOpenInBrowser,
                onOpenTerminal = onOpenTerminal,
                onClose = onDismiss
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudPreviewScreen(
    initialTab: Int = 0,
    activeProjectPath: String = "",
    onOpenInBrowser: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onClose: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(initialTab.coerceIn(0, 3)) }
    val tabs = listOf("Cloud Preview", "Android Builder", "REA APK", "GitHub & PC")

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text("CineCode-AI Studio Tools", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(
                            "Preview • App Builder • APK Analyzer • GitHub & PC",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 12.dp) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                    )
                }
            }

            when (selectedTab) {
                0 -> LiveCloudPreviewTab(onOpenInBrowser = {
                    onClose()
                    onOpenInBrowser()
                })
                1 -> AndroidBuilderTab(onRunInTerminal = { cmd ->
                    onClose()
                    onOpenTerminal()
                })
                2 -> ReaApkAnalyzerTab()
                3 -> GitHubAndPcTab(activeProjectPath = activeProjectPath)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LiveCloudPreviewTab(
    onOpenInBrowser: () -> Unit
) {
    var previewUrl by remember { mutableStateOf("http://127.0.0.1:5173") }
    var activeUrl by remember { mutableStateOf("http://127.0.0.1:5173") }
    var reloadKey by remember { mutableIntStateOf(0) }
    val presets = listOf(
        "Vite :5173" to "http://127.0.0.1:5173",
        "Next/React :3000" to "http://127.0.0.1:3000",
        "Server :8080" to "http://127.0.0.1:8080",
        "Bridge :1234" to "http://127.0.0.1:1234/api/health"
    )

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            presets.forEach { (label, url) ->
                FilterChip(
                    selected = activeUrl == url,
                    onClick = {
                        previewUrl = url
                        activeUrl = url
                        reloadKey++
                    },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = previewUrl,
                onValueChange = { previewUrl = it },
                singleLine = true,
                placeholder = { Text("http://127.0.0.1:3000") },
                modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                activeUrl = previewUrl.trim()
                reloadKey++
            }) { Text("Load") }
            OutlinedIconButton(onClick = {
                BrowserSessionManager.instance.openUrl(activeUrl, newTab = true)
                onOpenInBrowser()
            }) {
                Icon(Icons.Outlined.OpenInNew, contentDescription = "Open in Browser")
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            key(activeUrl, reloadKey) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.cacheMode = WebSettings.LOAD_DEFAULT
                            webViewClient = WebViewClient()
                            webChromeClient = WebChromeClient()
                            loadUrl(activeUrl)
                        }
                    },
                    onRelease = { webView ->
                        runCatching {
                            webView.stopLoading()
                            webView.destroy()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AndroidBuilderTab(
    onRunInTerminal: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var appName by remember { mutableStateOf("MyCineApp") }
    var packageName by remember { mutableStateOf("com.cinecode.myapp") }
    var toolchain by remember { mutableStateOf<AndroidToolchainStatus?>(null) }
    var lastProjectDir by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        toolchain = AndroidDevManager.inspectToolchain(context)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("On-Phone Android Toolchain", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    toolchain?.summary ?: "Checking java, gradle, aapt2, d8…",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedTextField(
            value = appName,
            onValueChange = { appName = it },
            label = { Text("App Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = packageName,
            onValueChange = { packageName = it },
            label = { Text("Package Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                isBusy = true
                scope.launch {
                    val res = AndroidDevManager.scaffoldComposeApp(context, appName, packageName)
                    isBusy = false
                    res.onSuccess {
                        lastProjectDir = it.projectDir
                        Toast.makeText(context, "Compose project created (${it.filesCreated} files)", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(context, "Error: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Android, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Create Jetpack Compose App")
        }

        if (lastProjectDir.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Created: $lastProjectDir", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    OutlinedButton(
                        onClick = {
                            val cmd = AndroidDevManager.buildCommandForProject(lastProjectDir)
                            LocalTerminalManager.createNewSession(
                                context = context,
                                workingDir = lastProjectDir,
                                initialCommand = cmd
                            )
                            onRunInTerminal(cmd)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Terminal, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Build APK in Terminal (assembleDebug)")
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaApkAnalyzerTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<ApkAnalysisReport?>(null) }
    var isBusy by remember { mutableStateOf(false) }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            isBusy = true
            scope.launch {
                val res = ReaManager.analyzeUri(context, uri)
                isBusy = false
                res.onSuccess { report = it }
                    .onFailure { Toast.makeText(context, "REA Error: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = { apkPicker.launch("*/*") },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.ManageSearch, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Select .APK File to Inspect (Streaming REA)")
        }

        if (isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        report?.let { r ->
            val sizeMb = DecimalFormat("#.##").format(r.fileSizeBytes / (1024.0 * 1024.0))
            Surface(
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(r.fileName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Package: ${r.packageName}", fontSize = 12.5.sp)
                    Text("Version: ${r.versionName} (${r.versionCode}) • Size: $sizeMb MB", fontSize = 12.5.sp)
                    Text("ABIs: ${r.abis.ifEmpty { listOf("Pure DEX") }.joinToString(", ")}", fontSize = 12.5.sp)
                    Text("DEX Files: ${r.dexCount} • Assets: ${r.assetCount} • Entries: ${r.totalEntries}", fontSize = 12.5.sp)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Security & Optimization Notes", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    r.securityNotes.forEach { note ->
                        Text("• $note", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (r.permissions.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text("Permissions (${r.permissions.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        r.permissions.take(20).forEach { p ->
                            Text(p, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        } ?: Text(
            "REA inspects APK manifest, ABIs, permissions, DEX count, and native libraries safely on 4GB RAM devices.",
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GitHubAndPcTab(activeProjectPath: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authPrefs = remember(context) { AuthPreferences(context) }

    var token by remember { mutableStateOf(GitHubManager.getToken(context)) }
    var searchQuery by remember { mutableStateOf("cinecode") }
    var repos by remember { mutableStateOf<List<GitHubRepoItem>>(emptyList()) }
    var isBusy by remember { mutableStateOf(false) }

    val pairing = remember { PcConnect.getPairingInfo() }
    var pcIp by remember { mutableStateOf("") }
    var pcStatus by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Connect to PC (LAN / ADB Bridge)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "Phone LAN IP: ${pairing.phoneLanIps.ifEmpty { listOf("Offline / Cellular") }.joinToString(", ")}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = pcIp,
                        onValueChange = { pcIp = it },
                        label = { Text("PC Local IP (e.g. 192.168.1.42)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    pcStatus = "Testing..."
                                    pcStatus = PcConnect.probePcBridge(pcIp).getOrElse { "Failed: ${it.message}" }
                                }
                            },
                            enabled = pcIp.isNotBlank()
                        ) { Text("Test PC") }
                        OutlinedButton(
                            onClick = {
                                val bridgeUrl = PcConnect.buildPcBridgeUrl(pcIp)
                                val hubUrl = PcConnect.buildPcHubUrl(pcIp)
                                scope.launch {
                                    authPrefs.saveAgyHubUrl(hubUrl)
                                    authPrefs.saveAgyBridgeHttpUrl(bridgeUrl)
                                    Toast.makeText(context, "Saved PC Bridge: $bridgeUrl", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = pcIp.isNotBlank()
                        ) { Text("Use PC Bridge") }
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("ADB Reverse", pairing.adbReverseCmd))
                                Toast.makeText(context, "Copied ADB reverse command", Toast.LENGTH_SHORT).show()
                            }
                        ) { Text("Copy ADB") }
                    }
                    pcStatus?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("GitHub Manager", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("GitHub PAT (Optional for private repos)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            GitHubManager.saveToken(context, token)
                            Toast.makeText(context, "GitHub token saved", Toast.LENGTH_SHORT).show()
                        }) { Text("Save Token") }
                        OutlinedButton(onClick = {
                            isBusy = true
                            scope.launch {
                                GitHubManager.listMyRepositories(context)
                                    .onSuccess { repos = it }
                                    .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                                isBusy = false
                            }
                        }) { Text("My Repos") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("Search GitHub") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = {
                            isBusy = true
                            scope.launch {
                                GitHubManager.searchRepositories(context, searchQuery)
                                    .onSuccess { repos = it }
                                    .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                                isBusy = false
                            }
                        }) { Text("Search") }
                    }
                    if (activeProjectPath.isNotBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val res = GitHubManager.pullProject(activeProjectPath)
                                    Toast.makeText(context, if (res.success) "Pulled" else res.error, Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("Git Pull") }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val res = GitHubManager.pushProject(activeProjectPath)
                                    Toast.makeText(context, if (res.success) "Pushed" else res.error, Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("Git Push") }
                        }
                    }
                }
            }
        }

        if (isBusy) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }

        items(repos, key = { it.fullName }) { repo ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(repo.fullName, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (repo.description.isNotBlank()) {
                            Text(repo.description, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text("★ ${repo.stars} ${if (repo.language.isNotBlank()) "• ${repo.language}" else ""}", fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            Toast.makeText(context, "Cloning ${repo.fullName}...", Toast.LENGTH_SHORT).show()
                            GitHubManager.cloneIntoWorkspace(context, repo.cloneUrl)
                                .onSuccess { Toast.makeText(context, "Cloned to $it", Toast.LENGTH_LONG).show() }
                                .onFailure { Toast.makeText(context, "Clone error: ${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    }) { Text("Clone") }
                }
            }
        }
    }
}
