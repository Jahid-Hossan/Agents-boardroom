import { BridgeSseEvent } from './types';

export type SseSubscriber = (event: BridgeSseEvent) => void;

/**
 * Lightweight pub/sub hub used to stream live bot status updates, streaming deltas,
 * and attributed responses over SSE to Web and Android Boardroom clients.
 */
export class SseHub {
  private readonly subscribers = new Set<SseSubscriber>();

  subscribe(subscriber: SseSubscriber): () => void {
    this.subscribers.add(subscriber);
    return () => {
      this.subscribers.delete(subscriber);
    };
  }

  publish(event: BridgeSseEvent): void {
    for (const subscriber of this.subscribers) {
      try {
        subscriber(event);
      } catch {
        // Isolate broken client listeners so one bad stream cannot disrupt others.
      }
    }
  }

  get subscriberCount(): number {
    return this.subscribers.size;
  }
}
