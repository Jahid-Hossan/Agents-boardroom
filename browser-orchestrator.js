import puppeteer from 'puppeteer-core';
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

export function findBrowserExecutable() {
  if (process.env.PUPPETEER_EXECUTABLE_PATH && fs.existsSync(process.env.PUPPETEER_EXECUTABLE_PATH)) {
    return process.env.PUPPETEER_EXECUTABLE_PATH;
  }
  const linuxPaths = [
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable'
  ];
  for (const p of linuxPaths) {
    if (fs.existsSync(p)) return p;
  }
  const winPaths = [
    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
    "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"
  ];
  for (const p of winPaths) {
    if (fs.existsSync(p)) return p;
  }
  return null;
}

function getPortForAccount(accountId) {
  let hash = 0;
  const str = String(accountId || 'muse-1');
  for (let i = 0; i < str.length; i++) {
    hash = (hash * 31 + str.charCodeAt(i)) & 0xFFFF;
  }
  return 9222 + (Math.abs(hash) % 500); // Always valid port between 9222 and 9721
}

function resolveProfileDir(accountId) {
  const str = String(accountId || 'muse-1');
  const m = str.match(/(?:profile|muse)[-_]?([1-4])/i);
  const folderName = m ? `profile${m[1]}` : `profile_${str.replace(/[^a-zA-Z0-9_-]/g, '')}`;

  const candidates = [
    path.join(__dirname, 'profiles', folderName),
    path.join("C:\\Users\\Jahid\\Desktop\\muse\\profiles", folderName)
  ];

  for (const dir of candidates) {
    if (fs.existsSync(dir)) return dir;
  }

  const fallback = candidates[0];
  fs.mkdirSync(fallback, { recursive: true });
  return fallback;
}

const activeBrowsers = {};

async function connectViaCdp(cdpEndpoint) {
  const target = cdpEndpoint.trim();
  if (target.startsWith('ws://') || target.startsWith('wss://')) {
    return await puppeteer.connect({ browserWSEndpoint: target, defaultViewport: null });
  }
  return await puppeteer.connect({ browserURL: target, defaultViewport: null });
}

export async function launchAccountBrowser(accountId, headless = true, customCdpUrl = '') {
  if (activeBrowsers[accountId]) {
    try {
      const b = activeBrowsers[accountId];
      if (typeof b.isConnected === 'function' ? b.isConnected() : Boolean(b.connected)) {
        return b;
      }
    } catch (e) {}
    delete activeBrowsers[accountId];
  }

  // 0a. If a Remote CDP / WebSocket URL is configured on the account, connect there first
  const remoteCdp = (customCdpUrl || '').trim();
  if (remoteCdp) {
    const remoteBrowser = await connectViaCdp(remoteCdp);
    console.log(`[Browser] Connected to remote CDP bridge (${remoteCdp}) for ${accountId}`);
    remoteBrowser.on('disconnected', () => { delete activeBrowsers[accountId]; });
    activeBrowsers[accountId] = remoteBrowser;
    return remoteBrowser;
  }

  // 0b. Attempt to connect to user's primary live Chrome on port 9222
  try {
    const mainBrowser = await puppeteer.connect({ browserURL: 'http://127.0.0.1:9222', defaultViewport: null });
    console.log(`[Browser] Connected to live Chrome bridge on port 9222 for ${accountId}`);
    mainBrowser.on('disconnected', () => { delete activeBrowsers[accountId]; });
    activeBrowsers[accountId] = mainBrowser;
    return mainBrowser;
  } catch (e) {
    // Port 9222 not open
  }

  const port = getPortForAccount(accountId);

  // 1. Attempt to connect to account-specific port via CDP
  try {
    const browser = await puppeteer.connect({ browserURL: `http://127.0.0.1:${port}`, defaultViewport: null });
    browser.on('disconnected', () => { delete activeBrowsers[accountId]; });
    activeBrowsers[accountId] = browser;
    console.log(`[Browser] Connected to active Chrome on port ${port} for ${accountId}`);
    return browser;
  } catch (e) {
    // Not running or cannot connect yet
  }

  const executablePath = findBrowserExecutable();
  if (!executablePath) {
    return null;
  }

  const profileDir = resolveProfileDir(accountId);
  try {
    const lockfile = path.join(profileDir, 'lockfile');
    if (fs.existsSync(lockfile)) fs.unlinkSync(lockfile);
  } catch (e) {}

  // On headless Linux servers (no X11 DISPLAY), launching headed Chrome crashes immediately
  const useHeadless = headless || (process.platform === 'linux' && !process.env.DISPLAY);

  console.log(`[Browser] Launching account ${accountId} on port ${port} (headless=${useHeadless}) with profile dir: ${profileDir}`);

  const browser = await puppeteer.launch({
    executablePath,
    headless: useHeadless,
    userDataDir: profileDir,
    defaultViewport: null,
    timeout: 10000,
    args: [
      `--remote-debugging-port=${port}`,
      '--no-sandbox',
      '--disable-setuid-sandbox',
      '--window-size=1200,800',
      '--disable-dev-shm-usage',
      '--disable-gpu',
      '--no-first-run'
    ]
  });

  browser.on('disconnected', () => {
    delete activeBrowsers[accountId];
  });

  activeBrowsers[accountId] = browser;
  return browser;
}

export async function queryLiveMuseAccount(account, promptText) {
  const accountId = account.id || 'muse-1';

  const browser = await launchAccountBrowser(accountId, false, account.cdpUrl || '');
  if (!browser) {
    return null;
  }

  console.log(`[Browser Engine] Sending live directive to ${account.name} (${accountId}): "${promptText}"`);
  const pages = await browser.pages();

  const targetUrl = account.customUrl && account.customUrl.trim() ? account.customUrl.trim() : 'https://muse.ai';
  let hostname = 'muse.ai';
  try { hostname = new URL(targetUrl).hostname; } catch (e) {}

  // Check if any open tab already has target hostname open
  let page = pages.find(p => p.url().includes(hostname));
  if (!page) {
    page = pages.length > 0 ? pages[0] : await browser.newPage();
    if (!page.url().includes(hostname)) {
      await page.goto(targetUrl, { waitUntil: 'domcontentloaded', timeout: 10000 }).catch(() => {});
    }
  }

  try {
    await page.bringToFront();
  } catch (e) {}

  // Combined selector avoids waiting sequentially across missing selectors
  const combinedSelector = [
    'textarea',
    '[contenteditable="true"]',
    'div[role="textbox"]',
    'input[type="text"]:not([type="hidden"])',
    '[placeholder*="Ask"]',
    '[placeholder*="Message"]',
    '[placeholder*="Chat"]'
  ].join(', ');

  let inputEl = null;
  try {
    await page.waitForSelector(combinedSelector, { timeout: 3000 });
    inputEl = await page.$(combinedSelector);
  } catch (e) {}

  if (!inputEl) {
    const isLoggedOut = await page.evaluate(() => {
      return Array.from(document.querySelectorAll('button, a')).some(el => {
        const t = (el.innerText || '').trim().toLowerCase();
        return t === 'log in' || t === 'sign in';
      });
    });

    if (isLoggedOut) {
      return `⚠️ **Please Log In to Muse:**\n\nThe browser is open at \`${targetUrl}\`. Please click **"Log in"** in that window to sign into your Muse account.\n\nOnce logged in, your session is saved and commands will process automatically.`;
    }
    return `⚠️ **Chat input not detected at \`${page.url()}\`:** Please ensure you are logged into Muse AI and on an active chat page in the connected browser.`;
  }

  await inputEl.click();
  await inputEl.type(promptText, { delay: 10 });
  await page.keyboard.press('Enter');
  await new Promise(r => setTimeout(r, 2500));

  // Grab latest output specifically from chat elements, ignoring footer/copyright
  const reply = await page.evaluate((sentPrompt) => {
    const candidates = document.querySelectorAll(
      '[class*="message"]:not(footer *):not([class*="footer"] *), ' +
      '[class*="bubble"], ' +
      '[class*="response"]:not(footer *), ' +
      '[data-testid*="message"], ' +
      '[role="log"] div, ' +
      'article p'
    );
    for (let i = candidates.length - 1; i >= 0; i--) {
      const t = (candidates[i].innerText || '').trim();
      if (
        t &&
        t !== sentPrompt &&
        !t.includes('© Meta') &&
        !t.includes('Terms') &&
        !t.includes('Privacy') &&
        !t.includes('Cookie')
      ) {
        return t;
      }
    }
    return null;
  }, promptText);

  return reply || `Live command "${promptText}" dispatched to ${account.name} (Active in session).`;
}
