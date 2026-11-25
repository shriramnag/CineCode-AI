package com.shriram.cinecode.data.local

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Relocates Termux / AntiGem rootfs trees and .deb packages so apt/pkg/dpkg, shebangs, configs,
 * symlinks, and termux-am work under CineCode-AI's package name (e.g. com.shriramnag.cinecodeai).
 */
object TermuxRelocation {
    private const val TAG = "TermuxRelocation"
    private const val TERMUX_PKG = "com.termux"
    private const val ANTIGEM_PKG = "ai.antigem"

    private val SKIP_EXTS = setOf(
        "apk", "jar", "zip", "aar", "whl", "dex", "jmod",
        "so", "a", "o", "node", "wasm", "png", "jpg", "jpeg",
        "webp", "gif", "ico", "ttf", "otf", "woff", "woff2",
        "db", "sqlite", "sqlite3", "gz", "xz", "bz2", "zst"
    )
    private val SKIP_SUFFIXES = listOf(".termuxam", ".x11.")

    data class Stats(
        val scannedFiles: Int = 0,
        val rewrittenFiles: Int = 0,
        val relinkedSymlinks: Int = 0
    )

    fun setup(context: Context) {
        val appCtx = context.applicationContext
        val newPkg = appCtx.packageName
        if (newPkg == TERMUX_PKG) return

        val prefixDir = LocalEnvironmentManager.getPrefixDir(appCtx)
        if (!prefixDir.exists()) return

        val prefixPath = "/data/data/$newPkg/files/usr"
        if (File(prefixDir, "etc/apt").isDirectory) {
            val relocatorFile = File(prefixDir, "libexec/cinecode/relocate-debs")
            val hookFile = File(prefixDir, "etc/apt/apt.conf.d/99cinecode-relocate")
            val legacyHook = File(prefixDir, "etc/apt/apt.conf.d/99antigem-relocate")

            runCatching {
                writeTextFile(relocatorFile, buildRelocateDebsScript(prefixPath, newPkg), executable = true)
                if (legacyHook.exists()) legacyHook.delete()
                writeTextFile(
                    hookFile,
                    buildString {
                        appendLine("// Written by CineCode-AI (edits are overwritten). Adapts downloaded Termux .deb")
                        appendLine("// packages for $newPkg before dpkg installs them.")
                        appendLine("DPkg::Pre-Install-Pkgs { \"${relocatorFile.absolutePath} --apt\"; };")
                    },
                    executable = false
                )
            }.onFailure {
                Log.w(TAG, "apt relocation hook setup failed: ${it.message}")
            }

            runCatching {
                installDpkgWrapper(prefixDir, prefixPath, relocatorFile.absolutePath)
            }.onFailure {
                Log.w(TAG, "dpkg wrapper setup failed: ${it.message}")
            }
        }

        runCatching {
            installAmApk(appCtx, prefixDir, prefixPath)
        }.onFailure {
            Log.w(TAG, "am.apk setup failed: ${it.message}")
        }
    }

    fun applyRelocation(
        context: Context,
        relocateFiles: Boolean = true,
        onProgress: ((Int) -> Unit)? = null
    ): Stats {
        val appCtx = context.applicationContext
        val prefixDir = LocalEnvironmentManager.getPrefixDir(appCtx)
        val stats = if (relocateFiles && appCtx.packageName != TERMUX_PKG) {
            relocateDirectory(prefixDir, appCtx.packageName, onProgress = onProgress)
        } else {
            Stats()
        }
        setup(appCtx)
        return stats
    }

    fun relocateDirectory(
        dir: File,
        newPkg: String,
        alsoReplaceAntiGem: Boolean = true,
        onProgress: ((Int) -> Unit)? = null
    ): Stats {
        if (!dir.exists() || newPkg == TERMUX_PKG) return Stats()

        if (newPkg.toByteArray(Charsets.US_ASCII).size == TERMUX_PKG.length) {
            return relocateFixedLength(dir, newPkg, alsoReplaceAntiGem, onProgress)
        }

        var scanned = 0
        var rewritten = 0
        var relinked = 0

        val stack = ArrayDeque<File>()
        stack.addLast(dir)

        val candidates = mutableListOf(
            "/data/data/$TERMUX_PKG" to "/data/data/$newPkg",
            "/data/user/0/$TERMUX_PKG" to "/data/user/0/$newPkg"
        )
        if (alsoReplaceAntiGem && newPkg != ANTIGEM_PKG) {
            candidates.add("/data/data/$ANTIGEM_PKG" to "/data/data/$newPkg")
            candidates.add("/data/user/0/$ANTIGEM_PKG" to "/data/user/0/$newPkg")
        }

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = if (current.name == ".git") {
                arrayOf(File(current, "config"))
            } else {
                current.listFiles() ?: continue
            }

            for (entry in children) {
                try {
                    val stat = Os.lstat(entry.absolutePath)
                    val mode = stat.st_mode
                    if (OsConstants.S_ISLNK(mode)) {
                        val target = Os.readlink(entry.absolutePath)
                        var updated = target
                        for ((oldPrefix, newPrefix) in candidates) {
                            if (updated.contains(oldPrefix)) {
                                updated = updated.replace(oldPrefix, newPrefix)
                            }
                        }
                        if (updated != target) {
                            entry.delete()
                            Os.symlink(updated, entry.absolutePath)
                            relinked++
                        }
                    } else if (OsConstants.S_ISDIR(mode)) {
                        stack.addLast(entry)
                    } else if (OsConstants.S_ISREG(mode)) {
                        scanned++
                        if (scanned % 500 == 0) onProgress?.invoke(scanned)
                        val ext = entry.extension.lowercase()
                        if (ext in SKIP_EXTS) continue
                        val len = entry.length()
                        if (len <= 0L || len > 2L * 1024L * 1024L) continue
                        if (!isLikelyTextFile(entry)) continue

                        val original = entry.readText(Charsets.UTF_8)
                        var updated = original
                        for ((oldPrefix, newPrefix) in candidates) {
                            if (updated.contains(oldPrefix)) {
                                updated = updated.replace(oldPrefix, newPrefix)
                            }
                        }
                        if (updated != original) {
                            val perm = mode and 4095
                            val wasWritable = (perm and 128) != 0
                            if (!wasWritable) {
                                runCatching { Os.chmod(entry.absolutePath, perm or 128) }
                            }
                            try {
                                entry.writeText(updated, Charsets.UTF_8)
                                rewritten++
                            } finally {
                                if (!wasWritable) {
                                    runCatching { Os.chmod(entry.absolutePath, perm) }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping relocation on ${entry.absolutePath}: ${e.message}")
                }
            }
        }
        return Stats(scanned, rewritten, relinked)
    }

    private fun isLikelyTextFile(file: File): Boolean {
        val sample = ByteArray(minOf(file.length(), 4096L).toInt())
        if (sample.isEmpty()) return false
        FileInputStream(file).use { input ->
            val read = input.read(sample)
            if (read <= 0) return false
            if (read >= 4 &&
                sample[0] == 0x7F.toByte() &&
                sample[1] == 'E'.code.toByte() &&
                sample[2] == 'L'.code.toByte() &&
                sample[3] == 'F'.code.toByte()
            ) {
                return false
            }
            for (i in 0 until read) {
                if (sample[i].toInt() == 0) return false
            }
        }
        return true
    }

    private fun relocateFixedLength(
        dir: File,
        newPkg: String,
        alsoReplaceAntiGem: Boolean,
        onProgress: ((Int) -> Unit)?
    ): Stats {
        val oldBytes = TERMUX_PKG.toByteArray(Charsets.US_ASCII)
        val newBytes = newPkg.toByteArray(Charsets.US_ASCII)
        val antiGemBytes = if (alsoReplaceAntiGem && newPkg != ANTIGEM_PKG) {
            ANTIGEM_PKG.toByteArray(Charsets.US_ASCII)
        } else {
            null
        }
        var scanned = 0
        var rewritten = 0
        var relinked = 0
        val stack = ArrayDeque<File>()
        stack.addLast(dir)

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = if (current.name == ".git") {
                arrayOf(File(current, "config"))
            } else {
                current.listFiles() ?: continue
            }
            for (entry in children) {
                try {
                    val stat = Os.lstat(entry.absolutePath)
                    val mode = stat.st_mode
                    if (OsConstants.S_ISLNK(mode)) {
                        val target = Os.readlink(entry.absolutePath)
                        if (target.contains(TERMUX_PKG) || (antiGemBytes != null && target.contains(ANTIGEM_PKG))) {
                            var updated = target.replace("/$TERMUX_PKG", "/$newPkg")
                            if (antiGemBytes != null) updated = updated.replace("/$ANTIGEM_PKG", "/$newPkg")
                            if (updated != target) {
                                entry.delete()
                                Os.symlink(updated, entry.absolutePath)
                                relinked++
                            }
                        }
                    } else if (OsConstants.S_ISDIR(mode)) {
                        stack.addLast(entry)
                    } else if (OsConstants.S_ISREG(mode)) {
                        scanned++
                        if (scanned % 500 == 0) onProgress?.invoke(scanned)
                        if (entry.extension.lowercase() in SKIP_EXTS) continue
                        val offsets = findByteOffsets(entry, oldBytes, antiGemBytes)
                        if (offsets.isNotEmpty()) {
                            val perm = mode and 4095
                            val wasWritable = (perm and 128) != 0
                            if (!wasWritable) runCatching { Os.chmod(entry.absolutePath, perm or 128) }
                            try {
                                RandomAccessFile(entry, "rw").use { raf ->
                                    for (pos in offsets) {
                                        raf.seek(pos)
                                        raf.write(newBytes)
                                    }
                                }
                                rewritten++
                            } finally {
                                if (!wasWritable) runCatching { Os.chmod(entry.absolutePath, perm) }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        return Stats(scanned, rewritten, relinked)
    }

    private fun findByteOffsets(file: File, pattern: ByteArray, altPattern: ByteArray?): List<Long> {
        val maxSuffix = SKIP_SUFFIXES.maxOf { it.length }
        val bufSize = 262_144 + pattern.size + maxSuffix
        val buf = ByteArray(bufSize)
        val result = ArrayList<Long>()
        val skipBytes = SKIP_SUFFIXES.map { it.toByteArray(Charsets.US_ASCII) }

        RandomAccessFile(file, "r").use { raf ->
            var fileOffset = 0L
            var buffered = 0
            while (true) {
                val read = raf.read(buf, buffered, bufSize - buffered)
                val eof = read <= 0
                if (!eof) buffered += read
                val limit = if (eof) buffered - pattern.size else buffered - pattern.size - maxSuffix
                var i = 0
                while (i <= limit) {
                    val matchPrimary = matchesBytes(buf, i, pattern)
                    val matchAlt = !matchPrimary && altPattern != null && matchesBytes(buf, i, altPattern)
                    if (matchPrimary || matchAlt) {
                        val nextIdx = i + pattern.size
                        val followedByProtectedSuffix = skipBytes.any { s ->
                            nextIdx + s.size <= buffered && matchesBytes(buf, nextIdx, s)
                        }
                        val precededBySlash = i > 0 && buf[i - 1] == '/'.code.toByte()
                        if (!followedByProtectedSuffix && (!matchAlt || precededBySlash)) {
                            result.add(fileOffset + i)
                        }
                        i += pattern.size
                    } else {
                        i++
                    }
                }
                if (eof) break
                val keepFrom = minOf(i, buffered)
                buffered -= keepFrom
                System.arraycopy(buf, keepFrom, buf, 0, buffered)
                fileOffset += keepFrom
            }
        }
        return result
    }

    private fun matchesBytes(haystack: ByteArray, offset: Int, needle: ByteArray): Boolean {
        if (offset < 0 || offset + needle.size > haystack.size) return false
        for (i in needle.indices) {
            if (haystack[offset + i] != needle[i]) return false
        }
        return true
    }

    private fun installAmApk(context: Context, prefixDir: File, prefixPath: String) {
        val amApk = File(prefixDir, "libexec/termux-am/am.apk")
        val parent = amApk.parentFile ?: return
        if (!parent.isDirectory) {
            if (!File(prefixDir, "bin").isDirectory) return
            parent.mkdirs()
        }
        val assetBytes = runCatching {
            context.assets.open("bin/am.apk").use { it.readBytes() }
        }.getOrNull() ?: return

        if (amApk.isFile && amApk.length() == assetBytes.size.toLong() && amApk.readBytes().contentEquals(assetBytes)) {
            return
        }
        divertPath(prefixDir, prefixPath, "libexec/termux-am/am.apk", rename = true)
        val tmp = File(parent, ".am.apk.tmp")
        tmp.delete()
        tmp.writeBytes(assetBytes)
        runCatching { Os.chmod(tmp.absolutePath, 292) } // 0444
        if (!tmp.renameTo(amApk)) {
            amApk.delete()
            tmp.renameTo(amApk)
        }
    }

    private fun isDiverted(prefixDir: File, prefixPath: String, relPath: String): Boolean {
        return try {
            val diversions = File(prefixDir, "var/lib/dpkg/diversions")
            if (!diversions.isFile) return false
            val target = "$prefixPath/$relPath"
            diversions.readLines().chunked(3).any { it.firstOrNull() == target }
        } catch (_: Exception) {
            false
        }
    }

    private fun divertPath(prefixDir: File, prefixPath: String, relPath: String, rename: Boolean): Boolean {
        if (isDiverted(prefixDir, prefixPath, relPath)) return true
        val dpkgDivert = File(prefixDir, "bin/dpkg-divert")
        if (!dpkgDivert.canExecute()) return true // No dpkg-divert yet (e.g. non-dpkg tree); caller can write directly
        val targetFile = File(prefixDir, relPath)
        val exists = runCatching { Os.lstat(targetFile.absolutePath); true }.getOrDefault(false)
        if (rename && !exists) return true

        val cmd = mutableListOf(dpkgDivert.absolutePath, "--add")
        if (rename) cmd.add("--rename")
        cmd.addAll(listOf("--divert", "$prefixPath/$relPath.distrib", "$prefixPath/$relPath"))

        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().apply {
            put("PREFIX", prefixPath)
            put("TMPDIR", "$prefixPath/tmp")
            put("PATH", "$prefixPath/bin:/system/bin")
            remove("LD_PRELOAD")
        }
        val proc = pb.start()
        val out = BufferedReader(InputStreamReader(proc.inputStream)).readText()
        if (!proc.waitFor(30L, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return false
        }
        val ok = proc.exitValue() == 0 || isDiverted(prefixDir, prefixPath, relPath)
        if (!ok) {
            Log.w(TAG, "dpkg-divert $relPath failed: ${out.trim()}")
        }
        return ok
    }

    private fun installDpkgWrapper(prefixDir: File, prefixPath: String, relocatorPath: String) {
        val dpkgFile = File(prefixDir, "bin/dpkg")
        if (!dpkgFile.exists()) return
        val dpkgDistrib = File(prefixDir, "bin/dpkg.distrib")
        if (!dpkgDistrib.isFile) {
            val isAlreadyScript = runCatching {
                FileInputStream(dpkgFile).use { input ->
                    val b = ByteArray(2)
                    input.read(b) == 2 && b[0] == '#'.code.toByte() && b[1] == '!'.code.toByte()
                }
            }.getOrDefault(false)
            if (isAlreadyScript && !isDiverted(prefixDir, prefixPath, "bin/dpkg")) {
                return
            }
        }
        if (!divertPath(prefixDir, prefixPath, "bin/dpkg", rename = true)) return

        val script = """
            |#!$prefixPath/bin/sh
            |# Written by CineCode-AI (edits are overwritten). Wraps dpkg so manually installed .deb files
            |# built for /data/data/com.termux are relocated for ${File(prefixPath).parentFile?.parentFile?.name ?: "com.shriramnag.cinecodeai"}.
            |REAL='$prefixPath/bin/dpkg.distrib'
            |locked=0
            |[ -n "${'$'}{DPKG_FRONTEND_LOCKED:-}" ] && locked=1
            |
            |needs() {
            |    [ -f "${'$'}1" ] || return 1
            |    case "${'$'}1" in *.deb) ;; *) return 1 ;; esac
            |    if [ "${'$'}locked" = 1 ]; then
            |        target=${'$'}(readlink -f "${'$'}1" 2>/dev/null || printf '%s' "${'$'}1")
            |        case "${'$'}target" in */cache/apt/archives/*) return 1 ;; esac
            |    fi
            |    return 0
            |}
            |
            |dir_needs() {
            |    find -L "${'$'}1" -type f -name '*.deb' 2>/dev/null | while IFS= read -r d; do
            |        if needs "${'$'}d"; then echo yes; break; fi
            |    done | grep -q yes
            |}
            |
            |install=0
            |recursive=0
            |for a in "${'$'}@"; do
            |    case "${'$'}a" in
            |        --install|--unpack|--record-avail) install=1 ;;
            |        --recursive) recursive=1 ;;
            |        --*) ;;
            |        -?*)
            |            case "${'$'}a" in *[iA]*) install=1 ;; esac
            |            case "${'$'}a" in *R*) recursive=1 ;; esac
            |            ;;
            |    esac
            |done
            |found=0
            |if [ "${'$'}install" = 1 ]; then
            |    for a in "${'$'}@"; do
            |        if needs "${'$'}a"; then found=1; break; fi
            |        if [ "${'$'}recursive" = 1 ] && [ -d "${'$'}a" ] && dir_needs "${'$'}a"; then found=1; break; fi
            |    done
            |fi
            |[ "${'$'}found" = 1 ] || exec "${'$'}REAL" "${'$'}@"
            |
            |TMP="${'$'}{TMPDIR:-$prefixPath/tmp}"
            |work=${'$'}(mktemp -d "${'$'}TMP/cinecode-dpkg.XXXXXX") || exit 1
            |trap 'rm -rf "${'$'}work"' EXIT
            |trap 'exit 130' INT TERM
            |n=0
            |adapt() {
            |    n=${'$'}((n + 1))
            |    adapted="${'$'}work/${'$'}n-${'$'}{1##*/}"
            |    cp "${'$'}1" "${'$'}adapted" && printf '%s\n' "${'$'}adapted" | '$relocatorPath' >&2
            |}
            |
            |count=${'$'}#
            |i=0
            |while [ "${'$'}i" -lt "${'$'}count" ]; do
            |    a=${'$'}1
            |    shift
            |    i=${'$'}((i + 1))
            |    if needs "${'$'}a"; then
            |        adapt "${'$'}a" || { echo "dpkg (CineCode-AI): could not adapt ${'$'}a" >&2; exit 1; }
            |        a=${'$'}adapted
            |    elif [ "${'$'}recursive" = 1 ] && [ -d "${'$'}a" ] && dir_needs "${'$'}a"; then
            |        dir="${'$'}work/dir${'$'}i"
            |        mkdir -p "${'$'}dir"
            |        find -L "${'$'}a" -type f -name '*.deb' > "${'$'}work/list${'$'}i"
            |        while IFS= read -r d; do
            |            if needs "${'$'}d"; then
            |                adapt "${'$'}d" || { echo "dpkg (CineCode-AI): could not adapt ${'$'}d" >&2; exit 1; }
            |                mv "${'$'}adapted" "${'$'}dir/"
            |            else
            |                n=${'$'}((n + 1))
            |                ln -s "${'$'}(readlink -f "${'$'}d")" "${'$'}dir/${'$'}n-${'$'}{d##*/}"
            |            fi
            |        done < "${'$'}work/list${'$'}i"
            |        a=${'$'}dir
            |    fi
            |    set -- "${'$'}@" "${'$'}a"
            |done
            |"${'$'}REAL" "${'$'}@"
        """.trimMargin()

        writeTextFile(dpkgFile, script + "\n", executable = true)
    }

    private fun buildRelocateDebsScript(prefixPath: String, newPkg: String): String {
        val skipCasePattern = SKIP_EXTS.joinToString("|") { "*.$it" }
        return """
            |#!$prefixPath/bin/sh
            |# Written by CineCode-AI (edits are overwritten). Termux packages are built for /data/data/com.termux
            |# (or /data/data/ai.antigem). Adapts each .deb package path/text/script/symlink for $newPkg.
            |OLD1='com.termux'
            |OLD2='ai.antigem'
            |NEW='$newPkg'
            |export LC_ALL=C
            |umask 022
            |TMP="${'$'}{TMPDIR:-$prefixPath/tmp}"
            |APT_MODE=0
            |[ "${'$'}{1:-}" = "--apt" ] && APT_MODE=1
            |
            |is_text_file() {
            |    # Skip ELF binaries (\x7fELF) and files with NUL bytes so binary payloads are never corrupted
            |    hdr=${'$'}(dd if="${'$'}1" bs=4 count=1 2>/dev/null | od -An -tx1 2>/dev/null | tr -d ' \n')
            |    case "${'$'}hdr" in
            |        7f454c46*) return 1 ;;
            |    esac
            |    # grep -I skips binary files
            |    grep -Iq . "${'$'}1" 2>/dev/null
            |}
            |
            |rewrite_text() {
            |    sed -i \
            |        -e "s#/data/data/${'$'}OLD1/#/data/data/${'$'}NEW/#g" \
            |        -e "s#/data/user/0/${'$'}OLD1/#/data/user/0/${'$'}NEW/#g" \
            |        -e "s#/data/data/${'$'}OLD2/#/data/data/${'$'}NEW/#g" \
            |        -e "s#/data/user/0/${'$'}OLD2/#/data/user/0/${'$'}NEW/#g" \
            |        "${'$'}1"
            |}
            |
            |relocate_deb() {
            |    work=${'$'}(mktemp -d "${'$'}TMP/cinecode-deb.XXXXXX") || return 1
            |    if ! dpkg-deb -R "${'$'}1" "${'$'}work/pkg"; then rm -rf "${'$'}work"; return 1; fi
            |    has_old=0
            |    if [ -d "${'$'}work/pkg/data/data/${'$'}OLD1" ] || [ -d "${'$'}work/pkg/data/data/${'$'}OLD2" ]; then
            |        has_old=1
            |    elif grep -rqE "/data/(data|user/0)/(${'$'}OLD1|${'$'}OLD2)/" "${'$'}work/pkg" 2>/dev/null; then
            |        has_old=1
            |    fi
            |    if [ "${'$'}has_old" = 0 ]; then
            |        rm -rf "${'$'}work"; return 0
            |    fi
            |    echo "CineCode-AI: adapting ${'$'}{1##*/} for ${'$'}NEW"
            |    for oldpkg in "${'$'}OLD1" "${'$'}OLD2"; do
            |        if [ "${'$'}oldpkg" != "${'$'}NEW" ] && [ -d "${'$'}work/pkg/data/data/${'$'}oldpkg" ]; then
            |            mkdir -p "${'$'}work/pkg/data/data/${'$'}NEW"
            |            cp -a "${'$'}work/pkg/data/data/${'$'}oldpkg/." "${'$'}work/pkg/data/data/${'$'}NEW/" && \
            |                rm -rf "${'$'}work/pkg/data/data/${'$'}oldpkg" || { rm -rf "${'$'}work"; return 1; }
            |        fi
            |    done
            |    grep -rlE "/data/(data|user/0)/(${'$'}OLD1|${'$'}OLD2)/" "${'$'}work/pkg" 2>/dev/null > "${'$'}work/files" || true
            |    while IFS= read -r f; do
            |        [ -n "${'$'}f" ] || continue
            |        case "${'$'}f" in $skipCasePattern) continue ;; esac
            |        [ -L "${'$'}f" ] && continue
            |        [ -f "${'$'}f" ] || continue
            |        is_text_file "${'$'}f" || continue
            |        [ -w "${'$'}f" ] || chmod u+w "${'$'}f"
            |        rewrite_text "${'$'}f" || { rm -rf "${'$'}work"; return 1; }
            |    done < "${'$'}work/files"
            |    find "${'$'}work/pkg" -type l > "${'$'}work/links" 2>/dev/null || true
            |    while IFS= read -r l; do
            |        t=${'$'}(readlink "${'$'}l")
            |        nt=${'$'}(printf '%s' "${'$'}t" | sed -e "s#/data/data/${'$'}OLD1/#/data/data/${'$'}NEW/#g" -e "s#/data/user/0/${'$'}OLD1/#/data/user/0/${'$'}NEW/#g" -e "s#/data/data/${'$'}OLD2/#/data/data/${'$'}NEW/#g" -e "s#/data/user/0/${'$'}OLD2/#/data/user/0/${'$'}NEW/#g")
            |        if [ "${'$'}nt" != "${'$'}t" ]; then
            |            ln -sfn "${'$'}nt" "${'$'}l" || { rm -rf "${'$'}work"; return 1; }
            |        fi
            |    done < "${'$'}work/links"
            |    if [ -f "${'$'}work/pkg/DEBIAN/md5sums" ]; then
            |        ( cd "${'$'}work/pkg" && cut -c35- DEBIAN/md5sums | xargs -r -d '\n' md5sum 2>/dev/null ) > "${'$'}work/md5sums" && \
            |            mv -f "${'$'}work/md5sums" "${'$'}work/pkg/DEBIAN/md5sums" || true
            |    fi
            |    chmod 0755 "${'$'}work/pkg/DEBIAN" 2>/dev/null || true
            |    for s in preinst postinst prerm postrm config; do
            |        [ -f "${'$'}work/pkg/DEBIAN/${'$'}s" ] && chmod 0755 "${'$'}work/pkg/DEBIAN/${'$'}s"
            |    done
            |    if ! dpkg-deb --root-owner-group -Zgzip -z1 -b "${'$'}work/pkg" "${'$'}work/out.deb" >/dev/null; then
            |        rm -rf "${'$'}work"; return 1
            |    fi
            |    mv -f "${'$'}work/out.deb" "${'$'}1"
            |    status=${'$'}?
            |    rm -rf "${'$'}work"
            |    return ${'$'}status
            |}
            |
            |fail=0
            |while IFS= read -r deb; do
            |    case "${'$'}deb" in *.deb) ;; *) continue ;; esac
            |    [ -f "${'$'}deb" ] || continue
            |    if [ "${'$'}APT_MODE" = 1 ]; then
            |        case "${'$'}deb" in */cache/apt/archives/*) ;; *) continue ;; esac
            |    fi
            |    relocate_deb "${'$'}deb" || { echo "CineCode-AI: could not adapt ${'$'}deb" >&2; fail=1; }
            |done
            |exit ${'$'}fail
        """.trimMargin() + "\n"
    }

    private fun writeTextFile(file: File, content: String, executable: Boolean) {
        if (file.isFile && runCatching { file.readText() == content }.getOrDefault(false)) {
            if (executable) runCatching { Os.chmod(file.absolutePath, 493) }
            return
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        runCatching { Os.chmod(tmp.absolutePath, if (executable) 493 else 420) }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        runCatching { Os.chmod(file.absolutePath, if (executable) 493 else 420) }
    }
}
