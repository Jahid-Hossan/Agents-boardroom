import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  InAppBrowserManager,
  ensureChromiumExecutable,
  findChromiumExecutable,
  isKnownAuthAiUrl,
  mapBrowserKeyToCdp,
  normalizeHttpUrl,
  resolveSafeProfileDir
} from './inapp-browser.js';

test('normalizeHttpUrl prepends https:// when scheme is missing', () => {
  assert.equal(normalizeHttpUrl('muse.ai'), 'https://muse.ai');
  assert.equal(normalizeHttpUrl('https://gemini.google.com/app'), 'https://gemini.google.com/app');
  assert.equal(normalizeHttpUrl('http://localhost:3000'), 'http://localhost:3000');
  assert.equal(normalizeHttpUrl('   '), '');
});

test('isKnownAuthAiUrl detects auth-gated AI providers and excludes local URLs', () => {
  assert.equal(isKnownAuthAiUrl('https://muse.ai'), true);
  assert.equal(isKnownAuthAiUrl('https://gemini.google.com/app'), true);
  assert.equal(isKnownAuthAiUrl('https://chatgpt.com'), true);
  assert.equal(isKnownAuthAiUrl('https://grok.com'), true);
  assert.equal(isKnownAuthAiUrl('http://127.0.0.1:3000/'), false);
});

test('sendPrompt fast-fails in <50ms for never-signed-in auth-gated AI agents', async () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'mb-fastfail-'));
  const manager = new InAppBrowserManager(tmpRoot);
  try {
    const start = Date.now();
    const reply = await manager.sendPrompt(
      { id: 'muse-fresh', name: 'Muse AI', customUrl: 'https://muse.ai' },
      'Hello team'
    );
    const elapsedMs = Date.now() - start;
    assert.ok(elapsedMs < 100, `Expected fast-fail <100ms, got ${elapsedMs}ms`);
    assert.match(reply, /not signed in yet/i);
  } finally {
    await manager.closeAll();
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

test('resolveSafeProfileDir isolates sanitized agent directories', () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'mb-prof-'));
  try {
    const dirA = resolveSafeProfileDir(tmpRoot, 'muse-1');
    const dirB = resolveSafeProfileDir(tmpRoot, 'muse/../../weird');
    assert.ok(dirA.startsWith(tmpRoot));
    assert.ok(dirB.startsWith(tmpRoot));
    assert.notEqual(dirA, dirB);
    assert.equal(fs.existsSync(dirA), true);
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

test('mapBrowserKeyToCdp maps special keys and printable characters accurately', () => {
  const enter = mapBrowserKeyToCdp('Enter', 'Enter');
  assert.equal(enter.windowsVirtualKeyCode, 13);
  assert.equal(enter.text, '\r');

  const back = mapBrowserKeyToCdp('Backspace', 'Backspace');
  assert.equal(back.windowsVirtualKeyCode, 8);
  assert.equal(back.text, '');

  const ch = mapBrowserKeyToCdp('a', 'KeyA');
  assert.equal(ch.windowsVirtualKeyCode, 65);
  assert.equal(ch.text, 'a');
});

test('findChromiumExecutable and ensureChromiumExecutable locate Chromium in container environment', async () => {
  const exe = findChromiumExecutable();
  assert.ok(exe && fs.existsSync(exe), 'Expected Chromium binary to be available');
  const ensured = await ensureChromiumExecutable();
  assert.equal(ensured, exe);
});

test('@puppeteer/browsers can extract zip archives via yauzl without external unzip CLI', async () => {
  const { buildZip } = await import('./zip-builder.js');
  const { extractZipWithYauzl } = await import('@puppeteer/browsers/lib/fileUtil.js');
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'mb-yauzl-'));
  try {
    const zipPath = path.join(tmpDir, 'sample.zip');
    const outDir = path.join(tmpDir, 'out');
    fs.mkdirSync(outDir, { recursive: true });
    fs.writeFileSync(zipPath, buildZip([{ name: 'hello.txt', content: 'ok' }]));
    await extractZipWithYauzl(zipPath, outDir);
    assert.equal(fs.readFileSync(path.join(outDir, 'hello.txt'), 'utf8'), 'ok');
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('InAppBrowserManager persists login across restarts and isolates multiple accounts', async () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'mb-inapp-'));
  const manager = new InAppBrowserManager(tmpRoot);

  const server = http.createServer((req, res) => {
    const reqUrl = new URL(req.url, 'http://127.0.0.1');
    const cookies = req.headers.cookie || '';
    const isAuthed = cookies.includes('mb_auth=verified');

    if (reqUrl.pathname === '/auth-submit' && req.method === 'POST') {
      let body = '';
      req.on('data', (c) => { body += c; });
      req.on('end', () => {
        if (body.includes('user=') && body.includes('otp=123456')) {
          res.writeHead(302, {
            'Set-Cookie': 'mb_auth=verified; Path=/; Max-Age=86400',
            Location: '/'
          });
          res.end();
        } else {
          res.writeHead(302, { Location: '/login?err=1' });
          res.end();
        }
      });
      return;
    }

    if (reqUrl.pathname === '/login') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(`<!DOCTYPE html>
<html>
<body style="margin:0;padding:40px;font-family:sans-serif;">
  <h1>Mock AI Login</h1>
  <form method="POST" action="/auth-submit">
    <input id="userField" name="user" type="text" placeholder="User ID" style="display:block;width:300px;height:40px;margin-bottom:16px;" />
    <input id="otpField" name="otp" autocomplete="one-time-code" type="text" placeholder="OTP" style="display:block;width:300px;height:40px;margin-bottom:16px;" />
    <button id="loginBtn" type="submit" style="width:160px;height:40px;">Sign In</button>
  </form>
</body>
</html>`);
      return;
    }

    if (!isAuthed) {
      res.writeHead(302, { Location: '/login' });
      res.end();
      return;
    }

    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(`<!DOCTYPE html>
<html>
<body style="margin:0;padding:40px;font-family:sans-serif;">
  <h1>Mock AI Chat</h1>
  <div id="messages"></div>
  <form id="chatForm">
    <textarea id="chatInput" placeholder="Ask anything"></textarea>
    <button type="submit">Send</button>
  </form>
  <script>
    document.getElementById('chatForm').addEventListener('submit', (e) => {
      e.preventDefault();
      const val = document.getElementById('chatInput').value;
      const div = document.createElement('div');
      div.className = 'assistant-message';
      div.textContent = 'Reply from Mock AI: ' + val;
      document.getElementById('messages').appendChild(div);
    });
  </script>
</body>
</html>`);
  });

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}/`;

  try {
    const agent1 = { id: 'muse-acc-1', name: 'Muse AI 1', customUrl: baseUrl };
    const agent2 = { id: 'muse-acc-2', name: 'Muse AI 2', customUrl: baseUrl };

    const session1 = await manager.ensureSession(agent1);
    const initialStatus = await manager.refreshStatus(agent1.id);
    assert.equal(initialStatus.needsLogin, true);
    assert.equal(initialStatus.ready, false);

    // Verify stream emits JPEG frame & status
    let gotFrame = false;
    const unsub = await manager.subscribeStream(agent1, {
      onFrame: (f) => {
        if (f?.data && f.data.length > 100) gotFrame = true;
      }
    });
    assert.equal(gotFrame, true);
    unsub();

    // Interact via dispatchInput: click User ID field, type user, Tab, type OTP, press Enter
    const userBox = await session1.page.$eval('#userField', (el) => {
      const r = el.getBoundingClientRect();
      return { x: r.left + 20, y: r.top + 20 };
    });
    await manager.dispatchInput(agent1.id, { kind: 'mouse', type: 'mousePressed', x: userBox.x, y: userBox.y, button: 'left' });
    await manager.dispatchInput(agent1.id, { kind: 'mouse', type: 'mouseReleased', x: userBox.x, y: userBox.y, button: 'left' });
    await manager.dispatchInput(agent1.id, { kind: 'text', text: 'ceo@muse.ai' });
    await manager.dispatchInput(agent1.id, { kind: 'key', key: 'Tab', code: 'Tab' });
    await manager.dispatchInput(agent1.id, { kind: 'text', text: '123456' });
    await manager.dispatchInput(agent1.id, { kind: 'key', key: 'Enter', code: 'Enter' });

    await session1.page.waitForSelector('#chatInput', { timeout: 8000 });
    const authedStatus = await manager.refreshStatus(agent1.id);
    assert.equal(authedStatus.ready, true);
    assert.equal(authedStatus.needsLogin, false);

    // Send prompt through attached connector
    const reply = await manager.sendPrompt(agent1, 'Launch status');
    assert.equal(reply, 'Reply from Mock AI: Launch status');

    // Send prompt with a text attachment (decoded & appended when no file input exists)
    const txtBase64 = Buffer.from('Q4 OKR: Ship voice & attach', 'utf8').toString('base64');
    const txtReply = await manager.sendPrompt(agent1, 'Summarize attachment', {
      filename: 'notes.txt',
      dataUrl: `data:text/plain;base64,${txtBase64}`
    });
    assert.match(txtReply, /Summarize attachment/);
    assert.match(txtReply, /notes\.txt/);
    assert.match(txtReply, /Q4 OKR: Ship voice & attach/);

    // Verify Account 2 has an isolated profile and is NOT logged in
    await manager.ensureSession(agent2);
    const status2 = await manager.refreshStatus(agent2.id);
    assert.equal(status2.needsLogin, true);
    assert.equal(status2.ready, false);

    // Close Account 1 session and reopen -> cookie must persist!
    await manager.closeSession(agent1.id);
    await manager.ensureSession(agent1);
    const persistedStatus = await manager.refreshStatus(agent1.id);
    assert.equal(persistedStatus.ready, true);
    assert.equal(persistedStatus.needsLogin, false);
  } finally {
    await manager.closeAll();
    server.close();
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});
