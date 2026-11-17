import test from 'node:test';
import assert from 'node:assert/strict';
import { buildZip, crc32 } from './zip-builder.js';
import { BridgeStore, detectSiteInfo } from './bridge-store.js';
import { generateExtensionFiles } from './bridge-extension-files.js';

test('crc32 matches standard IEEE 802.3 checksum', () => {
  assert.equal(crc32(Buffer.from('123456789', 'utf8')), 0xcbf43926);
});

test('buildZip creates valid PKZIP signatures for extension bundle', () => {
  const files = generateExtensionFiles({
    serverUrl: 'https://example.run.app',
    bridgeToken: 'mb_testtoken123'
  });
  const zipBuffer = buildZip(files);

  assert.equal(zipBuffer.readUInt32LE(0), 0x04034b50); // Local file header signature
  const eocdOffset = zipBuffer.length - 22;
  assert.equal(zipBuffer.readUInt32LE(eocdOffset), 0x06054b50); // EOCD signature
  assert.equal(zipBuffer.readUInt16LE(eocdOffset + 8), files.length);
  assert.ok(zipBuffer.includes(Buffer.from('https://example.run.app', 'utf8')));
  assert.ok(zipBuffer.includes(Buffer.from('mb_testtoken123', 'utf8')));

  const bg = files.find((f) => f.name.endsWith('background.js'));
  const popup = files.find((f) => f.name.endsWith('popup.js'));
  assert.doesNotThrow(() => new Function(bg.content));
  assert.doesNotThrow(() => new Function(popup.content));
});

test('detectSiteInfo classifies known AI platforms and generic URLs', () => {
  assert.equal(detectSiteInfo('https://muse.ai/chat').platform, 'muse');
  assert.equal(detectSiteInfo('https://muse.ai/chat').isKnownAi, true);
  assert.equal(detectSiteInfo('https://gemini.google.com/app').platform, 'gemini-web');
  assert.equal(detectSiteInfo('https://chatgpt.com/c/123').platform, 'chatgpt');
  assert.equal(detectSiteInfo('https://claude.ai/new').platform, 'claude');
  assert.equal(detectSiteInfo('https://grok.com/').platform, 'grok');
  assert.equal(detectSiteInfo('https://x.com/i/grok').platform, 'grok');
  assert.equal(detectSiteInfo('https://example.com/chat').isKnownAi, false);
  assert.equal(detectSiteInfo('https://example.com/chat').label, 'example.com');
});

test('BridgeStore manages heartbeat TTL, tab list, job claiming, and completion', async () => {
  const store = new BridgeStore('mb_unit_test', 5000);
  assert.equal(store.verifyToken('mb_unit_test'), true);
  assert.equal(store.verifyToken('wrong'), false);
  assert.equal(store.isConnected(10000), false);

  store.recordHeartbeat({
    tabs: [
      { tabId: 42, title: 'Muse AI Chat', url: 'https://muse.ai/chat', active: true },
      { tabId: 43, title: 'Gemini', url: 'https://gemini.google.com/app', active: false }
    ]
  }, 10000);

  assert.equal(store.isConnected(12000), true);
  assert.equal(store.isConnected(16000), false);
  assert.equal(store.getStatus(12000).tabs.length, 2);
  assert.equal(store.getStatus(12000).tabs[0].platform, 'muse');

  const jobPromise = store.enqueueJob({
    agentId: 'muse-1',
    agentName: 'Muse AI',
    targetTabId: 42,
    targetUrl: 'https://muse.ai',
    prompt: 'Summarize Q4 plan',
    timeoutMs: 5000
  });

  const claimed = store.claimPendingJobs();
  assert.equal(claimed.length, 1);
  assert.equal(claimed[0].targetTabId, 42);
  assert.equal(claimed[0].prompt, 'Summarize Q4 plan');

  // Second claim returns empty since job is already claimed
  assert.equal(store.claimPendingJobs().length, 0);

  const ok = store.completeJob(claimed[0].jobId, {
    success: true,
    reply: 'Q4 plan looks strong.',
    tabId: 42
  });
  assert.equal(ok, true);

  const result = await jobPromise;
  assert.equal(result.reply, 'Q4 plan looks strong.');
});

test('BridgeStore waitForJobs wakes immediately on enqueueJob and times out cleanly when idle', async () => {
  const store = new BridgeStore('mb_longpoll_test', 5000);

  const empty = await store.waitForJobs(25);
  assert.equal(empty.length, 0);

  const waitPromise = store.waitForJobs(2000);
  let jobPromise;
  setTimeout(() => {
    jobPromise = store.enqueueJob({
      agentId: 'grok',
      agentName: 'Grok',
      targetUrl: 'https://grok.com',
      prompt: 'Ping Grok',
      timeoutMs: 5000
    });
  }, 15);

  const jobs = await waitPromise;
  assert.equal(jobs.length, 1);
  assert.equal(jobs[0].agentId, 'grok');
  assert.equal(jobs[0].prompt, 'Ping Grok');

  store.completeJob(jobs[0].jobId, { success: true, reply: 'Pong from Grok' });
  const res = await jobPromise;
  assert.equal(res.reply, 'Pong from Grok');
});

test('BridgeStore isolates multiple Chrome Profiles and routes jobs by targetClientId', async () => {
  const store = new BridgeStore('mb_multiprofile_test', 90000);

  store.recordHeartbeat({
    clientId: 'prof_personal',
    profileName: 'Personal Chrome',
    tabs: [
      { tabId: 10, title: 'Muse AI (Personal)', url: 'https://muse.ai', active: true },
      { tabId: 11, title: 'Gemini (Personal)', url: 'https://gemini.google.com/app', active: false }
    ]
  }, 10000);

  store.recordHeartbeat({
    clientId: 'prof_work',
    profileName: 'Work Chrome',
    tabs: [
      { tabId: 20, title: 'Muse AI (Work)', url: 'https://muse.ai', active: true },
      { tabId: 21, title: 'Gemini (Work)', url: 'https://gemini.google.com/app', active: false }
    ]
  }, 10500);

  // Still connected after 65s (> 60s MV3 alarm period, < 90s TTL)
  assert.equal(store.isConnected(75000), true);
  assert.equal(store.isClientConnected('prof_personal', 75000), true);
  assert.equal(store.isClientConnected('prof_work', 75000), true);

  const status = store.getStatus(11000);
  assert.equal(status.clients.length, 2);
  assert.equal(status.tabs.length, 4);
  assert.equal(status.tabs.filter((t) => t.clientId === 'prof_personal').length, 2);
  assert.equal(status.tabs.filter((t) => t.clientId === 'prof_work').length, 2);

  // Enqueue open_tab targeted strictly at prof_work
  const openPromise = store.enqueueJob({
    action: 'open_tab',
    agentId: 'muse-work',
    agentName: 'Muse AI 2',
    targetClientId: 'prof_work',
    targetUrl: 'https://muse.ai',
    timeoutMs: 5000
  });

  // prof_personal must NOT claim a job targeted at prof_work
  const personalJobs = await store.waitForJobs(20, 10, null, 'prof_personal');
  assert.equal(personalJobs.length, 0);

  // prof_work claims it immediately
  const workJobs = await store.waitForJobs(20, 10, null, 'prof_work');
  assert.equal(workJobs.length, 1);
  assert.equal(workJobs[0].action, 'open_tab');
  assert.equal(workJobs[0].targetClientId, 'prof_work');

  store.completeJob(workJobs[0].jobId, {
    success: true,
    clientId: 'prof_work',
    profileName: 'Work Chrome',
    tabId: 22,
    tabUrl: 'https://muse.ai/login',
    ready: false,
    needsLogin: true
  });

  const openRes = await openPromise;
  assert.equal(openRes.tabId, 22);
  assert.equal(openRes.needsLogin, true);
  assert.equal(openRes.profileName, 'Work Chrome');
});

test('BridgeStore rejects timed out jobs cleanly', async () => {
  const store = new BridgeStore('mb_timeout_test', 5000);
  const jobPromise = store.enqueueJob({
    agentId: 'gem-1',
    agentName: 'Gemini Web',
    prompt: 'Hello',
    timeoutMs: 30
  });

  await assert.rejects(jobPromise, /timed out/i);
});
