import * as path from 'node:path';
import { BridgeError } from './types';

interface QueuedWaiter {
  resolve: () => void;
}

/**
 * Guarantees two invariants for browser-backed bots:
 * 1. Each bot has an exclusive, non-overlapping profileDir (no shared Chromium userDataDir).
 * 2. At most one task accesses a given bot session at a time, while bounding total
 *    concurrent browser workers across the host.
 */
export class SessionLockManager {
  private readonly profileOwners = new Map<string, string>();
  private readonly botQueues = new Map<string, QueuedWaiter[]>();
  private readonly activeBots = new Set<string>();

  private activeGlobalBrowsers = 0;
  private readonly globalWaiters: QueuedWaiter[] = [];

  constructor(private readonly maxConcurrentBrowsers: number = 4) {
    if (maxConcurrentBrowsers < 1) {
      throw new RangeError('maxConcurrentBrowsers must be >= 1');
    }
  }

  /**
   * Registers and verifies that a bot's persistent browser profile directory
   * is not shared with any other registered bot.
   */
  claimProfileDir(botId: string, profileDir: string): string {
    const normalized = path.resolve(profileDir);
    const existingOwner = this.profileOwners.get(normalized);
    if (existingOwner && existingOwner !== botId) {
      throw new BridgeError(
        'INVALID_CONFIG',
        `Profile directory "${normalized}" is already claimed by bot "${existingOwner}".`,
        { retryable: false, botId },
      );
    }
    this.profileOwners.set(normalized, botId);
    return normalized;
  }

  releaseProfileDir(botId: string): void {
    for (const [dir, owner] of this.profileOwners.entries()) {
      if (owner === botId) {
        this.profileOwners.delete(dir);
      }
    }
  }

  async runWithBotLock<T>(
    botId: string,
    requiresBrowserPermit: boolean,
    fn: () => Promise<T>,
  ): Promise<T> {
    await this.acquireBotLock(botId);
    try {
      if (requiresBrowserPermit) {
        await this.acquireGlobalBrowserPermit();
      }
      try {
        return await fn();
      } finally {
        if (requiresBrowserPermit) {
          this.releaseGlobalBrowserPermit();
        }
      }
    } finally {
      this.releaseBotLock(botId);
    }
  }

  private acquireBotLock(botId: string): Promise<void> {
    if (!this.activeBots.has(botId)) {
      this.activeBots.add(botId);
      return Promise.resolve();
    }
    return new Promise<void>((resolve) => {
      const queue = this.botQueues.get(botId) ?? [];
      queue.push({ resolve });
      this.botQueues.set(botId, queue);
    });
  }

  private releaseBotLock(botId: string): void {
    const queue = this.botQueues.get(botId);
    if (queue && queue.length > 0) {
      const next = queue.shift()!;
      if (queue.length === 0) {
        this.botQueues.delete(botId);
      }
      next.resolve();
      return;
    }
    this.activeBots.delete(botId);
  }

  private acquireGlobalBrowserPermit(): Promise<void> {
    if (this.activeGlobalBrowsers < this.maxConcurrentBrowsers) {
      this.activeGlobalBrowsers += 1;
      return Promise.resolve();
    }
    return new Promise<void>((resolve) => {
      this.globalWaiters.push({ resolve });
    });
  }

  private releaseGlobalBrowserPermit(): void {
    const next = this.globalWaiters.shift();
    if (next) {
      next.resolve();
      return;
    }
    this.activeGlobalBrowsers = Math.max(0, this.activeGlobalBrowsers - 1);
  }
}
