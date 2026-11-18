import { ExternalThreadRef } from './types';

export interface ConversationMapStore {
  loadAll(): Promise<ExternalThreadRef[]>;
  saveAll(entries: readonly ExternalThreadRef[]): Promise<void>;
}

export class InMemoryConversationMapStore implements ConversationMapStore {
  private entries: ExternalThreadRef[] = [];

  async loadAll(): Promise<ExternalThreadRef[]> {
    return [...this.entries];
  }

  async saveAll(entries: readonly ExternalThreadRef[]): Promise<void> {
    this.entries = [...entries];
  }
}

/**
 * Maintains independent provider session/thread references per (conversationId, botId) pair
 * so multi-bot broadcasts never cross-contaminate sessions across accounts.
 */
export class ConversationMapper {
  private readonly map = new Map<string, ExternalThreadRef>();
  private initialized = false;

  constructor(private readonly store: ConversationMapStore = new InMemoryConversationMapStore()) {}

  async init(): Promise<void> {
    if (this.initialized) return;
    const loaded = await this.store.loadAll();
    for (const ref of loaded) {
      this.map.set(this.keyOf(ref.conversationId, ref.botId), ref);
    }
    this.initialized = true;
  }

  get(conversationId: string, botId: string): ExternalThreadRef | null {
    return this.map.get(this.keyOf(conversationId, botId)) ?? null;
  }

  async set(ref: ExternalThreadRef): Promise<void> {
    this.map.set(this.keyOf(ref.conversationId, ref.botId), ref);
    await this.store.saveAll(Array.from(this.map.values()));
  }

  async clearBot(botId: string): Promise<void> {
    for (const [key, ref] of this.map.entries()) {
      if (ref.botId === botId) {
        this.map.delete(key);
      }
    }
    await this.store.saveAll(Array.from(this.map.values()));
  }

  private keyOf(conversationId: string, botId: string): string {
    return `${conversationId}::${botId}`;
  }
}
