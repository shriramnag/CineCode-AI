package com.shriram.cinecode.ui.browser.mcp

import com.shriram.cinecode.ui.browser.BrowserSessionManager
import org.json.JSONObject

/**
 * Lightweight WebView CDP (Chrome DevTools Protocol-style) DOM & JS inspector for CineCode-AI.
 * Operates directly on the active BrowserSessionManager WebView tab without extra background processes.
 */
object WebViewCdp {

    suspend fun getPageSnapshot(tabId: String? = null): Result<JSONObject> {
        val script = """
            (function() {
                var links = document.querySelectorAll('a').length;
                var inputs = document.querySelectorAll('input,textarea,select').length;
                var buttons = document.querySelectorAll('button,[role="button"]').length;
                var text = (document.body && document.body.innerText ? document.body.innerText : '').slice(0, 1800);
                return JSON.stringify({
                    title: document.title || '',
                    url: location.href || '',
                    links: links,
                    inputs: inputs,
                    buttons: buttons,
                    textPreview: text
                });
            })()
        """.trimIndent()
        return BrowserSessionManager.instance.evaluateJs(tabId, script).mapCatching { raw ->
            JSONObject(raw)
        }
    }

    suspend fun getInteractiveElements(tabId: String? = null): Result<String> {
        val script = """
            (function() {
                var nodes = Array.from(document.querySelectorAll('a,button,input,textarea,select,[role="button"]')).slice(0, 40);
                return JSON.stringify(nodes.map(function(el, i) {
                    var label = (el.innerText || el.value || el.placeholder || el.getAttribute('aria-label') || '').trim().slice(0, 60);
                    var idPart = el.id ? ('#' + el.id) : '';
                    var clsPart = (typeof el.className === 'string' && el.className.trim()) ? ('.' + el.className.trim().split(/\s+/).slice(0,2).join('.')) : '';
                    return { index: i, tag: el.tagName.toLowerCase(), selector: el.tagName.toLowerCase() + idPart + clsPart, label: label };
                }));
            })()
        """.trimIndent()
        return BrowserSessionManager.instance.evaluateJs(tabId, script)
    }

    suspend fun clickSelector(selector: String, tabId: String? = null): Result<String> {
        val escaped = JSONObject.quote(selector)
        val script = """
            (function() {
                var el = document.querySelector($escaped);
                if (!el) return 'Element not found: ' + $escaped;
                el.scrollIntoView({ block: 'center' });
                el.click();
                return 'Clicked ' + $escaped;
            })()
        """.trimIndent()
        return BrowserSessionManager.instance.evaluateJs(tabId, script)
    }

    suspend fun fillInput(selector: String, value: String, tabId: String? = null): Result<String> {
        val sel = JSONObject.quote(selector)
        val valQuoted = JSONObject.quote(value)
        val script = """
            (function() {
                var el = document.querySelector($sel);
                if (!el) return 'Input not found: ' + $sel;
                el.focus();
                el.value = $valQuoted;
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
                return 'Filled ' + $sel;
            })()
        """.trimIndent()
        return BrowserSessionManager.instance.evaluateJs(tabId, script)
    }

    suspend fun evalJs(jsCode: String, tabId: String? = null): Result<String> {
        val wrapped = """
            (function() {
                try {
                    var r = (function() { return ($jsCode); })();
                    return typeof r === 'object' ? JSON.stringify(r, null, 2) : String(r);
                } catch (e) {
                    return 'Error: ' + e.message;
                }
            })()
        """.trimIndent()
        return BrowserSessionManager.instance.evaluateJs(tabId, wrapped)
    }
}
