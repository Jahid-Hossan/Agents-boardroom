import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { GoogleGenAI } from '@google/genai';
import { findBrowserExecutable, queryLiveMuseAccount } from './browser-orchestrator.js';
import { BridgeStore, generateBridgeToken } from './lib/bridge-store.js';
import { buildZip } from './lib/zip-builder.js';
import { generateExtensionFiles } from './lib/bridge-extension-files.js';
import {
  InAppBrowserManager,
  findChromiumExecutable,
  isDownloadingChromium
} from './lib/inapp-browser.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PORT = Number(process.env.PORT) || 3000;
const CONFIG_FILE = path.join(__dirname, 'config.json');
const MESSAGES_FILE = path.join(__dirname, 'messages.json');
const UPLOADS_DIR = path.join(__dirname, 'public', 'uploads');

if (!fs.existsSync(UPLOADS_DIR)) {
  fs.mkdirSync(UPLOADS_DIR, { recursive: true });
}

function loadJson(file, defaultVal) {
  try {
    if (fs.existsSync(file)) {
      return JSON.parse(fs.readFileSync(file, 'utf-8'));
    }
  } catch (e) {
    console.error(`Error loading ${file}:`, e);
  }
  return defaultVal;
}

function saveJson(file, data) {
  try {
    fs.writeFileSync(file, JSON.stringify(data, null, 2), 'utf-8');
  } catch (e) {
    console.error(`Error saving ${file}:`, e);
  }
}

let config = loadJson(CONFIG_FILE, {
  companyName: 'AI Command Center',
  ceoName: 'CEO',
  bridgeToken: generateBridgeToken(),
  accounts: []
});

if (!config.bridgeToken) {
  config.bridgeToken = generateBridgeToken();
  saveJson(CONFIG_FILE, config);
}

const bridgeStore = new BridgeStore(config.bridgeToken);
const defaultProfilesRoot = process.env.PROFILES_DIR || path.join(os.homedir(), '.muse-boardroom', 'profiles');
const inAppBrowser = new InAppBrowserManager(defaultProfilesRoot);

let messages = loadJson(MESSAGES_FILE, [
  {
    id: 'init-1',
    sender: 'system',
    role: 'System',
    senderName: 'System',
    text: 'Command Center ready with **Muse AI**, **Gemini**, **ChatGPT**, and **Grok**. Click **In-App Browser** (or **Browser** on any agent) to sign in right inside the app—the attached connector keeps your session active.',
    timestamp: new Date().toISOString()
  }
]);

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon'
};

function sendJson(res, status, data) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(data));
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', (chunk) => {
      body += chunk;
      if (body.length > 20 * 1024 * 1024) {
        reject(new Error('Request body too large'));
      }
    });
    req.on('end', () => {
      try {
        resolve(body ? JSON.parse(body) : {});
      } catch (err) {
        reject(err);
      }
    });
    req.on('error', reject);
  });
}

function getRequestOrigin(req) {
  const protoHeader = req.headers['x-forwarded-proto'];
  const proto = protoHeader ? String(protoHeader).split(',')[0].trim() : 'http';
  const host = req.headers['x-forwarded-host'] || req.headers.host || `localhost:${PORT}`;
  return `${proto}://${host}`;
}

function extractBridgeToken(req, body = {}) {
  return (
    req.headers['x-bridge-token'] ||
    (req.headers.authorization || '').replace(/^Bearer\s+/i, '') ||
    body.token ||
    ''
  ).trim();
}

function getGeminiClient(overrideKey = '') {
  const apiKey = String(overrideKey || process.env.GEMINI_API_KEY || '').trim();
  if (!apiKey) return null;
  return new GoogleGenAI({
    apiKey,
    httpOptions: {
      headers: {
        'User-Agent': 'aistudio-build'
      }
    }
  });
}

function normalizeGeminiModel(rawModel = '') {
  const m = String(rawModel || '').trim();
  if (!m || /^gemini-(1\.5|2\.0|pro\b)/i.test(m)) {
    return 'gemini-4-hydrogen';
  }
  return m;
}

function formatGeminiError(err) {
  let raw = String(err?.message || 'Gemini request failed.');
  let code = Number(err?.status || err?.code || 500);
  try {
    const parsed = JSON.parse(raw);
    if (parsed?.error?.message) {
      raw = parsed.error.message;
    }
    if (parsed?.error?.code) {
      code = Number(parsed.error.code);
    }
  } catch {}
  const isKeyIssue =
    /API key not valid|API_KEY_INVALID|permission denied|unauthenticated|missing gemini api key/i.test(raw) ||
    code === 401 ||
    code === 403;
  return {
    status: isKeyIssue ? 503 : code >= 400 && code < 600 ? code : 500,
    fallbackSuggested: isKeyIssue,
    message: isKeyIssue
      ? 'Server GEMINI_API_KEY is missing or invalid. Configure a valid key in Secrets to enable Gemini voice transcription.'
      : raw
  };
}

function parseFileAttachment(fileAttachment = null) {
  if (!fileAttachment?.dataUrl) return null;
  const match = String(fileAttachment.dataUrl).match(/^data:([^;]+);base64,(.+)$/);
  if (!match) return null;
  const rawMime = match[1].toLowerCase().trim();
  const mimeType = rawMime.split(';')[0].trim() || 'application/octet-stream';
  const base64 = match[2];
  const filename = String(fileAttachment.filename || 'attachment').trim();
  const isText =
    mimeType.startsWith('text/') ||
    mimeType === 'application/json' ||
    /\.(txt|md|csv|json|js|ts|py|html|css|log)$/i.test(filename);
  let textContent = '';
  if (isText) {
    try {
      textContent = Buffer.from(base64, 'base64').toString('utf8').slice(0, 16000);
    } catch {}
  }
  return { filename, mimeType, base64, isText, textContent };
}

function appendAttachmentToTextPrompt(promptText, fileAttachment = null) {
  const parsed = parseFileAttachment(fileAttachment);
  if (!parsed) return promptText;
  if (parsed.isText && parsed.textContent) {
    return `${promptText}\n\n[Attached file: ${parsed.filename}]\n${parsed.textContent}`;
  }
  return `${promptText}\n\n[Attached file: ${parsed.filename} (${parsed.mimeType})]`;
}

// Call Google Gemini API via @google/genai SDK
async function callGeminiApi(agent, promptText, fileAttachment = null) {
  const ai = getGeminiClient(agent.apiKey);
  if (!ai) {
    throw new Error('Missing Gemini API Key. Configure GEMINI_API_KEY or set a key in Manage Agents.');
  }
  const modelName = normalizeGeminiModel(agent.model);
  const parts = [{ text: promptText }];
  const parsed = parseFileAttachment(fileAttachment);
  if (parsed) {
    if (parsed.isText && parsed.textContent) {
      parts.push({ text: `[Attached file: ${parsed.filename}]\n${parsed.textContent}` });
    } else {
      parts.push({
        inlineData: {
          mimeType: parsed.mimeType,
          data: parsed.base64
        }
      });
    }
  }

  const response = await ai.models.generateContent({
    model: modelName,
    contents: { parts },
    config: agent.systemPrompt ? { systemInstruction: agent.systemPrompt } : undefined
  });

  return (response.text || '').trim() || 'No response received from Gemini.';
}

// Call OpenAI / Groq / Ollama API
async function callOpenAiApi(agent, promptText, fileAttachment = null) {
  const apiKey = agent.apiKey;
  const endpoint = (agent.endpoint || 'https://api.openai.com/v1/chat/completions').trim();
  const modelName = agent.model || 'gpt-4o-mini';

  const headers = { 'Content-Type': 'application/json' };
  if (apiKey) headers['Authorization'] = `Bearer ${apiKey}`;

  const parsed = parseFileAttachment(fileAttachment);
  let userContent = promptText;
  if (parsed) {
    if (parsed.mimeType.startsWith('image/')) {
      userContent = [
        { type: 'text', text: promptText },
        { type: 'image_url', image_url: { url: fileAttachment.dataUrl } }
      ];
    } else if (parsed.isText && parsed.textContent) {
      userContent = `${promptText}\n\n[Attached file: ${parsed.filename}]\n${parsed.textContent}`;
    } else {
      userContent = `${promptText}\n\n[Attached file: ${parsed.filename} (${parsed.mimeType})]`;
    }
  }

  const response = await fetch(endpoint, {
    method: 'POST',
    headers,
    body: JSON.stringify({
      model: modelName,
      messages: [
        { role: 'system', content: agent.systemPrompt || 'You are a helpful AI assistant.' },
        { role: 'user', content: userContent }
      ],
      max_tokens: 1200
    }),
    signal: AbortSignal.timeout(25000)
  });

  if (!response.ok) {
    const err = await response.text();
    throw new Error(`API error (${response.status}): ${err.substring(0, 180)}`);
  }

  const resData = await response.json();
  return resData.choices?.[0]?.message?.content || 'No response received.';
}

// Call Docker Web Agent (Custom URL or OpenAI-compatible webhook)
async function callDockerWebAgent(agent, promptText) {
  const customUrl = agent.customUrl ? agent.customUrl.trim().replace(/\/+$/, '') : '';
  if (!customUrl) {
    return `**${agent.name} is not configured yet.**\n\nOpen **Manage Agents** and enter a public \`https://\` endpoint URL for this agent.`;
  }

  const attempts = [
    {
      url: `${customUrl}/v1/chat/completions`,
      body: {
        model: agent.model || 'default',
        messages: [
          ...(agent.systemPrompt ? [{ role: 'system', content: agent.systemPrompt }] : []),
          { role: 'user', content: promptText }
        ]
      }
    },
    {
      url: `${customUrl}/api/chat`,
      body: { message: promptText, prompt: promptText, input: promptText }
    },
    {
      url: `${customUrl}/chat`,
      body: { message: promptText, prompt: promptText, input: promptText }
    },
    {
      url: customUrl,
      body: { message: promptText, prompt: promptText, input: promptText }
    }
  ];

  const headers = { 'Content-Type': 'application/json' };
  if (agent.apiKey) headers['Authorization'] = `Bearer ${agent.apiKey}`;

  let lastError = 'No chat API endpoint responded';
  for (const attempt of attempts) {
    try {
      const response = await fetch(attempt.url, {
        method: 'POST',
        headers,
        body: JSON.stringify(attempt.body),
        signal: AbortSignal.timeout(8000)
      });
      if (response.ok) {
        const contentType = response.headers.get('content-type') || '';
        if (contentType.includes('application/json')) {
          const data = await response.json();
          const text = data.reply || data.response || data.message || (data.choices?.[0]?.message?.content);
          if (text) return text;
        }
      } else {
        lastError = `HTTP ${response.status} from ${attempt.url}`;
      }
    } catch (e) {
      lastError = e.message;
    }
  }

  return `Could not reach **${agent.name}** at \`${customUrl}\` (${lastError}). Ensure the URL is publicly accessible and exposes \`/v1/chat/completions\` or \`/api/chat\`.`;
}

function normalizeHttpUrl(raw = '') {
  const s = String(raw || '').trim();
  if (!s) return '';
  return /^https?:\/\//i.test(s) ? s : `https://${s}`;
}

// Call Desktop Browser Session via Chrome Bridge Extension (or local Puppeteer if available)
async function callDesktopBrowserSession(agent, promptText) {
  const targetUrl = normalizeHttpUrl(agent.customUrl || '');
  const activeClients = bridgeStore.getActiveClients();
  const targetClientId = agent.clientId || (activeClients.length === 1 ? activeClients[0].clientId : null);

  if (agent.clientId && !bridgeStore.isClientConnected(agent.clientId)) {
    const label = agent.profileName || agent.clientId;
    return `**Chrome Profile "${label}" is offline.**\n\nOpen the Chrome window for **${label}** on your computer—the bridge extension will reconnect automatically.`;
  }

  if (bridgeStore.isConnected()) {
    const result = await bridgeStore.enqueueJob({
      action: 'prompt',
      agentId: agent.id,
      agentName: agent.name,
      targetClientId,
      targetTabId: agent.tabId ?? null,
      targetUrl,
      prompt: promptText,
      timeoutMs: 42000
    });
    return result.reply;
  }

  // Fallback to local Puppeteer when running on desktop/VPS with Chrome installed
  const localReply = await queryLiveMuseAccount({ ...agent, customUrl: targetUrl }, promptText);
  if (localReply) return localReply;

  return `**Desktop Browser Bridge is not connected.**\n\nTo send commands directly to your logged-in **${agent.name}** tab (${targetUrl || 'e.g. muse.ai or gemini.google.com'}):\n1. Click **Desktop Bridge** in the top bar.\n2. Download & load the pre-configured Chrome extension once in \`chrome://extensions\`.\n3. Keep your AI tab logged in and open in Chrome.`;
}

function generateDemoResponse(agent, prompt) {
  const cleanPrompt = prompt.replace(/^@[\w.-]+\s*/i, '').trim();
  const lower = cleanPrompt.toLowerCase();
  const roleStr = agent.role ? ` (${agent.role})` : '';

  if (/^(hello|hi|hey|greetings|good\s*(morning|afternoon|evening)|hola|salam)\b/i.test(lower)) {
    return `Hello! **${agent.name}**${roleStr} is running in **Offline Demo** mode. Connect the Desktop Browser Bridge or an API key in **Manage Agents** for live AI responses.`;
  }

  return `**[Offline Demo — ${agent.name}${roleStr}]** Received: "${cleanPrompt}".\n\nSwitch this agent to **Desktop Browser Session**, **Google Gemini API**, or **OpenAI / Groq** in **Manage Agents** to get real AI output.`;
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || `localhost:${PORT}`}`);
  const pathname = url.pathname;

  const reqOrigin = req.headers.origin;
  if (reqOrigin) {
    res.setHeader('Access-Control-Allow-Origin', reqOrigin);
    res.setHeader('Access-Control-Allow-Credentials', 'true');
    res.setHeader('Vary', 'Origin');
  } else {
    res.setHeader('Access-Control-Allow-Origin', '*');
  }
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization, X-Bridge-Token');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  // Built-In In-App Browser + Attached Connector Endpoints
  if (pathname === '/api/inapp-browsers' && req.method === 'GET') {
    sendJson(res, 200, {
      statuses: inAppBrowser.getAllStatuses(config.accounts || []),
      browserReady: Boolean(findChromiumExecutable(inAppBrowser.profilesRoot)),
      browserDownloading: isDownloadingChromium()
    });
    return;
  }

  if (pathname === '/api/inapp-browser/open' && req.method === 'POST') {
    try {
      const { agentId, targetUrl, forceNavigate = false, agent: bodyAgent } = await readJsonBody(req);
      const id = String(agentId || bodyAgent?.id || 'muse');
      const found = (config.accounts || []).find((a) => a.id === id);
      const agent = found || {
        id,
        name: bodyAgent?.name || 'Browser',
        customUrl: targetUrl || bodyAgent?.customUrl || 'https://muse.ai'
      };
      await inAppBrowser.ensureSession(agent, {
        targetUrl: targetUrl || agent.customUrl || 'https://muse.ai',
        forceNavigate: Boolean(forceNavigate)
      });
      const status = inAppBrowser.getSessionStatus(agent.id);
      sendJson(res, 200, { success: true, status });
    } catch (err) {
      sendJson(res, 200, { success: false, error: err.message });
    }
    return;
  }

  if (pathname === '/api/inapp-browser/navigate' && req.method === 'POST') {
    try {
      const { agentId, action = 'goto', url: targetUrl = '' } = await readJsonBody(req);
      const id = String(agentId || '');
      if (!id) {
        sendJson(res, 400, { success: false, error: 'agentId is required' });
        return;
      }
      const agent = (config.accounts || []).find((a) => a.id === id) || {
        id,
        name: 'Browser',
        customUrl: targetUrl || 'https://muse.ai'
      };
      if (action === 'goto' && targetUrl && agent.id && (config.accounts || []).some((a) => a.id === id)) {
        const stored = config.accounts.find((a) => a.id === id);
        if (stored) {
          stored.customUrl = normalizeHttpUrl(targetUrl);
          saveJson(CONFIG_FILE, config);
        }
      }
      const status = await inAppBrowser.navigateAction(agent, { action, url: targetUrl });
      sendJson(res, 200, { success: true, status });
    } catch (err) {
      sendJson(res, 200, { success: false, error: err.message });
    }
    return;
  }

  if (pathname === '/api/inapp-browser/refresh' && req.method === 'POST') {
    try {
      const { agentId } = await readJsonBody(req);
      const id = String(agentId || '');
      if (!id) {
        sendJson(res, 400, { success: false, error: 'agentId is required' });
        return;
      }
      const status = await inAppBrowser.refreshStatus(id);
      sendJson(res, 200, { success: true, status: status || inAppBrowser.getSessionStatus(id) });
    } catch (err) {
      sendJson(res, 200, { success: false, error: err.message });
    }
    return;
  }

  if (pathname === '/api/inapp-browser/input' && req.method === 'POST') {
    try {
      const body = await readJsonBody(req);
      const id = String(body.agentId || '');
      if (!id) {
        sendJson(res, 400, { success: false, error: 'agentId is required' });
        return;
      }
      await inAppBrowser.dispatchInput(id, body);
      sendJson(res, 200, { success: true });
    } catch (err) {
      sendJson(res, 200, { success: false, error: err.message });
    }
    return;
  }

  if (pathname === '/api/inapp-browser/stream' && req.method === 'GET') {
    const id = String(url.searchParams.get('agentId') || '');
    if (!id) {
      sendJson(res, 400, { error: 'agentId is required' });
      return;
    }
    const agent = (config.accounts || []).find((a) => a.id === id) || {
      id,
      name: 'Browser',
      customUrl: 'https://muse.ai'
    };

    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache, no-transform',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no'
    });

    const writeEvent = (event, data) => {
      if (res.writableEnded) return;
      res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
    };

    let unsubscribe = null;
    const keepAlive = setInterval(() => {
      if (!res.writableEnded) res.write(': ping\n\n');
    }, 15000);

    const cleanup = () => {
      clearInterval(keepAlive);
      if (unsubscribe) {
        unsubscribe();
        unsubscribe = null;
      }
    };
    req.on('close', cleanup);

    try {
      unsubscribe = await inAppBrowser.subscribeStream(agent, {
        onFrame: (frame) => writeEvent('frame', frame),
        onStatus: (status) => writeEvent('status', status),
        onClose: () => {
          writeEvent('status', inAppBrowser.getSessionStatus(agent.id));
        }
      });
    } catch (err) {
      writeEvent('error', { message: err.message });
      cleanup();
      if (!res.writableEnded) res.end();
    }
    return;
  }

  // Desktop Bridge: Status
  if (pathname === '/api/bridge/status' && req.method === 'GET') {
    sendJson(res, 200, {
      ...bridgeStore.getStatus(),
      bridgeToken: config.bridgeToken,
      serverUrl: getRequestOrigin(req)
    });
    return;
  }

  // Desktop Bridge: Rotate Pairing Token
  if (pathname === '/api/bridge/token/rotate' && req.method === 'POST') {
    const newToken = bridgeStore.rotateToken();
    config.bridgeToken = newToken;
    saveJson(CONFIG_FILE, config);
    sendJson(res, 200, { success: true, bridgeToken: newToken });
    return;
  }

  // Desktop Bridge: Download Pre-configured MV3 Chrome Extension (.zip)
  if (pathname === '/api/bridge/extension.zip' && req.method === 'GET') {
    const files = generateExtensionFiles({
      serverUrl: getRequestOrigin(req),
      bridgeToken: config.bridgeToken
    });
    const zipBuffer = buildZip(files);
    res.writeHead(200, {
      'Content-Type': 'application/zip',
      'Content-Disposition': 'attachment; filename="muse-desktop-bridge.zip"',
      'Content-Length': zipBuffer.length
    });
    res.end(zipBuffer);
    return;
  }

  // Desktop Bridge: Heartbeat from Chrome Extension
  if (pathname === '/api/bridge/heartbeat' && req.method === 'POST') {
    try {
      const body = await readJsonBody(req);
      const token = extractBridgeToken(req, body);
      if (!bridgeStore.verifyToken(token)) {
        sendJson(res, 401, { error: 'Invalid bridge pairing token' });
        return;
      }
      const client = bridgeStore.recordHeartbeat(body);
      sendJson(res, 200, { ok: true, clientId: client.clientId, tabCount: client.tabs.length });
    } catch (e) {
      sendJson(res, 400, { error: e.message });
    }
    return;
  }

  // Desktop Bridge: Long-poll for pending jobs
  if (pathname === '/api/bridge/poll' && req.method === 'GET') {
    const token = extractBridgeToken(req);
    if (!bridgeStore.verifyToken(token)) {
      sendJson(res, 401, { error: 'Invalid bridge pairing token' });
      return;
    }
    const clientId = url.searchParams.get('clientId') || 'default-profile';
    const profileName = url.searchParams.get('profileName') || '';
    const waitMs = Math.min(Math.max(Number(url.searchParams.get('wait')) || 0, 0), 25000);
    const ac = new AbortController();
    req.on('close', () => ac.abort());

    bridgeStore.touchClient(clientId, profileName);
    const jobs = await bridgeStore.waitForJobs(waitMs, 10, ac.signal, clientId);
    if (ac.signal.aborted || res.writableEnded) return;
    bridgeStore.touchClient(clientId, profileName);
    sendJson(res, 200, { jobs });
    return;
  }

  // Desktop Bridge: Extension reports job result
  if (pathname === '/api/bridge/result' && req.method === 'POST') {
    try {
      const body = await readJsonBody(req);
      const token = extractBridgeToken(req, body);
      if (!bridgeStore.verifyToken(token)) {
        sendJson(res, 401, { error: 'Invalid bridge pairing token' });
        return;
      }
      const completed = bridgeStore.completeJob(body.jobId, body);
      sendJson(res, 200, { ok: completed });
    } catch (e) {
      sendJson(res, 400, { error: e.message });
    }
    return;
  }

  // Desktop Bridge: Open AI site in selected Chrome Profile for login & auto-bind
  if (pathname === '/api/bridge/open-login' && req.method === 'POST') {
    try {
      const { clientId, targetUrl, agentId } = await readJsonBody(req);
      const normalizedUrl = normalizeHttpUrl(targetUrl || 'https://muse.ai');
      const activeClients = bridgeStore.getActiveClients();
      const chosenClientId = clientId || activeClients[0]?.clientId || null;

      if (!chosenClientId || !bridgeStore.isClientConnected(chosenClientId)) {
        sendJson(res, 200, {
          success: false,
          bridgeOffline: true,
          error: 'No connected Chrome Profile selected. Install and connect the Desktop Bridge extension first.'
        });
        return;
      }

      const result = await bridgeStore.enqueueJob({
        action: 'open_tab',
        agentId: agentId || '',
        agentName: 'Login Tab',
        targetClientId: chosenClientId,
        targetUrl: normalizedUrl,
        timeoutMs: 18000
      });

      if (agentId) {
        const agent = config.accounts.find((a) => a.id === agentId);
        if (agent) {
          agent.clientId = result.clientId || chosenClientId;
          agent.profileName = result.profileName || agent.profileName || '';
          agent.tabId = result.tabId ?? agent.tabId ?? null;
          agent.customUrl = normalizedUrl || result.tabUrl || agent.customUrl;
          saveJson(CONFIG_FILE, config);
        }
      }

      sendJson(res, 200, {
        success: true,
        clientId: result.clientId || chosenClientId,
        profileName: result.profileName || '',
        tabId: result.tabId,
        tabUrl: result.tabUrl || normalizedUrl,
        tabTitle: result.tabTitle || '',
        ready: Boolean(result.ready),
        needsLogin: Boolean(result.needsLogin)
      });
    } catch (e) {
      sendJson(res, 200, { success: false, error: e.message });
    }
    return;
  }

  // Desktop Bridge: Check whether bound tab is logged in & chat input is ready
  if (pathname === '/api/bridge/check-login' && req.method === 'POST') {
    try {
      const { clientId, tabId, targetUrl, agentId } = await readJsonBody(req);
      const normalizedUrl = normalizeHttpUrl(targetUrl || '');
      const activeClients = bridgeStore.getActiveClients();
      const chosenClientId = clientId || activeClients[0]?.clientId || null;

      if (!chosenClientId || !bridgeStore.isClientConnected(chosenClientId)) {
        sendJson(res, 200, {
          success: false,
          bridgeOffline: true,
          error: 'Chrome Profile is not connected.'
        });
        return;
      }

      const result = await bridgeStore.enqueueJob({
        action: 'check_tab',
        agentId: agentId || '',
        agentName: 'Login Check',
        targetClientId: chosenClientId,
        targetTabId: tabId ?? null,
        targetUrl: normalizedUrl,
        timeoutMs: 12000
      });

      sendJson(res, 200, {
        success: true,
        clientId: result.clientId || chosenClientId,
        profileName: result.profileName || '',
        tabId: result.tabId,
        tabUrl: result.tabUrl || normalizedUrl,
        ready: Boolean(result.ready),
        needsLogin: Boolean(result.needsLogin)
      });
    } catch (e) {
      sendJson(res, 200, { success: false, error: e.message });
    }
    return;
  }

  // Test connection to Docker Web URL
  if (pathname === '/api/test-docker' && req.method === 'POST') {
    try {
      const { url: targetUrl } = await readJsonBody(req);
      if (!targetUrl) {
        sendJson(res, 400, { error: 'URL is required' });
        return;
      }
      const resp = await fetch(targetUrl, { method: 'GET', signal: AbortSignal.timeout(5000) });
      sendJson(res, 200, { success: true, status: resp.status, statusText: resp.statusText });
    } catch (err) {
      sendJson(res, 200, { success: false, error: err.message });
    }
    return;
  }

  // Open / Launch desktop browser window (local/VPS mode)
  if (pathname === '/api/open-browser' && req.method === 'POST') {
    try {
      const { accountId, targetUrl, cdpUrl } = await readJsonBody(req);
      const agent = config.accounts.find((a) => a.id === accountId) || { id: accountId || 'muse-1', name: 'Browser' };
      const { launchAccountBrowser } = await import('./browser-orchestrator.js');
      const browser = await launchAccountBrowser(agent.id, false, cdpUrl || agent.cdpUrl || '');
      if (!browser) {
        sendJson(res, 200, {
          success: false,
          error: 'No local Chrome installed on this host. Use the Desktop Chrome Bridge Extension instead.'
        });
        return;
      }
      const pages = await browser.pages();
      const page = pages.length > 0 ? pages[0] : await browser.newPage();
      const urlToOpen = targetUrl || agent.customUrl || 'https://muse.ai';
      page.goto(urlToOpen, { waitUntil: 'domcontentloaded' }).catch(() => {});
      sendJson(res, 200, { success: true, message: `Browser bridge connected for ${agent.name}` });
    } catch (err) {
      sendJson(res, 200, { success: false, error: err.message });
    }
    return;
  }

  if (pathname === '/api/config') {
    if (req.method === 'GET') {
      sendJson(res, 200, config);
      return;
    }
    if (req.method === 'POST') {
      try {
        const newConfig = await readJsonBody(req);
        config = {
          ...config,
          ...newConfig,
          bridgeToken: config.bridgeToken
        };
        saveJson(CONFIG_FILE, config);
        sendJson(res, 200, { success: true, config });
      } catch (e) {
        sendJson(res, 400, { error: e.message });
      }
      return;
    }
  }

  if (pathname === '/api/messages') {
    if (req.method === 'GET') {
      sendJson(res, 200, messages);
      return;
    }
    if (req.method === 'DELETE') {
      messages = [
        {
          id: `sys-${Date.now()}`,
          sender: 'system',
          role: 'System',
          senderName: 'System',
          text: 'Chat history cleared. Ready for your next command.',
          timestamp: new Date().toISOString()
        }
      ];
      saveJson(MESSAGES_FILE, messages);
      sendJson(res, 200, { success: true, messages });
      return;
    }
  }

  // Voice-to-prompt transcription via Gemini
  if (pathname === '/api/voice/transcribe' && req.method === 'POST') {
    try {
      const body = await readJsonBody(req);
      let base64Data = String(body.audioBase64 || '').trim();
      let cleanMime = String(body.mimeType || 'audio/webm').split(';')[0].trim().toLowerCase();

      if (!base64Data && body.dataUrl) {
        const m = String(body.dataUrl).match(/^data:([^;]+);base64,(.+)$/);
        if (m) {
          cleanMime = m[1].split(';')[0].trim().toLowerCase();
          base64Data = m[2];
        }
      }

      if (!base64Data) {
        sendJson(res, 400, { success: false, error: 'No audio recording was captured.' });
        return;
      }
      if (!cleanMime.startsWith('audio/') && cleanMime !== 'video/webm') {
        cleanMime = 'audio/webm';
      } else if (cleanMime === 'video/webm') {
        cleanMime = 'audio/webm';
      }

      const ai = getGeminiClient();
      if (!ai) {
        sendJson(res, 503, {
          success: false,
          fallbackSuggested: true,
          error: 'Server GEMINI_API_KEY is not configured. Configure a valid key in Secrets to enable Gemini voice transcription.'
        });
        return;
      }

      const response = await ai.models.generateContent({
        model: 'gemini-3.5-transcribe',
        contents: {
          parts: [
            { inlineData: { mimeType: cleanMime, data: base64Data } },
            {
              text: 'Transcribe the spoken audio accurately into clean prompt text ready to send to an AI assistant. Preserve @mentions if spoken. Return only the transcribed text with no surrounding quotes or commentary.'
            }
          ]
        }
      });

      const transcript = String(response.text || '').trim();
      if (!transcript) {
        sendJson(res, 200, {
          success: false,
          error: 'No speech detected in the recording. Please speak clearly and try again.'
        });
        return;
      }

      sendJson(res, 200, { success: true, transcript });
    } catch (err) {
      const formatted = formatGeminiError(err);
      sendJson(res, formatted.status, {
        success: false,
        fallbackSuggested: formatted.fallbackSuggested,
        error: formatted.message
      });
    }
    return;
  }

  // Upload endpoint
  if (pathname === '/api/upload' && req.method === 'POST') {
    try {
      const { filename, dataUrl } = await readJsonBody(req);
      if (!dataUrl) {
        sendJson(res, 400, { error: 'No data URL provided' });
        return;
      }
      const match = dataUrl.match(/^data:([a-zA-Z0-9/+-]+);base64,(.+)$/);
      if (!match) {
        sendJson(res, 400, { error: 'Invalid data format' });
        return;
      }
      const ext = path.extname(filename || 'image.png') || '.png';
      const safeName = `file-${Date.now()}-${Math.random().toString(36).slice(2, 7)}${ext}`;
      const savePath = path.join(UPLOADS_DIR, safeName);
      fs.writeFileSync(savePath, Buffer.from(match[2], 'base64'));
      sendJson(res, 200, { success: true, url: `/uploads/${safeName}`, filename: filename || safeName });
    } catch (e) {
      sendJson(res, 500, { error: e.message });
    }
    return;
  }

  // Command Dispatch Endpoint
  if (pathname === '/api/command' && req.method === 'POST') {
    try {
      const payload = await readJsonBody(req);
      const { command, mode = 'broadcast', targetAgentId, fileAttachment, stream = false } = payload;

      if (!command || !command.trim()) {
        sendJson(res, 400, { error: 'Command cannot be empty' });
        return;
      }

      const ceoMsg = {
        id: `ceo-${Date.now()}`,
        sender: 'ceo',
        role: 'User',
        senderName: config.ceoName || 'You',
        text: command.trim(),
        fileAttachment: fileAttachment || null,
        mode,
        targetAgentId: targetAgentId || 'all',
        timestamp: new Date().toISOString()
      };
      messages.push(ceoMsg);

      let agentsToInvoke = [];
      if (targetAgentId && targetAgentId !== 'all') {
        const targeted = config.accounts.find((a) => a.id === targetAgentId);
        if (targeted) agentsToInvoke = [targeted];
      } else if (/@all\b/i.test(command)) {
        agentsToInvoke = config.accounts;
      } else {
        const mentioned = config.accounts.filter((a) => {
          const re = new RegExp(`@${a.name.replace(/[-/\\^$*+?.()|[\]{}]/g, '\\$&')}`, 'i');
          return re.test(command);
        });
        agentsToInvoke = mentioned.length > 0 ? mentioned : config.accounts;
      }

      if (agentsToInvoke.length === 0) {
        const noticeMsg = {
          id: `sys-${Date.now()}`,
          sender: 'system',
          role: 'System',
          senderName: 'System',
          mode,
          targetAgentId: targetAgentId || 'all',
          text: 'No agents configured yet. Connect your In-App Browser or click "+ Add" to add an agent.',
          timestamp: new Date().toISOString()
        };
        messages.push(noticeMsg);
        saveJson(MESSAGES_FILE, messages);
        sendJson(res, 200, { success: true, ceoMessage: ceoMsg, replies: [noticeMsg] });
        return;
      }

      const allExternalBrowserAgents = agentsToInvoke.every(
        (a) => a.provider === 'browser-session' || a.provider === 'browser-automation'
      );
      if (allExternalBrowserAgents && !bridgeStore.isConnected() && !findBrowserExecutable()) {
        const names = agentsToInvoke.map((a) => `**${a.name}**`).join(', ');
        const noticeMsg = {
          id: `sys-${Date.now()}`,
          sender: 'system',
          role: 'System',
          senderName: 'System',
          mode,
          targetAgentId: targetAgentId || 'all',
          text:
            agentsToInvoke.length === 1
              ? `**Desktop Browser Bridge is not connected.**\n\nTo send commands to your logged-in **${agentsToInvoke[0].name}** tab (\`${normalizeHttpUrl(agentsToInvoke[0].customUrl) || 'https://muse.ai'}\`):\n1. Click **Ext. Bridge** in the top bar, or switch this agent to **In-App Browser** in **Manage Agents**.\n2. Keep your **${agentsToInvoke[0].name}** tab logged in and open.`
              : `**Desktop Browser Bridge is not connected.**\n\nTargeted agents: ${names}.\n1. Click **Ext. Bridge** in the top bar, or switch agents to **In-App Browser** in **Manage Agents**.`,
          timestamp: new Date().toISOString()
        };
        messages.push(noticeMsg);
        saveJson(MESSAGES_FILE, messages);
        sendJson(res, 200, { success: true, ceoMessage: ceoMsg, replies: [noticeMsg] });
        return;
      }

      const useStream = Boolean(stream);
      const writeSse = (event, data) => {
        if (!useStream || res.writableEnded) return;
        res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
      };

      if (useStream) {
        res.writeHead(200, {
          'Content-Type': 'text/event-stream; charset=utf-8',
          'Cache-Control': 'no-cache, no-transform',
          Connection: 'keep-alive',
          'X-Accel-Buffering': 'no'
        });
        writeSse('start', {
          ceoMessage: ceoMsg,
          targets: agentsToInvoke.map((a) => ({ id: a.id, name: a.name }))
        });
      }

      const replyPromises = agentsToInvoke.map(async (agent) => {
        let replyText = '';

        const executionPromise = (async () => {
          if (agent.provider === 'inapp-browser') {
            return await inAppBrowser.sendPrompt(agent, command, fileAttachment);
          }
          if (agent.provider === 'browser-session' || agent.provider === 'browser-automation') {
            return await callDesktopBrowserSession(agent, appendAttachmentToTextPrompt(command, fileAttachment));
          }
          if (agent.provider === 'gemini') {
            return await callGeminiApi(agent, command, fileAttachment);
          }
          if (agent.provider === 'openai' || agent.provider === 'custom-api') {
            return await callOpenAiApi(agent, command, fileAttachment);
          }
          if (agent.provider === 'docker-web') {
            return await callDockerWebAgent(agent, appendAttachmentToTextPrompt(command, fileAttachment));
          }
          return generateDemoResponse(agent, appendAttachmentToTextPrompt(command, fileAttachment));
        })();

        const timeoutPromise = new Promise((_, reject) =>
          setTimeout(() => reject(new Error('Request timed out after 30 seconds.')), 30000)
        );

        try {
          replyText = await Promise.race([executionPromise, timeoutPromise]);
        } catch (err) {
          replyText = `**${agent.name} error:** ${err.message}`;
        }

        const replyObj = {
          id: `reply-${agent.id}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
          sender: agent.id,
          targetAgentId: agent.id,
          mode,
          role: agent.role || agent.provider || 'AI Agent',
          senderName: agent.name,
          avatar: agent.avatar || 'AI',
          color: agent.color || '#3B82F6',
          text: replyText,
          timestamp: new Date().toISOString()
        };

        messages.push(replyObj);
        writeSse('reply', replyObj);
        return replyObj;
      });

      const resolvedReplies = await Promise.all(replyPromises);
      saveJson(MESSAGES_FILE, messages);

      if (useStream) {
        writeSse('done', { success: true, ceoMessage: ceoMsg, replies: resolvedReplies });
        if (!res.writableEnded) res.end();
      } else {
        sendJson(res, 200, {
          success: true,
          ceoMessage: ceoMsg,
          replies: resolvedReplies
        });
      }
    } catch (e) {
      sendJson(res, 500, { error: e.message });
    }
    return;
  }

  // Serve static files safely inside public/
  const publicDir = path.join(__dirname, 'public');
  const relPath = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '');
  const filePath = path.resolve(publicDir, relPath);
  if (!filePath.startsWith(publicDir)) {
    res.writeHead(403, { 'Content-Type': 'text/plain' });
    res.end('Forbidden');
    return;
  }

  const ext = path.extname(filePath).toLowerCase();
  const contentType = MIME_TYPES[ext] || 'application/octet-stream';

  fs.readFile(filePath, (err, content) => {
    if (err) {
      res.writeHead(err.code === 'ENOENT' ? 404 : 500, { 'Content-Type': 'text/plain' });
      res.end(err.code === 'ENOENT' ? '404 Not Found' : `Server Error: ${err.code}`);
      return;
    }
    res.writeHead(200, { 'Content-Type': contentType });
    res.end(content);
  });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Muse Boardroom active on http://0.0.0.0:${PORT}`);
});

async function shutdownGracefully() {
  try {
    await inAppBrowser.closeAll();
  } catch {}
  process.exit(0);
}

process.on('SIGINT', shutdownGracefully);
process.on('SIGTERM', shutdownGracefully);
