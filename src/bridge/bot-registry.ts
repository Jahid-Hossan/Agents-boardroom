import * as path from 'node:path';
import { AdapterRegistry } from './adapter-registry';
import { SessionLockManager } from './session-lock-manager';
import { SseHub } from './sse-hub';
import {
  AdapterFactoryContext,
  BotAdapter,
  BotConfig,
  BotHealthSnapshot,
  BotStatus,
  BridgeError,
  PublicBotView,
  SessionInitResult,
} from './types';

export interface RegisteredBotEntry {
  config: BotConfig;
  adapter: BotAdapter;
  health: BotHealthSnapshot;
}

/**
 * Manages bot registration, enable/disable state, broadcast selection,
 * isolated profile claiming, and live 5-state availability tracking.
 */
export class BotRegistry {
  private readonly entries = new Map<string, RegisteredBotEntry>();

  constructor(
    private readonly adapterRegistry: AdapterRegistry,
    private readonly lockManager: SessionLockManager,
    private readonly sseHub: SseHub,
    private readonly context: AdapterFactoryContext,
  ) {}

  registerBot(rawConfig: BotConfig): PublicBotView {
    const adapter = this.adapterRegistry.createAdapter(rawConfig, this.context);
    const capabilities = adapter.getCapabilities();

    let resolvedProfileDir = rawConfig.profileDir;
    if (capabilities.authMode === 'browser_profile') {
      const targetDir =
        rawConfig.profileDir ??
        path.join(this.context.dataDir, 'profiles', 'bots', rawConfig.botId);
      resolvedProfileDir = this.lockManager.claimProfileDir(rawConfig.botId, targetDir);
    }

    const normalizedConfig: BotConfig = {
      ...rawConfig,
      profileDir: resolvedProfileDir,
    };
    const finalAdapter =
      resolvedProfileDir !== rawConfig.profileDir
        ? this.adapterRegistry.createAdapter(normalizedConfig, this.context)
        : adapter;

    const initialHealth: BotHealthSnapshot = {
      botId: normalizedConfig.botId,
      provider: normalizedConfig.provider,
      status: normalizedConfig.enabled ? 'offline' : 'offline',
      reason: normalizedConfig.enabled ? 'Not initialized' : 'Disabled',
      checkedAt: new Date().toISOString(),
    };

    this.entries.set(normalizedConfig.botId, {
      config: normalizedConfig,
      adapter: finalAdapter,
      health: initialHealth,
    });

    return this.toPublicView(normalizedConfig.botId);
  }

  async initializeBot(botId: string): Promise<SessionInitResult> {
    const entry = this.requireEntry(botId);
    if (!entry.config.enabled) {
      this.updateStatus(botId, 'offline', 'Bot is disabled');
      return {
        botId,
        provider: entry.config.provider,
        status: 'offline',
        ready: false,
        message: 'Bot is currently disabled.',
      };
    }

    const result = await entry.adapter.initializeSession();
    this.updateStatus(botId, result.status, result.message);
    return result;
  }

  async refreshBotStatus(botId: string): Promise<BotHealthSnapshot> {
    const entry = this.requireEntry(botId);
    if (!entry.config.enabled) {
      return this.updateStatus(botId, 'offline', 'Bot is disabled');
    }
    const health = await entry.adapter.getStatus();
    entry.health = health;
    this.sseHub.publish({ type: 'bot:status', payload: health });
    return health;
  }

  async setEnabled(botId: string, enabled: boolean): Promise<PublicBotView> {
    const entry = this.requireEntry(botId);
    entry.config = { ...entry.config, enabled };
    if (!enabled) {
      await entry.adapter.dispose?.();
      this.updateStatus(botId, 'offline', 'Disabled by user');
    } else {
      await this.initializeBot(botId);
    }
    return this.toPublicView(botId);
  }

  setSelectedForBroadcast(botId: string, selected: boolean): PublicBotView {
    const entry = this.requireEntry(botId);
    entry.config = { ...entry.config, selectedForBroadcast: selected };
    return this.toPublicView(botId);
  }

  updateStatus(
    botId: string,
    status: BotStatus,
    reason?: string,
    activeTaskId?: string,
  ): BotHealthSnapshot {
    const entry = this.requireEntry(botId);
    const snapshot: BotHealthSnapshot = {
      botId,
      provider: entry.config.provider,
      status,
      reason,
      activeTaskId,
      checkedAt: new Date().toISOString(),
    };
    entry.health = snapshot;
    this.sseHub.publish({ type: 'bot:status', payload: snapshot });
    return snapshot;
  }

  getEntry(botId: string): RegisteredBotEntry | undefined {
    return this.entries.get(botId);
  }

  requireEntry(botId: string): RegisteredBotEntry {
    const entry = this.entries.get(botId);
    if (!entry) {
      throw new BridgeError('INVALID_CONFIG', `Unknown bot "${botId}".`, {
        retryable: false,
        botId,
      });
    }
    return entry;
  }

  resolveBroadcastTargets(targetBotIds?: readonly string[]): RegisteredBotEntry[] {
    if (targetBotIds && targetBotIds.length > 0) {
      const uniqueIds = Array.from(new Set(targetBotIds));
      return uniqueIds
        .map((id) => this.requireEntry(id))
        .filter((entry) => entry.config.enabled);
    }

    return Array.from(this.entries.values()).filter(
      (entry) => entry.config.enabled && entry.config.selectedForBroadcast,
    );
  }

  listBots(): PublicBotView[] {
    return Array.from(this.entries.keys()).map((id) => this.toPublicView(id));
  }

  async disposeAll(): Promise<void> {
    await Promise.allSettled(
      Array.from(this.entries.values()).map(async (entry) => {
        await entry.adapter.dispose?.();
        this.lockManager.releaseProfileDir(entry.config.botId);
      }),
    );
  }

  private toPublicView(botId: string): PublicBotView {
    const entry = this.requireEntry(botId);
    return {
      botId: entry.config.botId,
      displayName: entry.config.displayName,
      provider: entry.config.provider,
      enabled: entry.config.enabled,
      selectedForBroadcast: entry.config.selectedForBroadcast,
      capabilities: entry.adapter.getCapabilities(),
      health: entry.health,
    };
  }
}
