import {
  AdapterFactoryContext,
  BotAdapter,
  BotAdapterFactory,
  BotConfig,
  BridgeError,
  ProviderId,
} from './types';

/**
 * Maps provider identifiers (e.g. 'muse-web', 'gemini-api', 'openai-api', 'chatgpt-web', 'grok-api')
 * to their BotAdapter factory implementations without hardcoding providers in core dispatch logic.
 */
export class AdapterRegistry {
  private readonly factories = new Map<ProviderId, BotAdapterFactory>();

  register(provider: ProviderId, factory: BotAdapterFactory): this {
    this.factories.set(provider, factory);
    return this;
  }

  has(provider: ProviderId): boolean {
    return this.factories.has(provider);
  }

  listProviders(): ProviderId[] {
    return Array.from(this.factories.keys());
  }

  createAdapter(config: BotConfig, context: AdapterFactoryContext): BotAdapter {
    const factory = this.factories.get(config.provider);
    if (!factory) {
      throw new BridgeError(
        'INVALID_CONFIG',
        `No BotAdapter registered for provider "${config.provider}".`,
        { retryable: false, botId: config.botId, provider: config.provider },
      );
    }
    return factory(config, context);
  }
}
