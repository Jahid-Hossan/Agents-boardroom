import { IncomingMessage, ServerResponse } from 'node:http';
import { randomUUID } from 'node:crypto';
import { MultiBotBridge } from '../index';
import { FileBotConfigStore, FileBridgeMessageStore } from '../storage/file-stores';
import { BotConfig, BridgeError, ProviderId } from '../types';

export interface BridgeHttpOptions {
  readonly authToken?: string;
  readonly botConfigStore?: FileBotConfigStore;
  readonly messageStore?: FileBridgeMessageStore;
}

function sendJson(res: ServerResponse, status: number, body: unknown): void {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  });
  res.end(payload);
}

async function readJsonBody<T>(req: IncomingMessage): Promise<T> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) {
    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  }
  const raw = Buffer.concat(chunks).toString('utf8').trim();
  if (!raw) return {} as T;
  return JSON.parse(raw) as T;
}

function isAuthorized(req: IncomingMessage, authToken?: string): boolean {
  if (!authToken || !authToken.trim()) return true;
  const expected = authToken.trim();
  const header = req.headers.authorization;
  if (header && header.startsWith('Bearer ')) {
    return header.slice(7).trim() === expected;
  }
  const url = new URL(req.url || '/', 'http://localhost');
  return url.searchParams.get('token') === expected;
}

/**
 * Mountable HTTP/SSE request handler exposing the full Multi-Bot Bridge API
 * for both the Web Boardroom UI and Android client.
 */
export async function handleBridgeHttpRequest(
  bridge: MultiBotBridge,
  req: IncomingMessage,
  res: ServerResponse,
  options: BridgeHttpOptions = {},
): Promise<boolean> {
  const parsedUrl = new URL(req.url || '/', 'http://localhost');
  const pathname = parsedUrl.pathname;
  if (!pathname.startsWith('/api/bridge')) {
    return false;
  }

  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    });
    res.end();
    return true;
  }

  if (!isAuthorized(req, options.authToken)) {
    sendJson(res, 401, { error: 'Unauthorized bridge request.' });
    return true;
  }

  try {
    // Health & capabilities
    if (req.method === 'GET' && pathname === '/api/bridge/health') {
      const bots = bridge.botRegistry.listBots();
      sendJson(res, 200, {
        ok: true,
        providers: bridge.adapterRegistry.listProviders(),
        botCount: bots.length,
        onlineCount: bots.filter((b) => b.health.status === 'online').length,
        sseSubscribers: bridge.sseHub.subscriberCount,
      });
      return true;
    }

    if (req.method === 'GET' && pathname === '/api/bridge/providers') {
      sendJson(res, 200, { providers: bridge.adapterRegistry.listProviders() });
      return true;
    }

    // Live SSE event stream
    if (req.method === 'GET' && pathname === '/api/bridge/events') {
      res.writeHead(200, {
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-cache, no-transform',
        Connection: 'keep-alive',
        'Access-Control-Allow-Origin': '*',
      });

      const writeEvent = (eventName: string, data: unknown) => {
        res.write(`event: ${eventName}\n`);
        res.write(`data: ${JSON.stringify(data)}\n\n`);
      };

      // Initial bot snapshot immediately upon connection
      writeEvent('bridge:ready', { bots: bridge.botRegistry.listBots() });

      const unsubscribe = bridge.sseHub.subscribe((event) => {
        writeEvent(event.type, event.payload);
      });

      const heartbeat = setInterval(() => {
        res.write(': ping\n\n');
      }, 15_000);

      req.on('close', () => {
        clearInterval(heartbeat);
        unsubscribe();
      });
      return true;
    }

    // List bots
    if (req.method === 'GET' && pathname === '/api/bridge/bots') {
      sendJson(res, 200, { bots: bridge.botRegistry.listBots() });
      return true;
    }

    // Register a new bot
    if (req.method === 'POST' && pathname === '/api/bridge/bots') {
      const body = await readJsonBody<{
        botId?: string;
        displayName?: string;
        provider?: ProviderId;
        enabled?: boolean;
        selectedForBroadcast?: boolean;
        timeoutMs?: number;
        secretRef?: string;
        providerOptions?: Record<string, unknown>;
      }>(req);

      const botId = body.botId?.trim() || `bot-${randomUUID().slice(0, 8)}`;
      const config: BotConfig = {
        botId,
        displayName: body.displayName?.trim() || botId,
        provider: body.provider || 'muse-web',
        enabled: body.enabled ?? true,
        selectedForBroadcast: body.selectedForBroadcast ?? true,
        timeoutMs: body.timeoutMs ?? 60_000,
        secretRef: body.secretRef,
        providerOptions: body.providerOptions,
      };

      const view = bridge.botRegistry.registerBot(config);
      if (options.botConfigStore) {
        const allConfigs = bridge.botRegistry
          .listBots()
          .map((b) => bridge.botRegistry.requireEntry(b.botId).config);
        await options.botConfigStore.saveAll(allConfigs);
      }

      sendJson(res, 201, { bot: view });
      return true;
    }

    // Bot-specific lifecycle endpoints
    const botMatch = pathname.match(/^\/api\/bridge\/bots\/([^/]+)\/(enable|select|init|status)$/);
    if (botMatch) {
      const botId = decodeURIComponent(botMatch[1]);
      const action = botMatch[2];

      if (action === 'enable' && req.method === 'POST') {
        const body = await readJsonBody<{ enabled?: boolean }>(req);
        const bot = await bridge.botRegistry.setEnabled(botId, Boolean(body.enabled));
        sendJson(res, 200, { bot });
        return true;
      }

      if (action === 'select' && req.method === 'POST') {
        const body = await readJsonBody<{ selected?: boolean }>(req);
        const bot = bridge.botRegistry.setSelectedForBroadcast(botId, Boolean(body.selected));
        sendJson(res, 200, { bot });
        return true;
      }

      if (action === 'init' && req.method === 'POST') {
        const init = await bridge.botRegistry.initializeBot(botId);
        sendJson(res, 200, { init });
        return true;
      }

      if (action === 'status' && req.method === 'GET') {
        const health = await bridge.botRegistry.refreshBotStatus(botId);
        sendJson(res, 200, { health });
        return true;
      }
    }

    // Broadcast dispatch: one message -> N independent bot responses
    if (req.method === 'POST' && pathname === '/api/bridge/dispatch') {
      const body = await readJsonBody<{
        conversationId?: string;
        parentMessageId?: string;
        prompt?: string;
        targetBotIds?: string[];
      }>(req);

      if (!body.prompt?.trim()) {
        sendJson(res, 400, { error: 'prompt is required.' });
        return true;
      }

      const conversationId = body.conversationId?.trim() || 'boardroom-main';
      const parentMessageId = body.parentMessageId?.trim() || `msg_user_${randomUUID()}`;

      if (options.messageStore) {
        await options.messageStore.saveMessage({
          id: parentMessageId,
          conversationId,
          role: 'user',
          content: body.prompt.trim(),
          createdAt: new Date().toISOString(),
        });
      }

      const result = await bridge.dispatcher.broadcast({
        conversationId,
        parentMessageId,
        prompt: body.prompt.trim(),
        targetBotIds: body.targetBotIds,
      });

      sendJson(res, 200, result);
      return true;
    }

    // Cancel an active bot task
    const cancelMatch = pathname.match(/^\/api\/bridge\/tasks\/([^/]+)\/cancel$/);
    if (cancelMatch && req.method === 'POST') {
      const taskId = decodeURIComponent(cancelMatch[1]);
      const cancelled = await bridge.dispatcher.cancelTask(taskId);
      sendJson(res, cancelled ? 200 : 404, { cancelled, taskId });
      return true;
    }

    // List persisted bridge messages
    if (req.method === 'GET' && pathname === '/api/bridge/messages') {
      const conversationId = parsedUrl.searchParams.get('conversationId') || undefined;
      const messages = options.messageStore
        ? await options.messageStore.listMessages(conversationId)
        : [];
      sendJson(res, 200, { messages });
      return true;
    }

    sendJson(res, 404, { error: `Unknown bridge route: ${pathname}` });
    return true;
  } catch (error) {
    if (error instanceof BridgeError) {
      const status = error.code === 'INVALID_CONFIG' ? 400 : 500;
      sendJson(res, status, {
        error: error.message,
        code: error.code,
        botId: error.botId,
        provider: error.provider,
      });
      return true;
    }
    sendJson(res, 500, {
      error: error instanceof Error ? error.message : 'Internal bridge error.',
    });
    return true;
  }
}
