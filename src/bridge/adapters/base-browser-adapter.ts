import {
  AdapterCapabilities,
  BotAdapter,
  BotConfig,
  BotHealthSnapshot,
  BridgeError,
  ProviderId,
  SendMessageCallbacks,
  SendMessageRequest,
  SendMessageResult,
  SessionInitResult,
} from '../types';

export interface BrowserPageHandle {
  url(): string;
  goto(url: string, options?: { signal?: AbortSignal }): Promise<void>;
  isAuthenticated(): Promise<boolean>;
  hasChallengeWall(): Promise<boolean>;
  submitPromptAndStream(
    prompt: string,
    callbacks: {
      onDelta: (chunk: string, fullText: string) => void;
      signal: AbortSignal;
    },
  ): Promise<{ text: string; sessionUrl: string; externalThreadId?: string }>;
  close(): Promise<void>;
}

export interface BrowserSessionDriver {
  launchPersistentSession(options: {
    botId: string;
    profileDir: string;
    startUrl: string;
    headless: boolean;
  }): Promise<BrowserPageHandle>;
}

export interface BrowserProviderProfileSpec {
  readonly provider: ProviderId;
  readonly defaultUrl: string;
  readonly supportsAttachments: boolean;
}

/**
 * Reusable BotAdapter base class for browser-profile-backed AI accounts
 * (e.g. Muse AI Account 01..04, ChatGPT Web, Grok Web).
 * Enforces per-bot isolated profile directories and refuses to bypass login/CAPTCHA walls.
 */
export abstract class BaseBrowserAdapter implements BotAdapter {
  readonly botId: string;
  readonly provider: ProviderId;

  protected page: BrowserPageHandle | null = null;
  protected activeTaskIds = new Set<string>();

  protected constructor(
    protected readonly config: BotConfig,
    protected readonly spec: BrowserProviderProfileSpec,
    protected readonly driver?: BrowserSessionDriver,
  ) {
    this.botId = config.botId;
    this.provider = spec.provider;
  }

  getCapabilities(): AdapterCapabilities {
    return {
      authMode: 'browser_profile',
      supportsStreaming: true,
      supportsConversationResume: true,
      requiresExclusiveSession: true,
      supportsAttachments: this.spec.supportsAttachments,
      maxConcurrency: 1,
    };
  }

  async initializeSession(): Promise<SessionInitResult> {
    if (!this.config.profileDir) {
      return {
        botId: this.botId,
        provider: this.provider,
        status: 'error',
        ready: false,
        message: 'Missing isolated profileDir for browser-based bot.',
      };
    }

    if (!this.driver) {
      return {
        botId: this.botId,
        provider: this.provider,
        status: 'offline',
        ready: false,
        message: 'BrowserSessionDriver is not attached in this runtime.',
      };
    }

    const headless = this.config.providerOptions?.headless !== false;
    const startUrl =
      typeof this.config.providerOptions?.baseUrl === 'string'
        ? this.config.providerOptions.baseUrl
        : this.spec.defaultUrl;

    try {
      this.page = await this.driver.launchPersistentSession({
        botId: this.botId,
        profileDir: this.config.profileDir,
        startUrl,
        headless,
      });

      if (await this.page.hasChallengeWall()) {
        return {
          botId: this.botId,
          provider: this.provider,
          status: 'needs_auth',
          ready: false,
          message: 'Verification challenge detected. Complete authentication manually in this bot profile.',
        };
      }

      const authenticated = await this.page.isAuthenticated();
      if (!authenticated) {
        return {
          botId: this.botId,
          provider: this.provider,
          status: 'needs_auth',
          ready: false,
          message: `Session for ${this.config.displayName} is not signed in.`,
        };
      }

      return {
        botId: this.botId,
        provider: this.provider,
        status: 'online',
        ready: true,
        message: 'Persistent browser session ready.',
      };
    } catch (error) {
      return {
        botId: this.botId,
        provider: this.provider,
        status: 'error',
        ready: false,
        message: error instanceof Error ? error.message : 'Failed to launch persistent browser session.',
      };
    }
  }

  async getStatus(): Promise<BotHealthSnapshot> {
    const checkedAt = new Date().toISOString();

    if (this.activeTaskIds.size > 0) {
      return {
        botId: this.botId,
        provider: this.provider,
        status: 'busy',
        activeTaskId: Array.from(this.activeTaskIds)[0],
        reason: 'Generating response',
        checkedAt,
      };
    }

    if (!this.page) {
      return {
        botId: this.botId,
        provider: this.provider,
        status: 'offline',
        reason: 'Browser session not initialized',
        checkedAt,
      };
    }

    try {
      if (await this.page.hasChallengeWall() || !(await this.page.isAuthenticated())) {
        return {
          botId: this.botId,
          provider: this.provider,
          status: 'needs_auth',
          reason: 'Sign-in or challenge completion required in isolated browser profile',
          checkedAt,
        };
      }
      return {
        botId: this.botId,
        provider: this.provider,
        status: 'online',
        reason: 'Authenticated session active',
        checkedAt,
      };
    } catch (error) {
      return {
        botId: this.botId,
        provider: this.provider,
        status: 'error',
        reason: error instanceof Error ? error.message : 'Health check failed',
        checkedAt,
      };
    }
  }

  async sendMessage(
    request: SendMessageRequest,
    callbacks?: SendMessageCallbacks,
  ): Promise<SendMessageResult> {
    if (!this.page) {
      const init = await this.initializeSession();
      if (!init.ready) {
        throw new BridgeError(
          init.status === 'needs_auth' ? 'NEEDS_AUTH' : 'OFFLINE',
          init.message,
          { retryable: false, botId: this.botId, provider: this.provider },
        );
      }
    }

    const page = this.page!;
    this.activeTaskIds.add(request.taskId);
    const startedAt = Date.now();

    try {
      const targetUrl = request.externalThreadRef?.sessionUrl;
      if (targetUrl && page.url() !== targetUrl) {
        await page.goto(targetUrl, { signal: request.signal });
      }

      if (await page.hasChallengeWall()) {
        throw new BridgeError(
          'CAPTCHA_REQUIRED',
          'Challenge wall encountered. Manual authentication required.',
          { retryable: false, botId: this.botId, provider: this.provider },
        );
      }

      if (!(await page.isAuthenticated())) {
        throw new BridgeError(
          'NEEDS_AUTH',
          'Browser session expired. Please re-authenticate this bot profile.',
          { retryable: false, botId: this.botId, provider: this.provider },
        );
      }

      const result = await page.submitPromptAndStream(request.prompt, {
        signal: request.signal,
        onDelta: (chunk, fullText) => {
          callbacks?.onDelta?.(chunk, fullText);
        },
      });

      const updatedRef = {
        conversationId: request.conversationId,
        botId: this.botId,
        provider: this.provider,
        externalThreadId: result.externalThreadId,
        sessionUrl: result.sessionUrl,
        updatedAt: new Date().toISOString(),
      };
      callbacks?.onThreadRefUpdate?.(updatedRef);

      return {
        text: result.text,
        finishReason: 'completed',
        externalThreadRef: updatedRef,
        usage: {
          latencyMs: Date.now() - startedAt,
        },
      };
    } finally {
      this.activeTaskIds.delete(request.taskId);
    }
  }

  async abortTask(taskId: string): Promise<void> {
    this.activeTaskIds.delete(taskId);
  }

  async dispose(): Promise<void> {
    this.activeTaskIds.clear();
    if (this.page) {
      await this.page.close();
      this.page = null;
    }
  }
}
