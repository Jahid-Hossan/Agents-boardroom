package com.example.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.model.AgentConfig
import com.example.data.model.AttachmentData
import com.example.data.model.InAppBrowserStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class InAppBrowserCoordinator(private val context: Context) {

    private val prefs = context.getSharedPreferences("muse_inapp_browser_profiles", Context.MODE_PRIVATE)
    private val sessions = mutableMapOf<String, WebView>()
    private val promptMutex = Mutex()

    private val _statuses = MutableStateFlow<Map<String, InAppBrowserStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, InAppBrowserStatus>> = _statuses.asStateFlow()

    fun syncAgents(agents: List<AgentConfig>) {
        _statuses.update { current ->
            val next = mutableMapOf<String, InAppBrowserStatus>()
            for (agent in agents) {
                val existing = current[agent.id]
                val hasSaved = prefs.getBoolean(savedKey(agent.id), false)
                next[agent.id] = existing?.copy(hasSavedProfile = hasSaved)
                    ?: InAppBrowserStatus(
                        agentId = agent.id,
                        open = sessions.containsKey(agent.id),
                        hasSavedProfile = hasSaved,
                        currentUrl = AgentInferenceService.normalizeHttpUrl(agent.customUrl)
                    )
            }
            next
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun getOrCreateWebView(agent: AgentConfig, forceUrl: String? = null): WebView =
        withContext(Dispatchers.Main) {
            val existing = sessions[agent.id]
            val targetUrl = AgentInferenceService.normalizeHttpUrl(
                forceUrl?.takeIf { it.isNotBlank() } ?: agent.customUrl.ifBlank { "https://muse.ai" }
            )

            if (existing != null) {
                if (!forceUrl.isNullOrBlank() && existing.url != targetUrl) {
                    existing.loadUrl(targetUrl)
                }
                return@withContext existing
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            val webView = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    javaScriptCanOpenWindowsAutomatically = true
                    setSupportMultipleWindows(false)
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    userAgentString =
                        "Mozilla/5.0 (Linux; Android 15; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                }
                cookieManager.setAcceptThirdPartyCookies(this, true)
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        cookieManager.flush()
                        if (view != null) {
                            inspectWebViewState(agent.id, view)
                        }
                    }
                }
            }

            sessions[agent.id] = webView
            updateStatus(agent.id) {
                it.copy(
                    open = true,
                    currentUrl = targetUrl,
                    hasSavedProfile = prefs.getBoolean(savedKey(agent.id), false)
                )
            }
            if (targetUrl.isNotBlank()) {
                webView.loadUrl(targetUrl)
            }
            webView
        }

    suspend fun navigate(agent: AgentConfig, action: String, url: String = "") =
        withContext(Dispatchers.Main) {
            if (action == "close") {
                closeSession(agent.id)
                return@withContext
            }
            val webView = getOrCreateWebView(agent)
            when (action) {
                "goto" -> {
                    val normalized = AgentInferenceService.normalizeHttpUrl(url)
                    if (normalized.isNotBlank()) {
                        webView.loadUrl(normalized)
                    }
                }
                "back" -> if (webView.canGoBack()) webView.goBack()
                "reload" -> webView.reload()
            }
        }

    suspend fun refreshStatus(agent: AgentConfig): InAppBrowserStatus =
        withContext(Dispatchers.Main) {
            val webView = getOrCreateWebView(agent)
            val result = evaluateJsSuspend(webView, INSPECT_PAGE_STATE_JS)
            applyInspectResult(agent.id, result, webView.url.orEmpty())
        }

    suspend fun injectQuickText(agent: AgentConfig, text: String, pressEnter: Boolean = false) =
        withContext(Dispatchers.Main) {
            val webView = getOrCreateWebView(agent)
            val escaped = JSONObject.quote(text)
            val enterFlag = if (pressEnter) "true" else "false"
            val js = """
                (function() {
                  var el = document.activeElement;
                  if (!el || (el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA' && !el.isContentEditable)) {
                    el = document.querySelector('input[type="email"], input[type="password"], input[type="text"], textarea, [contenteditable="true"]');
                  }
                  if (!el) return JSON.stringify({ ok: false });
                  el.focus();
                  var text = $escaped;
                  if (text) {
                    if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
                      var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                      var setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                      if (setter) setter.call(el, text); else el.value = text;
                      el.dispatchEvent(new Event('input', { bubbles: true }));
                      el.dispatchEvent(new Event('change', { bubbles: true }));
                    } else {
                      document.execCommand('insertText', false, text);
                      el.dispatchEvent(new InputEvent('input', { bubbles: true, data: text, inputType: 'insertText' }));
                    }
                  }
                  if ($enterFlag) {
                    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                    el.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                  }
                  return JSON.stringify({ ok: true });
                })();
            """.trimIndent()
            evaluateJsSuspend(webView, js)
        }

    fun closeSession(agentId: String) {
        val webView = sessions.remove(agentId)
        webView?.stopLoading()
        webView?.destroy()
        CookieManager.getInstance().flush()
        updateStatus(agentId) {
            it.copy(
                open = false,
                ready = false,
                needsLogin = false,
                hasSavedProfile = prefs.getBoolean(savedKey(agentId), false)
            )
        }
    }

    suspend fun sendPrompt(
        agent: AgentConfig,
        promptText: String,
        attachment: AttachmentData?
    ): String = promptMutex.withLock {
        val currentStatus = _statuses.value[agent.id]
        val hasSaved = prefs.getBoolean(savedKey(agent.id), false)
        if (currentStatus?.open != true && !hasSaved && isKnownAuthAiUrl(agent.customUrl)) {
            return@withLock "**${agent.name} is not signed in yet.** Tap the globe icon on **${agent.name}** to sign in inside the In-App Browser once—after that your session stays connected."
        }

        val effectivePrompt = if (attachment != null) {
            if (attachment.isTextLike && attachment.textPreview.isNotBlank()) {
                "$promptText\n\n[Attached file: ${attachment.filename}]\n${attachment.textPreview.take(12000)}"
            } else {
                "$promptText\n\n[Attached file: ${attachment.filename} (${attachment.mimeType})]"
            }
        } else {
            promptText
        }

        val webView = withContext(Dispatchers.Main) {
            val wv = getOrCreateWebView(agent)
            delay(250)
            wv
        }

        val startJson = withContext(Dispatchers.Main) {
            val quoted = JSONObject.quote(effectivePrompt)
            val raw = evaluateJsSuspend(webView, buildStartPromptJs(quoted))
            parseJsonSafe(raw)
        }

        if (startJson == null || !startJson.optBoolean("started", false)) {
            val host = hostOf(agent.customUrl).ifBlank { agent.name }
            val msg = startJson?.optString("error").orEmpty()
                .ifBlank { "Not signed in yet on $host. Tap the globe icon on ${agent.name} to log in inside the app." }
            withContext(Dispatchers.Main) { inspectWebViewState(agent.id, webView) }
            return@withLock "**${agent.name} needs login in the In-App Browser.**\n\n$msg"
        }

        val beforeCount = startJson.optInt("beforeCount", 0)
        val beforeLast = startJson.optString("beforeLast", "")

        var latestText = ""
        var stableRounds = 0
        val deadline = System.currentTimeMillis() + 25_000L

        while (System.currentTimeMillis() < deadline) {
            delay(250)
            val pollJson = withContext(Dispatchers.Main) {
                val raw = evaluateJsSuspend(webView, buildPollResponseJs(JSONObject.quote(effectivePrompt)))
                parseJsonSafe(raw)
            } ?: continue

            if (pollJson.optBoolean("loginWall", false)) {
                return@withLock "**${agent.name} needs login in the In-App Browser.**\n\n${hostOf(agent.customUrl)} requires sign-in. Tap the globe icon on ${agent.name} to log in."
            }

            val count = pollJson.optInt("count", 0)
            val candidate = pollJson.optString("latest", "")
            val generating = pollJson.optBoolean("generating", false)

            val hasNew = count > beforeCount || (candidate.isNotBlank() && candidate != beforeLast && candidate != effectivePrompt)
            if (!hasNew) continue

            if (candidate == latestText && candidate.isNotEmpty() && !generating) {
                stableRounds += 1
                if (stableRounds >= 2) {
                    withContext(Dispatchers.Main) { inspectWebViewState(agent.id, webView) }
                    return@withLock candidate
                }
            } else {
                latestText = candidate
                stableRounds = 0
            }
        }

        if (latestText.isNotBlank()) {
            return@withLock latestText
        }

        val host = hostOf(agent.customUrl).ifBlank { agent.name }
        "**${agent.name} error:** Timed out waiting for a reply from $host. Verify the In-App Browser session is signed in."
    }

    private fun inspectWebViewState(agentId: String, webView: WebView) {
        webView.evaluateJavascript(INSPECT_PAGE_STATE_JS) { raw ->
            applyInspectResult(agentId, raw, webView.url.orEmpty())
        }
    }

    private fun applyInspectResult(agentId: String, rawResult: String?, fallbackUrl: String): InAppBrowserStatus {
        val json = parseJsonSafe(rawResult)
        val ready = json?.optBoolean("ready", false) ?: false
        val needsLogin = json?.optBoolean("needsLogin", false) ?: false
        val url = json?.optString("url").orEmpty().ifBlank { fallbackUrl }
        val title = json?.optString("title").orEmpty()

        if (ready && !needsLogin) {
            prefs.edit().putBoolean(savedKey(agentId), true).apply()
        } else if (needsLogin && !ready) {
            prefs.edit().remove(savedKey(agentId)).apply()
        }

        val status = InAppBrowserStatus(
            agentId = agentId,
            open = sessions.containsKey(agentId),
            ready = ready,
            needsLogin = needsLogin,
            hasSavedProfile = prefs.getBoolean(savedKey(agentId), false),
            currentUrl = url,
            pageTitle = title
        )
        updateStatus(agentId) { status }
        return status
    }

    private fun updateStatus(agentId: String, transform: (InAppBrowserStatus) -> InAppBrowserStatus) {
        _statuses.update { map ->
            val current = map[agentId] ?: InAppBrowserStatus(agentId = agentId)
            map + (agentId to transform(current))
        }
    }

    private suspend fun evaluateJsSuspend(webView: WebView, script: String): String {
        val deferred = CompletableDeferred<String>()
        webView.evaluateJavascript(script) { value ->
            deferred.complete(value ?: "null")
        }
        return deferred.await()
    }

    private fun parseJsonSafe(rawEvalResult: String?): JSONObject? {
        if (rawEvalResult.isNullOrBlank() || rawEvalResult == "null") return null
        return try {
            val unwrapped = if (rawEvalResult.startsWith("\"") && rawEvalResult.endsWith("\"")) {
                JSONObject("{\"v\":$rawEvalResult}").getString("v")
            } else {
                rawEvalResult
            }
            JSONObject(unwrapped)
        } catch (_: Exception) {
            null
        }
    }

    private fun savedKey(agentId: String) = "saved_session_$agentId"

    companion object {
        fun hostOf(rawUrl: String): String {
            return try {
                Uri.parse(AgentInferenceService.normalizeHttpUrl(rawUrl)).host.orEmpty()
                    .removePrefix("www.")
                    .lowercase()
            } catch (_: Exception) {
                ""
            }
        }

        fun isKnownAuthAiUrl(rawUrl: String): Boolean {
            val host = hostOf(rawUrl)
            if (host.isEmpty()) return false
            val known = listOf(
                "muse.ai",
                "gemini.google.com",
                "chatgpt.com",
                "chat.openai.com",
                "grok.com",
                "x.com",
                "claude.ai",
                "meta.ai"
            )
            return known.any { host == it || host.endsWith(".$it") }
        }

        private val INSPECT_PAGE_STATE_JS = """
            (function() {
              function isVisible(el) {
                if (!el) return false;
                var rect = el.getBoundingClientRect();
                var style = getComputedStyle(el);
                return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
              }
              var inputSelectors = [
                'rich-textarea .ql-editor[contenteditable="true"]',
                '#prompt-textarea',
                'div.ProseMirror[contenteditable="true"]',
                'textarea:not([disabled]):not([readonly])',
                '[contenteditable="true"][role="textbox"]',
                '[contenteditable="true"]',
                'div[role="textbox"]'
              ];
              var hasChatInput = inputSelectors.some(function(sel) {
                return Array.from(document.querySelectorAll(sel)).some(isVisible);
              });
              var hasPasswordOrOtp = Array.from(
                document.querySelectorAll('input[type="password"], input[autocomplete="one-time-code"], input[name*="otp" i]')
              ).some(isVisible);
              var hasLoginBtn = Array.from(document.querySelectorAll('button, a')).some(function(el) {
                var t = (el.innerText || '').trim().toLowerCase();
                return isVisible(el) && (t === 'log in' || t === 'sign in' || t === 'sign in to gemini' || t === 'continue with google');
              });
              var hrefLower = location.href.toLowerCase();
              var isLoginUrl = hrefLower.indexOf('login') !== -1 || hrefLower.indexOf('signin') !== -1 || hrefLower.indexOf('accounts.google.com') !== -1 || hrefLower.indexOf('auth') !== -1;
              return JSON.stringify({
                ready: hasChatInput && !hasPasswordOrOtp,
                needsLogin: hasPasswordOrOtp || (!hasChatInput && (hasLoginBtn || isLoginUrl)),
                url: location.href,
                title: document.title || location.hostname
              });
            })();
        """.trimIndent()

        private fun buildStartPromptJs(quotedPrompt: String): String = """
            (function() {
              var promptText = $quotedPrompt;
              var host = location.hostname.toLowerCase();
              function isVisible(el) {
                if (!el) return false;
                var rect = el.getBoundingClientRect();
                var style = getComputedStyle(el);
                return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
              }
              function hasLoginWall() {
                var hasPass = Array.from(document.querySelectorAll('input[type="password"], input[autocomplete="one-time-code"], input[name*="otp" i]')).some(isVisible);
                if (hasPass) return true;
                var h = location.href.toLowerCase();
                return h.indexOf('accounts.google.com') !== -1 || h.indexOf('/login') !== -1 || h.indexOf('/signin') !== -1;
              }
              if (hasLoginWall()) {
                return JSON.stringify({ started: false, error: 'Not signed in yet on ' + location.hostname + '.' });
              }
              var selectorsBySite = [];
              if (host.indexOf('gemini.google.com') !== -1) {
                selectorsBySite.push('rich-textarea .ql-editor[contenteditable="true"]', 'rich-textarea [contenteditable="true"]', 'div[role="textbox"]');
              } else if (host.indexOf('chatgpt.com') !== -1 || host.indexOf('openai.com') !== -1) {
                selectorsBySite.push('#prompt-textarea', 'div.ProseMirror[contenteditable="true"]', 'textarea[name="prompt-textarea"]');
              } else if (host.indexOf('grok.com') !== -1 || host.indexOf('x.com') !== -1) {
                selectorsBySite.push('textarea[aria-label*="Ask" i]', 'div.ProseMirror[contenteditable="true"]', 'textarea');
              } else if (host.indexOf('claude.ai') !== -1) {
                selectorsBySite.push('div[contenteditable="true"].ProseMirror', 'fieldset [contenteditable="true"]');
              } else if (host.indexOf('muse.ai') !== -1 || host.indexOf('meta.ai') !== -1) {
                selectorsBySite.push('textarea', '[contenteditable="true"][role="textbox"]', 'div[role="textbox"]');
              }
              var generic = ['textarea:not([disabled]):not([readonly])', '[contenteditable="true"][role="textbox"]', '[contenteditable="true"]', 'div[role="textbox"]'];
              var inputEl = null;
              var allSel = selectorsBySite.concat(generic);
              for (var i = 0; i < allSel.length; i++) {
                var list = Array.from(document.querySelectorAll(allSel[i]));
                var found = list.find(isVisible);
                if (found) { inputEl = found; break; }
              }
              if (!inputEl) {
                return JSON.stringify({ started: false, error: 'Chat input not ready yet on ' + location.hostname + '. Open the In-App Browser and sign in first.' });
              }
              var respSel = ['model-response message-content', '[data-message-author-role="assistant"] .markdown', '[data-message-author-role="assistant"]', '.markdown', 'article'];
              var beforeTexts = [];
              for (var r = 0; r < respSel.length; r++) {
                var nodes = document.querySelectorAll(respSel[r]);
                if (nodes.length > 0) {
                  nodes.forEach(function(n) { var t = (n.innerText || '').trim(); if (t) beforeTexts.push(t); });
                  if (beforeTexts.length > 0) break;
                }
              }
              inputEl.focus();
              var tag = inputEl.tagName.toLowerCase();
              if (tag === 'textarea' || tag === 'input') {
                var proto = tag === 'textarea' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                var setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                if (setter) setter.call(inputEl, promptText); else inputEl.value = promptText;
                inputEl.dispatchEvent(new Event('input', { bubbles: true }));
                inputEl.dispatchEvent(new Event('change', { bubbles: true }));
              } else {
                var sel = window.getSelection();
                var range = document.createRange();
                range.selectNodeContents(inputEl);
                sel.removeAllRanges();
                sel.addRange(range);
                document.execCommand('insertText', false, promptText);
                inputEl.dispatchEvent(new InputEvent('input', { bubbles: true, data: promptText, inputType: 'insertText' }));
              }
              setTimeout(function() {
                var btnSelectors = ['button[data-testid="send-button"]', 'button[aria-label*="Send" i]', 'button[data-testid*="send" i]', 'button[type="submit"]'];
                var clicked = false;
                for (var b = 0; b < btnSelectors.length; b++) {
                  var btn = Array.from(document.querySelectorAll(btnSelectors[b])).find(function(x) {
                    return isVisible(x) && !x.disabled && x.getAttribute('aria-disabled') !== 'true';
                  });
                  if (btn) { btn.click(); clicked = true; break; }
                }
                if (!clicked) {
                  inputEl.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                  inputEl.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                }
              }, 80);
              return JSON.stringify({
                started: true,
                beforeCount: beforeTexts.length,
                beforeLast: beforeTexts.length > 0 ? beforeTexts[beforeTexts.length - 1] : ''
              });
            })();
        """.trimIndent()

        private fun buildPollResponseJs(quotedPrompt: String): String = """
            (function() {
              var promptText = $quotedPrompt;
              var host = location.hostname.toLowerCase();
              function isVisible(el) {
                if (!el) return false;
                var rect = el.getBoundingClientRect();
                var style = getComputedStyle(el);
                return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
              }
              var stopSel = ['button[data-testid="stop-button"]', 'button[aria-label*="Stop" i]', 'button[title*="Stop" i]'];
              var generating = stopSel.some(function(s) { return Array.from(document.querySelectorAll(s)).some(isVisible); });
              var selectors = [];
              if (host.indexOf('gemini.google.com') !== -1) {
                selectors = ['model-response message-content', 'message-content .markdown', '.model-response-text'];
              } else if (host.indexOf('chatgpt.com') !== -1 || host.indexOf('openai.com') !== -1) {
                selectors = ['[data-message-author-role="assistant"] .markdown', '[data-message-author-role="assistant"]'];
              } else if (host.indexOf('grok.com') !== -1 || host.indexOf('x.com') !== -1) {
                selectors = ['.message-bubble .markdown', '[data-testid*="assistant" i]', '.response-content-markdown', '.markdown', 'article'];
              } else if (host.indexOf('claude.ai') !== -1) {
                selectors = ['[data-testid="assistant-message"]', '.font-claude-response', '[class*="assistant"] .markdown'];
              } else {
                selectors = ['[data-message-author-role="assistant"]', '[class*="assistant" i]', '[class*="response" i]', '.markdown', 'article'];
              }
              var results = [];
              for (var i = 0; i < selectors.length; i++) {
                var nodes = document.querySelectorAll(selectors[i]);
                if (nodes.length > 0) {
                  nodes.forEach(function(n) {
                    var t = (n.innerText || '').trim();
                    if (t && t !== promptText) results.push(t);
                  });
                  if (results.length > 0) break;
                }
              }
              return JSON.stringify({
                count: results.length,
                latest: results.length > 0 ? results[results.length - 1] : '',
                generating: generating,
                loginWall: false
              });
            })();
        """.trimIndent()
    }
}
