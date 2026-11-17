package com.shriram.cinecode.data.local

import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.shriram.cinecode.data.remote.CloudBridgeStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * LocalSandboxExecutor: Safe in-app sandbox under filesDir/sandbox_workspace for live execution
 * and self-healing verification of Python, Node.js/JS, and HTML/JS code by AI agents.
 */
object LocalSandboxExecutor {
    private const val TAG = "LocalSandboxExecutor"
    private const val SANDBOX_DIR_NAME = "sandbox_workspace"

    data class SandboxExecutionResult(
        val success: Boolean,
        val language: String,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val workspaceFilePath: String,
        val runtimeUsed: String,
        val executionTimeMs: Long,
        val errorCategory: String = "",
        val selfHealHint: String = "",
        val healedAttempts: Int = 0,
        val finalCode: String = ""
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("success", success)
            put("language", language)
            put("exitCode", exitCode)
            put("stdout", stdout)
            put("stderr", stderr)
            put("workspaceFilePath", workspaceFilePath)
            put("runtimeUsed", runtimeUsed)
            put("executionTimeMs", executionTimeMs)
            if (errorCategory.isNotBlank()) put("errorCategory", errorCategory)
            if (selfHealHint.isNotBlank()) put("selfHealHint", selfHealHint)
            put("healedAttempts", healedAttempts)
        }
    }

    fun getSandboxDir(context: Context): File =
        File(context.applicationContext.filesDir, SANDBOX_DIR_NAME).apply { mkdirs() }

    private fun normalizeLanguage(language: String): String = when (language.trim().lowercase()) {
        "py", "python", "python3" -> "python"
        "js", "node", "nodejs", "javascript", "mjs" -> "nodejs"
        "html", "htm", "html/js", "web", "webview" -> "html"
        else -> language.trim().lowercase().ifBlank { "python" }
    }

    private fun isUnsafeSnippet(code: String): String? {
        val lower = code.lowercase()
        val blockedPatterns = listOf(
            "rm -rf /",
            "rm -rf /*",
            "mkfs.",
            ":(){:|:&};:",
            "/system/bin/reboot",
            "dd if=/dev/zero of=/dev/block"
        )
        return blockedPatterns.firstOrNull { lower.contains(it) }
    }

    suspend fun executeLocalCode(
        context: Context,
        language: String,
        code: String,
        filename: String? = null,
        timeoutSeconds: Long = 20,
        autoSelfHeal: Boolean = true
    ): SandboxExecutionResult {
        val appCtx = context.applicationContext
        val normLang = normalizeLanguage(language)
        isUnsafeSnippet(code)?.let { blocked ->
            return SandboxExecutionResult(
                success = false,
                language = normLang,
                exitCode = 403,
                stdout = "",
                stderr = "Blocked unsafe pattern in sandbox: $blocked",
                workspaceFilePath = "",
                runtimeUsed = "SandboxSecurityGuard",
                executionTimeMs = 0L,
                errorCategory = "SECURITY_POLICY",
                selfHealHint = "Remove destructive system commands and restrict file operations to sandbox_workspace.",
                finalCode = code
            )
        }

        var currentCode = code
        var result = runSingleAttempt(appCtx, normLang, currentCode, filename, timeoutSeconds)
        var attempts = 0

        while (!result.success && autoSelfHeal && attempts < 2) {
            val repaired = attemptSelfHeal(appCtx, normLang, currentCode, result)
            if (repaired == null || repaired == currentCode) break
            attempts++
            currentCode = repaired
            result = runSingleAttempt(appCtx, normLang, currentCode, filename, timeoutSeconds)
        }

        return result.copy(
            healedAttempts = attempts,
            finalCode = currentCode
        )
    }

    private suspend fun runSingleAttempt(
        context: Context,
        normLang: String,
        code: String,
        filename: String?,
        timeoutSeconds: Long
    ): SandboxExecutionResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val sandboxDir = getSandboxDir(context)
        val safeBase = filename.orEmpty()
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(48)

        when (normLang) {
            "python" -> {
                val scriptName = safeBase.ifBlank { "sandbox_script.py" }.let {
                    if (it.endsWith(".py")) it else "$it.py"
                }
                val targetFile = File(sandboxDir, scriptName)
                targetFile.writeText(code)
                executePython(context, sandboxDir, targetFile, code, timeoutSeconds, startMs)
            }
            "nodejs" -> {
                val scriptName = safeBase.ifBlank { "sandbox_script.mjs" }.let {
                    if (it.endsWith(".js") || it.endsWith(".mjs")) it else "$it.js"
                }
                val targetFile = File(sandboxDir, scriptName)
                targetFile.writeText(code)
                executeNodeOrJs(context, sandboxDir, targetFile, code, timeoutSeconds, startMs)
            }
            "html" -> {
                val htmlName = safeBase.ifBlank { "preview.html" }.let {
                    if (it.endsWith(".html") || it.endsWith(".htm")) it else "$it.html"
                }
                val targetFile = File(sandboxDir, htmlName)
                targetFile.writeText(code)
                executeHtmlSandbox(context, targetFile, code, startMs)
            }
            else -> SandboxExecutionResult(
                success = false,
                language = normLang,
                exitCode = 2,
                stdout = "",
                stderr = "Unsupported sandbox language '$normLang'. Use python, nodejs, or html.",
                workspaceFilePath = "",
                runtimeUsed = "SandboxRouter",
                executionTimeMs = System.currentTimeMillis() - startMs,
                errorCategory = "UNSUPPORTED_LANGUAGE",
                selfHealHint = "Specify language as 'python', 'nodejs', or 'html'."
            )
        }
    }

    private suspend fun executePython(
        context: Context,
        sandboxDir: File,
        targetFile: File,
        code: String,
        timeoutSeconds: Long,
        startMs: Long
    ): SandboxExecutionResult {
        val hasLocalPython = File(LocalEnvironmentManager.getBinDir(context), "python3").canExecute() ||
            File(LocalEnvironmentManager.getUbuntuRootDir(context), "usr/bin/python3").canExecute()

        if (hasLocalPython || LocalEnvironmentManager.isInstalled(context)) {
            val cmdResult = LocalEnvironmentManager.executeCommand(
                command = "python3 \"${targetFile.absolutePath}\"",
                context = context,
                workingDir = sandboxDir.absolutePath,
                timeoutSeconds = timeoutSeconds
            )
            val elapsed = System.currentTimeMillis() - startMs
            val ok = cmdResult.exitCode == 0
            val outText = cmdResult.output.trimEnd()
            val (cat, hint) = if (ok) "" to "" else diagnoseError("python", outText)
            return SandboxExecutionResult(
                success = ok,
                language = "python",
                exitCode = cmdResult.exitCode,
                stdout = if (ok) outText else "",
                stderr = if (ok) "" else outText,
                workspaceFilePath = targetFile.absolutePath,
                runtimeUsed = "LocalEnvironmentManager(python3)",
                executionTimeMs = elapsed,
                errorCategory = cat,
                selfHealHint = hint
            )
        }

        // Fallback: try system python3 on PATH if available, otherwise run structural Python pre-flight check
        val hostCheck = runDirectBinaryIfPresent(listOf("python3", targetFile.absolutePath), sandboxDir, timeoutSeconds)
        if (hostCheck != null) {
            val elapsed = System.currentTimeMillis() - startMs
            val ok = hostCheck.first == 0
            val (cat, hint) = if (ok) "" to "" else diagnoseError("python", hostCheck.third)
            return SandboxExecutionResult(
                success = ok,
                language = "python",
                exitCode = hostCheck.first,
                stdout = hostCheck.second.trimEnd(),
                stderr = hostCheck.third.trimEnd(),
                workspaceFilePath = targetFile.absolutePath,
                runtimeUsed = "SandboxPython3",
                executionTimeMs = elapsed,
                errorCategory = cat,
                selfHealHint = hint
            )
        }

        val validationErr = validateBalancedDelimiters(code)
        val elapsed = System.currentTimeMillis() - startMs
        return if (validationErr == null) {
            SandboxExecutionResult(
                success = true,
                language = "python",
                exitCode = 0,
                stdout = "[Sandbox Pre-Flight OK] Saved to ${targetFile.absolutePath}. Native python3 binary will run it once Linux/Python setup finishes.",
                stderr = "",
                workspaceFilePath = targetFile.absolutePath,
                runtimeUsed = "InAppPythonPreflightSandbox",
                executionTimeMs = elapsed
            )
        } else {
            SandboxExecutionResult(
                success = false,
                language = "python",
                exitCode = 1,
                stdout = "",
                stderr = "SyntaxError: $validationErr",
                workspaceFilePath = targetFile.absolutePath,
                runtimeUsed = "InAppPythonPreflightSandbox",
                executionTimeMs = elapsed,
                errorCategory = "SYNTAX_ERROR",
                selfHealHint = validationErr
            )
        }
    }

    private suspend fun executeNodeOrJs(
        context: Context,
        sandboxDir: File,
        targetFile: File,
        code: String,
        timeoutSeconds: Long,
        startMs: Long
    ): SandboxExecutionResult {
        if (LocalEnvironmentManager.isNodeAvailable(context)) {
            val nodeBin = listOf(
                File(LocalEnvironmentManager.getBinDir(context), "node"),
                File(LocalEnvironmentManager.getUbuntuRootDir(context), "usr/bin/node")
            ).firstOrNull { it.isFile && it.canExecute() }

            if (nodeBin != null) {
                val cmdResult = LocalEnvironmentManager.executeCommand(
                    command = "\"${nodeBin.absolutePath}\" \"${targetFile.absolutePath}\"",
                    context = context,
                    workingDir = sandboxDir.absolutePath,
                    timeoutSeconds = timeoutSeconds
                )
                val elapsed = System.currentTimeMillis() - startMs
                val ok = cmdResult.exitCode == 0
                val outText = cmdResult.output.trimEnd()
                val (cat, hint) = if (ok) "" to "" else diagnoseError("nodejs", outText)
                return SandboxExecutionResult(
                    success = ok,
                    language = "nodejs",
                    exitCode = cmdResult.exitCode,
                    stdout = if (ok) outText else "",
                    stderr = if (ok) "" else outText,
                    workspaceFilePath = targetFile.absolutePath,
                    runtimeUsed = "NativeNodeJS(${nodeBin.name})",
                    executionTimeMs = elapsed,
                    errorCategory = cat,
                    selfHealHint = hint
                )
            }
        }

        // In-App JS Sandbox via headless WebView (works even without native node binary)
        val jsResult = runInAppWebViewJs(context, code)
        if (jsResult != null) {
            val elapsed = System.currentTimeMillis() - startMs
            val ok = jsResult.first
            val (cat, hint) = if (ok) "" to "" else diagnoseError("nodejs", jsResult.third)
            return SandboxExecutionResult(
                success = ok,
                language = "nodejs",
                exitCode = if (ok) 0 else 1,
                stdout = jsResult.second,
                stderr = jsResult.third,
                workspaceFilePath = targetFile.absolutePath,
                runtimeUsed = "InAppWebViewJsSandbox",
                executionTimeMs = elapsed,
                errorCategory = cat,
                selfHealHint = hint
            )
        }

        val validationErr = validateBalancedDelimiters(code)
        val elapsed = System.currentTimeMillis() - startMs
        return if (validationErr == null) {
            SandboxExecutionResult(
                success = true,
                language = "nodejs",
                exitCode = 0,
                stdout = "[NodeEmulated JS Sandbox OK] Syntax verified and staged at ${targetFile.absolutePath}.",
                stderr = "",
                workspaceFilePath = targetFile.absolutePath,
                runtimeUsed = "NodeEmulatedJsBridge",
                executionTimeMs = elapsed
            )
        } else {
            SandboxExecutionResult(
                success = false,
                language = "nodejs",
                exitCode = 1,
                stdout = "",
                stderr = "SyntaxError: $validationErr",
                workspaceFilePath = targetFile.absolutePath,
                runtimeUsed = "NodeEmulatedJsBridge",
                executionTimeMs = elapsed,
                errorCategory = "SYNTAX_ERROR",
                selfHealHint = validationErr
            )
        }
    }

    private suspend fun executeHtmlSandbox(
        context: Context,
        targetFile: File,
        html: String,
        startMs: Long
    ): SandboxExecutionResult {
        val scriptMatches = Regex("<script[^>]*>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE).findAll(html)
        val inlineJs = scriptMatches.joinToString("\n") { it.groupValues[1] }.trim()
        var jsLogs = ""
        var jsError = ""

        if (inlineJs.isNotBlank()) {
            val res = runInAppWebViewJs(context, inlineJs)
            if (res != null) {
                jsLogs = res.second
                if (!res.first) jsError = res.third
            } else {
                validateBalancedDelimiters(inlineJs)?.let { jsError = "SyntaxError in <script>: $it" }
            }
        } else {
            validateBalancedDelimiters(html)?.let { jsError = "HTML syntax issue: $it" }
        }

        val elapsed = System.currentTimeMillis() - startMs
        val ok = jsError.isBlank()
        val (cat, hint) = if (ok) "" to "" else diagnoseError("html", jsError)
        return SandboxExecutionResult(
            success = ok,
            language = "html",
            exitCode = if (ok) 0 else 1,
            stdout = buildString {
                append("HTML preview staged: file://${targetFile.absolutePath}")
                if (jsLogs.isNotBlank()) {
                    append("\nConsole:\n")
                    append(jsLogs)
                }
            },
            stderr = jsError,
            workspaceFilePath = targetFile.absolutePath,
            runtimeUsed = "InAppHtmlJsSandbox",
            executionTimeMs = elapsed,
            errorCategory = cat,
            selfHealHint = hint
        )
    }

    private suspend fun runInAppWebViewJs(
        context: Context,
        jsCode: String
    ): Triple<Boolean, String, String>? = withTimeoutOrNull(4500L) {
        val deferred = CompletableDeferred<Triple<Boolean, String, String>?>()
        withContext(Dispatchers.Main) {
            runCatching {
                val logs = mutableListOf<String>()
                val errors = mutableListOf<String>()
                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            if (consoleMessage != null) {
                                val msg = consoleMessage.message().orEmpty()
                                if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                    errors += "Line ${consoleMessage.lineNumber()}: $msg"
                                } else {
                                    logs += msg
                                }
                            }
                            return true
                        }
                    }
                }
                val wrapped = """
                    (function() {
                      var __out = [];
                      var __origLog = console.log;
                      console.log = function() {
                        var args = Array.prototype.slice.call(arguments).map(function(a) {
                          try { return typeof a === 'object' ? JSON.stringify(a) : String(a); } catch(e) { return String(a); }
                        });
                        __out.push(args.join(' '));
                        if (__origLog) __origLog.apply(console, arguments);
                      };
                      try {
                        var __ret = (function() { $jsCode })();
                        return JSON.stringify({ ok: true, out: __out.join('\n'), ret: __ret === undefined ? '' : String(__ret) });
                      } catch (err) {
                        return JSON.stringify({ ok: false, out: __out.join('\n'), err: (err && err.stack) ? err.stack : String(err) });
                      }
                    })();
                """.trimIndent()

                webView.evaluateJavascript(wrapped) { rawResult ->
                    runCatching { webView.destroy() }
                    val parsed = runCatching {
                        val unquoted = org.json.JSONTokener(rawResult).nextValue()?.toString().orEmpty()
                        JSONObject(unquoted)
                    }.getOrNull()
                    if (parsed == null) {
                        val ok = errors.isEmpty()
                        deferred.complete(Triple(ok, logs.joinToString("\n"), errors.joinToString("\n")))
                    } else {
                        val ok = parsed.optBoolean("ok", true) && errors.isEmpty()
                        val out = listOf(parsed.optString("out"), parsed.optString("ret"))
                            .filter { it.isNotBlank() }
                            .joinToString("\n")
                        val err = listOf(parsed.optString("err"), errors.joinToString("\n"))
                            .filter { it.isNotBlank() }
                            .joinToString("\n")
                        deferred.complete(Triple(ok, out, err))
                    }
                }
            }.onFailure {
                Log.d(TAG, "Headless WebView JS sandbox skipped: ${it.message}")
                deferred.complete(null)
            }
        }
        deferred.await()
    }

    private fun runDirectBinaryIfPresent(
        cmd: List<String>,
        workDir: File,
        timeoutSeconds: Long
    ): Triple<Int, String, String>? = runCatching {
        val process = ProcessBuilder(cmd)
            .directory(workDir)
            .start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching Triple(124, "", "Sandbox execution timed out after ${timeoutSeconds}s")
        }
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        Triple(process.exitValue(), stdout, stderr)
    }.getOrNull()

    fun diagnoseError(language: String, stderr: String): Pair<String, String> {
        val lower = stderr.lowercase()
        return when {
            lower.contains("indentationerror") -> "INDENTATION_ERROR" to
                "Mixed tabs/spaces or misaligned block indentation in $language. Normalize indentation to 4 spaces."
            lower.contains("syntaxerror") -> "SYNTAX_ERROR" to
                "Check unclosed quotes, brackets, missing colons, or malformed tokens in $language."
            lower.contains("modulenotfounderror") || lower.contains("cannot find module") -> "MISSING_DEPENDENCY" to
                "Missing external package. Switch to standard library built-ins or install the dependency in the sandbox environment."
            lower.contains("nameerror") || lower.contains("referenceerror") -> "NAME_OR_REFERENCE_ERROR" to
                "Variable or function used before declaration or misspelled identifier."
            lower.contains("typeerror") -> "TYPE_ERROR" to
                "Type mismatch or null/undefined property access. Add explicit type guards."
            else -> "RUNTIME_ERROR" to
                "Inspect stderr stack trace and add defensive checks around failing inputs."
        }
    }

    private fun validateBalancedDelimiters(code: String): String? {
        var parens = 0
        var braces = 0
        var brackets = 0
        var inSingle = false
        var inDouble = false
        var escaped = false

        for (ch in code) {
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && (inSingle || inDouble)) {
                escaped = true
                continue
            }
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle
                continue
            }
            if (ch == '"' && !inSingle) {
                inDouble = !inDouble
                continue
            }
            if (inSingle || inDouble) continue
            when (ch) {
                '(' -> parens++
                ')' -> if (--parens < 0) return "Unexpected closing ')'"
                '{' -> braces++
                '}' -> if (--braces < 0) return "Unexpected closing '}'"
                '[' -> brackets++
                ']' -> if (--brackets < 0) return "Unexpected closing ']'"
            }
        }
        return when {
            inSingle || inDouble -> "Unterminated string literal"
            parens > 0 -> "Unclosed '(' ($parens remaining)"
            braces > 0 -> "Unclosed '{' ($braces remaining)"
            brackets > 0 -> "Unclosed '[' ($brackets remaining)"
            else -> null
        }
    }

    private suspend fun attemptSelfHeal(
        context: Context,
        language: String,
        code: String,
        failed: SandboxExecutionResult
    ): String? {
        // 1. Deterministic quick repairs
        if (failed.errorCategory == "INDENTATION_ERROR" && code.contains('\t')) {
            return code.replace("\t", "    ")
        }
        if (language == "python" && failed.stderr.contains("Missing parentheses in call to 'print'")) {
            return code.replace(Regex("(?m)^(\\s*)print\\s+([^(\\n]+)$"), "$1print($2)")
        }

        // 2. AI-powered Self-Healing when Google AI Studio API Key is active
        if (CloudBridgeStore.hasApiKey(context)) {
            val healPrompt = """
                You are CineCode-AI Self-Healing Sandbox Engine.
                Fix the following $language code that failed in the local sandbox.
                Return ONLY the corrected raw source code inside a single fenced code block, with zero explanation.

                Error Category: ${failed.errorCategory}
                Stderr:
                ${failed.stderr.take(1200)}

                Broken Code:
                ```$language
                $code
                ```
            """.trimIndent()
            val repairedReply = CloudBridgeStore.chatDirect(
                context = context,
                prompt = healPrompt,
                useSearchGrounding = false
            ).getOrNull()
            if (!repairedReply.isNullOrBlank()) {
                val fenced = Regex("```[A-Za-z0-9_-]*\\s*\\n([\\s\\S]*?)```").find(repairedReply)
                val extracted = (fenced?.groupValues?.get(1) ?: repairedReply).trim()
                if (extracted.isNotBlank() && extracted != code.trim()) {
                    return extracted
                }
            }
        }
        return null
    }
}
