import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import puppeteer from 'puppeteer-core';
import {
  Browser,
  detectBrowserPlatform,
  install as installBrowser,
  resolveBuildId
} from '@puppeteer/browsers';

const DEFAULT_USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

export const VIEWPORT_WIDTH = 1200;
export const VIEWPORT_HEIGHT = 760;

export function normalizeHttpUrl(raw = '') {
  const s = String(raw || '').trim();
  if (!s) return '';
  return /^https?:\/\//i.test(s) ? s : `https://${s}`;
}

export function isKnownAuthAiUrl(raw = '') {
  try {
    const host = new URL(normalizeHttpUrl(raw)).hostname.replace(/^www\./i, '').toLowerCase();
    return [
      'muse.ai',
      'gemini.google.com',
      'chatgpt.com',
      'chat.openai.com',
      'grok.com',
      'x.com',
      'claude.ai',
      'meta.ai'
    ].some((domain) => host === domain || host.endsWith(`.${domain}`));
  } catch {
    return false;
  }
}

let cachedChromiumExecutable = null;
let downloadingBrowserPromise = null;

function searchExecutableInDir(rootDir, maxDepth = 4) {
  if (!rootDir || !fs.existsSync(rootDir)) return null;
  const targetNames =
    process.platform === 'win32'
      ? new Set(['chrome-headless-shell.exe', 'chrome.exe', 'chromium.exe', 'headless_shell.exe'])
      : new Set(['chrome-headless-shell', 'chrome', 'chromium', 'headless_shell']);

  const queue = [{ dir: rootDir, depth: 0 }];
  while (queue.length > 0) {
    const { dir, depth } = queue.shift();
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      const fullPath = path.join(dir, entry.name);
      if (entry.isFile() || entry.isSymbolicLink()) {
        if (targetNames.has(entry.name)) {
          try {
            const st = fs.statSync(fullPath);
            if (st.isFile()) return fullPath;
          } catch {}
        }
      } else if (entry.isDirectory() && depth < maxDepth) {
        queue.push({ dir: fullPath, depth: depth + 1 });
      }
    }
  }
  return null;
}

function findExecutableInPath(binNames) {
  const pathEnv = process.env.PATH || '';
  const dirs = pathEnv.split(path.delimiter).filter(Boolean);
  for (const dir of dirs) {
    for (const bin of binNames) {
      const candidate = path.join(dir, bin);
      try {
        if (fs.existsSync(candidate) && fs.statSync(candidate).isFile()) {
          return candidate;
        }
      } catch {}
    }
  }
  return null;
}

export function findChromiumExecutable(profilesRoot = '') {
  if (cachedChromiumExecutable && fs.existsSync(cachedChromiumExecutable)) {
    return cachedChromiumExecutable;
  }

  const envCandidates = [
    process.env.PUPPETEER_EXECUTABLE_PATH,
    process.env.CHROME_BIN,
    process.env.CHROMIUM_PATH,
    process.env.CHROME_PATH
  ].filter(Boolean);
  for (const p of envCandidates) {
    if (fs.existsSync(p)) {
      cachedChromiumExecutable = p;
      return p;
    }
  }

  const candidates = [
    '/ms-playwright/chrome-headless-shell-linux64/chrome-headless-shell',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
    '/usr/lib/chromium/chromium',
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/opt/google/chrome/chrome',
    '/snap/bin/chromium',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Chromium.app/Contents/MacOS/Chromium',
    '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
    path.join(os.homedir(), 'AppData', 'Local', 'Google', 'Chrome', 'Application', 'chrome.exe')
  ];
  for (const p of candidates) {
    if (p && fs.existsSync(p)) {
      cachedChromiumExecutable = p;
      return p;
    }
  }

  const pathFound = findExecutableInPath(
    process.platform === 'win32'
      ? ['chrome.exe', 'msedge.exe', 'chromium.exe']
      : ['chromium', 'chromium-browser', 'google-chrome', 'google-chrome-stable', 'chrome']
  );
  if (pathFound) {
    cachedChromiumExecutable = pathFound;
    return pathFound;
  }

  const cacheDirs = [
    process.env.PLAYWRIGHT_BROWSERS_PATH,
    process.env.PUPPETEER_CACHE_DIR,
    '/ms-playwright',
    path.join(os.homedir(), '.cache', 'ms-playwright'),
    path.join(os.homedir(), '.cache', 'puppeteer'),
    profilesRoot ? path.join(profilesRoot, '.browser-cache') : '',
    path.join(os.tmpdir(), 'mb-chromium')
  ].filter(Boolean);

  for (const dir of cacheDirs) {
    const found = searchExecutableInDir(dir, 5);
    if (found) {
      cachedChromiumExecutable = found;
      return found;
    }
  }

  return null;
}

function pickWritableCacheDir(profilesRoot = '') {
  const candidates = [
    process.env.PUPPETEER_CACHE_DIR,
    profilesRoot ? path.join(profilesRoot, '.browser-cache') : '',
    path.join(os.tmpdir(), 'mb-chromium')
  ].filter(Boolean);

  for (const dir of candidates) {
    try {
      fs.mkdirSync(dir, { recursive: true });
      fs.accessSync(dir, fs.constants.W_OK);
      return dir;
    } catch {}
  }
  return path.join(os.tmpdir(), 'mb-chromium');
}

export async function ensureChromiumExecutable(profilesRoot = '') {
  const existing = findChromiumExecutable(profilesRoot);
  if (existing) return existing;

  if (!downloadingBrowserPromise) {
    downloadingBrowserPromise = (async () => {
      const platform = detectBrowserPlatform();
      if (!platform) {
        throw new Error(`Unsupported OS platform for built-in Chromium (${process.platform}/${process.arch}).`);
      }
      const cacheDir = pickWritableCacheDir(profilesRoot);
      let buildId = '157.0.8103.78';
      try {
        buildId = await resolveBuildId(Browser.CHROMEHEADLESSSHELL, platform, 'stable');
      } catch {}

      const installed = await installBrowser({
        browser: Browser.CHROMEHEADLESSSHELL,
        buildId,
        cacheDir,
        platform
      });

      const exePath = installed?.executablePath || findChromiumExecutable(profilesRoot);
      if (!exePath || !fs.existsSync(exePath)) {
        throw new Error('Downloaded Chromium archive did not contain an executable binary.');
      }
      cachedChromiumExecutable = exePath;
      return exePath;
    })().finally(() => {
      downloadingBrowserPromise = null;
    });
  }

  return await downloadingBrowserPromise;
}

export function isDownloadingChromium() {
  return Boolean(downloadingBrowserPromise);
}

export function resolveSafeProfileDir(profilesRoot, agentId) {
  const safeId = String(agentId || 'default').replace(/[^a-zA-Z0-9_-]/g, '_') || 'default';
  const dir = path.resolve(profilesRoot, `agent_${safeId}`);
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

export function hasSavedProfileData(profilesRoot, agentId) {
  const safeId = String(agentId || 'default').replace(/[^a-zA-Z0-9_-]/g, '_') || 'default';
  const dir = path.resolve(profilesRoot, `agent_${safeId}`);
  return fs.existsSync(path.join(dir, '.mb-session-ready'));
}

export function mapBrowserKeyToCdp(key = '', code = '') {
  const special = {
    Enter: { keyCode: 13, text: '\r' },
    Backspace: { keyCode: 8 },
    Tab: { keyCode: 9 },
    Escape: { keyCode: 27 },
    Delete: { keyCode: 46 },
    ArrowLeft: { keyCode: 37 },
    ArrowUp: { keyCode: 38 },
    ArrowRight: { keyCode: 39 },
    ArrowDown: { keyCode: 40 },
    Home: { keyCode: 36 },
    End: { keyCode: 35 },
    PageUp: { keyCode: 33 },
    PageDown: { keyCode: 34 },
    ' ': { keyCode: 32, text: ' ' }
  };
  if (special[key]) {
    return {
      key,
      code: code || key,
      windowsVirtualKeyCode: special[key].keyCode,
      nativeVirtualKeyCode: special[key].keyCode,
      text: special[key].text || ''
    };
  }
  const upper = key.length === 1 ? key.toUpperCase().charCodeAt(0) : 0;
  return {
    key,
    code: code || key,
    windowsVirtualKeyCode: upper,
    nativeVirtualKeyCode: upper,
    text: key.length === 1 ? key : ''
  };
}

// Executed inside the in-app Chromium page to detect whether login is needed or chat is ready
async function inspectPageStateInBrowser() {
  const host = location.hostname.toLowerCase();
  function isVisible(el) {
    if (!el) return false;
    const rect = el.getBoundingClientRect();
    const style = getComputedStyle(el);
    return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
  }
  const inputSelectors = [
    'rich-textarea .ql-editor[contenteditable="true"]',
    '#prompt-textarea',
    'div.ProseMirror[contenteditable="true"]',
    'textarea:not([disabled]):not([readonly])',
    '[contenteditable="true"][role="textbox"]',
    '[contenteditable="true"]',
    'div[role="textbox"]'
  ];
  const hasChatInput = inputSelectors.some((sel) => Array.from(document.querySelectorAll(sel)).some(isVisible));
  const hasPasswordOrOtp = Array.from(
    document.querySelectorAll('input[type="password"], input[autocomplete="one-time-code"], input[name*="otp" i]')
  ).some(isVisible);
  const hasLoginBtn = Array.from(document.querySelectorAll('button, a')).some((el) => {
    const t = (el.innerText || '').trim().toLowerCase();
    return isVisible(el) && (t === 'log in' || t === 'sign in' || t === 'sign in to gemini' || t === 'continue with google');
  });
  const hrefLower = location.href.toLowerCase();
  const isLoginUrl =
    hrefLower.includes('login') ||
    hrefLower.includes('signin') ||
    hrefLower.includes('accounts.google.com') ||
    hrefLower.includes('auth');

  return {
    ready: hasChatInput && !hasPasswordOrOtp,
    needsLogin: hasPasswordOrOtp || (!hasChatInput && (hasLoginBtn || isLoginUrl)),
    host,
    url: location.href,
    title: document.title || location.hostname
  };
}

// Executed inside the in-app Chromium page by the attached connector to send a prompt and collect the AI reply
async function runPromptInBrowserPage(promptText) {
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

  const hasLoginWallNow = () => {
    const hasPassOrOtp = Array.from(
      document.querySelectorAll('input[type="password"], input[autocomplete="one-time-code"], input[name*="otp" i]')
    ).some(isVisible);
    if (hasPassOrOtp) return true;
    const hrefLower = location.href.toLowerCase();
    if (hrefLower.includes('accounts.google.com') || hrefLower.includes('/login') || hrefLower.includes('/signin')) {
      return true;
    }
    return Array.from(document.querySelectorAll('[role="dialog"]')).some((dlg) => {
      if (!isVisible(dlg)) return false;
      const t = (dlg.innerText || '').toLowerCase();
      return t.includes('log in') || t.includes('sign in') || t.includes('create an account');
    });
  };

  if (hasLoginWallNow()) {
    return {
      success: false,
      needsLogin: true,
      error: `Not signed in yet on ${location.hostname}. Click "Browser" on this agent to log in inside the app.`
    };
  }

  const inputEl = findInputElement();
  if (!inputEl) {
    return {
      success: false,
      needsLogin: true,
      error: `Not signed in yet on ${location.hostname}. Click "Browser" on this agent to log in inside the app.`
    };
  }

  const beforeTexts = getResponseTexts();
  const beforeCount = beforeTexts.length;
  const beforeLast = beforeCount > 0 ? beforeTexts[beforeCount - 1] : '';

  setInputValue(inputEl, promptText);
  await sleep(50);
  clickSendButton(inputEl);

  let latestText = '';
  let stableRounds = 0;
  const maxWaitMs = 25000;
  const start = Date.now();

  while (Date.now() - start < maxWaitMs) {
    await sleep(180);
    const current = getResponseTexts();
    if (current.length === 0) {
      if (Date.now() - start > 3500 && hasLoginWallNow()) {
        return {
          success: false,
          needsLogin: true,
          error: `${location.hostname} requires sign-in. Click "Browser" on this agent to log in.`
        };
      }
      continue;
    }
    const candidate = current[current.length - 1];
    const hasNew = current.length > beforeCount || (candidate && candidate !== beforeLast && candidate !== promptText);
    if (!hasNew) {
      if (Date.now() - start > 3500 && hasLoginWallNow()) {
        return {
          success: false,
          needsLogin: true,
          error: `${location.hostname} requires sign-in. Click "Browser" on this agent to log in.`
        };
      }
      continue;
    }

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
  return { success: false, error: `Timed out waiting for a reply from ${location.hostname}. Verify the browser session is signed in.` };
}

function isBrowserConnected(browser) {
  if (!browser) return false;
  return typeof browser.isConnected === 'function' ? browser.isConnected() : Boolean(browser.connected);
}

export class InAppBrowserManager {
  constructor(profilesRoot) {
    this.profilesRoot = profilesRoot;
    fs.mkdirSync(this.profilesRoot, { recursive: true });
    this.sessions = new Map(); // agentId -> SessionRecord
  }

  async warmupBrowser() {
    return await ensureChromiumExecutable(this.profilesRoot);
  }

  async ensureSession(agent, { targetUrl = '', forceNavigate = false } = {}) {
    const agentId = String(agent?.id || 'default');
    const desiredUrl = normalizeHttpUrl(targetUrl || agent?.customUrl || 'https://muse.ai');
    let session = this.sessions.get(agentId);

    if (session && isBrowserConnected(session.browser) && session.page && !session.page.isClosed()) {
      if (forceNavigate && desiredUrl) {
        await session.page.goto(desiredUrl, { waitUntil: 'domcontentloaded', timeout: 18000 }).catch(() => {});
        await this.refreshStatus(agentId);
      }
      return session;
    }

    // Deduplicate concurrent launches for the same agent
    if (session?.launchingPromise) {
      return await session.launchingPromise;
    }

    const launchingPromise = (async () => {
      let executablePath;
      try {
        executablePath = await ensureChromiumExecutable(this.profilesRoot);
      } catch (dlErr) {
        throw new Error(
          `Built-in Chromium could not be provisioned on this cloud host (${dlErr.message}). You can still connect via the Desktop Chrome Extension Bridge or API Key in Manage Agents.`
        );
      }

      const userDataDir = resolveSafeProfileDir(this.profilesRoot, agentId);
      try {
        const lockfile = path.join(userDataDir, 'SingletonLock');
        if (fs.existsSync(lockfile)) fs.unlinkSync(lockfile);
      } catch {}

      const isHeadlessShell = executablePath.includes('headless-shell') || executablePath.includes('headless_shell');
      let browser;
      try {
        browser = await puppeteer.launch({
          executablePath,
          headless: isHeadlessShell ? 'shell' : true,
          userDataDir,
          protocolTimeout: 30000,
          defaultViewport: { width: VIEWPORT_WIDTH, height: VIEWPORT_HEIGHT },
          args: [
            '--no-sandbox',
            '--disable-setuid-sandbox',
            '--disable-dev-shm-usage',
            '--disable-gpu',
            '--disable-blink-features=AutomationControlled',
            '--no-first-run',
            '--window-size=1200,760'
          ]
        });
      } catch (launchErr) {
        const msg = String(launchErr?.message || launchErr);
        if (/shared libraries|cannot open shared object|libnss|libatk|libgbm/i.test(msg)) {
          throw new Error(
            'This cloud host is missing Chromium OS shared libraries. Deploy using the included Dockerfile, or connect your accounts via the Desktop Chrome Extension Bridge or API Keys.'
          );
        }
        throw launchErr;
      }

      const pages = await browser.pages();
      const page = pages.length > 0 ? pages[0] : await browser.newPage();
      await page.setUserAgent(DEFAULT_USER_AGENT);
      await page.setViewport({ width: VIEWPORT_WIDTH, height: VIEWPORT_HEIGHT });
      await page.evaluateOnNewDocument(() => {
        Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
      });

      const cdp = await page.createCDPSession();

      const record = {
        agentId,
        browser,
        page,
        cdp,
        userDataDir,
        url: desiredUrl,
        title: agent?.name || 'Browser',
        ready: false,
        needsLogin: true,
        screencastActive: false,
        subscribers: new Set(),
        lastFrame: null,
        lastStatusAt: 0,
        launchingPromise: null
      };

      cdp.on('Page.screencastFrame', async (event) => {
        try {
          await cdp.send('Page.screencastFrameAck', { sessionId: event.sessionId });
        } catch {}
        record.lastFrame = {
          data: event.data,
          width: event.metadata?.deviceWidth || VIEWPORT_WIDTH,
          height: event.metadata?.deviceHeight || VIEWPORT_HEIGHT
        };
        for (const sub of record.subscribers) {
          sub.onFrame(record.lastFrame);
        }
      });

      page.on('framenavigated', (frame) => {
        if (frame === page.mainFrame()) {
          record.url = frame.url();
          this.refreshStatus(agentId).catch(() => {});
        }
      });

      page.on('load', () => {
        this.refreshStatus(agentId).catch(() => {});
      });

      browser.on('disconnected', () => {
        for (const sub of record.subscribers) {
          sub.onClose?.();
        }
        record.subscribers.clear();
        this.sessions.delete(agentId);
      });

      this.sessions.set(agentId, record);

      if (desiredUrl) {
        await page.goto(desiredUrl, { waitUntil: 'domcontentloaded', timeout: 18000 }).catch(() => {});
      }
      await this.refreshStatus(agentId);
      return record;
    })();

    this.sessions.set(agentId, { launchingPromise, subscribers: new Set() });
    try {
      return await launchingPromise;
    } catch (err) {
      this.sessions.delete(agentId);
      throw err;
    }
  }

  async refreshStatus(agentId) {
    const session = this.sessions.get(String(agentId));
    if (!session || !session.page || session.page.isClosed()) return null;
    try {
      const state = await session.page.evaluate(inspectPageStateInBrowser);
      session.url = state.url || session.url;
      session.title = state.title || session.title;
      session.ready = Boolean(state.ready);
      session.needsLogin = Boolean(state.needsLogin);
      session.lastStatusAt = Date.now();
      if (session.userDataDir) {
        const markerPath = path.join(session.userDataDir, '.mb-session-ready');
        try {
          if (session.ready && !session.needsLogin) {
            fs.writeFileSync(markerPath, new Date().toISOString(), 'utf8');
          } else if (session.needsLogin && !session.ready && fs.existsSync(markerPath)) {
            fs.unlinkSync(markerPath);
          }
        } catch {}
      }
      const status = this.getSessionStatus(agentId);
      for (const sub of session.subscribers) {
        sub.onStatus?.(status);
      }
      return status;
    } catch {
      return this.getSessionStatus(agentId);
    }
  }

  getSessionStatus(agentId) {
    const id = String(agentId);
    const session = this.sessions.get(id);
    const open = Boolean(session && isBrowserConnected(session.browser) && session.page && !session.page.isClosed());
    return {
      agentId: id,
      open,
      attached: open,
      hasSavedProfile: hasSavedProfileData(this.profilesRoot, id),
      url: session?.url || '',
      title: session?.title || '',
      ready: open ? Boolean(session.ready) : false,
      needsLogin: open ? Boolean(session.needsLogin) : false
    };
  }

  getAllStatuses(accounts = []) {
    const map = {};
    for (const acc of accounts) {
      map[acc.id] = this.getSessionStatus(acc.id);
    }
    return map;
  }

  async subscribeStream(agent, subscriber) {
    const session = await this.ensureSession(agent);
    session.subscribers.add(subscriber);

    if (session.lastFrame) {
      subscriber.onFrame(session.lastFrame);
    } else {
      try {
        const b64 = await session.page.screenshot({ type: 'jpeg', quality: 75, encoding: 'base64' });
        session.lastFrame = { data: b64, width: VIEWPORT_WIDTH, height: VIEWPORT_HEIGHT };
        subscriber.onFrame(session.lastFrame);
      } catch {}
    }

    subscriber.onStatus?.(this.getSessionStatus(agent.id));

    if (!session.screencastActive) {
      session.screencastActive = true;
      try {
        await session.cdp.send('Page.startScreencast', {
          format: 'jpeg',
          quality: 72,
          maxWidth: VIEWPORT_WIDTH,
          maxHeight: VIEWPORT_HEIGHT,
          everyNthFrame: 1
        });
      } catch {
        session.screencastActive = false;
      }
    }

    return () => {
      session.subscribers.delete(subscriber);
      if (session.subscribers.size === 0 && session.screencastActive) {
        session.screencastActive = false;
        session.cdp.send('Page.stopScreencast').catch(() => {});
      }
    };
  }

  async pushSnapshotFrame(agentId) {
    const session = this.sessions.get(String(agentId));
    if (!session || !session.page || session.page.isClosed()) return;
    try {
      const b64 = await session.page.screenshot({ type: 'jpeg', quality: 75, encoding: 'base64' });
      session.lastFrame = { data: b64, width: VIEWPORT_WIDTH, height: VIEWPORT_HEIGHT };
      for (const sub of session.subscribers) {
        sub.onFrame(session.lastFrame);
      }
    } catch {}
  }

  async dispatchInput(agentId, payload = {}) {
    const session = this.sessions.get(String(agentId));
    if (!session || !session.cdp || session.page.isClosed()) {
      throw new Error('Browser session is not open');
    }

    const { kind } = payload;
    if (kind === 'mouse') {
      const x = Math.max(0, Math.min(VIEWPORT_WIDTH, Number(payload.x) || 0));
      const y = Math.max(0, Math.min(VIEWPORT_HEIGHT, Number(payload.y) || 0));
      const type = payload.type || 'mousePressed';
      const button = payload.button || 'left';
      const clickCount = type === 'mouseMoved' ? 0 : 1;
      await session.cdp.send('Input.dispatchMouseEvent', {
        type,
        x,
        y,
        button: type === 'mouseMoved' ? 'none' : button,
        clickCount
      });
    } else if (kind === 'wheel') {
      const x = Math.max(0, Math.min(VIEWPORT_WIDTH, Number(payload.x) || VIEWPORT_WIDTH / 2));
      const y = Math.max(0, Math.min(VIEWPORT_HEIGHT, Number(payload.y) || VIEWPORT_HEIGHT / 2));
      await session.cdp.send('Input.dispatchMouseEvent', {
        type: 'mouseWheel',
        x,
        y,
        deltaX: Number(payload.deltaX) || 0,
        deltaY: Number(payload.deltaY) || 0
      });
    } else if (kind === 'key') {
      const mapped = mapBrowserKeyToCdp(payload.key || '', payload.code || '');
      const isChar = mapped.text && mapped.key !== 'Enter';
      if (isChar && !payload.ctrlKey && !payload.metaKey) {
        await session.cdp.send('Input.insertText', { text: mapped.text });
      } else {
        await session.cdp.send('Input.dispatchKeyEvent', {
          type: 'rawKeyDown',
          key: mapped.key,
          code: mapped.code,
          windowsVirtualKeyCode: mapped.windowsVirtualKeyCode,
          nativeVirtualKeyCode: mapped.nativeVirtualKeyCode
        });
        if (mapped.text) {
          await session.cdp.send('Input.dispatchKeyEvent', {
            type: 'char',
            text: mapped.text,
            unmodifiedText: mapped.text
          });
        }
        await session.cdp.send('Input.dispatchKeyEvent', {
          type: 'keyUp',
          key: mapped.key,
          code: mapped.code,
          windowsVirtualKeyCode: mapped.windowsVirtualKeyCode,
          nativeVirtualKeyCode: mapped.nativeVirtualKeyCode
        });
      }
    } else if (kind === 'text') {
      const text = String(payload.text || '');
      if (text) {
        await session.cdp.send('Input.insertText', { text });
      }
    }

    if (!session.screencastActive && (kind !== 'mouse' || payload.type !== 'mouseMoved')) {
      this.pushSnapshotFrame(agentId).catch(() => {});
    }
    if (kind !== 'mouse' || payload.type !== 'mouseMoved') {
      clearTimeout(session.statusTimer);
      session.statusTimer = setTimeout(() => this.refreshStatus(agentId).catch(() => {}), 300);
    }
  }

  async navigateAction(agent, { action = 'goto', url = '' } = {}) {
    const session = await this.ensureSession(agent);
    if (action === 'goto' && url) {
      await session.page.goto(normalizeHttpUrl(url), { waitUntil: 'domcontentloaded', timeout: 18000 }).catch(() => {});
    } else if (action === 'back') {
      await session.page.goBack({ waitUntil: 'domcontentloaded', timeout: 10000 }).catch(() => {});
    } else if (action === 'reload') {
      await session.page.reload({ waitUntil: 'domcontentloaded', timeout: 15000 }).catch(() => {});
    } else if (action === 'close') {
      await this.closeSession(agent.id);
      return this.getSessionStatus(agent.id);
    }
    this.pushSnapshotFrame(agent.id).catch(() => {});
    return await this.refreshStatus(agent.id);
  }

  async sendPrompt(agent, promptText, fileAttachment = null) {
    const status = this.getSessionStatus(agent.id);
    if (!status.open && !status.hasSavedProfile && isKnownAuthAiUrl(agent.customUrl)) {
      return `**${agent.name} is not signed in yet.** Click **Browser** on **${agent.name}** to sign in inside the app once—after that your session stays connected.`;
    }

    const wasOpen = status.open;
    const session = await this.ensureSession(agent);
    if (!wasOpen) {
      await new Promise((r) => setTimeout(r, 150));
    }

    let effectivePrompt = String(promptText || '');
    let tempAttachPath = null;
    if (fileAttachment?.dataUrl) {
      const m = String(fileAttachment.dataUrl).match(/^data:([^;]+);base64,(.+)$/);
      if (m) {
        const mimeType = m[1].toLowerCase();
        const buf = Buffer.from(m[2], 'base64');
        const safeFileName = path.basename(fileAttachment.filename || 'attachment').replace(/[^a-zA-Z0-9._-]/g, '_') || 'attachment';
        const fileInput = await session.page.$('input[type="file"]').catch(() => null);
        if (fileInput) {
          try {
            tempAttachPath = path.join(os.tmpdir(), `mb-attach-${Date.now()}-${safeFileName}`);
            fs.writeFileSync(tempAttachPath, buf);
            await fileInput.uploadFile(tempAttachPath);
            await new Promise((r) => setTimeout(r, 350));
          } catch {}
        } else if (
          mimeType.startsWith('text/') ||
          mimeType === 'application/json' ||
          /\.(txt|md|csv|json|js|ts|py|html|css|log)$/i.test(safeFileName)
        ) {
          const decoded = buf.toString('utf8').slice(0, 12000);
          effectivePrompt = `${effectivePrompt}\n\n[Attached file: ${safeFileName}]\n${decoded}`;
        } else {
          effectivePrompt = `${effectivePrompt}\n\n[Attached file: ${safeFileName} (${mimeType})]`;
        }
      }
    }

    let outcome;
    try {
      outcome = await session.page.evaluate(runPromptInBrowserPage, effectivePrompt);
    } finally {
      if (tempAttachPath) {
        try { fs.unlinkSync(tempAttachPath); } catch {}
      }
    }

    this.refreshStatus(agent.id).catch(() => {});
    if (session.subscribers.size > 0) {
      this.pushSnapshotFrame(agent.id).catch(() => {});
    }

    if (outcome?.success) {
      return outcome.reply;
    }
    return `**${agent.name} needs login in the In-App Browser.**\n\n${outcome?.error || 'Open Browser on this agent, sign in once with your credentials/OTP, and your session will stay attached.'}`;
  }

  async closeSession(agentId) {
    const id = String(agentId);
    const session = this.sessions.get(id);
    if (!session) return;
    this.sessions.delete(id);
    try {
      for (const sub of session.subscribers) {
        sub.onClose?.();
      }
      session.subscribers.clear();
      if (session.screencastActive && session.cdp) {
        await Promise.race([
          session.cdp.send('Page.stopScreencast').catch(() => {}),
          new Promise((r) => setTimeout(r, 500))
        ]);
      }
      if (session.browser) {
        await Promise.race([
          session.browser.close().catch(() => {}),
          new Promise((r) => setTimeout(r, 3000))
        ]);
        const proc = session.browser.process?.();
        if (proc && !proc.killed) {
          try { proc.kill('SIGKILL'); } catch {}
        }
      }
    } catch {}
  }

  async closeAll() {
    const ids = Array.from(this.sessions.keys());
    await Promise.all(ids.map((id) => this.closeSession(id)));
  }
}
