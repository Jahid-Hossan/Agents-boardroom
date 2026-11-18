import { AdapterRegistry } from './adapter-registry';
import { BrowserSessionDriver } from './adapters/base-browser-adapter';
import { ChatGptWebAdapter } from './adapters/chatgpt-web-adapter';
import { GeminiApiAdapter } from './adapters/gemini-api-adapter';
import { MuseBrowserAdapter } from './adapters/muse-browser-adapter';
import { OpenAiCompatibleAdapter } from './adapters/openai-compatible-adapter';
import { BotRegistry } from './bot-registry';
import { ConversationMapStore, ConversationMapper } from './conversation-mapper';
import {
  BridgeMessageStore,
  MessageDispatcher,
  MessageDispatcherOptions,
} from './message-dispatcher';
import { SessionLockManager } from './session-lock-manager';
import { SseHub } from './sse-hub';
import { SecretsResolver } from './types';

export * from './types';
export * from './adapter-registry';
export * from './bot-registry';
export * from './conversation-mapper';
export * from './message-dispatcher';
export * from './session-lock-manager';
export * from './sse-hub';
export * from './adapters/base-browser-adapter';
export * from './adapters/muse-browser-adapter';
export * from './adapters/chatgpt-web-adapter';
export * from './adapters/openai-compatible-adapter';
export * from './adapters/gemini-api-adapter';
export * from './drivers/puppeteer-driver';
export * from './storage/file-stores';
export * from './http/bridge-router';
export * from './server';

export interface MultiBotBridgeOptions {
  readonly dataDir: string;
  readonly maxConcurrentBrowsers?: number;
  readonly browserDriver?: BrowserSessionDriver;
  readonly secretsResolver?: SecretsResolver;
  readonly conversationStore?: ConversationMapStore;
  readonly messageStore?: BridgeMessageStore;
  readonly dispatcherOptions?: MessageDispatcherOptions;
}

export interface MultiBotBridge {
  readonly adapterRegistry: AdapterRegistry;
  readonly botRegistry: BotRegistry;
  readonly lockManager: SessionLockManager;
  readonly conversationMapper: ConversationMapper;
  readonly sseHub: SseHub;
  readonly dispatcher: MessageDispatcher;
}

const envSecretsResolver: SecretsResolver = {
  getSecret(secretRef: string): string | undefined {
    return process.env[secretRef];
  },
};

export function createDefaultAdapterRegistry(
  browserDriver?: BrowserSessionDriver,
): AdapterRegistry {
  const registry = new AdapterRegistry();

  registry.register('muse-web', (config) => new MuseBrowserAdapter(config, browserDriver));
  registry.register('chatgpt-web', (config) => new ChatGptWebAdapter(config, browserDriver));
  registry.register(
    'gemini-api',
    (config, ctx) => new GeminiApiAdapter(config, ctx.secretsResolver),
  );
  registry.register(
    'openai-api',
    (config, ctx) =>
      new OpenAiCompatibleAdapter(config, ctx.secretsResolver, {
        provider: 'openai-api',
        defaultBaseUrl: 'https://api.openai.com/v1',
        defaultModel: 'gpt-4.1-mini',
      }),
  );
  registry.register(
    'grok-api',
    (config, ctx) =>
      new OpenAiCompatibleAdapter(config, ctx.secretsResolver, {
        provider: 'grok-api',
        defaultBaseUrl: 'https://api.x.ai/v1',
        defaultModel: 'grok-3-mini',
      }),
  );

  return registry;
}

export function createMultiBotBridge(options: MultiBotBridgeOptions): MultiBotBridge {
  const adapterRegistry = createDefaultAdapterRegistry(options.browserDriver);
  const lockManager = new SessionLockManager(options.maxConcurrentBrowsers ?? 4);
  const sseHub = new SseHub();
  const conversationMapper = new ConversationMapper(options.conversationStore);
  const botRegistry = new BotRegistry(adapterRegistry, lockManager, sseHub, {
    dataDir: options.dataDir,
    secretsResolver: options.secretsResolver ?? envSecretsResolver,
  });
  const dispatcher = new MessageDispatcher(
    botRegistry,
    lockManager,
    conversationMapper,
    sseHub,
    options.messageStore,
    options.dispatcherOptions,
  );

  return {
    adapterRegistry,
    botRegistry,
    lockManager,
    conversationMapper,
    sseHub,
    dispatcher,
  };
}
