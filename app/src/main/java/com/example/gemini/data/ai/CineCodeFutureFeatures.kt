package com.shriram.cinecode.data.ai

/**
 * Self-Healing Code Runner तथा भविष्य के AI मॉड्यूल्स (Multi-Agent Workflow, Smart Voice/Media Automation)
 * के लिए हल्का और मॉड्यूलर कोर लेयर। कम RAM (4GB) पर सुरक्षित चलने के लिए सीमित बफ़र का उपयोग करता है।
 */
data class DetectedCodeError(
    val exitCode: Int?,
    val headline: String,
    val errorSnippet: String,
    val logTail: String
)

sealed class SelfHealingRunnerState {
    object Idle : SelfHealingRunnerState()
    object Succeeded : SelfHealingRunnerState()
    data class ErrorDetected(
        val error: DetectedCodeError,
        val attemptCount: Int,
        val maxAttempts: Int = MAX_HEAL_ATTEMPTS
    ) : SelfHealingRunnerState()

    data class HealingDispatched(
        val attemptCount: Int,
        val maxAttempts: Int = MAX_HEAL_ATTEMPTS
    ) : SelfHealingRunnerState()

    data class Exhausted(
        val error: DetectedCodeError,
        val maxAttempts: Int = MAX_HEAL_ATTEMPTS
    ) : SelfHealingRunnerState()

    companion object {
        const val MAX_HEAL_ATTEMPTS = 3
    }
}

object SelfHealingCoordinator {

    private val ANSI_REGEX = Regex("\\u001B\\[[;\\d]*[ -/]*[@-~]|\\u001B\\][^\\u0007]*\\u0007")

    private val ERROR_PATTERNS = listOf(
        Regex("Traceback \\(most recent call last\\)", RegexOption.IGNORE_CASE),
        Regex("\\b(SyntaxError|IndentationError|NameError|TypeError|ValueError|ImportError|ModuleNotFoundError|AttributeError|KeyError|IndexError|ZeroDivisionError)\\b"),
        Regex("\\b(ReferenceError|RangeError|URIError|EvalError)\\b"),
        Regex("\\b(NullPointerException|IllegalStateException|IllegalArgumentException|ClassNotFoundException|NoClassDefFoundError|RuntimeException)\\b"),
        Regex("(^|\\s)(error:|fatal:|exception:|panic:|segmentation fault|command not found|permission denied|no such file or directory)", RegexOption.IGNORE_CASE),
        Regex("BUILD FAILED", RegexOption.IGNORE_CASE),
        Regex("Unresolved reference:", RegexOption.IGNORE_CASE)
    )

    private val NOISE_LINES = setOf(
        "[Process completed - Press Enter to close tab]"
    )

    /**
     * टर्मिनल आउटपुट से ANSI कोड हटाकर अधिकतम [maxLines] अंतिम साफ़ लाइनें निकालता है।
     */
    fun extractCompactErrorTail(rawTranscript: String, maxLines: Int = 80): String {
        if (rawTranscript.isBlank()) return ""
        val clean = ANSI_REGEX.replace(rawTranscript, "")
        val lines = clean.lines()
            .map { it.trimEnd() }
            .filter { line -> line.isNotBlank() && NOISE_LINES.none { noise -> line.contains(noise) } }
        return if (lines.size > maxLines) {
            lines.takeLast(maxLines).joinToString("\n")
        } else {
            lines.joinToString("\n")
        }
    }

    /**
     * एग्जिट कोड और लॉग टेल का विश्लेषण करके बताता है कि कोड रन विफल हुआ है या नहीं।
     */
    fun detectFailure(exitCode: Int?, rawTranscript: String): DetectedCodeError? {
        val tail = extractCompactErrorTail(rawTranscript, maxLines = 80)
        val lines = tail.lines()
        val matchedLine = lines.lastOrNull { line ->
            ERROR_PATTERNS.any { pattern -> pattern.containsMatchIn(line) }
        }

        val failedByExit = exitCode != null && exitCode != 0
        if (!failedByExit && matchedLine == null) {
            return null
        }

        val snippetLines = if (matchedLine != null) {
            val idx = lines.indexOfLast { it == matchedLine }
            val start = (idx - 3).coerceAtLeast(0)
            val end = (idx + 6).coerceAtMost(lines.size)
            lines.subList(start, end).joinToString("\n")
        } else {
            lines.takeLast(12).joinToString("\n")
        }

        val headline = matchedLine?.trim()?.take(110)
            ?: if (exitCode != null) "प्रोसेस त्रुटि कोड $exitCode के साथ बंद हुआ" else "कोड निष्पादन में त्रुटि"

        return DetectedCodeError(
            exitCode = exitCode,
            headline = headline,
            errorSnippet = snippetLines.ifBlank { headline },
            logTail = tail
        )
    }

    /**
     * सक्रिय AI एजेंट (Antigravity / Claude) के लिए स्पष्ट Auto-Fix प्रॉम्प्ट तैयार करता है।
     */
    fun buildAutoFixPrompt(
        filePath: String?,
        fileContent: String?,
        command: String,
        error: DetectedCodeError,
        attemptCount: Int
    ): String {
        val safeContent = fileContent?.take(6000)
        return buildString {
            appendLine("[Self-Healing Code Runner · प्रयास #$attemptCount]")
            appendLine("कृपया इस कोड एरर का विश्लेषण करें, संबंधित फाइल को सीधे ठीक (patch/edit) करें, और सरल हिंदी में बताएं कि क्या सुधारा गया:")
            appendLine()
            appendLine("• चलाया गया कमांड: `$command`")
            if (!filePath.isNullOrBlank()) {
                appendLine("• सक्रिय फाइल: `$filePath`")
            }
            if (error.exitCode != null) {
                appendLine("• Exit Code: ${error.exitCode}")
            }
            appendLine()
            appendLine("एरर आउटपुट:")
            appendLine("```")
            appendLine(error.errorSnippet.ifBlank { error.logTail.takeLast(1800) })
            appendLine("```")
            if (!safeContent.isNullOrBlank()) {
                appendLine()
                appendLine("फाइल का वर्तमान कोड:")
                appendLine("```")
                appendLine(safeContent)
                appendLine("```")
            }
        }.trim()
    }
}

/**
 * भविष्य के AI मॉड्यूल्स (Multi-Agent Workflow और Smart Voice/Media Automation) के लिए मॉड्यूलर हुक्स।
 */
enum class MultiAgentRole {
    PLANNER,
    CODER,
    EXECUTOR
}

data class MultiAgentStepEvent(
    val role: MultiAgentRole,
    val summary: String,
    val isCompleted: Boolean = false
)

interface MultiAgentWorkflowHook {
    suspend fun onPlanReady(goal: String, steps: List<String>)
    suspend fun onStepUpdate(event: MultiAgentStepEvent)
}

interface VoiceMediaAutomationHook {
    suspend fun onVoiceIntentDetected(transcript: String, targetScreen: String?): Boolean
}

object FutureAiFeatureRegistry {
    @Volatile
    var multiAgentHook: MultiAgentWorkflowHook? = null

    @Volatile
    var voiceAutomationHook: VoiceMediaAutomationHook? = null
}
