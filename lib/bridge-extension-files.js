export function generateExtensionFiles({ serverUrl, bridgeToken }) {
  const cleanUrl = String(serverUrl || 'http://localhost:3000').replace(/\/+$/, '');

  const manifest = {
    manifest_version: 3,
    name: 'Muse Boardroom Desktop Bridge',
    version: '1.2.0',
    description: 'Connects multiple Chrome Profiles and logged-in AI tabs (Muse AI, Gemini, ChatGPT, Grok, Claude) permanently to your dashboard.',
    permissions: ['tabs', 'scripting', 'storage', 'alarms'],
    host_permissions: ['<all_urls>'],
    background: {
      service_worker: 'background.js'
    },
    action: {
      default_popup: 'popup.html',
      default_title: 'Muse Boardroom Bridge'
    }
  };

  const bridgeConfig = {
    serverUrl: cleanUrl,
    bridgeToken: String(bridgeToken || '')
  };

  const backgroundJs = `
const LONG_POLL_WAIT_MS = 18000;
const HEARTBEAT_EVERY_MS = 8000;

let cachedConfig = null;
let lastHeartbeatSent = 0;
let pollLoopActive = false;
let lastStatus = { connected: false, error: null, tabCount: 0, lastSync: null };

function generateClientId() {
  const arr = new Uint8Array(6);
  crypto.getRandomValues(arr);
  return 'prof_' + Array.from(arr, (b) => b.toString(16).padStart(2, '0')).join('');
}

async function loadConfig() {
  const stored = await chrome.storage.local.get(['serverUrl', 'bridgeToken', 'clientId', 'profileName']);
  let clientId = stored.clientId;
  if (!clientId) {
    clientId = generateClientId();
    await chrome.storage.local.set({ clientId });
  }
  const profileName = stored.profileName || ('Chrome (' + clientId.slice(-4) + ')');

  if (stored.serverUrl && stored.bridgeToken) {
    cachedConfig = {
      serverUrl: stored.serverUrl.replace(/\\/+$/, ''),
      bridgeToken: stored.bridgeToken,
      clientId,
      profileName
    };
    return cachedConfig;
  }

  try {
    const res = await fetch(chrome.runtime.getURL('bridge-config.json'));
    const defaults = await res.json();
    cachedConfig = {
      serverUrl: (stored.serverUrl || defaults.serverUrl || '').replace(/\\/+$/, ''),
      bridgeToken: stored.bridgeToken || defaults.bridgeToken || '',
      clientId,
      profileName
    };
    await chrome.storage.local.set(cachedConfig);
    return cachedConfig;
  } catch (e) {
    return { serverUrl: '', bridgeToken: '', clientId, profileName };
  }
}

async function listCandidateTabs() {
  const cfg = await loadConfig();
  const tabs = await chrome.tabs.query({});
  return tabs
    .filter((t) => {
      if (!t.url || !/^https?:\\/\\//i.test(t.url)) return false;
      if (cfg.serverUrl && t.url.startsWith(cfg.serverUrl)) return false;
      return true;
    })
    .map((t) => ({
      tabId: t.id,
      title: t.title || t.url,
      url: t.url,
      active: Boolean(t.active)
    }));
}

async function sendHeartbeat() {
  const cfg = await loadConfig();
  if (!cfg.serverUrl || !cfg.bridgeToken) return;
  try {
    const tabs = await listCandidateTabs();
    const res = await fetch(cfg.serverUrl + '/api/bridge/heartbeat', {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        'X-Bridge-Token': cfg.bridgeToken
      },
      body: JSON.stringify({
        clientId: cfg.clientId,
        profileName: cfg.profileName,
        tabs,
        browserInfo: { userAgent: navigator.userAgent }
      })
    });
    if (!res.ok) {
      lastStatus = { connected: false, error: 'HTTP ' + res.status, tabCount: tabs.length, lastSync: new Date().toISOString() };
      return;
    }
    lastHeartbeatSent = Date.now();
    lastStatus = { connected: true, error: null, tabCount: tabs.length, lastSync: new Date().toISOString() };
  } catch (e) {
    lastStatus = { connected: false, error: e.message, tabCount: 0, lastSync: new Date().toISOString() };
  }
}

function normalizeHttpUrl(raw) {
  const s = String(raw || '').trim();
  if (!s) return '';
  return /^https?:\\/\\//i.test(s) ? s : 'https://' + s;
}

function extractHost(rawUrl) {
  try {
    return new URL(normalizeHttpUrl(rawUrl)).hostname.replace(/^www\\./i, '').toLowerCase();
  } catch (e) {
    return '';
  }
}

function hostsMatch(urlA, urlB) {
  const hostA = extractHost(urlA);
  const hostB = extractHost(urlB);
  if (!hostA || !hostB) return false;
  if (hostA === hostB || hostA.includes(hostB) || hostB.includes(hostA)) return true;
  if ((hostA.includes('chatgpt.com') || hostA.includes('openai.com')) && (hostB.includes('chatgpt.com') || hostB.includes('openai.com'))) return true;
  if ((hostA.includes('grok.com') || hostA.includes('x.com')) && (hostB.includes('grok.com') || hostB.includes('x.com'))) return true;
  return false;
}

async function resolveTargetTab(job, createIfMissing = true) {
  const normalizedUrl = normalizeHttpUrl(job.targetUrl);

  if (job.targetTabId) {
    try {
      const existing = await chrome.tabs.get(Number(job.targetTabId));
      if (existing && existing.id) {
        if (!normalizedUrl || hostsMatch(existing.url || '', normalizedUrl)) {
          return existing;
        }
      }
    } catch (e) {
      // Tab closed or Chrome restarted with new tab IDs; fall back to URL match below
    }
  }

  const allTabs = await chrome.tabs.query({});
  if (normalizedUrl) {
    const match = allTabs.find((t) => t.url && hostsMatch(t.url, normalizedUrl));
    if (match) return match;
    if (createIfMissing) {
      const created = await chrome.tabs.create({ url: normalizedUrl, active: false });
      await waitForTabComplete(created.id, 12000);
      return await chrome.tabs.get(created.id);
    }
  }
  return null;
}

function waitForTabComplete(tabId, timeoutMs) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      chrome.tabs.onUpdated.removeListener(listener);
      resolve();
    }, timeoutMs);
    function listener(updatedId, info) {
      if (updatedId === tabId && info.status === 'complete') {
        clearTimeout(timer);
        chrome.tabs.onUpdated.removeListener(listener);
        setTimeout(resolve, 900);
      }
    }
    chrome.tabs.onUpdated.addListener(listener);
  });
}

// Lightweight check inside tab to detect whether user is logged in and chat box is ready
function checkPageLoginState() {
  const host = location.hostname.toLowerCase();
  function isVisible(el) {
    if (!el) return false;
    const rect = el.getBoundingClientRect();
    const style = getComputedStyle(el);
    return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
  }
  const selectors = [
    'rich-textarea .ql-editor[contenteditable="true"]',
    '#prompt-textarea',
    'div.ProseMirror[contenteditable="true"]',
    'textarea:not([disabled]):not([readonly])',
    '[contenteditable="true"][role="textbox"]',
    '[contenteditable="true"]',
    'div[role="textbox"]'
  ];
  const hasInput = selectors.some((sel) => Array.from(document.querySelectorAll(sel)).some(isVisible));
  const hasLoginBtn = Array.from(document.querySelectorAll('button, a')).some((el) => {
    const t = (el.innerText || '').trim().toLowerCase();
    return isVisible(el) && (t === 'log in' || t === 'sign in' || t === 'sign in to gemini' || t === 'continue with google');
  });
  return {
    ready: hasInput,
    needsLogin: !hasInput && (hasLoginBtn || location.href.toLowerCase().includes('login') || location.href.toLowerCase().includes('signin') || location.href.toLowerCase().includes('accounts.google.com')),
    host,
    href: location.href
  };
}

// Executed directly inside the target AI web page (Muse AI, Gemini, ChatGPT, Grok, Claude, etc.)
async function executePromptInPage(promptText) {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const host = location.hostname.toLowerCase();

  function isVisible(el) {
    if (!el) return false;
    const rect = el.getBoundingClientRect();
    const style = getComputedStyle(el);
    return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
  }

  function findInputElement() {
    const selectorsBySite = [];
    if (host.includes('gemini.google.com')) {
      selectorsBySite.push('rich-textarea .ql-editor[contenteditable="true"]', 'rich-textarea [contenteditable="true"]', 'div[role="textbox"]');
    } else if (host.includes('chatgpt.com') || host.includes('openai.com')) {
      selectorsBySite.push('#prompt-textarea', 'div.ProseMirror[contenteditable="true"]', 'textarea[name="prompt-textarea"]');
    } else if (host.includes('grok.com') || host.includes('x.com')) {
      selectorsBySite.push('textarea[aria-label*="Ask" i]', 'div.ProseMirror[contenteditable="true"]', 'textarea');
    } else if (host.includes('claude.ai')) {
      selectorsBySite.push('div[contenteditable="true"].ProseMirror', 'fieldset [contenteditable="true"]');
    } else if (host.includes('muse.ai') || host.includes('meta.ai')) {
      selectorsBySite.push('textarea', '[contenteditable="true"][role="textbox"]', 'div[role="textbox"]');
    }

    const generic = [
      'textarea:not([disabled]):not([readonly])',
      '[contenteditable="true"][role="textbox"]',
      '[contenteditable="true"]',
      'div[role="textbox"]',
      'input[type="text"]:not([type="hidden"])'
    ];

    for (const sel of [...selectorsBySite, ...generic]) {
      const list = Array.from(document.querySelectorAll(sel));
      const visible = list.find(isVisible);
      if (visible) return visible;
    }
    return null;
  }

  function isStillGenerating() {
    const stopSelectors = [
      'button[data-testid="stop-button"]',
      'button[aria-label*="Stop" i]',
      'button[title*="Stop" i]'
    ];
    return stopSelectors.some((sel) => Array.from(document.querySelectorAll(sel)).some(isVisible));
  }

  function getResponseTexts() {
    let selectors = [];
    if (host.includes('gemini.google.com')) {
      selectors = ['model-response message-content', 'message-content .markdown', '.model-response-text'];
    } else if (host.includes('chatgpt.com') || host.includes('openai.com')) {
      selectors = ['[data-message-author-role="assistant"] .markdown', '[data-message-author-role="assistant"]'];
    } else if (host.includes('grok.com') || host.includes('x.com')) {
      selectors = ['.message-bubble .markdown', '[data-testid*="assistant" i]', '.response-content-markdown', '.markdown', 'article'];
    } else if (host.includes('claude.ai')) {
      selectors = ['[data-testid="assistant-message"]', '.font-claude-response', '[class*="assistant"] .markdown'];
    } else {
      selectors = [
        '[data-message-author-role="assistant"]',
        '[class*="assistant" i]:not(footer *):not(nav *):not(header *)',
        '[class*="response" i]:not(footer *):not(nav *):not(button)',
        '[class*="message" i]:not(footer *):not(nav *):not([class*="footer" i] *)',
        '[class*="bubble" i]',
        '[data-testid*="message" i]',
        '[role="log"] > div',
        'article'
      ];
    }

    const noise = ['© meta', 'privacy policy', 'terms of service', 'cookie policy', 'log in', 'sign in', 'sign up'];
    const results = [];
    for (const sel of selectors) {
      const nodes = document.querySelectorAll(sel);
      if (nodes.length > 0) {
        for (const node of nodes) {
          const txt = (node.innerText || '').trim();
          if (!txt || txt === promptText) continue;
          const lower = txt.toLowerCase();
          if (txt.length < 48 && noise.some((n) => lower.includes(n))) continue;
          results.push(txt);
        }
        if (results.length > 0) break;
      }
    }
    return results;
  }

  function setInputValue(el, text) {
    el.focus();
    const tag = el.tagName.toLowerCase();
    if (tag === 'textarea' || tag === 'input') {
      const proto = tag === 'textarea' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
      const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
      if (setter) {
        setter.call(el, text);
      } else {
        el.value = text;
      }
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    } else {
      const sel = window.getSelection();
      const range = document.createRange();
      range.selectNodeContents(el);
      sel.removeAllRanges();
      sel.addRange(range);
      const inserted = document.execCommand('insertText', false, text);
      if (!inserted || !el.innerText.includes(text.slice(0, 12))) {
        el.textContent = text;
      }
      el.dispatchEvent(new InputEvent('input', { bubbles: true, data: text, inputType: 'insertText' }));
    }
  }

  function clickSendButton(inputEl) {
    const buttonSelectors = [
      'button[data-testid="send-button"]',
      'button[aria-label*="Send" i]',
      'button[data-testid*="send" i]',
      'button[title*="Send" i]',
      'button.send-button',
      'button[type="submit"]'
    ];
    for (const sel of buttonSelectors) {
      const btn = Array.from(document.querySelectorAll(sel)).find(
        (b) => isVisible(b) && !b.disabled && b.getAttribute('aria-disabled') !== 'true'
      );
      if (btn) {
        btn.click();
        return true;
      }
    }
    inputEl.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
    inputEl.dispatchEvent(new KeyboardEvent('keypress', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
    inputEl.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
    return false;
  }

  const inputEl = findInputElement();
  if (!inputEl) {
    const loginBtn = Array.from(document.querySelectorAll('button, a')).find((el) => {
      const t = (el.innerText || '').trim().toLowerCase();
      return t === 'log in' || t === 'sign in';
    });
    if (loginBtn) {
      return { success: false, error: 'Please log in to ' + location.hostname + ' in your Chrome tab first.' };
    }
    return { success: false, error: 'Chat input box not found on ' + location.href + '. Open an active chat in that tab.' };
  }

  const beforeTexts = getResponseTexts();
  const beforeCount = beforeTexts.length;
  const beforeLast = beforeCount > 0 ? beforeTexts[beforeCount - 1] : '';

  setInputValue(inputEl, promptText);
  await sleep(300);
  clickSendButton(inputEl);

  // Wait up to 36s for a new response to appear and finish streaming
  let latestText = '';
  let stableRounds = 0;
  const maxWaitMs = 36000;
  const start = Date.now();

  while (Date.now() - start < maxWaitMs) {
    await sleep(600);
    const current = getResponseTexts();
    if (current.length === 0) continue;
    const candidate = current[current.length - 1];
    const hasNew = current.length > beforeCount || (candidate && candidate !== beforeLast && candidate !== promptText);
    if (!hasNew) continue;

    if (candidate === latestText && candidate.length > 0 && !isStillGenerating()) {
      stableRounds += 1;
      if (stableRounds >= 2) {
        return { success: true, reply: candidate };
      }
    } else {
      latestText = candidate;
      stableRounds = 0;
    }
  }

  if (latestText) {
    return { success: true, reply: latestText };
  }
  return { success: false, error: 'Timed out waiting for a reply from ' + location.hostname + '. Verify the tab is logged in and ready.' };
}

async function processJob(job) {
  const cfg = await loadConfig();
  let payload = { jobId: job.jobId, success: false, error: 'Unknown error', clientId: cfg.clientId, profileName: cfg.profileName };
  try {
    if (job.action === 'open_tab') {
      const targetUrl = normalizeHttpUrl(job.targetUrl || 'https://muse.ai');
      const created = await chrome.tabs.create({ url: targetUrl, active: true });
      try {
        if (created.windowId && chrome.windows?.update) {
          await chrome.windows.update(created.windowId, { focused: true });
        }
      } catch (e) {}
      await waitForTabComplete(created.id, 9000);
      const updated = await chrome.tabs.get(created.id).catch(() => created);
      let pageState = { ready: false, needsLogin: false };
      try {
        const res = await chrome.scripting.executeScript({ target: { tabId: updated.id }, func: checkPageLoginState });
        if (res?.[0]?.result) pageState = res[0].result;
      } catch (e) {}
      await sendHeartbeat();
      payload = {
        jobId: job.jobId,
        success: true,
        reply: 'Opened ' + (updated.url || targetUrl),
        tabId: updated.id,
        tabUrl: updated.url || targetUrl,
        tabTitle: updated.title || targetUrl,
        clientId: cfg.clientId,
        profileName: cfg.profileName,
        ready: Boolean(pageState.ready),
        needsLogin: Boolean(pageState.needsLogin)
      };
    } else if (job.action === 'check_tab') {
      const tab = await resolveTargetTab(job, false);
      if (!tab || !tab.id) {
        payload = {
          jobId: job.jobId,
          success: true,
          ready: false,
          needsLogin: true,
          clientId: cfg.clientId,
          profileName: cfg.profileName,
          reply: 'Tab not found yet'
        };
      } else {
        let pageState = { ready: false, needsLogin: false };
        try {
          const res = await chrome.scripting.executeScript({ target: { tabId: tab.id }, func: checkPageLoginState });
          if (res?.[0]?.result) pageState = res[0].result;
        } catch (e) {}
        payload = {
          jobId: job.jobId,
          success: true,
          tabId: tab.id,
          tabUrl: tab.url,
          tabTitle: tab.title,
          clientId: cfg.clientId,
          profileName: cfg.profileName,
          ready: Boolean(pageState.ready),
          needsLogin: Boolean(pageState.needsLogin),
          reply: pageState.ready ? 'Ready' : 'Waiting for login'
        };
      }
    } else {
      const tab = await resolveTargetTab(job, true);
      if (!tab || !tab.id) {
        throw new Error('Target browser tab is not open. Open ' + (job.targetUrl || 'your AI site') + ' in Chrome.');
      }
      const execResults = await chrome.scripting.executeScript({
        target: { tabId: tab.id },
        func: executePromptInPage,
        args: [job.prompt]
      });
      const outcome = execResults?.[0]?.result;
      if (outcome && outcome.success) {
        payload = {
          jobId: job.jobId,
          success: true,
          reply: outcome.reply,
          tabId: tab.id,
          tabUrl: tab.url,
          clientId: cfg.clientId,
          profileName: cfg.profileName
        };
      } else {
        payload = {
          jobId: job.jobId,
          success: false,
          error: outcome?.error || 'Could not extract response from tab.',
          tabId: tab.id,
          tabUrl: tab.url,
          clientId: cfg.clientId,
          profileName: cfg.profileName
        };
      }
    }
  } catch (err) {
    payload = { jobId: job.jobId, success: false, error: err.message, clientId: cfg.clientId, profileName: cfg.profileName };
  }

  try {
    await fetch(cfg.serverUrl + '/api/bridge/result', {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        'X-Bridge-Token': cfg.bridgeToken
      },
      body: JSON.stringify(payload)
    });
  } catch (e) {
    // ignore network failure reporting back
  }
}

async function runLongPollLoop() {
  if (pollLoopActive) return;
  pollLoopActive = true;
  try {
    while (pollLoopActive) {
      const cfg = await loadConfig();
      if (!cfg.serverUrl || !cfg.bridgeToken) {
        await new Promise((r) => setTimeout(r, 2000));
        continue;
      }

      if (Date.now() - lastHeartbeatSent >= HEARTBEAT_EVERY_MS) {
        await sendHeartbeat();
      }

      try {
        const pollUrl = cfg.serverUrl +
          '/api/bridge/poll?wait=' + LONG_POLL_WAIT_MS +
          '&clientId=' + encodeURIComponent(cfg.clientId) +
          '&profileName=' + encodeURIComponent(cfg.profileName);
        const res = await fetch(pollUrl, {
          method: 'GET',
          credentials: 'include',
          headers: { 'X-Bridge-Token': cfg.bridgeToken }
        });
        if (!res.ok) {
          lastStatus = { ...lastStatus, connected: false, error: 'HTTP ' + res.status };
          await new Promise((r) => setTimeout(r, 2500));
          continue;
        }
        lastHeartbeatSent = Date.now();
        lastStatus = { ...lastStatus, connected: true, error: null, lastSync: new Date().toISOString() };
        const data = await res.json();
        if (Array.isArray(data.jobs) && data.jobs.length > 0) {
          await Promise.all(data.jobs.map((job) => processJob(job)));
        }
      } catch (e) {
        lastStatus = { ...lastStatus, connected: false, error: e.message };
        await new Promise((r) => setTimeout(r, 2500));
      }
    }
  } finally {
    pollLoopActive = false;
  }
}

function ensureStarted() {
  loadConfig().then(() => {
    sendHeartbeat();
    runLongPollLoop();
  });
  chrome.alarms.create('bridgeKeepAlive', { periodInMinutes: 1 });
}

chrome.runtime.onInstalled.addListener(ensureStarted);
chrome.runtime.onStartup.addListener(ensureStarted);
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === 'bridgeKeepAlive') {
    sendHeartbeat();
    runLongPollLoop();
  }
});

chrome.tabs.onUpdated.addListener((_id, info) => {
  if (info.status === 'complete') sendHeartbeat();
});

chrome.tabs.onRemoved.addListener(() => {
  sendHeartbeat();
});

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg.type === 'GET_STATE') {
    (async () => {
      const cfg = await loadConfig();
      const tabs = await listCandidateTabs();
      sendResponse({ cfg, status: lastStatus, tabs });
    })();
    return true;
  }
  if (msg.type === 'SAVE_CONFIG') {
    (async () => {
      const cur = await loadConfig();
      cachedConfig = {
        serverUrl: (msg.serverUrl || cur.serverUrl || '').trim().replace(/\\/+$/, ''),
        bridgeToken: (msg.bridgeToken || cur.bridgeToken || '').trim(),
        clientId: cur.clientId,
        profileName: (msg.profileName || cur.profileName || '').trim() || cur.profileName
      };
      await chrome.storage.local.set(cachedConfig);
      await sendHeartbeat();
      runLongPollLoop();
      const tabs = await listCandidateTabs();
      sendResponse({ ok: true, cfg: cachedConfig, status: lastStatus, tabs });
    })();
    return true;
  }
  if (msg.type === 'SYNC_NOW') {
    (async () => {
      await sendHeartbeat();
      runLongPollLoop();
      const tabs = await listCandidateTabs();
      sendResponse({ status: lastStatus, tabs });
    })();
    return true;
  }
});

ensureStarted();
`;

  const popupHtml = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <title>Muse Boardroom Bridge</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { width: 350px; font-family: system-ui, -apple-system, sans-serif; background: #0b0e14; color: #f1f5f9; padding: 14px; }
    h1 { font-size: 14px; font-weight: 700; margin-bottom: 4px; }
    .sub { font-size: 12px; color: #94a3b8; margin-bottom: 12px; }
    .status { font-size: 12px; font-weight: 600; padding: 8px 10px; border-radius: 8px; background: #121722; border: 1px solid #232d42; margin-bottom: 12px; }
    .status.ok { border-color: #10b981; color: #34d399; }
    .status.err { border-color: #ef4444; color: #f87171; }
    label { display: block; font-size: 11px; color: #94a3b8; margin-bottom: 4px; margin-top: 8px; }
    input { width: 100%; background: #121722; border: 1px solid #2b354b; border-radius: 6px; padding: 7px 9px; color: #f8fafc; font-size: 12px; }
    .row { display: flex; gap: 8px; margin-top: 10px; }
    button { flex: 1; padding: 8px 10px; border-radius: 6px; border: none; font-size: 12px; font-weight: 600; cursor: pointer; }
    .primary { background: #f59e0b; color: #090d16; }
    .secondary { background: #1e293b; color: #e2e8f0; border: 1px solid #334155; }
    .tabs { margin-top: 12px; max-height: 140px; overflow-y: auto; border-top: 1px solid #1e293b; padding-top: 8px; }
    .tab-item { font-size: 11px; padding: 5px 0; border-bottom: 1px solid #151c2c; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: #cbd5e1; }
  </style>
</head>
<body>
  <h1>Muse Boardroom Bridge</h1>
  <p class="sub">Keeps this Chrome Profile connected permanently for Muse AI, Gemini, ChatGPT, and Grok.</p>
  <div id="statusBox" class="status">Checking connection...</div>
  <label for="profileName">Chrome Profile Label (For Multi-Account)</label>
  <input id="profileName" type="text" placeholder="e.g. Work Chrome or Muse Account 2" />
  <label for="serverUrl">Command Center URL</label>
  <input id="serverUrl" type="text" placeholder="https://..." />
  <label for="bridgeToken">Pairing Token</label>
  <input id="bridgeToken" type="text" placeholder="mb_..." />
  <div class="row">
    <button id="saveBtn" class="primary">Save & Connect</button>
    <button id="syncBtn" class="secondary">Sync Tabs</button>
  </div>
  <div class="tabs" id="tabList"></div>
  <script src="popup.js"></script>
</body>
</html>`;

  const popupJs = `
function render(state) {
  const box = document.getElementById('statusBox');
  const profEl = document.getElementById('profileName');
  const urlEl = document.getElementById('serverUrl');
  const tokEl = document.getElementById('bridgeToken');
  const tabList = document.getElementById('tabList');

  if (state.cfg) {
    profEl.value = state.cfg.profileName || '';
    urlEl.value = state.cfg.serverUrl || '';
    tokEl.value = state.cfg.bridgeToken || '';
  }

  if (state.status?.connected) {
    box.className = 'status ok';
    box.textContent = 'Connected (' + (state.cfg?.profileName || 'Chrome') + ') · ' + (state.tabs?.length || 0) + ' tabs';
  } else {
    box.className = 'status err';
    box.textContent = 'Disconnected' + (state.status?.error ? ' (' + state.status.error + ')' : '');
  }

  tabList.innerHTML = '';
  (state.tabs || []).slice(0, 12).forEach((t) => {
    const div = document.createElement('div');
    div.className = 'tab-item';
    div.textContent = t.title + ' — ' + t.url;
    tabList.appendChild(div);
  });
}

chrome.runtime.sendMessage({ type: 'GET_STATE' }, (res) => {
  if (res) render(res);
});

document.getElementById('saveBtn').addEventListener('click', () => {
  const profileName = document.getElementById('profileName').value;
  const serverUrl = document.getElementById('serverUrl').value;
  const bridgeToken = document.getElementById('bridgeToken').value;
  chrome.runtime.sendMessage({ type: 'SAVE_CONFIG', profileName, serverUrl, bridgeToken }, (res) => {
    if (res) render(res);
  });
});

document.getElementById('syncBtn').addEventListener('click', () => {
  chrome.runtime.sendMessage({ type: 'SYNC_NOW' }, (res) => {
    if (res) {
      chrome.runtime.sendMessage({ type: 'GET_STATE' }, (full) => full && render(full));
    }
  });
});
`;

  return [
    { name: 'muse-desktop-bridge/manifest.json', content: JSON.stringify(manifest, null, 2) },
    { name: 'muse-desktop-bridge/bridge-config.json', content: JSON.stringify(bridgeConfig, null, 2) },
    { name: 'muse-desktop-bridge/background.js', content: backgroundJs.trimStart() },
    { name: 'muse-desktop-bridge/popup.html', content: popupHtml },
    { name: 'muse-desktop-bridge/popup.js', content: popupJs.trimStart() }
  ];
}
