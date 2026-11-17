package com.shriram.cinecode.ui.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shriram.cinecode.data.remote.CloudBridgeStore
import com.shriram.cinecode.ui.browser.PluginRepo
import com.shriram.cinecode.ui.browser.PluginRepositories
import com.shriram.cinecode.ui.browser.PluginVersions
import com.shriram.cinecode.ui.browser.RepoPlugin
import kotlinx.coroutines.launch

/**
 * Settings → CineCode-AI Plugins 🛠️ → Repositories: GitHub repositories of plugin folders, their plugins with
 * install/update status, and the guide for hosting one. [runOperation]'s result is shown as a notice.
 */
@Composable
fun PluginRepositoriesTab(busy: Boolean, runOperation: (suspend () -> String?) -> Unit, folderTaken: (String) -> Boolean, freeFolder: (String) -> String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { CloudBridgeStore.ensureInitialized(context) }
    val isCloudActive by CloudBridgeStore.isCloudActiveFlow.collectAsState()
    val activeGreen = if (isSystemInDarkTheme()) Color(0xFF34D399) else Color(0xFF15803D)
    var input by remember { mutableStateOf(PluginRepositories.CINECODE_REPO) }
    var addError by remember { mutableStateOf<String?>(null) }
    var guide by remember { mutableStateOf<String?>(null) }
    // A new install whose default folder name is taken waits here for another name
    var pending by remember { mutableStateOf<Pair<PluginRepo, RepoPlugin>?>(null) }
    var folder by remember { mutableStateOf("") }

    fun install(repo: PluginRepo, plugin: RepoPlugin, name: String) = runOperation {
        val installed = PluginRepositories.install(repo, plugin, name, updateId = null)
        "Installed ${installed.folderName} ${PluginVersions.label(installed.version)} from ${repo.label}"
    }
    fun installOrUpdate(repo: PluginRepo, plugin: RepoPlugin) {
        val installed = PluginRepositories.installedFor(repo, plugin)
        if (installed != null) runOperation {
            val updated = PluginRepositories.install(repo, plugin, installed.folderName, installed.id)
            "Updated ${updated.folderName}: ${PluginVersions.label(installed.version)} → ${PluginVersions.label(updated.version)}"
        } else {
            val name = plugin.path.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "-").trimStart('.', '-', '_').take(64).ifBlank { "plugin" }
            if (folderTaken(name)) { pending = repo to plugin; folder = freeFolder(name) } else install(repo, plugin, name)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it; addError = null },
            leadingIcon = { Icon(Icons.Outlined.Code, contentDescription = "GitHub Repository") },
            label = { Text("GitHub repository URL / path") },
            placeholder = { Text("shriramnag/CineCode-AI or owner/repo@branch") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("github_repo_input"),
            isError = addError != null,
            supportingText = { Text(addError ?: "Default repo: ${PluginRepositories.CINECODE_REPO} · Offline plugins sync from GitHub API active.") }
        )
        Button(
            enabled = input.isNotBlank(),
            onClick = {
                runCatching { PluginRepositories.parse(input) }
                    .onSuccess { parsed ->
                        val existing = PluginRepositories.repos.firstOrNull { it.label.equals(parsed.label, ignoreCase = true) }
                        if (existing != null) {
                            scope.launch { PluginRepositories.refresh(existing) }
                        } else {
                            runCatching { PluginRepositories.add(parsed) }
                                .onSuccess {
                                    if (!parsed.slug.equals(PluginRepositories.CINECODE_REPO, ignoreCase = true)) {
                                        input = ""
                                    }
                                    scope.launch { PluginRepositories.refresh(parsed) }
                                }
                                .onFailure { addError = it.message }
                        }
                    }
                    .onFailure { addError = it.message }
            },
            modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("add_sync_repo_button")
        ) { Text("Add / Sync Repository") }

        Card(Modifier.fillMaxWidth().testTag("repo_sync_status_card")) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🌐 Sync Offline Repos",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = PluginRepositories.syncOfflineRepos,
                        onCheckedChange = { enabled ->
                            PluginRepositories.updateSyncOfflineRepos(enabled)
                            if (enabled) scope.launch { PluginRepositories.refreshAll() }
                        },
                        modifier = Modifier.testTag("sync_offline_repos_switch")
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "🛠️ Custom API Key Integration status:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (isCloudActive) "API Active 🟢 (Direct Cloud)" else "Local Bridge Mode 🔵 (:1235)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isCloudActive) activeGreen else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("repo_api_integration_status")
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CineCode-AI Plugin Repository Guide", style = MaterialTheme.typography.titleSmall)
                    Text("GitHub रिपॉज़िटरी (${PluginRepositories.CINECODE_REPO}) व ${PluginRepositories.INDEX} / ${PluginRepositories.CINECODE_INDEX} सेटअप गाइड", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { guide = runCatching { readBundledGuide(context) }.getOrElse { "Guide not found: ${it.message}" } }) { Icon(Icons.Outlined.Visibility, "View guide") }
                IconButton(onClick = { scope.launch { saveToDownloads(context, "cinecode-plugin-repository.md", readBundledGuide(context)) } }) { Icon(Icons.Outlined.Download, "Download guide") }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CineCode-AI Plugin SDK for PC", style = MaterialTheme.typography.titleSmall)
                    Text("सभी प्लगइन डॉक्स और उदाहरण ZIP में; अपने PC पर कस्टम प्लगइन बनाने के लिए।", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { scope.launch { saveToDownloads(context, "cinecode-plugin-sdk.zip", bundledSdkZip(context), "application/zip") } }) { Icon(Icons.Outlined.FolderZip, "Download plugin SDK ZIP") }
            }
        }

        if (PluginRepositories.repos.isEmpty()) Text("No repositories added.", style = MaterialTheme.typography.bodySmall)
        PluginRepositories.repos.forEach { repo ->
            val listing = PluginRepositories.listings[repo.label]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(listing?.title ?: repo.label, style = MaterialTheme.typography.titleMedium)
                            if (listing?.title != null && listing.title != repo.label) Text(repo.label, style = MaterialTheme.typography.bodySmall)
                        }
                        if (repo.label in PluginRepositories.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else IconButton(onClick = { scope.launch { PluginRepositories.refresh(repo) } }) { Icon(Icons.Outlined.Refresh, "Check for updates") }
                        IconButton(onClick = { PluginRepositories.remove(repo) }) { Icon(Icons.Outlined.Delete, "Remove repository") }
                    }
                    PluginRepositories.errors[repo.label]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (listing != null && listing.plugins.isEmpty()) Text("This repository lists no plugins.", style = MaterialTheme.typography.bodySmall)
                    listing?.plugins?.forEach { plugin -> RepoPluginRow(repo, plugin, busy) { installOrUpdate(repo, plugin) } }
                }
            }
        }
    }

    guide?.let { MarkdownViewerDialog("CineCode-AI Plugin Repository Guide", it, "cinecode-plugin-repository.md") { guide = null } }
    pending?.let { (repo, plugin) ->
        val taken = folderTaken(folder)
        AlertDialog(
            onDismissRequest = { pending = null }, title = { Text("Install ${plugin.name}") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A plugin already uses the folder ${plugin.path.substringAfterLast('/')}. Choose another folder name for this one.")
                OutlinedTextField(value = folder, onValueChange = { folder = it }, label = { Text("Folder name") }, singleLine = true,
                    isError = taken, supportingText = if (taken) {{ Text("A plugin already uses this folder name") }} else null)
            } },
            confirmButton = { TextButton(enabled = folder.isNotBlank() && !taken, onClick = { val name = folder.trim(); pending = null; install(repo, plugin, name) }) { Text("Install") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun RepoPluginRow(repo: PluginRepo, plugin: RepoPlugin, busy: Boolean, onInstall: () -> Unit) {
    val installed = PluginRepositories.installedFor(repo, plugin)
    val comparison = installed?.let { PluginVersions.compare(plugin.version, it.version) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        PluginIcon(plugin.iconUrl, 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("${plugin.name} · ${PluginVersions.label(plugin.version)}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (plugin.description.isNotBlank()) Text(plugin.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (installed != null) Text(when {
                comparison == null -> "Installed ${PluginVersions.label(installed.version)} as ${installed.folderName}"
                comparison > 0 -> "Update available: ${PluginVersions.label(installed.version)} → ${PluginVersions.label(plugin.version)}"
                comparison == 0 -> "Installed · up to date"
                else -> "Installed ${PluginVersions.label(installed.version)} is newer than this repository's"
            }, style = MaterialTheme.typography.labelSmall, color = if (comparison != null && comparison > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(6.dp))
        when {
            installed == null -> Button(enabled = !busy, onClick = onInstall, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Install") }
            comparison != null && comparison > 0 -> Button(enabled = !busy, onClick = onInstall, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Update") }
            else -> TextButton(enabled = !busy, onClick = onInstall) { Text("Reinstall") }
        }
    }
}

/** Real progress of a repository install or update; the bar only moves without a percentage while the size is unknown. */
@Composable
internal fun PluginInstallProgress(progress: com.shriram.cinecode.ui.browser.RepoInstallProgress, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    fun size(bytes: Long) = android.text.format.Formatter.formatShortFileSize(context, bytes)
    val fraction = progress.fraction
    val files = if (progress.filesTotal > 0) "${progress.filesDone}/${progress.filesTotal} files" else null
    val label = when (progress.phase) {
        "listing" -> "Reading the repository…"
        "downloading" -> listOfNotNull(
            "Downloading", files,
            if (progress.bytesTotal > 0) "${size(progress.bytesDone)} / ${size(progress.bytesTotal)}" else size(progress.bytesDone)
        ).joinToString(" · ")
        "extracting" -> listOfNotNull("Extracting", files).joinToString(" · ")
        "installing" -> "Installing…"
        else -> "Starting plugin…"
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        if (progress.note.isNotBlank()) Text(progress.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (fraction != null) Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
