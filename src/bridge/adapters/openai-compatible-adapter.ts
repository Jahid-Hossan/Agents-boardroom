import {
  AdapterCapabilities,
  BotAdapter,
  BotConfig,
  BotHealthSnapshot,
  BridgeError,
  ProviderId,
  SecretsResolver,
  SendMessageCallbacks,
  SendMessageRequest,
  SendMessageResult,
  SessionInitResult,
} from '../types';

export interface OpenAiCompatibleOptions {
  readonly provider: ProviderId; // 'openai-api' | 'grok-api'
  readonly defaultBaseUrl: string;
  readonly defaultModel: string;
}

/**
 * Provider-agnostic HTTP BotAdapter for OpenAI-compatible Chat Completions APIs
 * (supports OpenAI and xAI Grok API via configurable baseUrl and server-side SecretsResolver).
 */
export class OpenAiCompatibleAdapter implements BotAdapter {
  readonly botId: string;
  readonly provider: ProviderId;
  private activeCount = 0;

  constructor(
    private readonly config: BotConfig,
    private readonly secretsResolver: SecretsResolver,
    private readonly options: OpenAiCompatibleOptions,
  ) {
    this.botId = config.botId;
    this.provider = options.provider;
  }

  getCapabilities(): AdapterCapabilities {
    return {
      authMode: 'api_key',
      supportsStreaming: true,
      supportsConversationResume: false,
      requiresExclusiveSession: false,
      supportsAttachments: false,
      maxConcurrency: 4,
    };
  }

  async initializeSession(): Promise<SessionInitResult> {
    const apiKey = this.resolveApiKey();
    if (!apiKey) {
      return {
        botId: this.botId,
        provider: this.provider,
        status: 'needs_auth',
        ready: false,
        message: `Missing server-side secret "${this.config.secretRef ?? 'API_KEY'}" for ${this.provider}.`,
      };
    }

    return {
      botId: this.botId,
      provider: this.provider,
      status: 'online',
      ready: true,
      message: `${this.provider} API adapter ready.`,
    };
  }

  async getStatus(): Promise<BotHealthSnapshot> {
    const checkedAt = new Date().toISOString();
    if (this.activeCount > 0) {
      return {
        botId: this.botId,
        provider: this.provider,
        status: 'busy',
        reason: `Processing ${this.activeCount} request(s)`,
        checkedAt,
      };
    }
    if (!this.resolveApiKey()) {
      return {
        botId: this.botId,
        provider: this.provider,
        status: 'needs_auth',
        reason: 'API key secret not configured on server',
        checkedAt,
      };
    }
    return {
      botId: this.botId,
      provider: this.provider,
      status: 'online',
      reason: 'API key configured',
      checkedAt,
    };
  }

  async sendMessage(
    request: SendMessageRequest,
    callbacks?: SendMessageCallbacks,
  ): Promise<SendMessageResult> {
    const apiKey = this.resolveApiKey();
    if (!apiKey) {
      throw new BridgeError('NEEDS_AUTH', `API secret missing for bot "${this.botId}".`, {
        retryable: false,
        botId: this.botId,
        provider: this.provider,
      });
    }

    const baseUrl =
      typeof this.config.providerOptions?.baseUrl === 'string'
        ? this.config.providerOptions.baseUrl.replace(/\/+$/, '')
        : this.options.defaultBaseUrl;
    const model =
      typeof this.config.providerOptions?.model === 'string'
        ? this.config.providerOptions.model
        : this.options.defaultModel;

    this.activeCount += 1;
    const startedAt = Date.now();

    try {
      const response = await fetch(`${baseUrl}/chat/completions`, {
        method: 'POST',
        signal: request.signal,
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${apiKey}`,
        },
        body: JSON.stringify({
          model,
          stream: false,
          messages: [{ role: 'user', content: request.prompt }],
        }),
      });

      if (response.status === 401 || response.status === 403) {
        throw new BridgeError('NEEDS_AUTH', `${this.provider} rejected API credentials.`, {
          retryable: false,
          botId: this.botId,
          provider: this.provider,
        });
      }

      if (response.status === 429 || response.status >= 500) {
        throw new BridgeError(
          response.status === 429 ? 'RATE_LIMITED' : 'TRANSIENT_FAILURE',
          `${this.provider} returned HTTP ${response.status}.`,
          { retryable: true, botId: this.botId, provider: this.provider },
        );
      }

      if (!response.ok) {
        throw new BridgeError(
          'PROVIDER_ERROR',
          `${this.provider} request failed with HTTP ${response.status}.`,
          { retryable: false, botId: this.botId, provider: this.provider },
        );
      }

      const payload = (await response.json()) as {
        id?: string;
        choices?: Array<{ message?: { content?: string } }>;
      };
      const text = payload.choices?.[0]?.message?.content?.trim() ?? '';
      if (text) {
        callbacks?.onDelta?.(text, text);
      }

      return {
        text,
        finishReason: 'completed',
        externalThreadRef: {
          conversationId: request.conversationId,
          botId: this.botId,
          provider: this.provider,
          externalThreadId: payload.id,
          updatedAt: new Date().toISOString(),
        },
        usage: { latencyMs: Date.now() - startedAt },
      };
    } finally {
      this.activeCount = Math.max(0, this.activeCount - 1);
    }
  }

  private resolveApiKey(): string | undefined {
    if (!this.config.secretRef) return undefined;
    const value = this.secretsResolver.getSecret(this.config.secretRef)?.trim();
    return value ? value : undefined;
  }
}
