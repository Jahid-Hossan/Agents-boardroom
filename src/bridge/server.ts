import * as http from 'node:http';
import * as path from 'node:path';
import { createMultiBotBridge } from './index';
import { PuppeteerSessionDriver } from './drivers/puppeteer-driver';
import { handleBridgeHttpRequest } from './http/bridge-router';
import {
  FileBotConfigStore,
  FileBridgeMessageStore,
  FileConversationMapStore,
} from './storage/file-stores';

export interface BridgeServerOptions {
  readonly port?: number;
  readonly host?: string;
  readonly dataDir?: string;
  readonly authToken?: string;
  readonly maxConcurrentBrowsers?: number;
}

export async function startBridgeServer(options: BridgeServerOptions = {}) {
  const dataDir = path.resolve(options.dataDir || process.env.BRIDGE_DATA_DIR || './data');
  const port = options.port ?? Number(process.env.BRIDGE_PORT || 8787);
  const host = options.host ?? process.env.BRIDGE_HOST ?? '0.0.0.0';
  const authToken = options.authToken ?? process.env.BRIDGE_AUTH_TOKEN;

  const botConfigStore = new FileBotConfigStore(dataDir);
  const conversationStore = new FileConversationMapStore(dataDir);
  const messageStore = new FileBridgeMessageStore(dataDir);

  const bridge = createMultiBotBridge({
    dataDir,
    maxConcurrentBrowsers: options.maxConcurrentBrowsers ?? Number(process.env.MAX_CONCURRENT_BROWSERS || 4),
    browserDriver: new PuppeteerSessionDriver(),
    conversationStore,
    messageStore,
  });

  await bridge.conversationMapper.init();

  const savedBots = await botConfigStore.loadAll();
  for (const bot of savedBots) {
    bridge.botRegistry.registerBot(bot);
  }

  const server = http.createServer((req, res) => {
    void handleBridgeHttpRequest(bridge, req, res, {
      authToken,
      botConfigStore,
      messageStore,
    }).then((handled) => {
      if (!handled) {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'Not found' }));
      }
    });
  });

  await new Promise<void>((resolve) => {
    server.listen(port, host, () => resolve());
  });

  const shutdown = async () => {
    await bridge.botRegistry.disposeAll();
    server.close();
  };

  return { server, bridge, shutdown };
}
