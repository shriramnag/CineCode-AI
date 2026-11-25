package com.shriram.cinecode.ui.browser.mcp

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.view.KeyEvent
import com.shriram.cinecode.ui.browser.BrowserSessionManager
import com.shriram.cinecode.ui.browser.BrowserTabSession
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full AntiGem-parity Browser Automation MCP implementation built on top of [BrowserSessionManager]
 * and `assets/browser-mcp/page-agent.js` (`window.__antigemAgent`).
 */
object BrowserAgentMcp {

    const val INSTRUCTIONS = "Controls the app's built-in browser (Android WebView tabs) like a person would. " +
        "Look, then act: open a page with browser_open_url, then get element refs (like ref_12) from browser_find (one element by description) " +
        "or browser_read_page (the page's structure), and act on refs with browser_computer (left_click, type, key, hover, scroll_to) " +
        "or browser_form_input (fill fields, pick options, tick boxes). Take a screenshot (browser_computer action=screenshot) when you need to see the page; " +
        "coordinates you pass are pixels in the latest screenshot of that tab. Read articles with browser_get_page_text. " +
        "Put steps you can predict into one browser_batch call (e.g. click a field, type, press Enter, screenshot). " +
        "Check results: actions report page changes and new tabs; read the page again or take a screenshot when unsure. Refs stay valid until the page changes. " +
        "Never click file inputs: use browser_upload_file with the ref. For a task of several steps, call browser_lock once first and browser_lock locked=false when you finish. " +
        "If a page opens an alert/confirm/prompt, answer it with browser_handle_dialog. Omit tab_id to use the active tab; browser_list_tabs lists all tabs."

    private val TOOL_ALIASES = mapOf(
        "computer" to "browser_computer",
        "navigate" to "browser_open_url",
        "javascript_tool" to "browser_eval_js",
        "tabs_context" to "browser_list_tabs",
        "tabs_context_mcp" to "browser_list_tabs",
        "read_console_messages" to "browser_get_console_logs",
        "read_network_requests" to "browser_read_network",
        "file_upload" to "browser_upload_file"
    )

    val LEGACY_TOOLS = setOf("browser_screenshot", "browser_inspect_dom", "browser_interact")

    @Volatile
    private var cachedPageAgentScript: String? = null

    private fun pageAgentScript(context: Context): String {
        cachedPageAgentScript?.let { return it }
        val loaded = runCatching {
            context.assets.open("browser-mcp/page-agent.js").bufferedReader().use { it.readText() }
        }.getOrElse { error("Missing assets/browser-mcp/page-agent.js: ${it.message}") }
        cachedPageAgentScript = loaded
        return loaded
    }

    private fun prop(type: String, description: String, enumValues: List<String>? = null): JSONObject =
        JSONObject().apply {
            put("type", type)
            put("description", description)
            if (enumValues != null) put("enum", JSONArray(enumValues))
        }

    private fun arrayOfNumbers(len: Int, description: String): JSONObject = JSONObject().apply {
        put("type", "array")
        put("description", description)
        put("minItems", len)
        put("maxItems", len)
        put("items", JSONObject().put("type", "number"))
    }

    private fun toolDef(
        name: String,
        description: String,
        required: List<String> = emptyList(),
        vararg props: Pair<String, JSONObject>
    ): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("inputSchema", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                props.forEach { (k, v) -> put(k, v) }
            })
            if (required.isNotEmpty()) put("required", JSONArray(required))
        })
    }

    private val tabIdProp = "tab_id" to prop("string", "Tab id from browser_list_tabs; omit for the active tab")
    private val summaryProp = "action_summary" to prop(
        "string",
        "Optional: 2-5 words saying what you are doing, e.g. \"Searching for item\". Never include passwords."
    )

    fun tools(): JSONArray = JSONArray().apply {
        put(toolDef(
            "browser_open_url",
            "Open a URL in the in-app WebView browser (supports localhost, http, https). Runs in background without closing chat.",
            listOf("url"),
            "url" to prop("string", "URL to load, e.g. http://localhost:5173 or https://example.com"),
            "new_tab" to prop("boolean", "Whether to open in a new tab (default false)"),
            "desktop_mode" to prop("boolean", "Open in desktop/web view mode if true, mobile if false (optional)"),
            "mode" to prop("string", "View mode: 'desktop' (or 'web') vs 'mobile' (optional)")
        ))
        put(toolDef(
            "browser_set_view_mode",
            "Toggle or set mobile vs desktop/web view mode for a browser tab.",
            emptyList(),
            tabIdProp,
            "desktop_mode" to prop("boolean", "true for desktop/web mode, false for mobile mode"),
            "mode" to prop("string", "'desktop' (or 'web') vs 'mobile'")
        ))
        put(toolDef(
            "browser_read_page",
            "Read the page as an indented tree of elements: role, name, state and value, each with a ref (e.g. ref_12) that browser_computer, browser_form_input and browser_upload_file accept. filter=\"interactive\" lists only controls you can click or type into.",
            emptyList(),
            tabIdProp,
            "filter" to prop("string", "all (default) or interactive", listOf("all", "interactive")),
            "depth" to prop("integer", "Maximum tree depth (default 25)"),
            "ref_id" to prop("string", "Read only this element ref and what is inside it"),
            "max_chars" to prop("integer", "Maximum characters returned (default 50000)")
        ))
        put(toolDef(
            "browser_find",
            "Find elements by what they are or say, e.g. \"search box\", \"Add to cart button\", \"email field\". Returns up to 20 matches with refs.",
            listOf("query"),
            tabIdProp,
            "query" to prop("string", "What to look for, in plain words")
        ))
        put(toolDef(
            "browser_get_page_text",
            "Read the page's main text as plain text (article or main content). Has no refs; use browser_find or browser_read_page to act on things.",
            emptyList(),
            tabIdProp,
            "max_chars" to prop("integer", "Maximum characters returned (default 50000)"),
            "include_hidden" to prop("boolean", "Also read hidden text: collapsed sections, inactive tabs (default false)")
        ))
        put(toolDef(
            "browser_computer",
            "Use mouse and keyboard on a tab, and take screenshots. Target elements by ref (preferred; scrolls into view) or coordinate [x, y] pixels from the latest screenshot.",
            listOf("action"),
            tabIdProp,
            "action" to prop(
                "string",
                "Action to perform",
                listOf(
                    "screenshot", "left_click", "right_click", "double_click", "triple_click",
                    "hover", "left_click_drag", "type", "key", "scroll", "scroll_to", "zoom", "wait"
                )
            ),
            "ref" to prop("string", "Element ref, e.g. ref_12 (clicks, hover, scroll_to)"),
            "coordinate" to arrayOfNumbers(2, "[x, y] in pixels of latest screenshot (clicks, hover, scroll; drag end point)"),
            "start_coordinate" to arrayOfNumbers(2, "[x, y] where left_click_drag starts"),
            "text" to prop("string", "Text to type (action=type), or space-separated keys (action=key, e.g. Enter, Tab, ctrl+a)"),
            "modifiers" to prop("string", "Modifier keys held during click: ctrl, shift, alt, meta (e.g. \"ctrl+shift\")"),
            "repeat" to prop("integer", "key: press the sequence this many times (1-100, default 1)"),
            "scroll_direction" to prop("string", "scroll direction", listOf("up", "down", "left", "right")),
            "scroll_amount" to prop("integer", "scroll: wheel ticks 1-10 (default 3)"),
            "region" to arrayOfNumbers(4, "zoom: [x0, y0, x1, y1] rectangle in pixels of latest screenshot"),
            "duration" to prop("number", "wait: seconds, up to 10"),
            "scale" to prop("number", "screenshot/zoom: 0.1-1 to scale image size down"),
            summaryProp
        ))
        put(toolDef(
            "browser_form_input",
            "Set a form field by ref cleanly (text/number/date inputs, textareas, <select>, checkboxes, radios, contenteditable).",
            listOf("ref", "value"),
            tabIdProp,
            "ref" to prop("string", "Field ref from browser_find / browser_read_page"),
            "value" to JSONObject().apply {
                put("description", "New value: string, number, boolean, or array of option strings for multi-select")
                put("type", JSONArray(listOf("string", "number", "boolean", "array")))
            },
            summaryProp
        ))
        put(toolDef(
            "browser_upload_file",
            "Attach one or more local files to a file upload control or drop zone without clicking native pickers.",
            listOf("file_paths"),
            tabIdProp,
            "ref" to prop("string", "Element ref of the file input or upload button (preferred)"),
            "file_paths" to JSONObject().apply {
                put("type", "array")
                put("items", JSONObject().put("type", "string"))
                put("description", "Local file path(s) to upload")
            },
            "selector" to prop("string", "CSS selector fallback if ref omitted"),
            "x" to prop("number", "Fallback viewport X coordinate"),
            "y" to prop("number", "Fallback viewport Y coordinate"),
            "method" to prop("string", "auto, chooser, assign, or drop")
        ))
        put(toolDef(
            "browser_eval_js",
            "Run JavaScript in the page and get the value of the last expression.",
            listOf("script"),
            tabIdProp,
            "script" to prop("string", "JavaScript code to run in page context")
        ))
        put(toolDef(
            "browser_get_console_logs",
            "Retrieve captured browser console logs and network errors for a tab.",
            emptyList(),
            tabIdProp,
            "level" to prop("string", "Optional filter: DEBUG, LOG, INFO, WARN, ERROR, ALL"),
            "pattern" to prop("string", "Filter messages containing text/regex"),
            "only_errors" to prop("boolean", "Return only ERROR level messages"),
            "limit" to prop("integer", "Newest N logs (default 100)"),
            "clear" to prop("boolean", "Clear console log buffer after reading")
        ))
        put(toolDef(
            "browser_read_network",
            "List the tab's network requests (documents, XHR/fetch, scripts, images) with method, status, type and errors.",
            emptyList(),
            tabIdProp,
            "url_pattern" to prop("string", "Only URLs containing this text (e.g. /api/)"),
            "only_failed" to prop("boolean", "Only failed requests and HTTP errors (status >= 400)"),
            "limit" to prop("integer", "Newest N requests (default 100, max 500)"),
            "clear" to prop("boolean", "Clear recorded requests after reading")
        ))
        put(toolDef(
            "browser_list_tabs",
            "List all open browser tabs with IDs, URLs, titles, loading progress, lock state, dialogs, and error counts."
        ))
        put(toolDef(
            "browser_switch_tab",
            "Switch active browser tab by tab_id.",
            listOf("tab_id"),
            "tab_id" to prop("string", "ID of tab to activate")
        ))
        put(toolDef(
            "browser_close_tab",
            "Close a browser tab by tab_id.",
            listOf("tab_id"),
            "tab_id" to prop("string", "ID of tab to close")
        ))
        put(toolDef(
            "browser_lock",
            "Take or give back control of the browser for your task. locked=true blocks user touches, keyboard and zoom; locked=false releases lock.",
            emptyList(),
            "tab_id" to prop("string", "Lock: tab to lock (default active). Unlock: specific tab, or omit to unlock all"),
            "locked" to prop("boolean", "true to lock (default), false to release"),
            summaryProp
        ))
        put(toolDef(
            "browser_handle_dialog",
            "Answer an open JavaScript alert, confirm, prompt or beforeunload dialog.",
            listOf("accept"),
            "tab_id" to prop("string", "Tab with the dialog; omit for whichever tab has it"),
            "accept" to prop("boolean", "true for OK/Leave, false for Cancel/Stay"),
            "prompt_text" to prop("string", "Text for prompt dialogs")
        ))
        put(toolDef(
            "browser_batch",
            "Run several browser tool calls sequentially in one round trip, stopping at the first error. Batches cannot be nested.",
            listOf("actions"),
            "actions" to JSONObject().apply {
                put("type", "array")
                put("minItems", 1)
                put("description", "Tool calls to run in order")
                put("items", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("name", prop("string", "Tool name, e.g. browser_computer"))
                        put("input", JSONObject().put("type", "object").put("description", "Tool arguments"))
                    })
                    put("required", JSONArray(listOf("name", "input")))
                })
            }
        ))
    }

    fun isBrowserTool(name: String): Boolean {
        val resolved = TOOL_ALIASES[name] ?: name
        if (resolved in LEGACY_TOOLS) return true
        val defs = tools()
        return (0 until defs.length()).any { defs.getJSONObject(it).getString("name") == resolved }
    }

    private fun textResult(text: String, isError: Boolean = false): JSONObject = JSONObject().apply {
        if (isError) put("isError", true)
        put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
    }

    private fun imageResult(base64Jpeg: String, caption: String? = null): JSONObject = JSONObject().apply {
        val arr = JSONArray()
        arr.put(JSONObject().apply {
            put("type", "image")
            put("data", base64Jpeg)
            put("mimeType", "image/jpeg")
        })
        if (!caption.isNullOrBlank()) {
            arr.put(JSONObject().put("type", "text").put("text", caption))
        }
        put("content", arr)
    }

    private fun resolveTab(tabId: String?): BrowserTabSession {
        val mgr = BrowserSessionManager.instance
        if (tabId.isNullOrBlank()) {
            return mgr.getActiveTab()
                ?: throw IllegalStateException("No open browser tab. Call browser_open_url first.")
        }
        return mgr.getTab(tabId)
            ?: throw IllegalStateException("No tab with id $tabId. Open tabs: ${mgr.tabs.joinToString { it.id }.ifBlank { "none" }}.")
    }

    private fun checkNoOpenDialog() {
        val dlgTab = BrowserSessionManager.instance.findTabWithDialog() ?: return
        val dlg = dlgTab.pendingDialog ?: return
        val label = if (dlg.type == "beforeunload") "\"Leave this page?\" dialog" else "JavaScript ${dlg.type}"
        val extra = if (dlg.type == "prompt") " (pass prompt_text to answer)" else ""
        val leave = if (dlg.type == "beforeunload") "/Leave" else ""
        throw IllegalStateException(
            "A $label is open on tab ${dlgTab.id}: \"${dlg.message.take(200)}\"$extra. " +
                "Every page is paused until answered. Call browser_handle_dialog with accept=true (OK$leave) or accept=false (Cancel)."
        )
    }

    private suspend fun ensurePageAgent(context: Context, tab: BrowserTabSession) {
        val mgr = BrowserSessionManager.instance
        val check = mgr.evalMainJs(tab.id, "Boolean(window.__antigemAgent && window.__antigemAgent.version === 10)")
            .getOrNull()?.trim()
        if (check == "true") return
        val script = pageAgentScript(context)
        val res = mgr.evalMainJs(tab.id, script)
        if (res.isFailure) {
            throw IllegalStateException("Failed to inject page helper: ${res.exceptionOrNull()?.message}")
        }
    }

    private suspend fun callAgentJson(context: Context, tab: BrowserTabSession, jsExpr: String): JSONObject {
        ensurePageAgent(context, tab)
        val raw = BrowserSessionManager.instance.evalMainJs(tab.id, jsExpr).getOrThrow().trim()
        val parsed = runCatching {
            val unwrapped = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                JSONObject("{\"s\":$raw}").getString("s")
            } else raw
            JSONObject(unwrapped)
        }.getOrNull() ?: throw IllegalStateException("Unexpected page helper result: ${raw.take(200)}")
        if (!parsed.optBoolean("ok", true)) {
            throw IllegalStateException(parsed.optString("error", "Browser page action failed"))
        }
        return parsed
    }

    suspend fun call(context: Context, rawName: String, args: JSONObject, depth: Int = 0): JSONObject {
        val name = TOOL_ALIASES[rawName] ?: rawName
        return runCatching {
            when (name) {
                "browser_handle_dialog" -> {
                    val accept = args.getBoolean("accept")
                    val tabId = args.optString("tab_id").ifBlank { null }
                    val promptText = if (args.has("prompt_text") && !args.isNull("prompt_text")) args.optString("prompt_text") else null
                    val msg = BrowserSessionManager.instance.handleJsDialog(tabId, accept, promptText).getOrThrow()
                    textResult(msg)
                }
                "browser_list_tabs" -> {
                    textResult(BrowserSessionManager.instance.listTabsJson().toString(2))
                }
                "browser_switch_tab" -> {
                    val tabId = args.getString("tab_id")
                    val ok = BrowserSessionManager.instance.switchTab(tabId)
                    if (!ok) textResult("Tab $tabId not found", isError = true)
                    else textResult("Switched to tab $tabId")
                }
                "browser_close_tab" -> {
                    val tabId = args.getString("tab_id")
                    val ok = BrowserSessionManager.instance.closeTab(tabId)
                    if (!ok) textResult("Tab $tabId not found", isError = true)
                    else textResult("Closed tab $tabId")
                }
                "browser_lock" -> {
                    val tabId = args.optString("tab_id").ifBlank { null }
                    val locked = if (args.has("locked")) args.optBoolean("locked", true) else true
                    val msg = BrowserSessionManager.instance.setAgentLock(tabId, locked).getOrThrow()
                    textResult(msg)
                }
                "browser_open_url" -> {
                    checkNoOpenDialog()
                    val url = args.getString("url")
                    val newTab = args.optBoolean("new_tab", false)
                    val desktopMode = when {
                        args.has("desktop_mode") -> args.optBoolean("desktop_mode")
                        args.has("mode") -> args.optString("mode").lowercase() in setOf("desktop", "web")
                        else -> null
                    }
                    val tab = BrowserSessionManager.instance.openUrl(url, newTab = newTab, desktopMode = desktopMode)
                    BrowserSessionManager.instance.waitForPageLoad(tab.id, 6000)
                    val modeStr = if (tab.isDesktopMode) "desktop" else "mobile"
                    textResult("Opened ${tab.url.ifBlank { url }} in tab ${tab.id} ('${tab.title}') [viewMode: $modeStr]")
                }
                "browser_set_view_mode" -> {
                    val tab = resolveTab(args.optString("tab_id").ifBlank { null })
                    val enableDesktop = when {
                        args.has("desktop_mode") -> args.optBoolean("desktop_mode")
                        args.has("mode") -> args.optString("mode").lowercase() in setOf("desktop", "web")
                        else -> !tab.isDesktopMode
                    }
                    BrowserSessionManager.instance.setDesktopMode(tab, enableDesktop)
                    textResult("Switched tab ${tab.id} to ${if (enableDesktop) "desktop" else "mobile"} mode (URL: ${tab.url})")
                }
                "browser_read_page" -> {
                    checkNoOpenDialog()
                    val tab = resolveTab(args.optString("tab_id").ifBlank { null })
                    val opts = JSONObject().apply {
                        put("filter", args.optString("filter", "all").ifBlank { "all" })
                        if (args.has("depth")) put("depth", args.optInt("depth", 25))
                        args.optString("ref_id").takeIf { it.isNotBlank() }?.let { put("refId", it) }
                        if (args.has("max_chars")) put("maxChars", args.optInt("max_chars", 50000))
                    }
                    val res = callAgentJson(context, tab, "window.__antigemAgent.tree($opts)")
                    textResult(res.optString("text", ""))
                }
                "browser_find" -> {
                    checkNoOpenDialog()
                    val tab = resolveTab(args.optString("tab_id").ifBlank { null })
                    val query = JSONObject.quote(args.getString("query"))
                    val res = callAgentJson(context, tab, "window.__antigemAgent.find($query)")
                    textResult(res.optString("text", ""))
                }
                "browser_get_page_text" -> {
                    checkNoOpenDialog()
                    val tab = resolveTab(args.optString("tab_id").ifBlank { null })
                    val maxChars = args.optInt("max_chars", 50000)
                    val includeHidden = args.optBoolean("include_hidden", false)
                    val res = callAgentJson(context, tab, "window.__antigemAgent.pageText($maxChars, $includeHidden)")
                    textResult(res.optString("text", ""))
                }
                "browser_form_input" -> {
                    checkNoOpenDialog()
                    val tab = resolveTab(args.optString("tab_id").ifBlank { null })
                    val ref = JSONObject.quote(args.getString("ref"))
                    val rawVal = args.opt("value")
                    val valLiteral = when (rawVal) {
                        is JSONArray -> rawVal.toString()
                        is Boolean, is Number -> rawVal.toString()
                        null, JSONObject.NULL -> "null"
                        else -> JSONObject.quote(rawVal.toString())
                    }
                    val res = callAgentJson(context, tab, "window.__antigemAgent.formInput($ref, $valLiteral)")
                    textResult(res.optString("text", "Updated form field."))
                }
                "browser_computer" -> {
                    checkNoOpenDialog()
                    val tab = resolveTab(args.optString("tab_id").ifBlank { null })
                    executeComputerAction(context, tab, args)
                }
                "browser_read_network" -> {
                    val tab = resolveTab(args.optString("tab_id").ifBlank { null })
                    val pattern = args.optString("url_pattern").ifBlank { null }
                    val onlyFailed = args.optBoolean("only_failed", false)
                    val limit = args.optInt("limit", 100).coerceIn(1, 500)
                    val clear = args.optBoolean("clear", false)
                    val records = BrowserSessionManager.instance.getNetworkRecords(tab.id, pattern, onlyFailed, limit, clear)
                    val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                    if (records.isEmpty()) {
                        val errs = BrowserSessionManager.instance.getNetworkErrors(tab.id, limit)
                        if (errs.isEmpty()) {
                            textResult("No network requests recorded yet on tab ${tab.id}.")
                        } else {
                            textResult(errs.joinToString("\n") { "[${fmt.format(Date(it.timestamp))}] ERR ${it.errorCode} ${it.url} — ${it.description}" })
                        }
                    } else {
                        val lines = records.joinToString("\n") { rec ->
                            val err = rec.errorDescription?.let { " ($it)" } ?: ""
                            "[${fmt.format(Date(rec.timestamp))}] ${rec.method} ${rec.status} [${rec.resourceType}] ${rec.url}$err"
                        }
                        textResult("${records.size} request(s), oldest first:\n$lines")
                    }
                }
                "browser_batch" -> {
                    if (depth > 0) throw IllegalArgumentException("browser_batch calls cannot be nested.")
                    val actions = args.getJSONArray("actions")
                    if (actions.length() == 0) throw IllegalArgumentException("actions must be a non-empty array.")
                    val combinedContent = JSONArray()
                    for (i in 0 until actions.length()) {
                        val step = actions.getJSONObject(i)
                        val stepName = step.getString("name")
                        val stepInput = step.optJSONObject("input") ?: JSONObject()
                        val res = call(context, stepName, stepInput, depth + 1)
                        val arr = res.optJSONArray("content") ?: JSONArray()
                        for (j in 0 until arr.length()) {
                            combinedContent.put(arr.getJSONObject(j))
                        }
                        if (res.optBoolean("isError", false)) {
                            return JSONObject().put("isError", true).put("content", combinedContent)
                        }
                    }
                    JSONObject().put("content", combinedContent)
                }
                "browser_eval_js" -> {
                    checkNoOpenDialog()
                    val tabId = args.optString("tab_id").ifBlank { null }
                    val res = BrowserSessionManager.instance.evaluateJs(tabId, args.getString("script"))
                    textResult(res.getOrElse { "Error: ${it.message}" }, isError = res.isFailure)
                }
                "browser_get_console_logs" -> {
                    val tab = resolveTab(args.optString("tab_id").ifBlank { null })
                    val level = args.optString("level").ifBlank { null }
                    val pattern = args.optString("pattern").ifBlank { null }
                    val onlyErrors = args.optBoolean("only_errors", false)
                    val limit = args.optInt("limit", 100)
                    val logs = BrowserSessionManager.instance.getConsoleLogs(tab.id, if (onlyErrors) "ERROR" else level, limit)
                        .filter { pattern == null || it.message.contains(pattern, ignoreCase = true) || it.sourceId.contains(pattern, ignoreCase = true) }
                    if (args.optBoolean("clear", false)) tab.consoleLogs.clear()
                    val netErrors = BrowserSessionManager.instance.getNetworkErrors(tab.id, limit)
                    val out = JSONObject()
                        .put("consoleLogs", JSONArray().apply { logs.forEach { put(it.toJsonObject()) } })
                        .put("networkErrors", JSONArray().apply { netErrors.forEach { put(it.toJsonObject()) } })
                    textResult(out.toString(2))
                }
                "browser_upload_file" -> {
                    checkNoOpenDialog()
                    val tab = resolveTab(args.optString("tab_id").ifBlank { null })
                    val paths = args.optJSONArray("file_paths")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                    var fx: Float? = null
                    var fy: Float? = null
                    var x = if (args.has("x")) args.optDouble("x").toFloat() else null
                    var y = if (args.has("y")) args.optDouble("y").toFloat() else null
                    val ref = args.optString("ref").ifBlank { null }
                    if (ref != null) {
                        val r = callAgentJson(context, tab, "window.__antigemAgent.resolve(${JSONObject.quote(ref)}, true)")
                        val vw = r.optDouble("vw", 1.0).toFloat().coerceAtLeast(1f)
                        val vh = r.optDouble("vh", 1.0).toFloat().coerceAtLeast(1f)
                        fx = r.optDouble("x", 0.0).toFloat() / vw
                        fy = r.optDouble("y", 0.0).toFloat() / vh
                        x = null; y = null
                    }
                    val res = BrowserSessionManager.instance.uploadFiles(
                        tabId = tab.id,
                        paths = paths,
                        selector = args.optString("selector").ifBlank { null },
                        x = x, y = y, fx = fx, fy = fy,
                        method = args.optString("method").ifBlank { "auto" },
                        timeoutMs = args.optInt("timeout_seconds", 10).coerceIn(1, 30) * 1000L
                    )
                    if (res.isFailure) textResult("Upload failed: ${res.exceptionOrNull()?.message}", isError = true)
                    else textResult(res.getOrThrow().toString(2))
                }
                "browser_screenshot" -> {
                    val tabId = args.optString("tab_id").ifBlank { null }
                    val res = BrowserSessionManager.instance.captureScreenshotBase64(tabId, args.optBoolean("full_page", false), args.optInt("quality", 80))
                    if (res.isSuccess) imageResult(res.getOrNull()!!.removePrefix("data:image/jpeg;base64,"))
                    else textResult("Screenshot failed: ${res.exceptionOrNull()?.message}", isError = true)
                }
                "browser_inspect_dom" -> {
                    val tabId = args.optString("tab_id").ifBlank { null }
                    val res = BrowserSessionManager.instance.inspectDom(tabId)
                    if (res.isSuccess) textResult(res.getOrThrow().toString(2))
                    else textResult("Inspect DOM failed: ${res.exceptionOrNull()?.message}", isError = true)
                }
                "browser_interact" -> {
                    val mgr = BrowserSessionManager.instance
                    val tabId = args.optString("tab_id").ifBlank { null }
                    val action = args.optString("action", "")
                    val selector = args.optString("selector").ifBlank { null }
                    when (action) {
                        "click" -> {
                            val r = mgr.clickElement(tabId, selector, if (args.has("x")) args.optDouble("x").toFloat() else null, if (args.has("y")) args.optDouble("y").toFloat() else null)
                            textResult(if (r.isSuccess) "Clicked successfully" else "Click failed: ${r.exceptionOrNull()?.message}", isError = r.isFailure)
                        }
                        "type" -> {
                            val r = mgr.typeText(tabId, selector ?: "input", args.optString("text"), args.optBoolean("clear_first", false))
                            textResult(if (r.isSuccess) "Typed text successfully" else "Type failed: ${r.exceptionOrNull()?.message}", isError = r.isFailure)
                        }
                        "scroll" -> {
                            val r = mgr.scrollPage(tabId, args.optInt("dx", 0), args.optInt("dy", 0), selector)
                            textResult(if (r.isSuccess) "Scrolled successfully" else "Scroll failed: ${r.exceptionOrNull()?.message}", isError = r.isFailure)
                        }
                        else -> textResult("Unknown browser_interact action: $action", isError = true)
                    }
                }
                else -> textResult("Unknown browser tool: $rawName", isError = true)
            }
        }.getOrElse { err ->
            textResult(err.message ?: err.javaClass.simpleName, isError = true)
        }
    }

    private suspend fun executeComputerAction(
        context: Context,
        tab: BrowserTabSession,
        args: JSONObject
    ): JSONObject {
        val mgr = BrowserSessionManager.instance
        val action = args.getString("action")
        val wv = tab.webView ?: throw IllegalStateException("WebView is not initialized for tab ${tab.id}")
        val scale = args.optDouble("scale", 1.0).coerceIn(0.1, 1.0)

        fun encodeJpeg(bmp: Bitmap, quality: Int = 78): String {
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        }

        fun takeScaledScreenshot(fullFrameBmp: Bitmap): String {
            return try {
                if (scale < 0.99) {
                    val sw = (fullFrameBmp.width * scale).toInt().coerceAtLeast(1)
                    val sh = (fullFrameBmp.height * scale).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(fullFrameBmp, sw, sh, true)
                    try { encodeJpeg(scaled) } finally { scaled.recycle() }
                } else {
                    encodeJpeg(fullFrameBmp)
                }
            } finally {
                fullFrameBmp.recycle()
            }
        }

        suspend fun resolveTargetCssAndPx(scrollIfRef: Boolean = true): Triple<Double, Double, String> {
            val ref = args.optString("ref").ifBlank { null }
            val coord = args.optJSONArray("coordinate")
            val vp = callAgentJson(context, tab, "window.__antigemAgent.viewport()")
            val vw = vp.optDouble("w", 360.0).coerceAtLeast(1.0)
            val vh = vp.optDouble("h", 640.0).coerceAtLeast(1.0)
            val viewW = wv.width.takeIf { it > 0 } ?: 1080
            val viewH = wv.height.takeIf { it > 0 } ?: 1920

            if (ref != null) {
                val r = callAgentJson(context, tab, "window.__antigemAgent.resolve(${JSONObject.quote(ref)}, $scrollIfRef)")
                if (r.optBoolean("fileInput", false) && action.endsWith("click")) {
                    throw IllegalStateException("$ref opens a file picker: call browser_upload_file(ref=\"$ref\", file_paths=[...]) instead.")
                }
                val cssX = r.optDouble("x", 0.0)
                val cssY = r.optDouble("y", 0.0)
                val rVw = r.optDouble("vw", vw).coerceAtLeast(1.0)
                val rVh = r.optDouble("vh", vh).coerceAtLeast(1.0)
                val pxX = (cssX / rVw) * viewW
                val pxY = (cssY / rVh) * viewH
                val note = r.optString("desc") + r.optString("note")
                return Triple(pxX, pxY, note)
            }

            if (coord != null && coord.length() == 2) {
                val sx = coord.getDouble(0)
                val sy = coord.getDouble(1)
                val shotW = tab.latestScreenshotWidth.takeIf { it > 0 } ?: viewW
                val shotH = tab.latestScreenshotHeight.takeIf { it > 0 } ?: viewH
                if (sx < 0 || sy < 0 || sx > shotW + 2 || sy > shotH + 2) {
                    throw IllegalStateException("Coordinate [$sx, $sy] is outside latest screenshot (${shotW}x$shotH).")
                }
                val pxX = (sx / shotW) * viewW
                val pxY = (sy / shotH) * viewH
                val cssX = (sx / shotW) * vw
                val cssY = (sy / shotH) * vh
                val hit = callAgentJson(context, tab, "window.__antigemAgent.hitTest($cssX, $cssY)")
                return Triple(pxX, pxY, hit.optString("desc"))
            }

            throw IllegalArgumentException("$action needs either ref (preferred) or coordinate [x, y].")
        }

        return when (action) {
            "screenshot" -> {
                val bmp = mgr.captureScreenshot(tab.id, fullPage = false, quality = 80).getOrThrow()
                val frame = "${bmp.width}x${bmp.height}"
                val b64 = takeScaledScreenshot(bmp)
                imageResult(b64, "Tab ${tab.id} (${tab.url}) — screenshot frame ${frame}px")
            }
            "zoom" -> {
                val reg = args.optJSONArray("region")
                if (reg == null || reg.length() != 4) {
                    throw IllegalArgumentException("zoom requires region [x0, y0, x1, y1] in screenshot pixels.")
                }
                val shotW = tab.latestScreenshotWidth
                val shotH = tab.latestScreenshotHeight
                if (shotW <= 0 || shotH <= 0) {
                    throw IllegalStateException("Take a screenshot first before calling zoom.")
                }
                val x0 = reg.getDouble(0).toInt().coerceIn(0, shotW - 1)
                val y0 = reg.getDouble(1).toInt().coerceIn(0, shotH - 1)
                val x1 = reg.getDouble(2).toInt().coerceIn(x0 + 1, shotW)
                val y1 = reg.getDouble(3).toInt().coerceIn(y0 + 1, shotH)
                val bmp = mgr.captureScreenshot(tab.id, fullPage = false, quality = 85).getOrThrow()
                val crop = Bitmap.createBitmap(
                    bmp,
                    x0.coerceAtMost(bmp.width - 1),
                    y0.coerceAtMost(bmp.height - 1),
                    (x1 - x0).coerceIn(1, bmp.width - x0.coerceAtMost(bmp.width - 1)),
                    (y1 - y0).coerceIn(1, bmp.height - y0.coerceAtMost(bmp.height - 1))
                )
                bmp.recycle()
                val b64 = takeScaledScreenshot(crop)
                imageResult(b64, "Zoomed [$x0,$y0,$x1,$y1] on tab ${tab.id}")
            }
            "left_click", "double_click", "triple_click" -> {
                val clicks = when (action) {
                    "double_click" -> 2
                    "triple_click" -> 3
                    else -> 1
                }
                val beforeUrl = tab.url
                val (pxX, pxY, targetDesc) = resolveTargetCssAndPx(scrollIfRef = true)
                mgr.dispatchTapPx(tab.id, pxX.toFloat(), pxY.toFloat(), clicks).getOrThrow()
                delay(220)
                val navNote = if (tab.url != beforeUrl) " Navigated to ${tab.url}." else ""
                textResult("$action on $targetDesc.$navNote")
            }
            "right_click", "hover" -> {
                val (pxX, pxY, targetDesc) = resolveTargetCssAndPx(scrollIfRef = true)
                val vp = callAgentJson(context, tab, "window.__antigemAgent.viewport()")
                val viewW = wv.width.takeIf { it > 0 } ?: 1080
                val viewH = wv.height.takeIf { it > 0 } ?: 1920
                val cssX = (pxX / viewW) * vp.optDouble("w", 360.0)
                val cssY = (pxY / viewH) * vp.optDouble("h", 640.0)
                val kind = if (action == "right_click") "context" else "hover"
                callAgentJson(context, tab, "window.__antigemAgent.pointerEvent($cssX, $cssY, '$kind')")
                textResult("$action on $targetDesc.")
            }
            "scroll_to" -> {
                val ref = args.optString("ref").ifBlank { null }
                    ?: throw IllegalArgumentException("scroll_to requires ref.")
                val res = callAgentJson(context, tab, "window.__antigemAgent.scrollIntoViewRef(${JSONObject.quote(ref)})")
                textResult("Scrolled ${res.optString("desc", ref)} into view${res.optString("note")}.")
            }
            "left_click_drag" -> {
                val start = args.optJSONArray("start_coordinate")
                val end = args.optJSONArray("coordinate")
                if (start == null || start.length() != 2 || end == null || end.length() != 2) {
                    throw IllegalArgumentException("left_click_drag requires start_coordinate and coordinate [x, y].")
                }
                val viewW = wv.width.takeIf { it > 0 } ?: 1080
                val viewH = wv.height.takeIf { it > 0 } ?: 1920
                val shotW = tab.latestScreenshotWidth.takeIf { it > 0 } ?: viewW
                val shotH = tab.latestScreenshotHeight.takeIf { it > 0 } ?: viewH
                val sx = (start.getDouble(0) / shotW * viewW).toFloat()
                val sy = (start.getDouble(1) / shotH * viewH).toFloat()
                val ex = (end.getDouble(0) / shotW * viewW).toFloat()
                val ey = (end.getDouble(1) / shotH * viewH).toFloat()
                mgr.dispatchDragPx(tab.id, sx, sy, ex, ey).getOrThrow()
                delay(180)
                textResult("Dragged from [${start.getDouble(0).toInt()},${start.getDouble(1).toInt()}] to [${end.getDouble(0).toInt()},${end.getDouble(1).toInt()}].")
            }
            "type" -> {
                val textToType = args.optString("text", "")
                if (textToType.isEmpty()) throw IllegalArgumentException("type requires non-empty text.")
                val focus = callAgentJson(context, tab, "window.__antigemAgent.focusInfo()")
                if (!focus.optBoolean("editable", false)) {
                    throw IllegalStateException("No editable field has focus (focused: ${focus.optString("desc")}). Click a text field first.")
                }
                val parts = textToType.split('\n')
                for (i in parts.indices) {
                    if (parts[i].isNotEmpty()) {
                        callAgentJson(context, tab, "window.__antigemAgent.insertText(${JSONObject.quote(parts[i])})")
                    }
                    if (i < parts.lastIndex) {
                        mgr.dispatchKey(tab.id, KeyEvent.KEYCODE_ENTER)
                    }
                }
                textResult("Typed into ${focus.optString("desc", "focused field")}.")
            }
            "key" -> {
                val keySpec = args.optString("text", "").trim()
                if (keySpec.isEmpty()) throw IllegalArgumentException("key action requires text (e.g. \"Enter\", \"Tab\", \"ctrl+a\").")
                val repeat = args.optInt("repeat", 1).coerceIn(1, 100)
                val tokens = keySpec.split(Regex("\\s+")).filter { it.isNotBlank() }
                repeat(repeat) {
                    for (token in tokens) {
                        val (keyCode, meta) = parseKeyCombo(token)
                        mgr.dispatchKeyModified(tab.id, keyCode, meta)
                        delay(30)
                    }
                }
                delay(120)
                textResult("Pressed key(s): $keySpec (x$repeat).")
            }
            "scroll" -> {
                val dir = args.optString("scroll_direction", "down").lowercase()
                val amount = args.optInt("scroll_amount", 3).coerceIn(1, 10) * 120
                val (dx, dy) = when (dir) {
                    "up" -> 0 to -amount
                    "down" -> 0 to amount
                    "left" -> -amount to 0
                    "right" -> amount to 0
                    else -> throw IllegalArgumentException("scroll_direction must be up, down, left, or right.")
                }
                val vp = callAgentJson(context, tab, "window.__antigemAgent.viewport()")
                val vw = vp.optDouble("w", 360.0)
                val vh = vp.optDouble("h", 640.0)
                val coord = args.optJSONArray("coordinate")
                val (cssX, cssY) = if (coord != null && coord.length() == 2) {
                    val shotW = tab.latestScreenshotWidth.takeIf { it > 0 } ?: (wv.width.takeIf { it > 0 } ?: 1080)
                    val shotH = tab.latestScreenshotHeight.takeIf { it > 0 } ?: (wv.height.takeIf { it > 0 } ?: 1920)
                    ((coord.getDouble(0) / shotW) * vw) to ((coord.getDouble(1) / shotH) * vh)
                } else {
                    (vw / 2.0) to (vh / 2.0)
                }
                val res = callAgentJson(context, tab, "window.__antigemAgent.scrollAt($cssX, $cssY, $dx, $dy)")
                delay(180)
                val bmp = mgr.captureScreenshot(tab.id, fullPage = false, quality = 78).getOrThrow()
                val frame = "${bmp.width}x${bmp.height}"
                val b64 = takeScaledScreenshot(bmp)
                val targetNote = res.optString("scrolled").ifBlank { "no change (at scroll edge)" }
                imageResult(b64, "Scrolled $dir ($targetNote); screenshot frame ${frame}px")
            }
            "wait" -> {
                val secs = args.optDouble("duration", 1.0).coerceIn(0.1, 10.0)
                delay((secs * 1000).toLong())
                textResult("Waited ${"%.1f".format(secs)}s. Tab ${tab.id} URL: ${tab.url}")
            }
            else -> throw IllegalArgumentException("Unknown browser_computer action: $action")
        }
    }

    private fun parseKeyCombo(combo: String): Pair<Int, Int> {
        val parts = combo.split('+').map { it.trim() }
        var meta = 0
        for (i in 0 until parts.lastIndex) {
            when (parts[i].lowercase()) {
                "ctrl", "control" -> meta = meta or KeyEvent.META_CTRL_ON
                "shift" -> meta = meta or KeyEvent.META_SHIFT_ON
                "alt" -> meta = meta or KeyEvent.META_ALT_ON
                "meta", "cmd", "super" -> meta = meta or KeyEvent.META_META_ON
            }
        }
        val base = parts.last().lowercase()
        val keyCode = when (base) {
            "enter", "return" -> KeyEvent.KEYCODE_ENTER
            "tab" -> KeyEvent.KEYCODE_TAB
            "escape", "esc" -> KeyEvent.KEYCODE_ESCAPE
            "backspace" -> KeyEvent.KEYCODE_DEL
            "delete", "del" -> KeyEvent.KEYCODE_FORWARD_DEL
            "space", " " -> KeyEvent.KEYCODE_SPACE
            "arrowup", "up" -> KeyEvent.KEYCODE_DPAD_UP
            "arrowdown", "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "arrowleft", "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "arrowright", "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "home" -> KeyEvent.KEYCODE_MOVE_HOME
            "end" -> KeyEvent.KEYCODE_MOVE_END
            "pageup" -> KeyEvent.KEYCODE_PAGE_UP
            "pagedown" -> KeyEvent.KEYCODE_PAGE_DOWN
            else -> if (base.length == 1 && base[0] in 'a'..'z') {
                KeyEvent.KEYCODE_A + (base[0] - 'a')
            } else if (base.length == 1 && base[0] in '0'..'9') {
                KeyEvent.KEYCODE_0 + (base[0] - '0')
            } else {
                KeyEvent.KEYCODE_UNKNOWN
            }
        }
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            throw IllegalArgumentException("Unsupported key \"$combo\"")
        }
        return keyCode to meta
    }
}
