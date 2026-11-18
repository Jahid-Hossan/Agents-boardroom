import { BotConfig } from '../types';
import { BaseBrowserAdapter, BrowserSessionDriver } from './base-browser-adapter';

export const CHATGPT_WEB_DEFAULT_URL = 'https://chatgpt.com/';

/**
 * Extensible ChatGPT Web BotAdapter backed by an isolated persistent browser profile.
 */
export class ChatGptWebAdapter extends BaseBrowserAdapter {
  constructor(config: BotConfig, driver?: BrowserSessionDriver) {
    super(
      config,
      {
        provider: 'chatgpt-web',
        defaultUrl: CHATGPT_WEB_DEFAULT_URL,
        supportsAttachments: true,
      },
      driver,
    );
  }
}
