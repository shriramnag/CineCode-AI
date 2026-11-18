package com.shriram.cinecode.data.checkpoint

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class ProjectCheckpoint(
    val id: String = UUID.randomUUID().toString().take(8),
    val projectPath: String,
    val projectName: String,
    val label: String,
    val createdAt: Long = System.currentTimeMillis(),
    val fileCount: Int = 0,
    val archiveSizeBytes: Long = 0L,
    val archivePath: String
)

/**
 * Lightweight, 4GB-RAM & 64GB-storage safe Project Checkpoints manager.
 * Streams workspace files into compressed ZIP snapshots (excluding build/cache/node_modules/.git)
 * and caps stored snapshots at 12 per workspace to protect phone storage.
 */
object ProjectCheckpoints {
    private const val MAX_CHECKPOINTS_PER_PROJECT = 12
    private const val MAX_SINGLE_FILE_BYTES = 8L * 1024L * 1024L
    private val SKIP_DIRS = setOf(".git", "node_modules", "build", ".gradle", "__pycache__", ".venv", "dist")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _checkpoints = MutableStateFlow<List<ProjectCheckpoint>>(emptyList())
    val checkpoints: StateFlow<List<ProjectCheckpoint>> = _checkpoints.asStateFlow()

    private fun rootDir(context: Context): File =
        File(context.applicationContext.filesDir, "cinecode_checkpoints").apply { mkdirs() }

    private fun indexFile(context: Context): File = File(rootDir(context), "index.json")

    suspend fun refresh(context: Context, projectPath: String? = null) = withContext(Dispatchers.IO) {
        val all = readIndex(context).filter { File(it.archivePath).exists() }
        _checkpoints.value = if (projectPath.isNullOrBlank()) all else all.filter { it.projectPath == projectPath }
    }

    suspend fun createCheckpoint(
        context: Context,
        projectPath: String,
        label: String = "Safe Snapshot"
    ): Result<ProjectCheckpoint> = withContext(Dispatchers.IO) {
        runCatching {
            val srcDir = File(projectPath)
            require(srcDir.exists() && srcDir.isDirectory) { "Project directory not found: $projectPath" }
            val id = UUID.randomUUID().toString().take(8)
            val archiveFile = File(rootDir(context), "cp_${System.currentTimeMillis()}_$id.zip")
            var count = 0
            ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zos ->
                val buffer = ByteArray(8192)
                fun walk(dir: File, prefix: String) {
                    val children = dir.listFiles() ?: return
                    for (child in children) {
                        if (child.isDirectory) {
                            if (child.name in SKIP_DIRS) continue
                            walk(child, if (prefix.isEmpty()) child.name else "$prefix/${child.name}")
                        } else if (child.isFile && child.length() <= MAX_SINGLE_FILE_BYTES) {
                            val entryName = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                            zos.putNextEntry(ZipEntry(entryName))
                            BufferedInputStream(FileInputStream(child)).use { bis ->
                                while (true) {
                                    val read = bis.read(buffer)
                                    if (read <= 0) break
                                    zos.write(buffer, 0, read)
                                }
                            }
                            zos.closeEntry()
                            count++
                        }
                    }
                }
                walk(srcDir, "")
            }
            val cp = ProjectCheckpoint(
                id = id,
                projectPath = srcDir.absolutePath,
                projectName = srcDir.name.ifBlank { "Project" },
                label = label.ifBlank { "Safe Snapshot" },
                fileCount = count,
                archiveSizeBytes = archiveFile.length(),
                archivePath = archiveFile.absolutePath
            )
            val existing = readIndex(context).filter { File(it.archivePath).exists() }.toMutableList()
            existing.add(0, cp)
            // Keep max snapshots per project to avoid filling 64GB storage
            val forProject = existing.filter { it.projectPath == cp.projectPath }
            if (forProject.size > MAX_CHECKPOINTS_PER_PROJECT) {
                forProject.drop(MAX_CHECKPOINTS_PER_PROJECT).forEach { stale ->
                    runCatching { File(stale.archivePath).delete() }
                    existing.remove(stale)
                }
            }
            writeIndex(context, existing)
            _checkpoints.value = existing.filter { it.projectPath == cp.projectPath }
            cp
        }
    }

    suspend fun restoreCheckpoint(context: Context, checkpoint: ProjectCheckpoint): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val archive = File(checkpoint.archivePath)
            require(archive.exists()) { "Checkpoint archive missing." }
            val destDir = File(checkpoint.projectPath).apply { mkdirs() }
            val canonicalDest = destDir.canonicalPath
            var restored = 0
            val buffer = ByteArray(8192)
            ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val outFile = File(destDir, entry.name)
                        require(outFile.canonicalPath.startsWith(canonicalDest)) { "Unsafe zip entry: ${entry.name}" }
                        outFile.parentFile?.mkdirs()
                        BufferedOutputStream(FileOutputStream(outFile)).use { bos ->
                            while (true) {
                                val read = zis.read(buffer)
                                if (read <= 0) break
                                bos.write(buffer, 0, read)
                            }
                        }
                        restored++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            restored
        }
    }

    suspend fun deleteCheckpoint(context: Context, id: String) = withContext(Dispatchers.IO) {
        val current = readIndex(context).toMutableList()
        val target = current.firstOrNull { it.id == id }
        if (target != null) {
            runCatching { File(target.archivePath).delete() }
            current.remove(target)
            writeIndex(context, current)
            _checkpoints.value = current
        }
    }

    private fun readIndex(context: Context): List<ProjectCheckpoint> {
        val f = indexFile(context)
        if (!f.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<ProjectCheckpoint>>(f.readText()) }.getOrDefault(emptyList())
    }

    private fun writeIndex(context: Context, list: List<ProjectCheckpoint>) {
        runCatching { indexFile(context).writeText(json.encodeToString(list)) }
    }
}
