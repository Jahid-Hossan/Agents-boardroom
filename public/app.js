// Muse Boardroom — Unified Multi-Agent & In-App Browser Connector Controller

let appConfig = { companyName: 'Muse Boardroom', ceoName: 'You', accounts: [] };
let bridgeState = { connected: false, clients: [], tabs: [], bridgeToken: '', serverUrl: '' };
let inAppStatuses = {};
let inAppEngineState = { ready: true, downloading: false };
let cachedMessages = [];
let currentView = 'boardroom'; // 'boardroom' | 'direct'
let currentDirectAgentId = null;
let activeTabId = null;
let activeBrowserAgentId = null;
let browserEventSource = null;
let lastMouseMoveAt = 0;
let currentAttachment = null;
let isSending = false;
let speechRecognition = null;
let mediaRecorder = null;
let mediaStream = null;
let audioChunks = [];
let voiceTimerInterval = null;
let voiceStartAt = 0;
let cancelVoiceRequested = false;
let isRecording = false;
let isStartingMic = false;
let isTranscribing = false;
let preferBrowserSpeech = false;
let confirmCallback = null;
let toastTimer = null;
let lastBridgeSig = '';
let lastInAppSig = '';

const UI_ICONS = {
  chat: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>',
  browser: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>',
  browserLg: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>',
  edit: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>',
  remove: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>',
  send: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>',
  sending: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="icon-spin" aria-hidden="true"><line x1="12" y1="2" x2="12" y2="6"/><line x1="12" y1="18" x2="12" y2="22"/><line x1="4.93" y1="4.93" x2="7.76" y2="7.76"/><line x1="16.24" y1="16.24" x2="19.07" y2="19.07"/><line x1="2" y1="12" x2="6" y2="12"/><line x1="18" y1="12" x2="22" y2="12"/><line x1="4.93" y1="19.07" x2="7.76" y2="16.24"/><line x1="16.24" y1="7.76" x2="19.07" y2="4.93"/></svg>',
  mic: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="22"/></svg>',
  stop: '<svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" aria-hidden="true"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>',
  file: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>',
  plus: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>',
  check: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="20 6 9 17 4 12"/></svg>',
  link: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>',
  copy: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>',
  userPlus: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><line x1="19" y1="8" x2="19" y2="14"/><line x1="22" y1="11" x2="16" y2="11"/></svg>',
  plug: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 22v-5"/><path d="M9 8V2"/><path d="M15 8V2"/><path d="M18 8v5a4 4 0 0 1-4 4h-4a4 4 0 0 1-4-4V8Z"/></svg>',
  shieldCheck: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><path d="m9 12 2 2 4-4"/></svg>',
  externalLink: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>',
  zap: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>'
};

document.addEventListener('DOMContentLoaded', async () => {
  bindStaticEvents();
  setupClipboardPaste();
  await Promise.all([loadConfig(), loadMessages(), loadBridgeStatus(), loadInAppStatuses()]);
  setInterval(() => {
    loadBridgeStatus();
    loadInAppStatuses();
  }, 3000);
});

function bindStaticEvents() {
  document.getElementById('mobileMenuBtn')?.addEventListener('click', toggleMobileSidebar);
  document.getElementById('sidebarBackdrop')?.addEventListener('click', closeMobileSidebar);

  document.getElementById('btnViewBoardroom')?.addEventListener('click', () => switchView('boardroom'));
  document.getElementById('btnViewIndividual')?.addEventListener('click', toggleIndividualDropdown);

  document.getElementById('btnOpenInAppBrowser')?.addEventListener('click', () => openInAppBrowserModal());
  document.getElementById('btnSidebarOpenBrowser')?.addEventListener('click', () => {
    closeMobileSidebar();
    openInAppBrowserModal();
  });
  document.getElementById('btnCloseBrowserWorkspace')?.addEventListener('click', closeInAppBrowserModal);
  document.getElementById('btnBrowserBack')?.addEventListener('click', () => navigateInAppBrowser('back'));
  document.getElementById('btnBrowserReload')?.addEventListener('click', () => navigateInAppBrowser('reload'));
  document.getElementById('browserUrlForm')?.addEventListener('submit', (e) => {
    e.preventDefault();
    const url = document.getElementById('browserUrlInput')?.value.trim() || '';
    if (url) navigateInAppBrowser('goto', url);
  });
  document.querySelectorAll('.browser-site-chip').forEach((chip) => {
    chip.addEventListener('click', () => {
      const targetUrl = chip.getAttribute('data-url');
      if (targetUrl) navigateInAppBrowser('goto', targetUrl);
    });
  });
  document.getElementById('btnBrowserCheckReady')?.addEventListener('click', refreshInAppBrowserStatus);
  document.getElementById('btnBrowserCloseTab')?.addEventListener('click', closeCurrentInAppBrowserTab);
  document.getElementById('browserQuickTextForm')?.addEventListener('submit', (e) => {
    e.preventDefault();
    sendInAppQuickText();
  });
  document.getElementById('btnBrowserPressEnter')?.addEventListener('click', () => {
    if (activeBrowserAgentId) {
      sendInAppInput(activeBrowserAgentId, { kind: 'key', key: 'Enter', code: 'Enter' });
    }
  });
  bindBrowserViewportEvents();

  document.getElementById('btnBridgeSetup')?.addEventListener('click', openBridgeModal);
  document.getElementById('btnSidebarBridgeSetup')?.addEventListener('click', openBridgeModal);
  document.getElementById('btnCloseBridgeModal')?.addEventListener('click', closeBridgeModal);
  document.getElementById('btnDoneBridgeModal')?.addEventListener('click', closeBridgeModal);

  document.getElementById('btnManageAgents')?.addEventListener('click', () => openConfigModal());
  document.getElementById('btnQuickAddAgent')?.addEventListener('click', () => {
    openConfigModal();
    addNewAgent();
  });
  document.getElementById('btnCloseConfigModal')?.addEventListener('click', closeConfigModal);
  document.getElementById('btnCancelConfigModal')?.addEventListener('click', closeConfigModal);
  document.getElementById('btnSaveAgentConfig')?.addEventListener('click', saveCurrentAccountConfig);
  document.getElementById('btnModalDeleteAgent')?.addEventListener('click', () => {
    if (activeTabId) deleteCurrentAgent(activeTabId);
  });

  document.getElementById('btnClearChat')?.addEventListener('click', clearBoardroom);
  document.getElementById('btnReturnBoardroom')?.addEventListener('click', () => switchView('boardroom'));

  document.getElementById('attachBtn')?.addEventListener('click', () => {
    const picker = document.getElementById('filePicker');
    if (picker) picker.click();
  });
  document.getElementById('filePicker')?.addEventListener('change', handleFileSelect);
  document.getElementById('btnClearAttachment')?.addEventListener('click', clearAttachment);
  document.getElementById('btnTranscribeAttachment')?.addEventListener('click', transcribeCurrentAttachment);
  document.getElementById('micBtn')?.addEventListener('click', toggleVoiceDictation);
  document.getElementById('btnStopVoice')?.addEventListener('click', stopVoiceRecording);
  document.getElementById('btnCancelVoice')?.addEventListener('click', cancelVoiceRecording);
  setupComposerDragAndDrop();

  document.getElementById('commandForm')?.addEventListener('submit', (e) => {
    e.preventDefault();
    sendCommand();
  });

  document.getElementById('btnCopyServerUrl')?.addEventListener('click', () => copyInput('bridgeServerUrlInput', 'Command Center URL copied'));
  document.getElementById('btnCopyBridgeToken')?.addEventListener('click', () => copyInput('bridgeTokenInput', 'Pairing token copied'));
  document.getElementById('btnRotateToken')?.addEventListener('click', rotateBridgeToken);

  document.getElementById('btnCloseConfirmModal')?.addEventListener('click', closeConfirmModal);
  document.getElementById('btnConfirmCancel')?.addEventListener('click', closeConfirmModal);

  document.addEventListener('click', (e) => {
    const dd = document.getElementById('agentDropdown');
    const btn = document.getElementById('btnViewIndividual');
    if (dd && dd.classList.contains('open') && !e.target.closest('.dropdown-wrapper')) {
      dd.classList.remove('open');
      if (btn) btn.setAttribute('aria-expanded', 'false');
    }
  });
}

function toggleMobileSidebar() {
  document.getElementById('sidebar')?.classList.toggle('open');
  document.getElementById('sidebarBackdrop')?.classList.toggle('open');
}

function closeMobileSidebar() {
  document.getElementById('sidebar')?.classList.remove('open');
  document.getElementById('sidebarBackdrop')?.classList.remove('open');
}

function showToast(message, isError = false) {
  const toast = document.getElementById('appToast');
  if (!toast) return;
  toast.textContent = message;
  toast.className = `toast open ${isError ? 'error' : 'success'}`;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    toast.classList.remove('open');
  }, 3500);
}

function showConfirmModal(title, message, onConfirm) {
  const modal = document.getElementById('confirmModal');
  const titleEl = document.getElementById('confirmModalTitle');
  const msgEl = document.getElementById('confirmModalMessage');
  const okBtn = document.getElementById('confirmModalOkBtn');
  if (!modal || !okBtn) return;

  if (titleEl) titleEl.textContent = title;
  if (msgEl) msgEl.textContent = message;
  confirmCallback = onConfirm;

  okBtn.onclick = async () => {
    const cb = confirmCallback;
    closeConfirmModal();
    if (cb) await cb();
  };

  modal.classList.add('open');
}

function closeConfirmModal() {
  document.getElementById('confirmModal')?.classList.remove('open');
  confirmCallback = null;
}

async function copyInput(inputId, label) {
  const input = document.getElementById(inputId);
  if (!input) return;
  try {
    await navigator.clipboard.writeText(input.value);
    showToast(label);
  } catch {
    input.select();
    showToast('Selected text — press Ctrl+C to copy');
  }
}

// Data Loading
async function loadConfig() {
  try {
    const res = await fetch('/api/config');
    appConfig = await res.json();
    if (!Array.isArray(appConfig.accounts)) appConfig.accounts = [];
    renderHeaderMeta();
    renderRoster();
    renderMentionChips();
    renderMessages();
  } catch (err) {
    console.error('Config load failed:', err);
  }
}

async function loadMessages() {
  try {
    const res = await fetch('/api/messages');
    cachedMessages = await res.json();
    renderMessages();
  } catch (err) {
    console.error('Messages load failed:', err);
  }
}

function normalizeHttpUrl(raw = '') {
  const s = String(raw || '').trim();
  if (!s) return '';
  return /^https?:\/\//i.test(s) ? s : `https://${s}`;
}

function getHostname(rawUrl = '') {
  try {
    return new URL(normalizeHttpUrl(rawUrl)).hostname.replace(/^www\./i, '').toLowerCase();
  } catch {
    return '';
  }
}

function samePlatformFamily(hostA, hostB) {
  if (!hostA || !hostB) return false;
  if (hostA === hostB || hostA.includes(hostB) || hostB.includes(hostA)) return true;
  const families = [
    ['chatgpt.com', 'chat.openai.com'],
    ['grok.com', 'x.com']
  ];
  return families.some((fam) => fam.includes(hostA) && fam.includes(hostB));
}

function findMatchingTabForAgent(agent, tabs = []) {
  if (!Array.isArray(tabs) || tabs.length === 0) return null;
  const pool = agent.clientId ? tabs.filter((t) => t.clientId === agent.clientId) : tabs;
  if (agent.tabId) {
    const exact = pool.find((t) => Number(t.tabId) === Number(agent.tabId));
    if (exact) return exact;
  }
  const targetHost = getHostname(agent.customUrl);
  if (!targetHost) return null;
  return pool.find((t) => samePlatformFamily(getHostname(t.url), targetHost)) || null;
}

function findUnboundAgentForTab(tab, accounts = []) {
  const tabHost = getHostname(tab.url);
  if (!tabHost) return null;
  return accounts.find((a) => {
    if (a.provider !== 'browser-session' && a.provider !== 'browser-automation') return false;
    if (a.tabId) return false;
    if (a.clientId && tab.clientId && a.clientId !== tab.clientId) return false;
    return samePlatformFamily(getHostname(a.customUrl), tabHost);
  }) || null;
}

function isAgentProfileOnline(agent) {
  if (!bridgeState.connected) return false;
  if (!agent.clientId) return true;
  const clients = Array.isArray(bridgeState.clients) ? bridgeState.clients : [];
  return clients.some((c) => c.clientId === agent.clientId);
}

async function loadBridgeStatus() {
  try {
    const res = await fetch('/api/bridge/status');
    bridgeState = await res.json();
    const sig = JSON.stringify({
      connected: Boolean(bridgeState.connected),
      clients: (bridgeState.clients || []).map((c) => [c.clientId, c.profileName, c.tabCount]),
      tabs: (bridgeState.tabs || []).map((t) => [t.clientId, t.tabId, t.url])
    });
    const changed = sig !== lastBridgeSig;
    lastBridgeSig = sig;
    renderBridgeState();
    renderHeaderMeta();
    if (changed) {
      renderRoster();
      renderMessages();
      if (document.getElementById('configModal')?.classList.contains('open')) {
        refreshModalBridgeControls();
      }
    }
  } catch (err) {
    // Ignore transient polling failures
  }
}

async function loadInAppStatuses() {
  try {
    const res = await fetch('/api/inapp-browsers');
    const data = await res.json();
    inAppStatuses = data.statuses || {};
    inAppEngineState = {
      ready: data.browserReady !== false,
      downloading: Boolean(data.browserDownloading)
    };
    const sig = JSON.stringify(inAppStatuses);
    const changed = sig !== lastInAppSig;
    lastInAppSig = sig;
    if (changed) {
      renderHeaderMeta();
      renderBridgeState();
      renderRoster();
      renderMessages();
    }
  } catch {
    // Ignore transient polling failures
  }
}

function getInAppSummary() {
  const vals = Object.values(inAppStatuses || {});
  const openCount = vals.filter((s) => s.open).length;
  const readyCount = vals.filter((s) => s.open && s.ready).length;
  const savedCount = vals.filter((s) => s.hasSavedProfile).length;
  return { openCount, readyCount, savedCount };
}

function renderHeaderMeta() {
  const count = appConfig.accounts?.length || 0;
  const countEl = document.getElementById('agentCountText');
  const bridgeTextEl = document.getElementById('headerBridgeText');
  const bridgeDotEl = document.getElementById('headerBridgeDot');
  const browserDotEl = document.getElementById('headerBrowserDot');
  const clientCount = bridgeState.clients?.length || 0;
  const tabCount = bridgeState.tabs?.length || 0;
  const { openCount, readyCount, savedCount } = getInAppSummary();

  if (countEl) countEl.textContent = `${count} ${count === 1 ? 'agent' : 'agents'}`;
  if (bridgeTextEl) {
    if (readyCount > 0) {
      bridgeTextEl.textContent = `${readyCount} in-app ${readyCount === 1 ? 'session' : 'sessions'} connected`;
    } else if (openCount > 0) {
      bridgeTextEl.textContent = `${openCount} in-app ${openCount === 1 ? 'tab' : 'tabs'} open`;
    } else if (bridgeState.connected) {
      bridgeTextEl.textContent = clientCount > 1
        ? `Ext. bridge (${clientCount} profiles · ${tabCount} tabs)`
        : `Ext. bridge (${tabCount} tabs)`;
    } else if (savedCount > 0) {
      bridgeTextEl.textContent = `${savedCount} saved ${savedCount === 1 ? 'session' : 'sessions'}`;
    } else {
      bridgeTextEl.textContent = 'In-App Connector ready';
    }
  }
  if (bridgeDotEl) bridgeDotEl.classList.toggle('connected', Boolean(bridgeState.connected));
  if (browserDotEl) browserDotEl.classList.toggle('connected', openCount > 0 || savedCount > 0);
}

function renderBridgeState() {
  const sidebarDot = document.getElementById('sidebarBridgeDot');
  const statusText = document.getElementById('sidebarBridgeStatus');
  const helpText = document.getElementById('sidebarBridgeHelp');
  const tabsList = document.getElementById('detectedTabsList');
  const modalStatus = document.getElementById('bridgeModalStatus');
  const profilesList = document.getElementById('bridgeProfilesList');
  const urlInput = document.getElementById('bridgeServerUrlInput');
  const tokenInput = document.getElementById('bridgeTokenInput');
  const clients = Array.isArray(bridgeState.clients) ? bridgeState.clients : [];
  const tabs = Array.isArray(bridgeState.tabs) ? bridgeState.tabs : [];
  const { openCount, readyCount, savedCount } = getInAppSummary();

  if (sidebarDot) sidebarDot.classList.toggle('connected', true);
  if (statusText) {
    if (readyCount > 0) {
      statusText.textContent = `Connector active · ${readyCount} ready`;
    } else if (openCount > 0) {
      statusText.textContent = `Connector attached · ${openCount} open`;
    } else if (savedCount > 0) {
      statusText.textContent = `Connector ready · ${savedCount} saved`;
    } else if (bridgeState.connected) {
      statusText.textContent = `Ext. Bridge · ${tabs.length} tabs`;
    } else {
      statusText.textContent = 'Connector attached';
    }
  }
  if (helpText) {
    helpText.textContent = bridgeState.connected
      ? 'In-App Browser + Ext. Bridge ready. Click Browser on any agent to view or sign in.'
      : 'Click Browser on any agent to sign in inside this app. Each account saves its own session.';
  }
  if (modalStatus) {
    modalStatus.textContent = bridgeState.connected
      ? `Connected (${clients.length || 1} Chrome ${clients.length === 1 ? 'Profile' : 'Profiles'}, ${tabs.length} tabs)`
      : 'Waiting for Chrome extension heartbeat...';
  }
  if (urlInput && bridgeState.serverUrl) urlInput.value = bridgeState.serverUrl;
  if (tokenInput && bridgeState.bridgeToken) tokenInput.value = bridgeState.bridgeToken;

  if (profilesList) {
    profilesList.innerHTML = '';
    if (clients.length === 0) {
      const span = document.createElement('span');
      span.style.fontSize = '12px';
      span.style.color = 'var(--text-secondary)';
      span.textContent = 'No Chrome Profiles connected yet.';
      profilesList.appendChild(span);
    } else {
      clients.forEach((c) => {
        const row = document.createElement('div');
        row.className = 'profile-row';
        const left = document.createElement('div');
        left.innerHTML = `<strong>${escapeHtml(c.profileName)}</strong> <span class="mono" style="color: var(--text-muted); font-size: 11px;">(${escapeHtml(c.clientId)})</span>`;
        const right = document.createElement('span');
        right.style.color = '#34d399';
        right.textContent = `Connected · ${c.tabCount} tabs`;
        row.append(left, right);
        profilesList.appendChild(row);
      });
    }
  }

  if (!tabsList) return;
  tabsList.innerHTML = '';

  if (!bridgeState.connected || tabs.length === 0) return;

  // Sort known AI sites first
  const sorted = [...tabs].sort((a, b) => Number(b.isKnownAi) - Number(a.isKnownAi));
  sorted.slice(0, 10).forEach((tab) => {
    const row = document.createElement('div');
    row.className = 'detected-tab-row';

    const info = document.createElement('div');
    info.className = 'detected-tab-info';
    const nameEl = document.createElement('div');
    nameEl.className = 'detected-tab-name';
    const baseLabel = tab.isKnownAi ? `${tab.label} — ${tab.title}` : tab.title;
    nameEl.textContent = clients.length > 1 && tab.profileName ? `[${tab.profileName}] ${baseLabel}` : baseLabel;
    const urlEl = document.createElement('div');
    urlEl.className = 'detected-tab-url mono';
    urlEl.textContent = tab.url;
    info.append(nameEl, urlEl);

    const exactAgent = appConfig.accounts.find(
      (a) => Number(a.tabId) === Number(tab.tabId) && (!a.clientId || !tab.clientId || a.clientId === tab.clientId)
    );
    const unboundMatch = !exactAgent ? findUnboundAgentForTab(tab, appConfig.accounts) : null;
    const bindBtn = document.createElement('button');
    bindBtn.type = 'button';
    bindBtn.className = exactAgent ? 'btn btn-sm btn-icon-sm' : 'btn btn-sm btn-icon-sm btn-primary';
    bindBtn.innerHTML = exactAgent ? UI_ICONS.check : (unboundMatch ? UI_ICONS.link : UI_ICONS.plus);
    bindBtn.disabled = Boolean(exactAgent);
    const bindLabel = exactAgent ? `${tab.title} bound` : (unboundMatch ? `Bind ${unboundMatch.name}` : `Connect tab ${tab.title}`);
    bindBtn.title = bindLabel;
    bindBtn.setAttribute('aria-label', bindLabel);
    bindBtn.addEventListener('click', () => connectBrowserTabAsAgent(tab));

    row.append(info, bindBtn);
    tabsList.appendChild(row);
  });
}

async function connectBrowserTabAsAgent(tab) {
  const unboundMatch = findUnboundAgentForTab(tab, appConfig.accounts);
  if (unboundMatch) {
    unboundMatch.clientId = tab.clientId || unboundMatch.clientId || null;
    unboundMatch.profileName = tab.profileName || unboundMatch.profileName || '';
    unboundMatch.tabId = tab.tabId;
    unboundMatch.customUrl = tab.url;
    await persistConfig();
    renderBridgeState();
    renderRoster();
    showToast(`Bound "${unboundMatch.name}" to ${tab.profileName || 'Chrome'} tab #${tab.tabId}.`);
    return;
  }

  const baseName = tab.isKnownAi ? tab.label : tab.title.split(' - ')[0].slice(0, 24);
  const existingNames = new Set(appConfig.accounts.map((a) => a.name));
  let name = baseName || 'Browser Agent';
  let idx = 2;
  while (existingNames.has(name)) {
    name = `${baseName} ${idx++}`;
  }

  const newAgent = {
    id: `tab-${tab.tabId}-${Date.now()}`,
    name,
    role: tab.profileName ? `${tab.profileName}` : 'Desktop Browser Session',
    avatar: tab.avatar || 'B',
    color: tab.color || '#3B82F6',
    provider: 'browser-session',
    clientId: tab.clientId || null,
    profileName: tab.profileName || '',
    tabId: tab.tabId,
    customUrl: tab.url,
    systemPrompt: ''
  };

  appConfig.accounts.push(newAgent);
  await persistConfig();
  renderBridgeState();
  showToast(`Added "${name}" (${tab.profileName || 'Chrome'} tab #${tab.tabId}).`);
}

function providerLabel(provider) {
  switch (provider) {
    case 'inapp-browser': return 'In-App Browser';
    case 'browser-session': return 'Desktop Tab';
    case 'browser-automation': return 'Live Browser';
    case 'gemini': return 'Gemini API';
    case 'openai':
    case 'custom-api': return 'OpenAI API';
    case 'docker-web': return 'Docker / Webhook';
    default: return 'Offline Demo';
  }
}

function renderRoster() {
  const roster = document.getElementById('agentRoster');
  if (!roster) return;
  roster.innerHTML = '';

  if (!appConfig.accounts || appConfig.accounts.length === 0) {
    const empty = document.createElement('p');
    empty.className = 'empty-roster';
    empty.textContent = 'No agents connected yet. Click + Add or open the In-App Browser.';
    roster.appendChild(empty);
    return;
  }

  appConfig.accounts.forEach((agent) => {
    const isActive = currentView === 'direct' && currentDirectAgentId === agent.id;
    const card = document.createElement('div');
    card.className = `agent-card ${isActive ? 'active' : ''}`;

    const top = document.createElement('div');
    top.className = 'agent-card-top';

    const left = document.createElement('div');
    left.style.minWidth = '0';

    const title = document.createElement('div');
    title.className = 'agent-card-title';
    title.textContent = agent.name;

    const meta = document.createElement('div');
    meta.className = 'agent-card-meta';
    if (agent.provider === 'inapp-browser') {
      const st = inAppStatuses[agent.id] || {};
      const host = getHostname(st.url || agent.customUrl) || 'site';
      if (st.open && st.ready) {
        meta.textContent = `In-App Browser · Connected (${host})`;
      } else if (st.open && st.needsLogin) {
        meta.textContent = `In-App Browser · Sign in on ${host}`;
      } else if (st.open) {
        meta.textContent = `In-App Browser · Tab open (${host})`;
      } else if (st.hasSavedProfile) {
        meta.textContent = `In-App Browser · Saved session (${host})`;
      } else {
        meta.textContent = `In-App Browser · ${host}`;
      }
    } else if (agent.provider === 'browser-session' || agent.provider === 'browser-automation') {
      const online = isAgentProfileOnline(agent);
      const matchedTab = online ? findMatchingTabForAgent(agent, bridgeState.tabs) : null;
      const host = getHostname(agent.customUrl);
      const profLabel = matchedTab?.profileName || agent.profileName || '';
      const prefix = profLabel ? `${profLabel} · ` : '';
      if (!online) {
        meta.textContent = `${providerLabel(agent.provider)} · ${profLabel ? profLabel + ' offline' : 'Bridge offline'}`;
      } else if (matchedTab) {
        meta.textContent = `${providerLabel(agent.provider)} · ${prefix}Tab #${matchedTab.tabId} active`;
      } else {
        meta.textContent = `${providerLabel(agent.provider)} · ${prefix}Auto-opens ${host || 'site'}`;
      }
    } else {
      const detail = agent.model || agent.customUrl || 'Ready';
      meta.textContent = `${providerLabel(agent.provider)} · ${detail}`;
    }

    left.append(title, meta);
    top.appendChild(left);

    const actions = document.createElement('div');
    actions.className = 'agent-card-actions';

    const leftBtns = document.createElement('div');
    leftBtns.className = 'agent-card-actions-left';

    const chatBtn = document.createElement('button');
    chatBtn.type = 'button';
    chatBtn.className = `btn btn-sm btn-icon-sm ${isActive ? 'btn-primary' : ''}`;
    chatBtn.innerHTML = UI_ICONS.chat;
    chatBtn.title = isActive ? `Active 1-on-1 chat with ${agent.name}` : `Chat 1-on-1 with ${agent.name}`;
    chatBtn.setAttribute('aria-label', `Chat 1-on-1 with ${agent.name}`);
    chatBtn.addEventListener('click', () => {
      switchView('direct', agent.id);
      closeMobileSidebar();
    });

    leftBtns.appendChild(chatBtn);

    if (agent.provider === 'inapp-browser') {
      const browserBtn = document.createElement('button');
      browserBtn.type = 'button';
      const st = inAppStatuses[agent.id] || {};
      browserBtn.className = (st.open && st.ready) ? 'btn btn-sm btn-icon-sm' : 'btn btn-sm btn-icon-sm btn-primary';
      browserBtn.innerHTML = UI_ICONS.browser;
      browserBtn.title = `Open In-App Browser for ${agent.name}`;
      browserBtn.setAttribute('aria-label', `Open In-App Browser for ${agent.name}`);
      browserBtn.addEventListener('click', () => {
        closeMobileSidebar();
        openInAppBrowserModal(agent.id);
      });
      leftBtns.appendChild(browserBtn);
    }

    const editBtn = document.createElement('button');
    editBtn.type = 'button';
    editBtn.className = 'btn btn-sm btn-icon-sm';
    editBtn.innerHTML = UI_ICONS.edit;
    editBtn.title = `Edit ${agent.name}`;
    editBtn.setAttribute('aria-label', `Edit ${agent.name}`);
    editBtn.addEventListener('click', () => openConfigModal(agent.id));

    leftBtns.appendChild(editBtn);

    const removeBtn = document.createElement('button');
    removeBtn.type = 'button';
    removeBtn.className = 'btn btn-sm btn-icon-sm btn-danger';
    removeBtn.innerHTML = UI_ICONS.remove;
    removeBtn.title = `Remove ${agent.name}`;
    removeBtn.setAttribute('aria-label', `Remove ${agent.name}`);
    removeBtn.addEventListener('click', () => removeAccount(agent.id));

    actions.append(leftBtns, removeBtn);
    card.append(top, actions);
    roster.appendChild(card);
  });
}

function renderMentionChips() {
  const container = document.getElementById('mentionChips');
  if (!container) return;
  container.innerHTML = '';

  const allBtn = document.createElement('button');
  allBtn.type = 'button';
  allBtn.className = 'mention-btn';
  allBtn.textContent = '@all';
  allBtn.addEventListener('click', () => insertMention('@all'));
  container.appendChild(allBtn);

  (appConfig.accounts || []).forEach((agent) => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'mention-btn';
    btn.textContent = `@${agent.name}`;
    btn.addEventListener('click', () => insertMention(`@${agent.name}`));
    container.appendChild(btn);
  });
}

function insertMention(tag) {
  const input = document.getElementById('commandInput');
  if (!input) return;
  const val = input.value.trim();
  if (!val) {
    input.value = `${tag} `;
  } else if (!val.includes(tag)) {
    input.value = `${tag} ${val}`;
  }
  input.focus();
}

function switchView(view, agentId = null) {
  currentView = view;
  const btnBoardroom = document.getElementById('btnViewBoardroom');
  const btnIndividual = document.getElementById('btnViewIndividual');
  const bannerText = document.getElementById('bannerText');
  const btnReturn = document.getElementById('btnReturnBoardroom');
  const input = document.getElementById('commandInput');

  if (view === 'boardroom') {
    currentDirectAgentId = null;
    btnBoardroom?.classList.add('active');
    btnBoardroom?.setAttribute('aria-selected', 'true');
    if (btnIndividual) {
      btnIndividual.classList.remove('active');
      btnIndividual.setAttribute('aria-selected', 'false');
      btnIndividual.title = 'Direct Chat';
      btnIndividual.setAttribute('aria-label', 'Direct Chat');
    }
    if (bannerText) {
      bannerText.innerHTML = '<strong>Boardroom Broadcast</strong> · Commands route to all connected agents';
    }
    if (btnReturn) btnReturn.style.display = 'none';
    if (input) input.placeholder = 'Type a prompt for your agents (or @AgentName to target one)...';
  } else {
    currentDirectAgentId = agentId || appConfig.accounts?.[0]?.id || null;
    const agent = appConfig.accounts?.find((a) => a.id === currentDirectAgentId);
    const agentName = agent ? agent.name : 'Agent';

    btnBoardroom?.classList.remove('active');
    btnBoardroom?.setAttribute('aria-selected', 'false');
    if (btnIndividual) {
      btnIndividual.classList.add('active');
      btnIndividual.setAttribute('aria-selected', 'true');
      btnIndividual.title = `Direct Chat · ${agentName}`;
      btnIndividual.setAttribute('aria-label', `Direct Chat · ${agentName}`);
    }
    if (bannerText) {
      bannerText.innerHTML = `<strong>Direct Chat</strong> · ${escapeHtml(agentName)} (${escapeHtml(providerLabel(agent?.provider))})`;
    }
    if (btnReturn) btnReturn.style.display = 'inline-flex';
    if (input) input.placeholder = `Message ${agentName} directly...`;
  }

  renderRoster();
  renderMessages();
  input?.focus();
}

function toggleIndividualDropdown(e) {
  e.stopPropagation();
  const dd = document.getElementById('agentDropdown');
  const btn = document.getElementById('btnViewIndividual');
  if (!dd) return;
  const willOpen = !dd.classList.contains('open');
  if (willOpen) {
    renderDropdownItems();
    dd.classList.add('open');
    btn?.setAttribute('aria-expanded', 'true');
  } else {
    dd.classList.remove('open');
    btn?.setAttribute('aria-expanded', 'false');
  }
}

function renderDropdownItems() {
  const dd = document.getElementById('agentDropdown');
  if (!dd) return;
  dd.innerHTML = '';

  if (!appConfig.accounts || appConfig.accounts.length === 0) {
    const item = document.createElement('div');
    item.style.padding = '8px 10px';
    item.style.fontSize = '12px';
    item.style.color = 'var(--text-secondary)';
    item.textContent = 'No agents added yet.';
    dd.appendChild(item);
    return;
  }

  appConfig.accounts.forEach((agent) => {
    const item = document.createElement('button');
    item.type = 'button';
    item.className = `dropdown-item ${currentView === 'direct' && currentDirectAgentId === agent.id ? 'active' : ''}`;
    item.setAttribute('role', 'menuitem');

    const nameSpan = document.createElement('span');
    nameSpan.textContent = agent.name;
    const provSpan = document.createElement('span');
    provSpan.style.fontSize = '11px';
    provSpan.style.color = 'var(--text-secondary)';
    provSpan.textContent = providerLabel(agent.provider);

    item.append(nameSpan, provSpan);
    item.addEventListener('click', (e) => {
      e.stopPropagation();
      dd.classList.remove('open');
      switchView('direct', agent.id);
    });
    dd.appendChild(item);
  });
}

function escapeHtml(str = '') {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function renderMessages() {
  const feed = document.getElementById('chatFeed');
  if (!feed) return;
  feed.innerHTML = '';

  let filtered = cachedMessages;
  if (currentView === 'direct') {
    filtered = cachedMessages.filter((msg) => {
      if (msg.sender === 'system') {
        return msg.mode === 'direct' && msg.targetAgentId === currentDirectAgentId;
      }
      return msg.targetAgentId === currentDirectAgentId || msg.sender === currentDirectAgentId;
    });
  } else {
    filtered = cachedMessages.filter((msg) => !(msg.mode === 'direct' && msg.targetAgentId && msg.targetAgentId !== 'all'));
  }

  const { openCount, readyCount, savedCount } = getInAppSummary();
  const hasInAppAgents = (appConfig.accounts || []).some((a) => a.provider === 'inapp-browser');
  const showOnboardingCard =
    currentView === 'boardroom' &&
    ((!appConfig.accounts || appConfig.accounts.length === 0) ||
      (hasInAppAgents && readyCount === 0 && openCount === 0 && savedCount === 0 && !bridgeState.connected && filtered.length <= 2));

  if (showOnboardingCard) {
    const card = document.createElement('div');
    card.className = 'onboarding-card';
    card.innerHTML = `
      <h2>Open In-App Browser &amp; Connect Your AIs</h2>
      <p>
        Sign into <strong>Muse AI</strong>, <strong>Gemini</strong>, <strong>ChatGPT</strong>, and <strong>Grok</strong> right inside this app—or create multiple accounts per AI with isolated persistent browser profiles.
      </p>
    `;
    const actions = document.createElement('div');
    actions.className = 'onboarding-actions';

    const openBrowserBtn = document.createElement('button');
    openBrowserBtn.type = 'button';
    openBrowserBtn.className = 'btn btn-primary btn-icon-md';
    openBrowserBtn.innerHTML = UI_ICONS.browserLg;
    openBrowserBtn.title = 'Open In-App Browser';
    openBrowserBtn.setAttribute('aria-label', 'Open In-App Browser');
    openBrowserBtn.addEventListener('click', () => openInAppBrowserModal());

    const addBtn = document.createElement('button');
    addBtn.type = 'button';
    addBtn.className = 'btn btn-icon-md';
    addBtn.innerHTML = UI_ICONS.userPlus;
    addBtn.title = 'Manage Agents / Add Account';
    addBtn.setAttribute('aria-label', 'Manage Agents / Add Account');
    addBtn.addEventListener('click', () => openConfigModal());

    actions.append(openBrowserBtn, addBtn);
    card.appendChild(actions);
    feed.appendChild(card);
  }

  if (filtered.length === 0 && currentView === 'direct') {
    const empty = document.createElement('div');
    empty.style.textAlign = 'center';
    empty.style.color = 'var(--text-secondary)';
    empty.style.padding = '36px 16px';
    empty.textContent = 'No messages in this 1-on-1 thread yet. Send a prompt below to start.';
    feed.appendChild(empty);
    return;
  }

  filtered.forEach((msg) => {
    const card = document.createElement('article');
    const isUser = msg.sender === 'ceo';
    const isSystem = msg.sender === 'system';
    card.className = `msg-card ${isUser ? 'user-msg' : isSystem ? 'system-msg' : ''}`;

    const header = document.createElement('div');
    header.className = 'msg-header';

    const sender = document.createElement('div');
    sender.className = 'msg-sender';

    const nameEl = document.createElement('span');
    nameEl.className = 'msg-sender-name';
    nameEl.textContent = msg.senderName || 'Agent';

    const metaEl = document.createElement('span');
    metaEl.className = 'msg-sender-meta';
    const parts = [];
    if (msg.role && msg.role !== msg.senderName) parts.push(msg.role);
    if (msg.mode === 'direct') parts.push('Direct');
    metaEl.textContent = parts.join(' · ');

    sender.append(nameEl);
    if (metaEl.textContent) sender.appendChild(metaEl);

    const timeEl = document.createElement('span');
    timeEl.className = 'msg-time mono';
    timeEl.textContent = new Date(msg.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

    header.append(sender, timeEl);

    const body = document.createElement('div');
    body.className = 'msg-body';
    body.innerHTML = window.marked ? marked.parse(msg.text || '') : escapeHtml(msg.text || '');

    if (msg.fileAttachment?.dataUrl) {
      const attachDiv = document.createElement('div');
      const isImg = /^data:image\//i.test(msg.fileAttachment.dataUrl);
      attachDiv.className = isImg ? 'msg-attachment' : 'msg-attachment file-only';
      if (isImg) {
        const img = document.createElement('img');
        img.src = msg.fileAttachment.dataUrl;
        img.alt = msg.fileAttachment.filename || 'Attached image';
        attachDiv.appendChild(img);
      } else {
        const chip = document.createElement('a');
        chip.className = 'msg-file-chip';
        chip.href = msg.fileAttachment.dataUrl;
        chip.download = msg.fileAttachment.filename || 'attachment';
        chip.innerHTML = `${UI_ICONS.file}<span>${escapeHtml(msg.fileAttachment.filename || 'Attached file')}</span>`;
        attachDiv.appendChild(chip);
      }
      body.appendChild(attachDiv);
    }

    card.append(header, body);
    feed.appendChild(card);
  });

  feed.scrollTop = feed.scrollHeight;
}

async function sendCommand() {
  if (isSending) return;
  const input = document.getElementById('commandInput');
  const text = input ? input.value.trim() : '';
  if (!text && !currentAttachment) return;

  const userText = text || 'Analyze the attached file.';
  const pendingAttachment = currentAttachment;

  if (input) input.value = '';
  clearAttachment();

  isSending = true;
  const sendBtn = document.getElementById('sendBtn');
  if (sendBtn) {
    sendBtn.disabled = true;
    sendBtn.innerHTML = UI_ICONS.sending;
    sendBtn.setAttribute('aria-label', 'Sending message');
  }

  const mode = currentView === 'direct' ? 'direct' : 'broadcast';
  const targetAgentId = currentView === 'direct' ? currentDirectAgentId : 'all';

  const tempId = `temp-${Date.now()}`;
  cachedMessages.push({
    id: tempId,
    sender: 'ceo',
    role: 'User',
    senderName: appConfig.ceoName || 'You',
    text: userText,
    fileAttachment: pendingAttachment,
    mode,
    targetAgentId,
    timestamp: new Date().toISOString()
  });
  renderMessages();
  appendThinkingCard('Dispatching command...');

  let receivedStream = false;
  const pendingAgents = new Map();

  const updatePendingBanner = () => {
    if (pendingAgents.size > 0) {
      appendThinkingCard(`Waiting for ${Array.from(pendingAgents.values()).join(', ')}...`);
    } else {
      document.getElementById('thinkingBubble')?.remove();
    }
  };

  try {
    const res = await fetch('/api/command', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        command: userText,
        mode,
        targetAgentId,
        fileAttachment: pendingAttachment,
        stream: true
      })
    });

    const contentType = res.headers.get('content-type') || '';
    if (res.ok && contentType.includes('text/event-stream') && res.body) {
      receivedStream = true;
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      const handleEventBlock = (block) => {
        const lines = block.split('\n');
        let evtName = 'message';
        const dataLines = [];
        for (const line of lines) {
          if (line.startsWith('event:')) evtName = line.slice(6).trim();
          else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
        }
        if (dataLines.length === 0) return;
        let parsed = null;
        try {
          parsed = JSON.parse(dataLines.join('\n'));
        } catch {
          return;
        }
        if (evtName === 'start') {
          if (parsed.ceoMessage) {
            const idx = cachedMessages.findIndex((m) => m.id === tempId);
            if (idx !== -1) cachedMessages[idx] = parsed.ceoMessage;
          }
          if (Array.isArray(parsed.targets)) {
            parsed.targets.forEach((t) => pendingAgents.set(t.id, t.name));
            updatePendingBanner();
          }
        } else if (evtName === 'reply' && parsed) {
          pendingAgents.delete(parsed.sender);
          cachedMessages.push(parsed);
          renderMessages();
          updatePendingBanner();
        } else if (evtName === 'done') {
          pendingAgents.clear();
          document.getElementById('thinkingBubble')?.remove();
        }
      };

      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let sep;
        while ((sep = buffer.indexOf('\n\n')) !== -1) {
          const block = buffer.slice(0, sep);
          buffer = buffer.slice(sep + 2);
          if (block.trim()) handleEventBlock(block);
        }
      }
    } else {
      const data = await res.json();
      if (!res.ok || data.error) {
        showToast(data.error || 'Failed to send command', true);
      } else {
        if (data.ceoMessage) {
          const idx = cachedMessages.findIndex((m) => m.id === tempId);
          if (idx !== -1) cachedMessages[idx] = data.ceoMessage;
        }
        if (Array.isArray(data.replies)) {
          cachedMessages.push(...data.replies);
        }
        renderMessages();
      }
    }
  } catch (err) {
    showToast(`Send failed: ${err.message}`, true);
  } finally {
    isSending = false;
    document.getElementById('thinkingBubble')?.remove();
    if (sendBtn) {
      sendBtn.disabled = false;
      sendBtn.innerHTML = UI_ICONS.send;
      sendBtn.setAttribute('aria-label', 'Send message');
    }
    if (!receivedStream) {
      await loadMessages();
    }
    input?.focus();
  }
}

function appendThinkingCard(messageText = 'Dispatching command to connected agents...') {
  const feed = document.getElementById('chatFeed');
  if (!feed) return;
  let card = document.getElementById('thinkingBubble');
  if (!card) {
    card = document.createElement('div');
    card.id = 'thinkingBubble';
    card.className = 'msg-card system-msg';
    feed.appendChild(card);
  }
  card.textContent = messageText;
  feed.scrollTop = feed.scrollHeight;
}

// File Attachments
const MAX_ATTACHMENT_BYTES = 12 * 1024 * 1024;

function attachFileObject(file) {
  if (!file) return;
  if (file.size > MAX_ATTACHMENT_BYTES) {
    showToast('Attachment exceeds the 12 MB limit.', true);
    return;
  }
  const reader = new FileReader();
  reader.onload = (event) => {
    const dataUrl = event.target?.result;
    if (typeof dataUrl !== 'string') {
      showToast('Could not read the selected file.', true);
      return;
    }
    const filename = file.name || 'attachment';
    const mimeType = file.type || '';
    currentAttachment = { filename, mimeType, dataUrl, size: file.size };
    showAttachmentPreview(filename, dataUrl, mimeType);
  };
  reader.onerror = () => {
    showToast('Failed to read the selected file.', true);
  };
  reader.readAsDataURL(file);
}

function handleFileSelect(e) {
  const file = e.target.files?.[0];
  if (file) attachFileObject(file);
  e.target.value = '';
}

function setupComposerDragAndDrop() {
  const composer = document.querySelector('.composer');
  if (!composer) return;

  ['dragenter', 'dragover'].forEach((evt) => {
    composer.addEventListener(evt, (e) => {
      e.preventDefault();
      e.stopPropagation();
      composer.classList.add('drag-over');
    });
  });

  ['dragleave', 'drop'].forEach((evt) => {
    composer.addEventListener(evt, (e) => {
      e.preventDefault();
      e.stopPropagation();
      composer.classList.remove('drag-over');
    });
  });

  composer.addEventListener('drop', (e) => {
    const file = e.dataTransfer?.files?.[0];
    if (file) attachFileObject(file);
  });
}

function setupClipboardPaste() {
  window.addEventListener('paste', (e) => {
    const items = (e.clipboardData || e.originalEvent?.clipboardData)?.items || [];
    for (const item of items) {
      if (item.type.includes('image')) {
        const blob = item.getAsFile();
        if (!blob) continue;
        attachFileObject(new File([blob], 'pasted-image.png', { type: blob.type || 'image/png' }));
        break;
      }
    }
  });
}

function showAttachmentPreview(name, dataUrl, mimeType = '') {
  const bar = document.getElementById('attachmentPreview');
  const img = document.getElementById('previewImg');
  const fileBadge = document.getElementById('previewFileBadge');
  const nameEl = document.getElementById('previewName');
  const transcribeBtn = document.getElementById('btnTranscribeAttachment');
  if (!bar || !nameEl) return;

  const isImage = /^data:image\//i.test(dataUrl) || String(mimeType).startsWith('image/');
  const isAudio =
    /^data:audio\//i.test(dataUrl) ||
    String(mimeType).startsWith('audio/') ||
    /\.(mp3|wav|m4a|ogg|webm|flac|aac)$/i.test(name || '');

  if (img) {
    if (isImage) {
      img.src = dataUrl;
      img.style.display = 'block';
    } else {
      img.removeAttribute('src');
      img.style.display = 'none';
    }
  }
  if (fileBadge) {
    fileBadge.style.display = isImage ? 'none' : 'inline-flex';
  }
  if (transcribeBtn) {
    transcribeBtn.style.display = isAudio ? 'inline-flex' : 'none';
  }
  nameEl.textContent = name || 'Attached file';
  bar.classList.add('active');
}

function clearAttachment() {
  currentAttachment = null;
  const bar = document.getElementById('attachmentPreview');
  const img = document.getElementById('previewImg');
  const fileBadge = document.getElementById('previewFileBadge');
  const transcribeBtn = document.getElementById('btnTranscribeAttachment');
  if (img) {
    img.removeAttribute('src');
    img.style.display = 'none';
  }
  if (fileBadge) fileBadge.style.display = 'none';
  if (transcribeBtn) transcribeBtn.style.display = 'none';
  bar?.classList.remove('active');
}

async function transcribeCurrentAttachment() {
  if (!currentAttachment?.dataUrl || isTranscribing) return;
  const btn = document.getElementById('btnTranscribeAttachment');
  isTranscribing = true;
  if (btn) {
    btn.disabled = true;
    btn.innerHTML = UI_ICONS.sending;
  }
  try {
    const res = await fetch('/api/voice/transcribe', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        dataUrl: currentAttachment.dataUrl,
        mimeType: currentAttachment.mimeType || ''
      })
    });
    const data = await res.json();
    if (!res.ok || !data.success) {
      showToast(data.error || 'Could not transcribe audio attachment.', true);
      return;
    }
    insertTranscriptIntoInput(data.transcript);
    clearAttachment();
    showToast('Prompt generated from audio.');
  } catch (err) {
    showToast(`Transcription error: ${err.message}`, true);
  } finally {
    isTranscribing = false;
    if (btn) {
      btn.disabled = false;
      btn.innerHTML = UI_ICONS.mic;
    }
  }
}

// Voice-to-Prompt (AI Studio style: MediaRecorder -> Gemini Audio Transcription, with Web Speech fallback)
function insertTranscriptIntoInput(transcript) {
  const clean = String(transcript || '').trim();
  if (!clean) return;
  const input = document.getElementById('commandInput');
  if (input) {
    input.value = (input.value ? `${input.value.trim()} ` : '') + clean;
    input.focus();
  }
}

function updateVoiceRecordingUi(recording, transcribing = false) {
  const micBtn = document.getElementById('micBtn');
  const bar = document.getElementById('voiceRecordingBar');
  const statusText = document.getElementById('voiceStatusText');
  const timerEl = document.getElementById('voiceTimer');
  const stopBtn = document.getElementById('btnStopVoice');
  const cancelBtn = document.getElementById('btnCancelVoice');

  if (micBtn) {
    micBtn.classList.toggle('recording', recording);
    micBtn.disabled = transcribing;
    if (transcribing) {
      micBtn.innerHTML = UI_ICONS.sending;
      micBtn.setAttribute('aria-label', 'Generating prompt from voice');
      micBtn.title = 'Generating prompt from voice...';
    } else if (recording) {
      micBtn.innerHTML = UI_ICONS.stop;
      micBtn.setAttribute('aria-label', 'Stop voice recording');
      micBtn.title = 'Stop recording & generate prompt';
    } else {
      micBtn.innerHTML = UI_ICONS.mic;
      micBtn.setAttribute('aria-label', 'Voice prompt');
      micBtn.title = 'Voice prompt';
    }
  }

  if (bar) {
    bar.classList.toggle('active', recording || transcribing);
  }
  if (statusText) {
    statusText.textContent = transcribing
      ? 'Generating prompt from voice...'
      : 'Listening... speak your prompt';
  }
  if (stopBtn) stopBtn.disabled = transcribing;
  if (cancelBtn) cancelBtn.disabled = transcribing;
  if (!recording && !transcribing && timerEl) {
    timerEl.textContent = '0:00';
  }
}

function startVoiceTimer() {
  stopVoiceTimer();
  voiceStartAt = Date.now();
  const timerEl = document.getElementById('voiceTimer');
  if (timerEl) timerEl.textContent = '0:00';
  voiceTimerInterval = setInterval(() => {
    const secs = Math.floor((Date.now() - voiceStartAt) / 1000);
    const mins = Math.floor(secs / 60);
    const rem = secs % 60;
    if (timerEl) timerEl.textContent = `${mins}:${String(rem).padStart(2, '0')}`;
  }, 300);
}

function stopVoiceTimer() {
  if (voiceTimerInterval) {
    clearInterval(voiceTimerInterval);
    voiceTimerInterval = null;
  }
}

function cleanupMediaStream() {
  if (mediaStream) {
    mediaStream.getTracks().forEach((track) => track.stop());
    mediaStream = null;
  }
  mediaRecorder = null;
  audioChunks = [];
}

function pickSupportedAudioMime() {
  if (typeof MediaRecorder === 'undefined' || typeof MediaRecorder.isTypeSupported !== 'function') {
    return '';
  }
  const candidates = [
    'audio/webm;codecs=opus',
    'audio/webm',
    'audio/mp4',
    'audio/ogg;codecs=opus',
    'audio/ogg'
  ];
  return candidates.find((type) => MediaRecorder.isTypeSupported(type)) || '';
}

function blobToBase64(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onloadend = () => {
      const result = String(reader.result || '');
      const commaIdx = result.indexOf(',');
      resolve(commaIdx !== -1 ? result.slice(commaIdx + 1) : '');
    };
    reader.onerror = () => reject(new Error('Failed to read recorded audio'));
    reader.readAsDataURL(blob);
  });
}

async function transcribeAudioBlob(blob, mimeType) {
  if (!blob || blob.size < 256) {
    showToast('Recording was too short. Hold and speak a bit longer.', true);
    updateVoiceRecordingUi(false, false);
    return;
  }

  isTranscribing = true;
  updateVoiceRecordingUi(false, true);

  try {
    const audioBase64 = await blobToBase64(blob);
    const res = await fetch('/api/voice/transcribe', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        audioBase64,
        mimeType: mimeType || blob.type || 'audio/webm'
      })
    });
    const data = await res.json();
    if (!res.ok || !data.success) {
      const hasSpeechApi = Boolean(window.SpeechRecognition || window.webkitSpeechRecognition);
      if (data.fallbackSuggested && hasSpeechApi) {
        preferBrowserSpeech = true;
        showToast('Server voice API key unavailable — switched to live browser speech recognition. Click mic to dictate.', true);
      } else {
        showToast(data.error || 'Voice transcription failed.', true);
      }
      return;
    }
    insertTranscriptIntoInput(data.transcript);
    showToast('Prompt generated from voice.');
  } catch (err) {
    showToast(`Voice transcription failed: ${err.message}`, true);
  } finally {
    isTranscribing = false;
    updateVoiceRecordingUi(false, false);
  }
}

async function startMediaRecorderVoice() {
  if (isStartingMic || isRecording) return;
  isStartingMic = true;
  cancelVoiceRequested = false;
  audioChunks = [];

  try {
    mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true });
  } catch (err) {
    const msg =
      err?.name === 'NotAllowedError' || err?.name === 'PermissionDeniedError'
        ? 'Microphone access was blocked. Allow microphone permission in your browser settings.'
        : 'No working microphone found or microphone access failed.';
    showToast(msg, true);
    cleanupMediaStream();
    updateVoiceRecordingUi(false, false);
    isStartingMic = false;
    return;
  }

  const chosenMime = pickSupportedAudioMime();
  try {
    mediaRecorder = chosenMime
      ? new MediaRecorder(mediaStream, { mimeType: chosenMime })
      : new MediaRecorder(mediaStream);
  } catch (err) {
    showToast(`Could not start audio recorder: ${err.message}`, true);
    cleanupMediaStream();
    updateVoiceRecordingUi(false, false);
    isStartingMic = false;
    return;
  }

  const effectiveMime = mediaRecorder.mimeType || chosenMime || 'audio/webm';

  mediaRecorder.ondataavailable = (evt) => {
    if (evt.data && evt.data.size > 0) {
      audioChunks.push(evt.data);
    }
  };

  mediaRecorder.onstop = async () => {
    stopVoiceTimer();
    isRecording = false;
    const shouldCancel = cancelVoiceRequested;
    const chunks = audioChunks.slice();
    cleanupMediaStream();

    if (shouldCancel) {
      updateVoiceRecordingUi(false, false);
      return;
    }
    const blob = new Blob(chunks, { type: effectiveMime });
    await transcribeAudioBlob(blob, effectiveMime);
  };

  mediaRecorder.onerror = () => {
    stopVoiceTimer();
    isRecording = false;
    isStartingMic = false;
    cleanupMediaStream();
    updateVoiceRecordingUi(false, false);
    showToast('Microphone recording error occurred.', true);
  };

  isStartingMic = false;
  isRecording = true;
  updateVoiceRecordingUi(true, false);
  startVoiceTimer();
  mediaRecorder.start(250);
}

function stopVoiceRecording() {
  if (mediaRecorder && mediaRecorder.state !== 'inactive') {
    cancelVoiceRequested = false;
    mediaRecorder.stop();
    return;
  }
  if (speechRecognition && isRecording) {
    speechRecognition.stop();
  }
}

function cancelVoiceRecording() {
  if (mediaRecorder && mediaRecorder.state !== 'inactive') {
    cancelVoiceRequested = true;
    mediaRecorder.stop();
    return;
  }
  if (speechRecognition && isRecording) {
    cancelVoiceRequested = true;
    speechRecognition.abort();
  }
  stopVoiceTimer();
  isRecording = false;
  cleanupMediaStream();
  updateVoiceRecordingUi(false, false);
}

function startBrowserSpeechRecognition() {
  const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SpeechRecognition) {
    showToast('Voice recording is not supported in this browser.', true);
    return;
  }
  cancelVoiceRequested = false;
  speechRecognition = new SpeechRecognition();
  speechRecognition.continuous = false;
  speechRecognition.interimResults = false;
  speechRecognition.lang = 'en-US';
  speechRecognition.onstart = () => {
    isRecording = true;
    updateVoiceRecordingUi(true, false);
    startVoiceTimer();
  };
  speechRecognition.onresult = (event) => {
    const transcript = event.results?.[0]?.[0]?.transcript || '';
    if (transcript) {
      insertTranscriptIntoInput(transcript);
      showToast('Prompt generated from voice.');
    }
  };
  speechRecognition.onerror = (event) => {
    stopVoiceTimer();
    isRecording = false;
    updateVoiceRecordingUi(false, false);
    if (event.error !== 'aborted') {
      showToast(`Voice input error: ${event.error || 'unavailable'}`, true);
    }
  };
  speechRecognition.onend = () => {
    stopVoiceTimer();
    isRecording = false;
    updateVoiceRecordingUi(false, false);
  };
  speechRecognition.start();
}

function toggleVoiceDictation() {
  if (isTranscribing || isStartingMic) return;
  if (isRecording) {
    stopVoiceRecording();
    return;
  }
  if (!preferBrowserSpeech && navigator.mediaDevices?.getUserMedia && typeof MediaRecorder !== 'undefined') {
    startMediaRecorderVoice();
    return;
  }
  startBrowserSpeechRecognition();
}

// Bridge Setup Modal
function openBridgeModal() {
  loadBridgeStatus();
  document.getElementById('bridgeModal')?.classList.add('open');
}

function closeBridgeModal() {
  document.getElementById('bridgeModal')?.classList.remove('open');
}

async function rotateBridgeToken() {
  try {
    const res = await fetch('/api/bridge/token/rotate', { method: 'POST' });
    const data = await res.json();
    if (data.success) {
      await loadBridgeStatus();
      showToast('Pairing token rotated. Re-download or update token in the extension popup.');
    }
  } catch (err) {
    showToast(`Failed to rotate token: ${err.message}`, true);
  }
}

// Manage Agents Modal
function openConfigModal(agentId = null) {
  if (appConfig.accounts.length > 0) {
    const exists = agentId && appConfig.accounts.some((a) => a.id === agentId);
    activeTabId = exists ? agentId : appConfig.accounts[0].id;
  } else {
    activeTabId = null;
  }
  renderModalTabs();
  renderModalForm();
  document.getElementById('configModal')?.classList.add('open');
}

function closeConfigModal() {
  document.getElementById('configModal')?.classList.remove('open');
}

function renderModalTabs() {
  const tabs = document.getElementById('modalTabs');
  if (!tabs) return;
  tabs.innerHTML = '';

  appConfig.accounts.forEach((a) => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = `tab-btn ${a.id === activeTabId ? 'active' : ''}`;
    btn.textContent = a.name;
    btn.addEventListener('click', () => {
      activeTabId = a.id;
      renderModalTabs();
      renderModalForm();
    });
    tabs.appendChild(btn);
  });

  const addBtn = document.createElement('button');
  addBtn.type = 'button';
  addBtn.className = 'btn btn-sm btn-icon-sm btn-primary';
  addBtn.style.marginLeft = 'auto';
  addBtn.innerHTML = UI_ICONS.plus;
  addBtn.title = 'Add Agent';
  addBtn.setAttribute('aria-label', 'Add Agent');
  addBtn.addEventListener('click', addNewAgent);
  tabs.appendChild(addBtn);
}

function getUniqueAgentName(baseName) {
  const existing = new Set(appConfig.accounts.map((a) => a.name));
  if (!existing.has(baseName)) return baseName;
  let idx = 2;
  while (existing.has(`${baseName} ${idx}`)) idx++;
  return `${baseName} ${idx}`;
}

function addNewAgent(preset = null) {
  const newId = `agent-${Date.now()}`;
  const defaultName = preset?.name ? getUniqueAgentName(preset.name) : `Agent-${appConfig.accounts.length + 1}`;
  const newAgent = {
    id: newId,
    name: defaultName,
    role: preset?.role || 'In-App Browser AI',
    avatar: preset?.avatar || 'AI',
    color: preset?.color || '#3B82F6',
    provider: 'inapp-browser',
    clientId: null,
    profileName: '',
    tabId: null,
    customUrl: preset?.url || 'https://muse.ai',
    apiKey: '',
    model: 'gemini-2.0-flash',
    endpoint: '',
    systemPrompt: ''
  };
  appConfig.accounts.push(newAgent);
  activeTabId = newId;
  renderModalTabs();
  renderModalForm();
}

function cloneAgentForMultiAccount(agent) {
  const newId = `agent-${Date.now()}`;
  const clone = {
    ...agent,
    id: newId,
    name: getUniqueAgentName(agent.name.replace(/\s+\d+$/, '')),
    clientId: null,
    profileName: '',
    tabId: null
  };
  appConfig.accounts.push(clone);
  activeTabId = newId;
  renderModalTabs();
  renderModalForm();
  showToast(
    clone.provider === 'inapp-browser'
      ? `Created "${clone.name}" with its own isolated browser profile. Click Open In-App Browser or Save.`
      : `Created "${clone.name}". Select a Chrome Profile and open its login tab.`
  );
}

const MODAL_AI_PRESETS = [
  { label: 'Muse AI', url: 'https://muse.ai', role: 'Muse AI', avatar: 'M', color: '#3B82F6' },
  { label: 'Gemini', url: 'https://gemini.google.com/app', role: 'Google Gemini', avatar: 'G', color: '#8B5CF6' },
  { label: 'ChatGPT', url: 'https://chatgpt.com', role: 'OpenAI ChatGPT', avatar: 'C', color: '#10B981' },
  { label: 'Grok', url: 'https://grok.com', role: 'xAI Grok', avatar: 'X', color: '#E2E8F0' },
  { label: 'Claude', url: 'https://claude.ai', role: 'Anthropic Claude', avatar: 'C', color: '#F59E0B' }
];

function buildProfileSelectOptions(selectedClientId, savedProfileName) {
  const clients = Array.isArray(bridgeState.clients) ? bridgeState.clients : [];
  let html = `<option value="">Any connected Chrome Profile (auto)</option>`;
  let foundSelected = !selectedClientId;
  clients.forEach((c) => {
    const isSel = selectedClientId && c.clientId === selectedClientId;
    if (isSel) foundSelected = true;
    html += `<option value="${escapeHtml(c.clientId)}" data-profile-name="${escapeHtml(c.profileName)}" ${isSel ? 'selected' : ''}>${escapeHtml(c.profileName)} (${c.tabCount} tabs · Connected)</option>`;
  });
  if (selectedClientId && !foundSelected) {
    html += `<option value="${escapeHtml(selectedClientId)}" data-profile-name="${escapeHtml(savedProfileName || selectedClientId)}" selected>${escapeHtml(savedProfileName || selectedClientId)} (Offline — open Chrome to reconnect)</option>`;
  }
  return html;
}

function buildTabSelectOptions(selectedClientId, selectedTabId) {
  const tabs = Array.isArray(bridgeState.tabs) ? bridgeState.tabs : [];
  const filteredTabs = selectedClientId ? tabs.filter((t) => t.clientId === selectedClientId) : tabs;
  let html = `<option value="">Auto-match by URL (${bridgeState.connected ? `${filteredTabs.length} tabs` : 'Bridge disconnected'})</option>`;
  let foundSelected = !selectedTabId;
  filteredTabs.forEach((t) => {
    const prefix = t.profileName ? `[${t.profileName}] ` : '';
    const isSel = Number(selectedTabId) === Number(t.tabId) && (!selectedClientId || t.clientId === selectedClientId);
    if (isSel) foundSelected = true;
    html += `<option value="${t.tabId}" data-client-id="${escapeHtml(t.clientId || '')}" data-profile-name="${escapeHtml(t.profileName || '')}" ${isSel ? 'selected' : ''}>${escapeHtml(prefix + t.label)} — ${escapeHtml(t.title.slice(0, 40))}</option>`;
  });
  if (selectedTabId && !foundSelected) {
    html += `<option value="${escapeHtml(String(selectedTabId))}" data-client-id="${escapeHtml(selectedClientId || '')}" selected>Tab #${escapeHtml(String(selectedTabId))} (Bound)</option>`;
  }
  return html;
}

function refreshModalBridgeControls() {
  const clientSelect = document.getElementById('cfgClientId');
  const tabSelect = document.getElementById('cfgTabId');
  if (!clientSelect || !tabSelect) return;
  const curClient = clientSelect.value;
  const curTab = tabSelect.value;
  const agent = appConfig.accounts.find((a) => a.id === activeTabId);
  clientSelect.innerHTML = buildProfileSelectOptions(curClient, agent?.profileName);
  tabSelect.innerHTML = buildTabSelectOptions(clientSelect.value, curTab);
  updateOpenLoginButtonLabel();
}

function updateOpenLoginButtonLabel() {
  const urlVal = normalizeHttpUrl(document.getElementById('cfgSessionUrl')?.value.trim() || '');
  const host = getHostname(urlVal) || 'AI Site';
  const openBtn = document.getElementById('btnOpenLoginTab');
  const directLink = document.getElementById('directOpenSiteLink');
  if (openBtn) {
    openBtn.title = `Open ${host} in Chrome to Log In`;
    openBtn.setAttribute('aria-label', `Open ${host} in Chrome to Log In`);
  }
  if (directLink) {
    directLink.href = urlVal || 'https://muse.ai';
  }
}

function renderModalForm() {
  const form = document.getElementById('modalForm');
  const deleteBtn = document.getElementById('btnModalDeleteAgent');
  const saveBtn = document.getElementById('btnSaveAgentConfig');
  if (!form) return;

  const agent = appConfig.accounts.find((a) => a.id === activeTabId);
  if (!agent) {
    form.innerHTML = '<p style="color: var(--text-secondary); padding: 12px 0;">No agent selected. Click <strong>+ Add Agent</strong> above to create one.</p>';
    if (deleteBtn) deleteBtn.style.display = 'none';
    if (saveBtn) saveBtn.style.display = 'none';
    return;
  }

  if (deleteBtn) deleteBtn.style.display = 'inline-flex';
  if (saveBtn) saveBtn.style.display = 'inline-flex';

  const presetButtons = MODAL_AI_PRESETS.map(
    (p) => `<button type="button" class="btn btn-sm preset-chip" data-url="${escapeHtml(p.url)}" data-label="${escapeHtml(p.label)}" data-role="${escapeHtml(p.role)}">${escapeHtml(p.label)}</button>`
  ).join('');

  const targetUrl = normalizeHttpUrl(agent.customUrl || 'https://muse.ai');
  const host = getHostname(targetUrl) || 'AI Site';
  const inAppSt = inAppStatuses[agent.id] || {};
  const inAppHint = inAppSt.open && inAppSt.ready
    ? `Session connected and ready on ${getHostname(inAppSt.url || targetUrl)}.`
    : inAppSt.hasSavedProfile
      ? 'Saved browser profile found. Click below to open or verify sign-in inside the app.'
      : 'Opens this AI site directly inside the app with an isolated profile and attached connector.';

  form.innerHTML = `
    <div class="form-grid">
      <div class="form-field">
        <label for="cfgName">Agent Name</label>
        <input type="text" id="cfgName" value="${escapeHtml(agent.name)}" />
      </div>
      <div class="form-field">
        <label for="cfgRole">Account / Role Label</label>
        <input type="text" id="cfgRole" value="${escapeHtml(agent.role || '')}" placeholder="e.g. Muse Account 1, Work Gemini" />
      </div>
    </div>

    <div class="form-field">
      <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px;">
        <label for="cfgProvider">Connection Type</label>
        <button type="button" class="btn btn-sm btn-icon-sm" id="btnCloneAgent" aria-label="Duplicate for 2nd Account" title="Duplicate for 2nd Account">${UI_ICONS.copy}</button>
      </div>
      <select id="cfgProvider">
        <option value="inapp-browser" ${agent.provider === 'inapp-browser' ? 'selected' : ''}>In-App Browser + Attached Connector (Recommended)</option>
        <option value="browser-session" ${agent.provider === 'browser-session' || agent.provider === 'browser-automation' ? 'selected' : ''}>External Desktop Chrome Extension</option>
        <option value="gemini" ${agent.provider === 'gemini' ? 'selected' : ''}>Google Gemini API</option>
        <option value="openai" ${agent.provider === 'openai' || agent.provider === 'custom-api' ? 'selected' : ''}>OpenAI / Groq / Custom API</option>
        <option value="docker-web" ${agent.provider === 'docker-web' ? 'selected' : ''}>Docker / Webhook URL</option>
        <option value="simulation" ${agent.provider === 'simulation' ? 'selected' : ''}>Offline Demo</option>
      </select>
    </div>

    <div id="inAppBrowserFields" class="provider-box" style="display: none;">
      <div class="form-field">
        <label>Quick AI Target</label>
        <div class="preset-row">${presetButtons}</div>
      </div>

      <div class="form-field">
        <label for="cfgInAppUrl">Target AI Site URL</label>
        <input type="text" id="cfgInAppUrl" value="${escapeHtml(agent.customUrl || '')}" placeholder="https://muse.ai or https://gemini.google.com/app" />
      </div>

      <div class="login-connect-box">
        <div style="font-size: 12px; font-weight: 700; color: var(--text-primary);">
          In-App Browser &amp; Persistent Session
        </div>
        <div class="login-connect-actions">
          <button type="button" class="btn btn-sm btn-icon-sm btn-primary" id="btnOpenInAppFromModal" aria-label="Open ${escapeHtml(host)} in In-App Browser" title="Open ${escapeHtml(host)} in In-App Browser">${UI_ICONS.browser}</button>
        </div>
        <div class="login-connect-status ${inAppSt.open && inAppSt.ready ? 'ok' : ''}">${escapeHtml(inAppHint)}</div>
      </div>
    </div>

    <div id="browserSessionFields" class="provider-box" style="display: none;">
      <div class="form-field">
        <label>Quick AI Target</label>
        <div class="preset-row">${presetButtons}</div>
      </div>

      <div class="form-grid">
        <div class="form-field">
          <label for="cfgClientId">Chrome Profile (For Multi-Account)</label>
          <select id="cfgClientId">
            ${buildProfileSelectOptions(agent.clientId, agent.profileName)}
          </select>
        </div>
        <div class="form-field">
          <label for="cfgTabId">Bound Open Tab</label>
          <select id="cfgTabId">
            ${buildTabSelectOptions(agent.clientId, agent.tabId)}
          </select>
        </div>
      </div>

      <div class="form-field">
        <label for="cfgSessionUrl">Target AI Site URL</label>
        <input type="text" id="cfgSessionUrl" value="${escapeHtml(agent.customUrl || '')}" placeholder="https://muse.ai or https://gemini.google.com/app" />
      </div>

      <div class="login-connect-box">
        <div style="font-size: 12px; font-weight: 700; color: var(--text-primary);">
          Connect via External Chrome Extension
        </div>
        <div class="login-connect-actions">
          <button type="button" class="btn btn-sm btn-icon-sm btn-primary" id="btnOpenLoginTab" aria-label="Open ${escapeHtml(host)} in Chrome to Log In" title="Open ${escapeHtml(host)} in Chrome to Log In">${UI_ICONS.externalLink}</button>
          <button type="button" class="btn btn-sm btn-icon-sm" id="btnCheckLoginTab" aria-label="Verify Login Ready" title="Verify Login Ready">${UI_ICONS.shieldCheck}</button>
          <a href="${escapeHtml(targetUrl)}" target="_blank" rel="noopener noreferrer" class="btn btn-sm" id="directOpenSiteLink">Open Link</a>
        </div>
        <div id="loginConnectStatus" class="login-connect-status">
          ${bridgeState.connected
            ? 'Click the launch icon above to open this AI site in your selected Chrome Profile, enter credentials or OTP, and bind automatically.'
            : `Desktop Chrome Bridge is offline. <button type="button" class="btn btn-sm btn-icon-sm" id="btnSetupBridgeFromModal" style="margin-left: 4px;" aria-label="Setup Chrome Bridge" title="Setup Chrome Bridge">${UI_ICONS.plug}</button>`}
        </div>
      </div>
    </div>

    <div id="apiFields" class="provider-box" style="display: none;">
      <div class="form-field">
        <label for="cfgApiKey">API Key</label>
        <input type="password" id="cfgApiKey" value="${escapeHtml(agent.apiKey || '')}" placeholder="Paste API key" />
      </div>
      <div class="form-grid">
        <div class="form-field">
          <label for="cfgModel">Model</label>
          <input type="text" id="cfgModel" value="${escapeHtml(agent.model || '')}" placeholder="gemini-4-hydrogen or gpt-4o-mini" />
        </div>
        <div class="form-field">
          <label for="cfgEndpoint">Endpoint (Optional)</label>
          <input type="text" id="cfgEndpoint" value="${escapeHtml(agent.endpoint || '')}" placeholder="https://api.openai.com/v1/chat/completions" />
        </div>
      </div>
    </div>

    <div id="dockerFields" class="provider-box" style="display: none;">
      <div class="form-field">
        <label for="cfgDockerUrl">Public Agent URL</label>
        <input type="text" id="cfgDockerUrl" value="${escapeHtml(agent.customUrl || '')}" placeholder="https://your-agent.example.com" />
      </div>
      <div style="display: flex; align-items: center; gap: 10px;">
        <button type="button" class="btn btn-sm btn-icon-sm" id="btnTestDockerUrl" aria-label="Test Connection" title="Test Connection">${UI_ICONS.zap}</button>
        <span id="dockerTestStatus" style="font-size: 12px; color: var(--text-secondary);"></span>
      </div>
    </div>

    <div class="form-field">
      <label for="cfgPrompt">System Instructions (For API Agents)</label>
      <textarea id="cfgPrompt" rows="2">${escapeHtml(agent.systemPrompt || '')}</textarea>
    </div>
  `;

  document.getElementById('cfgProvider')?.addEventListener('change', syncProviderFieldsVisibility);
  document.getElementById('btnTestDockerUrl')?.addEventListener('click', testDockerConnection);
  document.getElementById('btnCloneAgent')?.addEventListener('click', () => cloneAgentForMultiAccount(agent));
  document.getElementById('cfgSessionUrl')?.addEventListener('input', updateOpenLoginButtonLabel);
  document.getElementById('cfgInAppUrl')?.addEventListener('input', () => {
    const val = normalizeHttpUrl(document.getElementById('cfgInAppUrl')?.value.trim() || '');
    const btn = document.getElementById('btnOpenInAppFromModal');
    if (btn) {
      btn.title = `Open ${getHostname(val) || 'AI Site'} in In-App Browser`;
      btn.setAttribute('aria-label', btn.title);
    }
  });
  document.getElementById('btnOpenInAppFromModal')?.addEventListener('click', async () => {
    agent.name = document.getElementById('cfgName')?.value.trim() || agent.name;
    agent.role = document.getElementById('cfgRole')?.value.trim() || agent.role;
    agent.provider = 'inapp-browser';
    agent.customUrl = normalizeHttpUrl(document.getElementById('cfgInAppUrl')?.value.trim() || agent.customUrl || 'https://muse.ai');
    await persistConfig();
    closeConfigModal();
    openInAppBrowserModal(agent.id);
  });
  document.getElementById('cfgClientId')?.addEventListener('change', () => {
    const clientSel = document.getElementById('cfgClientId');
    const tabSel = document.getElementById('cfgTabId');
    if (clientSel && tabSel) {
      tabSel.innerHTML = buildTabSelectOptions(clientSel.value, null);
    }
  });
  document.getElementById('cfgTabId')?.addEventListener('change', (e) => {
    const opt = e.target.selectedOptions?.[0];
    const tabClient = opt?.getAttribute('data-client-id');
    const clientSel = document.getElementById('cfgClientId');
    if (tabClient && clientSel && !clientSel.value) {
      clientSel.value = tabClient;
    }
  });
  form.querySelectorAll('.preset-chip').forEach((btn) => {
    btn.addEventListener('click', () => {
      const target = btn.getAttribute('data-url') || '';
      const urlInput = document.getElementById('cfgSessionUrl');
      const inAppUrlInput = document.getElementById('cfgInAppUrl');
      const nameInput = document.getElementById('cfgName');
      const roleInput = document.getElementById('cfgRole');
      if (urlInput) urlInput.value = target;
      if (inAppUrlInput) inAppUrlInput.value = target;
      if (nameInput && nameInput.value.startsWith('Agent-')) {
        nameInput.value = getUniqueAgentName(btn.getAttribute('data-label') || 'Agent');
      }
      if (roleInput && (!roleInput.value || roleInput.value === 'Assistant' || roleInput.value === 'In-App Browser AI')) {
        roleInput.value = btn.getAttribute('data-role') || '';
      }
      updateOpenLoginButtonLabel();
      const openInAppBtn = document.getElementById('btnOpenInAppFromModal');
      if (openInAppBtn) {
        openInAppBtn.title = `Open ${getHostname(target) || 'AI Site'} in In-App Browser`;
        openInAppBtn.setAttribute('aria-label', openInAppBtn.title);
      }
    });
  });
  document.getElementById('btnOpenLoginTab')?.addEventListener('click', () => openAgentLoginTab(agent));
  document.getElementById('btnCheckLoginTab')?.addEventListener('click', () => checkAgentLoginTab(agent));
  document.getElementById('btnSetupBridgeFromModal')?.addEventListener('click', () => {
    closeConfigModal();
    openBridgeModal();
  });

  syncProviderFieldsVisibility();
}

async function openAgentLoginTab(agent) {
  const statusEl = document.getElementById('loginConnectStatus');
  const targetUrl = normalizeHttpUrl(document.getElementById('cfgSessionUrl')?.value.trim() || agent.customUrl || 'https://muse.ai');
  const clientId = document.getElementById('cfgClientId')?.value || agent.clientId || '';

  if (statusEl) {
    statusEl.className = 'login-connect-status';
    statusEl.textContent = `Opening ${getHostname(targetUrl) || 'site'} in your Chrome Profile...`;
  }

  try {
    // Persist unsaved/cloned agents first so server-side binding updates the same agent cleanly
    agent.name = document.getElementById('cfgName')?.value.trim() || agent.name;
    agent.role = document.getElementById('cfgRole')?.value.trim() || agent.role;
    agent.customUrl = targetUrl;
    agent.clientId = clientId || null;
    await persistConfig();

    const res = await fetch('/api/bridge/open-login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ clientId, targetUrl, agentId: agent.id })
    });
    const data = await res.json();
    if (!data.success) {
      if (statusEl) {
        statusEl.className = 'login-connect-status warn';
        statusEl.innerHTML = data.bridgeOffline
          ? 'Desktop Chrome Bridge is not connected yet. Click <strong>Desktop Bridge</strong> to load the Chrome extension, or use <strong>Open Link</strong> to open in this browser.'
          : escapeHtml(data.error || 'Could not open Chrome tab.');
      }
      return;
    }

    await loadConfig();
    const updatedAgent = appConfig.accounts.find((a) => a.id === agent.id) || agent;
    updatedAgent.clientId = data.clientId;
    updatedAgent.profileName = data.profileName || updatedAgent.profileName;
    updatedAgent.tabId = data.tabId;
    updatedAgent.customUrl = targetUrl;

    const clientSel = document.getElementById('cfgClientId');
    const tabSel = document.getElementById('cfgTabId');
    if (clientSel) {
      clientSel.innerHTML = buildProfileSelectOptions(data.clientId, data.profileName);
      if (data.clientId) clientSel.value = data.clientId;
    }
    if (tabSel) {
      tabSel.innerHTML = buildTabSelectOptions(clientSel?.value || data.clientId, data.tabId);
      if (data.tabId) tabSel.value = String(data.tabId);
    }

    const newStatusEl = document.getElementById('loginConnectStatus');
    if (newStatusEl) {
      if (data.ready) {
        newStatusEl.className = 'login-connect-status ok';
        newStatusEl.textContent = `Connected to ${data.profileName || 'Chrome'} (Tab #${data.tabId}). Logged-in chat detected and bound permanently!`;
      } else {
        newStatusEl.className = 'login-connect-status warn';
        newStatusEl.textContent = `Opened in ${data.profileName || 'Chrome'} (Tab #${data.tabId}). Enter your User ID / password / OTP in that Chrome window, then click Verify Login Ready.`;
      }
    }
    showToast(`Opened ${getHostname(targetUrl)} in ${data.profileName || 'Chrome'}.`);
  } catch (err) {
    if (statusEl) {
      statusEl.className = 'login-connect-status warn';
      statusEl.textContent = `Error: ${err.message}`;
    }
  }
}

async function checkAgentLoginTab(agent) {
  const statusEl = document.getElementById('loginConnectStatus');
  const targetUrl = normalizeHttpUrl(document.getElementById('cfgSessionUrl')?.value.trim() || agent.customUrl || '');
  const clientId = document.getElementById('cfgClientId')?.value || agent.clientId || '';
  const tabId = document.getElementById('cfgTabId')?.value || agent.tabId || null;

  if (statusEl) {
    statusEl.className = 'login-connect-status';
    statusEl.textContent = 'Checking login & chat readiness in Chrome...';
  }

  try {
    const res = await fetch('/api/bridge/check-login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ clientId, tabId, targetUrl, agentId: agent.id })
    });
    const data = await res.json();
    if (!data.success) {
      if (statusEl) {
        statusEl.className = 'login-connect-status warn';
        statusEl.textContent = data.error || 'Chrome Profile not connected.';
      }
      return;
    }
    if (statusEl) {
      if (data.ready) {
        statusEl.className = 'login-connect-status ok';
        statusEl.textContent = `Connection established! Logged into ${getHostname(data.tabUrl || targetUrl)} on ${data.profileName || 'Chrome'} (Tab #${data.tabId}).`;
      } else {
        statusEl.className = 'login-connect-status warn';
        statusEl.textContent = `Still waiting for sign-in on ${getHostname(data.tabUrl || targetUrl)}. Finish logging in inside Chrome and click Verify Login Ready again.`;
      }
    }
  } catch (err) {
    if (statusEl) {
      statusEl.className = 'login-connect-status warn';
      statusEl.textContent = `Error: ${err.message}`;
    }
  }
}

function syncProviderFieldsVisibility() {
  const provider = document.getElementById('cfgProvider')?.value;
  const inAppBox = document.getElementById('inAppBrowserFields');
  const browserBox = document.getElementById('browserSessionFields');
  const apiBox = document.getElementById('apiFields');
  const dockerBox = document.getElementById('dockerFields');

  if (inAppBox) inAppBox.style.display = (provider === 'inapp-browser') ? 'flex' : 'none';
  if (browserBox) browserBox.style.display = (provider === 'browser-session' || provider === 'browser-automation') ? 'flex' : 'none';
  if (apiBox) apiBox.style.display = (provider === 'gemini' || provider === 'openai' || provider === 'custom-api') ? 'flex' : 'none';
  if (dockerBox) dockerBox.style.display = (provider === 'docker-web') ? 'flex' : 'none';
}

async function testDockerConnection() {
  const status = document.getElementById('dockerTestStatus');
  const url = document.getElementById('cfgDockerUrl')?.value.trim() || '';
  if (!url) {
    showToast('Enter a URL first.', true);
    return;
  }
  if (status) status.textContent = 'Testing...';
  try {
    const res = await fetch('/api/test-docker', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url })
    });
    const data = await res.json();
    if (status) {
      status.textContent = data.success ? `Reachable (HTTP ${data.status})` : `Unreachable (${data.error})`;
    }
  } catch (err) {
    if (status) status.textContent = `Error: ${err.message}`;
  }
}

async function persistConfig() {
  const res = await fetch('/api/config', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(appConfig)
  });
  const data = await res.json();
  if (data.success) {
    await loadConfig();
  }
  return data;
}

async function saveCurrentAccountConfig() {
  const agent = appConfig.accounts.find((a) => a.id === activeTabId);
  if (!agent) return;

  const name = document.getElementById('cfgName')?.value.trim();
  if (!name) {
    showToast('Agent name is required.', true);
    return;
  }

  agent.name = name;
  agent.role = document.getElementById('cfgRole')?.value.trim() || '';
  agent.provider = document.getElementById('cfgProvider')?.value || 'simulation';
  agent.systemPrompt = document.getElementById('cfgPrompt')?.value.trim() || '';

  if (agent.provider === 'inapp-browser') {
    agent.tabId = null;
    agent.clientId = null;
    agent.customUrl = normalizeHttpUrl(document.getElementById('cfgInAppUrl')?.value.trim() || agent.customUrl || 'https://muse.ai');
  } else if (agent.provider === 'browser-session' || agent.provider === 'browser-automation') {
    const clientSel = document.getElementById('cfgClientId');
    const tabSel = document.getElementById('cfgTabId');
    const rawTabId = tabSel?.value;
    const selectedTabOpt = tabSel?.selectedOptions?.[0];
    const selectedClientOpt = clientSel?.selectedOptions?.[0];

    agent.tabId = rawTabId ? Number(rawTabId) : null;
    agent.clientId = clientSel?.value || selectedTabOpt?.getAttribute('data-client-id') || null;
    agent.profileName =
      selectedClientOpt?.getAttribute('data-profile-name') ||
      selectedTabOpt?.getAttribute('data-profile-name') ||
      (agent.clientId ? agent.profileName || '' : '');
    agent.customUrl = normalizeHttpUrl(document.getElementById('cfgSessionUrl')?.value.trim() || '');
  } else if (agent.provider === 'docker-web') {
    agent.customUrl = normalizeHttpUrl(document.getElementById('cfgDockerUrl')?.value.trim() || '');
    agent.tabId = null;
    agent.clientId = null;
  } else {
    agent.tabId = null;
    agent.clientId = null;
  }

  agent.apiKey = document.getElementById('cfgApiKey')?.value.trim() || '';
  agent.model = document.getElementById('cfgModel')?.value.trim() || '';
  agent.endpoint = document.getElementById('cfgEndpoint')?.value.trim() || '';

  try {
    const data = await persistConfig();
    if (data.success) {
      closeConfigModal();
      showToast(`Saved "${agent.name}".`);
    }
  } catch (err) {
    showToast(`Save failed: ${err.message}`, true);
  }
}

function removeAccount(agentId) {
  const agent = appConfig.accounts.find((a) => a.id === agentId);
  if (!agent) return;
  showConfirmModal(
    'Remove Agent',
    `Remove "${agent.name}" from your connected agents?`,
    async () => {
      appConfig.accounts = appConfig.accounts.filter((a) => a.id !== agentId);
      if (currentDirectAgentId === agentId) switchView('boardroom');
      await persistConfig();
      renderBridgeState();
      showToast(`Removed "${agent.name}".`);
    }
  );
}

function deleteCurrentAgent(agentId) {
  const agent = appConfig.accounts.find((a) => a.id === agentId);
  if (!agent) return;
  showConfirmModal(
    'Remove Agent',
    `Remove "${agent.name}" from your connected agents?`,
    async () => {
      appConfig.accounts = appConfig.accounts.filter((a) => a.id !== agentId);
      activeTabId = appConfig.accounts[0]?.id || null;
      if (currentDirectAgentId === agentId) switchView('boardroom');
      await persistConfig();
      renderModalTabs();
      renderModalForm();
      renderBridgeState();
      showToast(`Removed "${agent.name}".`);
    }
  );
}

function clearBoardroom() {
  showConfirmModal(
    'Clear Chat History',
    'Clear all messages from the boardroom?',
    async () => {
      try {
        await fetch('/api/messages', { method: 'DELETE' });
        await loadMessages();
        showToast('Chat cleared.');
      } catch (err) {
        showToast(`Failed to clear chat: ${err.message}`, true);
      }
    }
  );
}

// Built-In In-App Browser + Attached Connector Workspace
async function openInAppBrowserModal(agentId = null) {
  const modal = document.getElementById('browserWorkspaceModal');
  if (!modal) return;

  let target = agentId ? appConfig.accounts.find((a) => a.id === agentId) : null;
  if (!target) {
    target =
      appConfig.accounts.find((a) => a.provider === 'inapp-browser') ||
      appConfig.accounts[0] ||
      null;
  }

  if (!target) {
    const newId = `muse-${Date.now()}`;
    target = {
      id: newId,
      name: 'Muse AI',
      role: 'Muse AI',
      avatar: 'M',
      color: '#3B82F6',
      provider: 'inapp-browser',
      customUrl: 'https://muse.ai',
      systemPrompt: ''
    };
    appConfig.accounts.push(target);
    await persistConfig();
  }

  modal.classList.add('open');
  await switchBrowserAgent(target.id);
}

function closeInAppBrowserModal() {
  disconnectBrowserStream();
  document.getElementById('browserWorkspaceModal')?.classList.remove('open');
  loadInAppStatuses();
}

function renderBrowserAgentTabs() {
  const tabsEl = document.getElementById('browserAgentTabs');
  if (!tabsEl) return;
  tabsEl.innerHTML = '';

  const browserAgents = (appConfig.accounts || []).filter(
    (a) => a.provider === 'inapp-browser' || a.id === activeBrowserAgentId
  );

  browserAgents.forEach((a) => {
    const st = inAppStatuses[a.id] || {};
    const dot = st.open && st.ready ? ' ●' : st.open ? ' ○' : '';
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = `tab-btn ${a.id === activeBrowserAgentId ? 'active' : ''}`;
    btn.textContent = `${a.name}${dot}`;
    btn.addEventListener('click', () => switchBrowserAgent(a.id));
    tabsEl.appendChild(btn);
  });

  const addAccountBtn = document.createElement('button');
  addAccountBtn.type = 'button';
  addAccountBtn.id = 'btnBrowserNewAccount';
  addAccountBtn.className = 'btn btn-sm btn-icon-sm btn-primary';
  addAccountBtn.style.marginLeft = 'auto';
  addAccountBtn.innerHTML = UI_ICONS.plus;
  addAccountBtn.title = 'Create New Isolated Account';
  addAccountBtn.setAttribute('aria-label', 'Create New Isolated Account');
  addAccountBtn.addEventListener('click', createNewInAppAccountFromWorkspace);
  tabsEl.appendChild(addAccountBtn);
}

async function createNewInAppAccountFromWorkspace() {
  const baseAgent = appConfig.accounts.find((a) => a.id === activeBrowserAgentId) || appConfig.accounts[0];
  const baseName = baseAgent ? baseAgent.name.replace(/\s+\d+$/, '') : 'Muse AI';
  const newName = getUniqueAgentName(baseName);
  const newAgent = {
    id: `agent-${Date.now()}`,
    name: newName,
    role: baseAgent?.role || 'In-App Browser AI',
    avatar: baseAgent?.avatar || 'AI',
    color: baseAgent?.color || '#3B82F6',
    provider: 'inapp-browser',
    clientId: null,
    profileName: '',
    tabId: null,
    customUrl: baseAgent?.customUrl || 'https://muse.ai',
    apiKey: '',
    model: '',
    endpoint: '',
    systemPrompt: ''
  };
  appConfig.accounts.push(newAgent);
  await persistConfig();
  showToast(`Created isolated browser profile for "${newName}".`);
  await switchBrowserAgent(newAgent.id);
}

async function switchBrowserAgent(agentId) {
  activeBrowserAgentId = agentId;
  renderBrowserAgentTabs();

  const agent = appConfig.accounts.find((a) => a.id === agentId);
  const urlInput = document.getElementById('browserUrlInput');
  const img = document.getElementById('browserStreamImg');
  const placeholder = document.getElementById('browserViewportPlaceholder');

  if (urlInput && agent?.customUrl) urlInput.value = agent.customUrl;
  if (img) img.classList.remove('active');
  if (placeholder) {
    placeholder.style.display = 'flex';
    placeholder.textContent = !inAppEngineState.ready
      ? `Provisioning built-in browser engine on this host (first-time setup)... Please wait ~10s.`
      : `Opening isolated profile for ${agent?.name || 'Agent'} and attaching connector...`;
  }
  updateBrowserConnectorBadge({ open: false, ready: false, needsLogin: false });

  disconnectBrowserStream();

  try {
    const res = await fetch('/api/inapp-browser/open', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        agentId,
        targetUrl: agent?.customUrl || 'https://muse.ai',
        agent
      })
    });
    const data = await res.json();
    if (!data.success) {
      if (placeholder) placeholder.textContent = data.error || 'Could not launch In-App Browser.';
      showToast(data.error || 'Failed to launch In-App Browser', true);
      return;
    }
    if (data.status) {
      inAppStatuses[agentId] = data.status;
      updateBrowserConnectorBadge(data.status);
    }
    connectBrowserStream(agentId);
  } catch (err) {
    if (placeholder) placeholder.textContent = `Error: ${err.message}`;
  }
}

function connectBrowserStream(agentId) {
  disconnectBrowserStream();
  const img = document.getElementById('browserStreamImg');
  const placeholder = document.getElementById('browserViewportPlaceholder');
  const urlInput = document.getElementById('browserUrlInput');

  const es = new EventSource(`/api/inapp-browser/stream?agentId=${encodeURIComponent(agentId)}`);
  browserEventSource = es;

  es.addEventListener('frame', (e) => {
    try {
      const frame = JSON.parse(e.data);
      if (frame?.data && img) {
        img.src = `data:image/jpeg;base64,${frame.data}`;
        img.classList.add('active');
        if (placeholder) placeholder.style.display = 'none';
      }
    } catch {}
  });

  es.addEventListener('status', (e) => {
    try {
      const status = JSON.parse(e.data);
      if (!status) return;
      inAppStatuses[status.agentId || agentId] = status;
      if (urlInput && document.activeElement !== urlInput && status.url) {
        urlInput.value = status.url;
      }
      updateBrowserConnectorBadge(status);
      renderBrowserAgentTabs();
      renderRoster();
      renderHeaderMeta();
    } catch {}
  });

  es.addEventListener('error', (e) => {
    if (e.data) {
      try {
        const errData = JSON.parse(e.data);
        if (placeholder) {
          placeholder.style.display = 'flex';
          placeholder.textContent = errData.message || 'Browser stream error.';
        }
      } catch {}
    }
  });
}

function disconnectBrowserStream() {
  if (browserEventSource) {
    browserEventSource.close();
    browserEventSource = null;
  }
}

function updateBrowserConnectorBadge(status = {}) {
  const badge = document.getElementById('browserConnectorBadge');
  if (!badge) return;
  if (!status.open) {
    badge.className = 'connector-badge';
    badge.textContent = 'Connector Attaching...';
  } else if (status.ready) {
    badge.className = 'connector-badge ready';
    badge.textContent = 'Connector Attached · Chat Ready';
  } else if (status.needsLogin) {
    badge.className = 'connector-badge login';
    badge.textContent = 'Connector Attached · Sign In';
  } else {
    badge.className = 'connector-badge';
    badge.textContent = 'Connector Attached · Active';
  }
}

async function navigateInAppBrowser(action, url = '') {
  if (!activeBrowserAgentId) return;
  try {
    const res = await fetch('/api/inapp-browser/navigate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ agentId: activeBrowserAgentId, action, url })
    });
    const data = await res.json();
    if (data.status) {
      inAppStatuses[activeBrowserAgentId] = data.status;
      updateBrowserConnectorBadge(data.status);
      const urlInput = document.getElementById('browserUrlInput');
      if (urlInput && data.status.url) urlInput.value = data.status.url;
    }
  } catch (err) {
    showToast(`Navigation error: ${err.message}`, true);
  }
}

async function refreshInAppBrowserStatus() {
  if (!activeBrowserAgentId) return;
  try {
    const res = await fetch('/api/inapp-browser/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ agentId: activeBrowserAgentId })
    });
    const data = await res.json();
    if (data.status) {
      inAppStatuses[activeBrowserAgentId] = data.status;
      updateBrowserConnectorBadge(data.status);
      renderRoster();
      renderHeaderMeta();
      showToast(
        data.status.ready
          ? 'Session connected and ready for commands!'
          : 'Still on sign-in screen — finish logging in inside the viewport above.',
        !data.status.ready
      );
    }
  } catch (err) {
    showToast(`Check failed: ${err.message}`, true);
  }
}

async function closeCurrentInAppBrowserTab() {
  if (!activeBrowserAgentId) return;
  await navigateInAppBrowser('close');
  disconnectBrowserStream();
  const img = document.getElementById('browserStreamImg');
  const placeholder = document.getElementById('browserViewportPlaceholder');
  if (img) img.classList.remove('active');
  if (placeholder) {
    placeholder.style.display = 'flex';
    placeholder.textContent = 'Tab closed. Cookies & login session remain saved on disk.';
  }
  await loadInAppStatuses();
  showToast('Browser tab closed. Your login session stays saved.');
}

async function sendInAppQuickText() {
  if (!activeBrowserAgentId) return;
  const input = document.getElementById('browserQuickTextInput');
  const text = input?.value || '';
  if (!text) return;
  input.value = '';
  await sendInAppInput(activeBrowserAgentId, { kind: 'text', text });
  document.getElementById('browserStreamImg')?.focus();
}

function mapClientToViewportCoords(img, clientX, clientY) {
  const rect = img.getBoundingClientRect();
  if (!rect.width || !rect.height) return { x: 0, y: 0 };
  const x = Math.round(((clientX - rect.left) / rect.width) * 1200);
  const y = Math.round(((clientY - rect.top) / rect.height) * 760);
  return {
    x: Math.max(0, Math.min(1200, x)),
    y: Math.max(0, Math.min(760, y))
  };
}

async function sendInAppInput(agentId, payload) {
  if (!agentId) return;
  try {
    await fetch('/api/inapp-browser/input', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ agentId, ...payload })
    });
  } catch {
    // Ignore transient input errors
  }
}

function bindBrowserViewportEvents() {
  const img = document.getElementById('browserStreamImg');
  if (!img) return;

  img.addEventListener('mousedown', (e) => {
    e.preventDefault();
    img.focus();
    const { x, y } = mapClientToViewportCoords(img, e.clientX, e.clientY);
    const button = e.button === 2 ? 'right' : e.button === 1 ? 'middle' : 'left';
    sendInAppInput(activeBrowserAgentId, { kind: 'mouse', type: 'mousePressed', x, y, button });
  });

  img.addEventListener('mouseup', (e) => {
    e.preventDefault();
    const { x, y } = mapClientToViewportCoords(img, e.clientX, e.clientY);
    const button = e.button === 2 ? 'right' : e.button === 1 ? 'middle' : 'left';
    sendInAppInput(activeBrowserAgentId, { kind: 'mouse', type: 'mouseReleased', x, y, button });
  });

  img.addEventListener('mousemove', (e) => {
    const now = Date.now();
    if (now - lastMouseMoveAt < 90) return;
    lastMouseMoveAt = now;
    const { x, y } = mapClientToViewportCoords(img, e.clientX, e.clientY);
    sendInAppInput(activeBrowserAgentId, { kind: 'mouse', type: 'mouseMoved', x, y });
  });

  img.addEventListener('wheel', (e) => {
    e.preventDefault();
    const { x, y } = mapClientToViewportCoords(img, e.clientX, e.clientY);
    sendInAppInput(activeBrowserAgentId, {
      kind: 'wheel',
      x,
      y,
      deltaX: Math.round(e.deltaX),
      deltaY: Math.round(e.deltaY)
    });
  }, { passive: false });

  img.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'v') return;
    e.preventDefault();
    sendInAppInput(activeBrowserAgentId, {
      kind: 'key',
      key: e.key,
      code: e.code,
      ctrlKey: e.ctrlKey,
      metaKey: e.metaKey
    });
  });

  img.addEventListener('paste', (e) => {
    const text = e.clipboardData?.getData('text') || '';
    if (text) {
      e.preventDefault();
      sendInAppInput(activeBrowserAgentId, { kind: 'text', text });
    }
  });
}
