import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import { ConversationMapStore } from '../conversation-mapper';
import { BridgeMessageStore } from '../message-dispatcher';
import { BoardroomMessageRecord, BotConfig, ExternalThreadRef } from '../types';

async function readJsonFile<T>(filePath: string, fallback: T): Promise<T> {
  try {
    const raw = await fs.readFile(filePath, 'utf8');
    return JSON.parse(raw) as T;
  } catch (error) {
    if ((error as NodeJS.ErrnoException)?.code === 'ENOENT') {
      return fallback;
    }
    throw error;
  }
}

async function writeJsonAtomic(filePath: string, data: unknown): Promise<void> {
  const dir = path.dirname(filePath);
  await fs.mkdir(dir, { recursive: true });
  const tempPath = `${filePath}.${process.pid}.${Date.now()}.tmp`;
  await fs.writeFile(tempPath, JSON.stringify(data, null, 2), 'utf8');
  await fs.rename(tempPath, filePath);
}

/**
 * Persists non-secret bot definitions (<DATA_DIR>/bridge/bots.json).
 * Intentionally strips/omits any transient runtime credentials or cookies.
 */
export class FileBotConfigStore {
  private readonly filePath: string;

  constructor(dataDir: string) {
    this.filePath = path.join(dataDir, 'bridge', 'bots.json');
  }

  async loadAll(): Promise<BotConfig[]> {
    return readJsonFile<BotConfig[]>(this.filePath, []);
  }

  async saveAll(bots: readonly BotConfig[]): Promise<void> {
    const sanitized = bots.map((bot) => ({
      botId: bot.botId,
      displayName: bot.displayName,
      provider: bot.provider,
      enabled: bot.enabled,
      selectedForBroadcast: bot.selectedForBroadcast,
      timeoutMs: bot.timeoutMs,
      profileDir: bot.profileDir,
      secretRef: bot.secretRef,
      providerOptions: bot.providerOptions,
    }));
    await writeJsonAtomic(this.filePath, sanitized);
  }
}

export class FileConversationMapStore implements ConversationMapStore {
  private readonly filePath: string;

  constructor(dataDir: string) {
    this.filePath = path.join(dataDir, 'bridge', 'conversation-map.json');
  }

  async loadAll(): Promise<ExternalThreadRef[]> {
    return readJsonFile<ExternalThreadRef[]>(this.filePath, []);
  }

  async saveAll(entries: readonly ExternalThreadRef[]): Promise<void> {
    await writeJsonAtomic(this.filePath, entries);
  }
}

export class FileBridgeMessageStore implements BridgeMessageStore {
  private readonly filePath: string;
  private writeChain: Promise<void> = Promise.resolve();

  constructor(dataDir: string) {
    this.filePath = path.join(dataDir, 'bridge', 'messages.json');
  }

  async listMessages(conversationId?: string): Promise<BoardroomMessageRecord[]> {
    const all = await readJsonFile<BoardroomMessageRecord[]>(this.filePath, []);
    if (!conversationId) return all;
    return all.filter((msg) => msg.conversationId === conversationId);
  }

  async saveMessage(message: BoardroomMessageRecord): Promise<void> {
    this.writeChain = this.writeChain.then(async () => {
      const existing = await readJsonFile<BoardroomMessageRecord[]>(this.filePath, []);
      const idx = existing.findIndex((m) => m.id === message.id);
      if (idx >= 0) {
        existing[idx] = message;
      } else {
        existing.push(message);
      }
      await writeJsonAtomic(this.filePath, existing);
    });
    await this.writeChain;
  }
}
