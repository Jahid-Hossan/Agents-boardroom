/**
 * Core provider-agnostic contracts for the Multi-Bot Messaging Bridge.
 */

export type BotStatus =
  | 'online'
  | 'busy'
  | 'offline'
  | 'needs_auth'
  | 'error';

export type KnownProviderId =
  | 'muse-web'
  | 'chatgpt-web'
  | 'gemini-api'
  | 'openai-api'
  | 'grok-api'
  | 'grok-web';

export type ProviderId = KnownProviderId | (string & {});

export type AuthMode = 'browser_profile' | 'api_key' | 'oauth';

export type BridgeErrorCode =
  | 'NEEDS_AUTH'
  | 'CAPTCHA_REQUIRED'
  | 'RATE_LIMITED'
  | 'TIMEOUT'
  | 'ABORTED'
  | 'OFFLINE'
  | 'TRANSIENT_FAILURE'
  | 'INVALID_CONFIG'
  | 'PROVIDER_ERROR';

export class BridgeError extends Error {
  readonly code: BridgeErrorCode;
  readonly retryable: boolean;
  readonly botId?: string;
  readonly provider?: ProviderId;

  constructor(
    code: BridgeErrorCode,
    message: string,
    options?: {
      retryable?: boolean;
      botId?: string;
      provider?: ProviderId;
      cause?: unknown;
    },
  ) {
    super(message, options?.cause !== undefined ? { cause: options.cause } : undefined);
    this.name = 'BridgeError';
    this.code = code;
    this.retryable = options?.retryable ?? (code === 'RATE_LIMITED' || code === 'TRANSIENT_FAILURE');
    this.botId = options?.botId;
    this.provider = options?.provider;
  }
}

export interface AdapterCapabilities {
  readonly authMode: AuthMode;
  readonly supportsStreaming: boolean;
  readonly supportsConversationResume: boolean;
  readonly requiresExclusiveSession: boolean;
  readonly supportsAttachments: boolean;
  readonly maxConcurrency: number;
}

export interface BotConfig {
  readonly botId: string;
  readonly displayName: string;
  readonly provider: ProviderId;
  readonly enabled: boolean;
  readonly selectedForBroadcast: boolean;
  readonly timeoutMs: number;
  /** Server-side isolated directory for browser-based bots (never exposed to clients). */
  readonly profileDir?: string;
  /** Reference key for SecretsResolver (e.g. env var name), never the raw secret itself. */
  readonly secretRef?: string;
  /** Non-secret provider-specific configuration (model, baseUrl, headless, selectors). */
  readonly providerOptions?: Readonly<Record<string, unknown>>;
}

/** Public-safe view returned to Web and Android clients (strips profileDir and secretRef). */
export interface PublicBotView {
  readonly botId: string;
  readonly displayName: string;
  readonly provider: ProviderId;
  readonly enabled: boolean;
  readonly selectedForBroadcast: boolean;
  readonly capabilities: AdapterCapabilities;
  readonly health: BotHealthSnapshot;
}

export interface BotHealthSnapshot {
  readonly botId: string;
  readonly provider: ProviderId;
  readonly status: BotStatus;
  readonly reason?: string;
  readonly activeTaskId?: string;
  readonly checkedAt: string;
}

export interface SessionInitResult {
  readonly botId: string;
  readonly provider: ProviderId;
  readonly status: BotStatus;
  readonly ready: boolean;
  readonly message: string;
  readonly authHint?: string;
}

export interface ExternalThreadRef {
  readonly conversationId: string;
  readonly botId: string;
  readonly provider: ProviderId;
  readonly externalThreadId?: string;
  readonly sessionUrl?: string;
  readonly metadata?: Readonly<Record<string, unknown>>;
  readonly updatedAt: string;
}

export interface BridgeAttachment {
  readonly id: string;
  readonly name: string;
  readonly mimeType: string;
  readonly localPath: string;
}

export interface SendMessageRequest {
  readonly taskId: string;
  readonly dispatchBatchId: string;
  readonly parentMessageId: string;
  readonly conversationId: string;
  readonly prompt: string;
  readonly externalThreadRef: ExternalThreadRef | null;
  readonly attachments?: readonly BridgeAttachment[];
  readonly timeoutMs: number;
  readonly signal: AbortSignal;
}

export interface SendMessageCallbacks {
  readonly onDelta?: (chunkText: string, fullTextSoFar: string) => void;
  readonly onThreadRefUpdate?: (updatedRef: ExternalThreadRef) => void;
}

export interface SendMessageResult {
  readonly text: string;
  readonly finishReason: 'completed' | 'cancelled';
  readonly externalThreadRef: ExternalThreadRef | null;
  readonly usage?: {
    readonly latencyMs: number;
    readonly inputTokens?: number;
    readonly outputTokens?: number;
  };
}

/**
 * Provider-agnostic adapter contract enforced across all browser-session and API-based bots.
 * Core bridge components (BotRegistry, MessageDispatcher, SseHub) interact exclusively
 * through this interface.
 */
export interface BotAdapter {
  readonly botId: string;
  readonly provider: ProviderId;

  getCapabilities(): AdapterCapabilities;

  /**
   * Initializes the isolated provider session (e.g. launches/attaches this bot's dedicated
   * browser profile or validates server-side API credentials) without bypassing auth/CAPTCHA walls.
   */
  initializeSession(): Promise<SessionInitResult>;

  /**
   * Checks current availability and authentication state of this bot.
   */
  getStatus(): Promise<BotHealthSnapshot>;

  /**
   * Delivers one prompt to this bot's independent session and returns its attributed response.
   */
  sendMessage(
    request: SendMessageRequest,
    callbacks?: SendMessageCallbacks,
  ): Promise<SendMessageResult>;

  /**
   * Optional cancellation hook for aborting an in-flight generation cleanly.
   */
  abortTask?(taskId: string): Promise<void>;

  /**
   * Optional cleanup hook called when a bot is disabled or the server shuts down.
   */
  dispose?(): Promise<void>;
}

export type DeliveryTaskStatus =
  | 'queued'
  | 'running'
  | 'completed'
  | 'failed'
  | 'cancelled'
  | 'needs_auth';

export interface DeliveryTaskRecord {
  readonly taskId: string;
  readonly dispatchBatchId: string;
  readonly conversationId: string;
  readonly parentMessageId: string;
  readonly botId: string;
  readonly provider: ProviderId;
  readonly status: DeliveryTaskStatus;
  readonly attempts: number;
  readonly errorCode?: BridgeErrorCode;
  readonly errorMessage?: string;
  readonly responseMessageId?: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface BoardroomMessageRecord {
  readonly id: string;
  readonly conversationId: string;
  readonly role: 'user' | 'assistant' | 'system';
  readonly content: string;
  readonly authorBotId?: string;
  readonly authorName?: string;
  readonly provider?: ProviderId;
  readonly parentMessageId?: string;
  readonly deliveryTaskId?: string;
  readonly dispatchBatchId?: string;
  readonly createdAt: string;
}

export interface DispatchBroadcastRequest {
  readonly conversationId: string;
  readonly parentMessageId: string;
  readonly prompt: string;
  readonly targetBotIds?: readonly string[];
  readonly attachments?: readonly BridgeAttachment[];
}

export interface DispatchBroadcastResult {
  readonly dispatchBatchId: string;
  readonly conversationId: string;
  readonly parentMessageId: string;
  readonly tasks: readonly DeliveryTaskRecord[];
  readonly responses: readonly BoardroomMessageRecord[];
}

export type BridgeSseEvent =
  | {
      readonly type: 'bot:status';
      readonly payload: BotHealthSnapshot;
    }
  | {
      readonly type: 'dispatch:started';
      readonly payload: {
        readonly dispatchBatchId: string;
        readonly conversationId: string;
        readonly parentMessageId: string;
        readonly taskIds: readonly string[];
      };
    }
  | {
      readonly type: 'task:queued' | 'task:running' | 'task:completed' | 'task:failed' | 'task:cancelled';
      readonly payload: DeliveryTaskRecord;
    }
  | {
      readonly type: 'task:delta';
      readonly payload: {
        readonly taskId: string;
        readonly dispatchBatchId: string;
        readonly conversationId: string;
        readonly parentMessageId: string;
        readonly botId: string;
        readonly chunkText: string;
        readonly fullTextSoFar: string;
      };
    }
  | {
      readonly type: 'message:created';
      readonly payload: BoardroomMessageRecord;
    };

export interface SecretsResolver {
  getSecret(secretRef: string): string | undefined;
}

export interface AdapterFactoryContext {
  readonly secretsResolver: SecretsResolver;
  readonly dataDir: string;
}

export type BotAdapterFactory = (
  config: BotConfig,
  context: AdapterFactoryContext,
) => BotAdapter;
