import { BotConfig } from '../types';
import { BaseBrowserAdapter, BrowserSessionDriver } from './base-browser-adapter';

export const MUSE_DEFAULT_URL = 'https://muse.ai/';

/**
 * Muse AI BotAdapter using an isolated persistent Chromium profile per bot account
 * (e.g. Bot 01 -> Muse Account 01, Bot 02 -> Muse Account 02).
 */
export class MuseBrowserAdapter extends BaseBrowserAdapter {
  constructor(config: BotConfig, driver?: BrowserSessionDriver) {
    super(
      config,
      {
        provider: 'muse-web',
        defaultUrl: MUSE_DEFAULT_URL,
        supportsAttachments: true,
      },
      driver,
    );
  }
}
