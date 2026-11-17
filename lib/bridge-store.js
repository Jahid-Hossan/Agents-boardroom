import crypto from 'node:crypto';

const KNOWN_AI_SITES = [
  { match: 'muse.ai', platform: 'muse', label: 'Muse AI', avatar: 'M', color: '#3B82F6', defaultUrl: 'https://muse.ai' },
  { match: 'meta.ai', platform: 'meta', label: 'Meta AI', avatar: 'M', color: '#06B6D4', defaultUrl: 'https://www.meta.ai' },
  { match: 'gemini.google.com', platform: 'gemini-web', label: 'Gemini', avatar: 'G', color: '#8B5CF6', defaultUrl: 'https://gemini.google.com/app' },
  { match: 'aistudio.google.com', platform: 'aistudio', label: 'AI Studio', avatar: 'A', color: '#6366F1', defaultUrl: 'https://aistudio.google.com' },
  { match: 'chatgpt.com', platform: 'chatgpt', label: 'ChatGPT', avatar: 'C', color: '#10B981', defaultUrl: 'https://chatgpt.com' },
  { match: 'chat.openai.com', platform: 'chatgpt', label: 'ChatGPT', avatar: 'C', color: '#10B981', defaultUrl: 'https://chatgpt.com' },
  { match: 'grok.com', platform: 'grok', label: 'Grok', avatar: 'X', color: '#E2E8F0', defaultUrl: 'https://grok.com' },
  { match: 'x.com/i/grok', platform: 'grok', label: 'Grok', avatar: 'X', color: '#E2E8F0', defaultUrl: 'https://grok.com' },
  { match: 'claude.ai', platform: 'claude', label: 'Claude', avatar: 'C', color: '#F59E0B', defaultUrl: 'https://claude.ai' },
  { match: 'perplexity.ai', platform: 'perplexity', label: 'Perplexity', avatar: 'P', color: '#14B8A6', defaultUrl: 'https://www.perplexity.ai' },
  { match: 'copilot.microsoft.com', platform: 'copilot', label: 'Copilot', avatar: 'M', color: '#38BDF8', defaultUrl: 'https://copilot.microsoft.com' },
  { match: 'poe.com', platform: 'poe', label: 'Poe', avatar: 'P', color: '#EC4899', defaultUrl: 'https://poe.com' }
];

export function detectSiteInfo(url = '') {
  const lower = String(url).toLowerCase();
  for (const site of KNOWN_AI_SITES) {
    if (lower.includes(site.match)) {
      return {
        platform: site.platform,
        label: site.label,
        avatar: site.avatar,
        color: site.color,
        isKnownAi: true
      };
    }
  }
  let host = 'Web Tab';
  try {
    host = new URL(url).hostname.replace(/^www\./, '') || 'Web Tab';
  } catch {
    // ignore invalid URL
  }
  return {
    platform: 'generic',
    label: host,
    avatar: 'W',
    color: '#64748B',
    isKnownAi: false
  };
}

export function generateBridgeToken() {
  return 'mb_' + crypto.randomBytes(16).toString('hex');
}

export class BridgeStore {
  constructor(initialToken = null, ttlMs = 90000) {
    this.token = initialToken || generateBridgeToken();
    this.ttlMs = ttlMs;
    this.clients = new Map(); // clientId -> { clientId, profileName, lastHeartbeatAt, browserInfo, tabs }
    this.jobs = new Map(); // jobId -> job record
    this.pollWaiters = new Set(); // Set<{ clientId: string, wake: () => void }>
  }

  setToken(token) {
    if (token && typeof token === 'string') {
      this.token = token;
    }
  }

  rotateToken() {
    this.token = generateBridgeToken();
    this.clients.clear();
    this.notifyPollWaiters();
    return this.token;
  }

  verifyToken(candidate) {
    return Boolean(candidate && candidate === this.token);
  }

  touchClient(clientId = 'default-profile', profileName = '', now = Date.now()) {
    const id = String(clientId || 'default-profile').trim() || 'default-profile';
    const existing = this.clients.get(id);
    if (existing) {
      existing.lastHeartbeatAt = now;
      if (profileName && String(profileName).trim()) {
        existing.profileName = String(profileName).trim().slice(0, 60);
      }
      return existing;
    }
    const record = {
      clientId: id,
      profileName: (profileName && String(profileName).trim().slice(0, 60)) || `Chrome Profile ${this.clients.size + 1}`,
      lastHeartbeatAt: now,
      browserInfo: null,
      tabs: []
    };
    this.clients.set(id, record);
    return record;
  }

  recordHeartbeat({ clientId = 'default-profile', profileName = '', tabs = [], browserInfo = null } = {}, now = Date.now()) {
    const record = this.touchClient(clientId, profileName, now);
    if (browserInfo) record.browserInfo = browserInfo;
    record.tabs = Array.isArray(tabs)
      ? tabs.map((t) => {
          const info = detectSiteInfo(t.url || '');
          return {
            clientId: record.clientId,
            profileName: record.profileName,
            tabId: t.tabId ?? t.id,
            title: String(t.title || info.label).slice(0, 120),
            url: String(t.url || ''),
            active: Boolean(t.active),
            platform: info.platform,
            label: info.label,
            avatar: info.avatar,
            color: info.color,
            isKnownAi: info.isKnownAi
          };
        })
      : [];
    return record;
  }

  getActiveClients(now = Date.now()) {
    const list = [];
    for (const client of this.clients.values()) {
      if (client.lastHeartbeatAt > 0 && (now - client.lastHeartbeatAt) <= this.ttlMs) {
        list.push(client);
      }
    }
    return list;
  }

  isClientConnected(clientId, now = Date.now()) {
    if (!clientId) return this.isConnected(now);
    const client = this.clients.get(String(clientId));
    return Boolean(client && client.lastHeartbeatAt > 0 && (now - client.lastHeartbeatAt) <= this.ttlMs);
  }

  isConnected(now = Date.now()) {
    return this.getActiveClients(now).length > 0;
  }

  getStatus(now = Date.now()) {
    const active = this.getActiveClients(now);
    let latestHeartbeat = 0;
    const tabs = [];
    const clients = active.map((c) => {
      if (c.lastHeartbeatAt > latestHeartbeat) latestHeartbeat = c.lastHeartbeatAt;
      tabs.push(...c.tabs);
      return {
        clientId: c.clientId,
        profileName: c.profileName,
        lastSeenAt: new Date(c.lastHeartbeatAt).toISOString(),
        tabCount: c.tabs.length,
        browserInfo: c.browserInfo
      };
    });

    return {
      connected: active.length > 0,
      lastSeenAt: latestHeartbeat ? new Date(latestHeartbeat).toISOString() : null,
      browserInfo: active[0]?.browserInfo || null,
      clients,
      tabs,
      pendingJobs: Array.from(this.jobs.values()).filter((j) => j.status === 'pending' || j.status === 'claimed').length
    };
  }

  notifyPollWaiters(targetClientId = null) {
    for (const waiter of this.pollWaiters) {
      if (!targetClientId || !waiter.clientId || waiter.clientId === targetClientId) {
        waiter.wake();
      }
    }
  }

  enqueueJob({
    action = 'prompt',
    agentId = '',
    agentName = 'Agent',
    targetClientId = null,
    targetTabId = null,
    targetUrl = '',
    prompt = '',
    timeoutMs = 45000
  }) {
    const cleanClientId = targetClientId ? String(targetClientId).trim() : null;
    const jobId = `job_${Date.now()}_${crypto.randomBytes(4).toString('hex')}`;
    const promise = new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        const job = this.jobs.get(jobId);
        if (job) {
          this.jobs.delete(jobId);
          reject(new Error(`Desktop browser bridge timed out after ${Math.round(timeoutMs / 1000)}s waiting for ${agentName || 'tab'} response.`));
        }
      }, timeoutMs);

      this.jobs.set(jobId, {
        id: jobId,
        action,
        agentId,
        agentName: agentName || 'Agent',
        targetClientId: cleanClientId || null,
        targetTabId: targetTabId !== undefined && targetTabId !== null && targetTabId !== '' ? Number(targetTabId) : null,
        targetUrl: targetUrl || '',
        prompt,
        status: 'pending',
        createdAt: Date.now(),
        resolve,
        reject,
        timer
      });
    });

    this.notifyPollWaiters(cleanClientId);
    return promise;
  }

  claimPendingJobs(limit = 10, clientId = null) {
    const claimed = [];
    const reqClient = clientId ? String(clientId).trim() : null;
    for (const job of this.jobs.values()) {
      if (job.status !== 'pending') continue;
      if (job.targetClientId && reqClient && job.targetClientId !== reqClient) continue;
      if (job.targetClientId && !reqClient) continue;

      job.status = 'claimed';
      claimed.push({
        jobId: job.id,
        action: job.action || 'prompt',
        agentId: job.agentId,
        agentName: job.agentName,
        targetClientId: job.targetClientId,
        targetTabId: job.targetTabId,
        targetUrl: job.targetUrl,
        prompt: job.prompt
      });
      if (claimed.length >= limit) break;
    }
    return claimed;
  }

  async waitForJobs(waitMs = 0, limit = 10, signal = null, clientId = null) {
    if (signal?.aborted) return [];
    const immediate = this.claimPendingJobs(limit, clientId);
    if (immediate.length > 0 || waitMs <= 0) {
      return immediate;
    }

    await new Promise((resolve) => {
      let done = false;
      const waiter = {
        clientId: clientId ? String(clientId).trim() : null,
        wake: () => {
          if (done) return;
          done = true;
          clearTimeout(timer);
          this.pollWaiters.delete(waiter);
          if (signal) signal.removeEventListener('abort', waiter.wake);
          resolve();
        }
      };
      const timer = setTimeout(waiter.wake, Math.min(waitMs, 25000));
      this.pollWaiters.add(waiter);
      if (signal) signal.addEventListener('abort', waiter.wake, { once: true });
    });

    if (signal?.aborted) return [];
    return this.claimPendingJobs(limit, clientId);
  }

  completeJob(jobId, payload = {}) {
    const job = this.jobs.get(jobId);
    if (!job) return false;
    clearTimeout(job.timer);
    this.jobs.delete(jobId);
    const {
      success = true,
      reply = '',
      error = '',
      tabId = null,
      tabUrl = '',
      tabTitle = '',
      clientId = null,
      profileName = '',
      ready = false,
      needsLogin = false
    } = payload;
    if (success) {
      job.resolve({ reply, tabId, tabUrl, tabTitle, clientId, profileName, ready, needsLogin });
    } else {
      job.reject(new Error(error || 'Browser tab execution failed'));
    }
    return true;
  }
}
