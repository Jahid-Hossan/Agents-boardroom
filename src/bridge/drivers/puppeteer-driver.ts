import * as fs from 'node:fs/promises';
import {
  BrowserPageHandle,
  BrowserSessionDriver,
} from '../adapters/base-browser-adapter';
import { BridgeError } from '../types';

interface PuppeteerPageLike {
  url(): string;
  goto(url: string, opts?: Record<string, unknown>): Promise<unknown>;
  evaluate<T>(fn: (...args: any[]) => T, ...args: any[]): Promise<T>;
  close(): Promise<void>;
}

interface PuppeteerBrowserLike {
  newPage(): Promise<PuppeteerPageLike>;
  pages(): Promise<PuppeteerPageLike[]>;
  close(): Promise<void>;
}

interface PuppeteerModuleLike {
  launch(options: Record<string, unknown>): Promise<PuppeteerBrowserLike>;
}

export interface PuppeteerDriverOptions {
  readonly executablePath?: string;
  readonly pollIntervalMs?: number;
  readonly defaultResponseTimeoutMs?: number;
}

/**
 * Isolated persistent Chromium driver backed by puppeteer-core.
 * Each bot receives its own BrowserContext/process pinned to its dedicated profileDir.
 * Never bypasses login/MFA/CAPTCHA walls.
 */
export class PuppeteerSessionDriver implements BrowserSessionDriver {
  private readonly pollIntervalMs: number;
  private readonly defaultTimeoutMs: number;

  constructor(private readonly options: PuppeteerDriverOptions = {}) {
    this.pollIntervalMs = options.pollIntervalMs ?? 300;
    this.defaultTimeoutMs = options.defaultResponseTimeoutMs ?? 45_000;
  }

  async launchPersistentSession(launchOpts: {
    botId: string;
    profileDir: string;
    startUrl: string;
    headless: boolean;
  }): Promise<BrowserPageHandle> {
    await fs.mkdir(launchOpts.profileDir, { recursive: true });

    const puppeteer = await this.loadPuppeteer();
    const executablePath =
      this.options.executablePath ||
      process.env.PUPPETEER_EXECUTABLE_PATH ||
      process.env.CHROME_PATH;

    const browser = await puppeteer.launch({
      headless: launchOpts.headless,
      userDataDir: launchOpts.profileDir,
      executablePath: executablePath || undefined,
      args: [
        '--no-sandbox',
        '--disable-setuid-sandbox',
        '--disable-dev-shm-usage',
      ],
    });

    const pages = await browser.pages();
    const page = pages[0] ?? (await browser.newPage());
    if (!page.url() || page.url() === 'about:blank') {
      await page.goto(launchOpts.startUrl, { waitUntil: 'domcontentloaded', timeout: 30_000 });
    }

    return new PuppeteerPageAdapter(
      launchOpts.botId,
      browser,
      page,
      this.pollIntervalMs,
      this.defaultTimeoutMs,
    );
  }

  private async loadPuppeteer(): Promise<PuppeteerModuleLike> {
    try {
      const mod = (await import('puppeteer-core')) as unknown as {
        default?: PuppeteerModuleLike;
      } & PuppeteerModuleLike;
      return mod.default ?? mod;
    } catch (error) {
      throw new BridgeError(
        'PROVIDER_ERROR',
        'puppeteer-core is not installed or failed to load in this runtime.',
        { retryable: false, cause: error },
      );
    }
  }
}

class PuppeteerPageAdapter implements BrowserPageHandle {
  constructor(
    private readonly botId: string,
    private readonly browser: PuppeteerBrowserLike,
    private readonly page: PuppeteerPageLike,
    private readonly pollIntervalMs: number,
    private readonly defaultTimeoutMs: number,
  ) {}

  url(): string {
    return this.page.url();
  }

  async goto(url: string, options?: { signal?: AbortSignal }): Promise<void> {
    if (options?.signal?.aborted) {
      throw new BridgeError('ABORTED', 'Navigation aborted.', { botId: this.botId });
    }
    await this.page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30_000 });
  }

  async isAuthenticated(): Promise<boolean> {
    return this.page.evaluate(() => {
      const isVisible = (el: Element | null) => {
        if (!el) return false;
        const rect = el.getBoundingClientRect();
        const style = getComputedStyle(el);
        return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
      };
      const selectors = [
        '#prompt-textarea',
        'rich-textarea .ql-editor[contenteditable="true"]',
        'div.ProseMirror[contenteditable="true"]',
        'textarea:not([disabled]):not([readonly])',
        '[contenteditable="true"][role="textbox"]',
        'div[role="textbox"]',
      ];
      return selectors.some((sel) => Array.from(document.querySelectorAll(sel)).some(isVisible));
    });
  }

  async hasChallengeWall(): Promise<boolean> {
    return this.page.evaluate(() => {
      const href = location.href.toLowerCase();
      if (
        href.includes('accounts.google.com') ||
        href.includes('/login') ||
        href.includes('/signin') ||
        href.includes('challenge')
      ) {
        return true;
      }
      const hasAuthField = Boolean(
        document.querySelector('input[type="password"], input[autocomplete="one-time-code"], iframe[src*="captcha" i]'),
      );
      return hasAuthField;
    });
  }

  async submitPromptAndStream(
    prompt: string,
    callbacks: {
      onDelta: (chunk: string, fullText: string) => void;
      signal: AbortSignal;
    },
  ): Promise<{ text: string; sessionUrl: string; externalThreadId?: string }> {
    const startResult = await this.page.evaluate((promptText: string) => {
      const isVisible = (el: Element | null): el is HTMLElement => {
        if (!el) return false;
        const rect = el.getBoundingClientRect();
        const style = getComputedStyle(el);
        return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
      };
      const selectors = [
        '#prompt-textarea',
        'rich-textarea .ql-editor[contenteditable="true"]',
        'div.ProseMirror[contenteditable="true"]',
        'textarea:not([disabled]):not([readonly])',
        '[contenteditable="true"][role="textbox"]',
        'div[role="textbox"]',
      ];
      let inputEl: HTMLElement | null = null;
      for (const sel of selectors) {
        const found = Array.from(document.querySelectorAll(sel)).find(isVisible);
        if (found) {
          inputEl = found;
          break;
        }
      }
      if (!inputEl) {
        return { started: false, beforeCount: 0, beforeLast: '', error: 'Chat input element not ready.' };
      }

      const respSelectors = [
        '[data-message-author-role="assistant"] .markdown',
        '[data-message-author-role="assistant"]',
        'model-response message-content',
        '.markdown',
        'article',
      ];
      const beforeTexts: string[] = [];
      for (const sel of respSelectors) {
        const nodes = Array.from(document.querySelectorAll(sel));
        if (nodes.length > 0) {
          for (const node of nodes) {
            const txt = (node as HTMLElement).innerText?.trim();
            if (txt) beforeTexts.push(txt);
          }
          if (beforeTexts.length > 0) break;
        }
      }

      inputEl.focus();
      const tag = inputEl.tagName.toLowerCase();
      if (tag === 'textarea' || tag === 'input') {
        const area = inputEl as HTMLTextAreaElement;
        area.value = promptText;
        area.dispatchEvent(new Event('input', { bubbles: true }));
      } else {
        inputEl.textContent = promptText;
        inputEl.dispatchEvent(new InputEvent('input', { bubbles: true, data: promptText }));
      }

      setTimeout(() => {
        const btnSelectors = [
          'button[data-testid="send-button"]',
          'button[aria-label*="Send" i]',
          'button[type="submit"]',
        ];
        for (const sel of btnSelectors) {
          const btn = Array.from(document.querySelectorAll(sel)).find(
            (x) => isVisible(x) && !(x as HTMLButtonElement).disabled,
          ) as HTMLButtonElement | undefined;
          if (btn) {
            btn.click();
            return;
          }
        }
        inputEl!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      }, 60);

      return {
        started: true,
        beforeCount: beforeTexts.length,
        beforeLast: beforeTexts[beforeTexts.length - 1] ?? '',
        error: '',
      };
    }, prompt);

    if (!startResult.started) {
      throw new BridgeError('NEEDS_AUTH', startResult.error || 'Chat composer unavailable.', {
        retryable: false,
        botId: this.botId,
      });
    }

    const deadline = Date.now() + this.defaultTimeoutMs;
    let latestText = '';
    let stableRounds = 0;

    while (Date.now() < deadline) {
      if (callbacks.signal.aborted) {
        throw new BridgeError('ABORTED', 'Prompt streaming aborted.', {
          retryable: false,
          botId: this.botId,
        });
      }

      await new Promise((resolve) => setTimeout(resolve, this.pollIntervalMs));

      const snapshot = await this.page.evaluate((submittedPrompt: string) => {
        const isVisible = (el: Element | null) => {
          if (!el) return false;
          const rect = el.getBoundingClientRect();
          const style = getComputedStyle(el);
          return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
        };
        const generating = ['button[data-testid="stop-button"]', 'button[aria-label*="Stop" i]'].some(
          (sel) => Array.from(document.querySelectorAll(sel)).some(isVisible),
        );
        const respSelectors = [
          '[data-message-author-role="assistant"] .markdown',
          '[data-message-author-role="assistant"]',
          'model-response message-content',
          '.markdown',
          'article',
        ];
        const results: string[] = [];
        for (const sel of respSelectors) {
          const nodes = Array.from(document.querySelectorAll(sel));
          if (nodes.length > 0) {
            for (const node of nodes) {
              const txt = (node as HTMLElement).innerText?.trim();
              if (txt && txt !== submittedPrompt) results.push(txt);
            }
            if (results.length > 0) break;
          }
        }
        return {
          count: results.length,
          latest: results[results.length - 1] ?? '',
          generating,
        };
      }, prompt);

      const hasNew =
        snapshot.count > startResult.beforeCount ||
        (snapshot.latest.length > 0 &&
          snapshot.latest !== startResult.beforeLast &&
          snapshot.latest !== prompt);

      if (!hasNew) continue;

      if (snapshot.latest.length > latestText.length) {
        const delta = snapshot.latest.slice(latestText.length);
        latestText = snapshot.latest;
        stableRounds = 0;
        callbacks.onDelta(delta, latestText);
      } else if (snapshot.latest === latestText && !snapshot.generating && latestText.length > 0) {
        stableRounds += 1;
        if (stableRounds >= 2) {
          return {
            text: latestText,
            sessionUrl: this.page.url(),
          };
        }
      }
    }

    if (latestText.length > 0) {
      return {
        text: latestText,
        sessionUrl: this.page.url(),
      };
    }

    throw new BridgeError('TIMEOUT', 'Timed out waiting for browser bot response.', {
      retryable: false,
      botId: this.botId,
    });
  }

  async close(): Promise<void> {
    await this.browser.close();
  }
}
