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

/**
 * Gemini API BotAdapter implementing the common BotAdapter interface.
 * Reads API credentials strictly on the server via SecretsResolver.
 */
export class GeminiApiAdapter implements BotAdapter {
  readonly botId: string;
  readonly provider: ProviderId = 'gemini-api';
  private activeCount = 0;

  constructor(
    private readonly config: BotConfig,
    private readonly secretsResolver: SecretsResolver,
  ) {
    this.botId = config.botId;
  }

  getCapabilities(): AdapterCapabilities {
    return {
      authMode: 'api_key',
      supportsStreaming: false,
      supportsConversationResume: false,
      requiresExclusiveSession: false,
      supportsAttachments: false,
      maxConcurrency: 4,
    };
  }

  async initializeSession(): Promise<SessionInitResult> {
    if (!this.resolveApiKey()) {
      return {
        botId: this.botId,
        provider: this.provider,
        status: 'needs_auth',
        ready: false,
        message: `Missing server-side secret "${this.config.secretRef ?? 'GEMINI_API_KEY'}".`,
      };
    }
    return {
      botId: this.botId,
      provider: this.provider,
      status: 'online',
      ready: true,
      message: 'Gemini API adapter ready.',
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
        reason: 'Gemini API secret not configured on server',
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
      throw new BridgeError('NEEDS_AUTH', `Gemini API secret missing for bot "${this.botId}".`, {
        retryable: false,
        botId: this.botId,
        provider: this.provider,
      });
    }

    const model =
      typeof this.config.providerOptions?.model === 'string'
        ? this.config.providerOptions.model
        : 'gemini-2.5-flash';

    this.activeCount += 1;
    const startedAt = Date.now();

    try {
      const endpoint = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`;
      const response = await fetch(endpoint, {
        method: 'POST',
        signal: request.signal,
        headers: {
          'Content-Type': 'application/json',
          'x-goog-api-key': apiKey,
        },
        body: JSON.stringify({
          contents: [{ role: 'user', parts: [{ text: request.prompt }] }],
        }),
      });

      if (response.status === 401 || response.status === 403) {
        throw new BridgeError('NEEDS_AUTH', 'Gemini API rejected server credentials.', {
          retryable: false,
          botId: this.botId,
          provider: this.provider,
        });
      }

      if (response.status === 429 || response.status >= 500) {
        throw new BridgeError(
          response.status === 429 ? 'RATE_LIMITED' : 'TRANSIENT_FAILURE',
          `Gemini API returned HTTP ${response.status}.`,
          { retryable: true, botId: this.botId, provider: this.provider },
        );
      }

      if (!response.ok) {
        throw new BridgeError(
          'PROVIDER_ERROR',
          `Gemini API failed with HTTP ${response.status}.`,
          { retryable: false, botId: this.botId, provider: this.provider },
        );
      }

      const payload = (await response.json()) as {
        candidates?: Array<{ content?: { parts?: Array<{ text?: string }> } }>;
      };
      const text =
        payload.candidates?.[0]?.content?.parts
          ?.map((part) => part.text ?? '')
          .join('')
          .trim() ?? '';

      if (text) {
        callbacks?.onDelta?.(text, text);
      }

      return {
        text,
        finishReason: 'completed',
        externalThreadRef: request.externalThreadRef,
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
