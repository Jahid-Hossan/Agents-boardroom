import { randomUUID } from 'node:crypto';
import { BotRegistry, RegisteredBotEntry } from './bot-registry';
import { ConversationMapper } from './conversation-mapper';
import { SessionLockManager } from './session-lock-manager';
import { SseHub } from './sse-hub';
import {
  BoardroomMessageRecord,
  BridgeError,
  DeliveryTaskRecord,
  DeliveryTaskStatus,
  DispatchBroadcastRequest,
  DispatchBroadcastResult,
  SendMessageRequest,
} from './types';

export interface BridgeMessageStore {
  saveMessage(message: BoardroomMessageRecord): Promise<void>;
}

export class InMemoryBridgeMessageStore implements BridgeMessageStore {
  readonly messages: BoardroomMessageRecord[] = [];

  async saveMessage(message: BoardroomMessageRecord): Promise<void> {
    this.messages.push(message);
  }
}

export interface MessageDispatcherOptions {
  readonly maxTransientRetries?: number;
  readonly retryDelayMs?: number;
}

/**
 * Orchestrates fan-out broadcasting of a single Boardroom message to N selected bots.
 * Each bot executes against its own BotAdapter, session mapping, timeout, and error boundary,
 * posting an independent attributed response back into the original conversation.
 */
export class MessageDispatcher {
  private readonly activeControllers = new Map<
    string,
    { controller: AbortController; entry: RegisteredBotEntry }
  >();
  private readonly maxTransientRetries: number;
  private readonly retryDelayMs: number;

  constructor(
    private readonly botRegistry: BotRegistry,
    private readonly lockManager: SessionLockManager,
    private readonly conversationMapper: ConversationMapper,
    private readonly sseHub: SseHub,
    private readonly messageStore: BridgeMessageStore = new InMemoryBridgeMessageStore(),
    options?: MessageDispatcherOptions,
  ) {
    this.maxTransientRetries = options?.maxTransientRetries ?? 1;
    this.retryDelayMs = options?.retryDelayMs ?? 1200;
  }

  async broadcast(request: DispatchBroadcastRequest): Promise<DispatchBroadcastResult> {
    const targets = this.botRegistry.resolveBroadcastTargets(request.targetBotIds);
    const dispatchBatchId = `batch_${randomUUID()}`;

    const tasks = new Map<string, DeliveryTaskRecord>();
    for (const target of targets) {
      const taskId = `task_${randomUUID()}`;
      const now = new Date().toISOString();
      const record: DeliveryTaskRecord = {
        taskId,
        dispatchBatchId,
        conversationId: request.conversationId,
        parentMessageId: request.parentMessageId,
        botId: target.config.botId,
        provider: target.config.provider,
        status: 'queued',
        attempts: 0,
        createdAt: now,
        updatedAt: now,
      };
      tasks.set(taskId, record);
      this.sseHub.publish({ type: 'task:queued', payload: record });
    }

    this.sseHub.publish({
      type: 'dispatch:started',
      payload: {
        dispatchBatchId,
        conversationId: request.conversationId,
        parentMessageId: request.parentMessageId,
        taskIds: Array.from(tasks.keys()),
      },
    });

    const collectedResponses: BoardroomMessageRecord[] = [];

    await Promise.allSettled(
      targets.map(async (target) => {
        const task = Array.from(tasks.values()).find((t) => t.botId === target.config.botId)!;
        const response = await this.executeSingleBotTask(target, task, request, tasks);
        if (response) {
          collectedResponses.push(response);
        }
      }),
    );

    return {
      dispatchBatchId,
      conversationId: request.conversationId,
      parentMessageId: request.parentMessageId,
      tasks: Array.from(tasks.values()),
      responses: collectedResponses,
    };
  }

  async cancelTask(taskId: string): Promise<boolean> {
    const active = this.activeControllers.get(taskId);
    if (!active) return false;
    active.controller.abort(new BridgeError('ABORTED', 'Task cancelled by user.'));
    await active.entry.adapter.abortTask?.(taskId);
    return true;
  }

  private async executeSingleBotTask(
    entry: RegisteredBotEntry,
    initialTask: DeliveryTaskRecord,
    broadcast: DispatchBroadcastRequest,
    tasksMap: Map<string, DeliveryTaskRecord>,
  ): Promise<BoardroomMessageRecord | null> {
    const { botId, provider, displayName, timeoutMs } = entry.config;
    const requiresPermit = entry.adapter.getCapabilities().requiresExclusiveSession;

    if (entry.health.status === 'needs_auth') {
      const blocked = this.updateTask(tasksMap, initialTask.taskId, {
        status: 'needs_auth',
        errorCode: 'NEEDS_AUTH',
        errorMessage: `Bot "${displayName}" requires authentication before receiving messages.`,
      });
      this.sseHub.publish({ type: 'task:failed', payload: blocked });
      return null;
    }

    return this.lockManager.runWithBotLock(botId, requiresPermit, async () => {
      let attempt = 0;

      while (attempt <= this.maxTransientRetries) {
        attempt += 1;
        this.updateTask(tasksMap, initialTask.taskId, {
          status: 'running',
          attempts: attempt,
        });
        this.botRegistry.updateStatus(botId, 'busy', `Processing task ${initialTask.taskId}`, initialTask.taskId);
        this.sseHub.publish({ type: 'task:running', payload: tasksMap.get(initialTask.taskId)! });

        const controller = new AbortController();
        const timer = setTimeout(() => {
          controller.abort(
            new BridgeError('TIMEOUT', `Bot "${displayName}" timed out after ${timeoutMs}ms.`, {
              retryable: false,
              botId,
              provider,
            }),
          );
        }, timeoutMs);

        this.activeControllers.set(initialTask.taskId, { controller, entry });

        try {
          const existingRef = this.conversationMapper.get(broadcast.conversationId, botId);
          const sendRequest: SendMessageRequest = {
            taskId: initialTask.taskId,
            dispatchBatchId: initialTask.dispatchBatchId,
            parentMessageId: broadcast.parentMessageId,
            conversationId: broadcast.conversationId,
            prompt: broadcast.prompt,
            externalThreadRef: existingRef,
            attachments: broadcast.attachments,
            timeoutMs,
            signal: controller.signal,
          };

          const result = await entry.adapter.sendMessage(sendRequest, {
            onDelta: (chunkText, fullTextSoFar) => {
              this.sseHub.publish({
                type: 'task:delta',
                payload: {
                  taskId: initialTask.taskId,
                  dispatchBatchId: initialTask.dispatchBatchId,
                  conversationId: broadcast.conversationId,
                  parentMessageId: broadcast.parentMessageId,
                  botId,
                  chunkText,
                  fullTextSoFar,
                },
              });
            },
            onThreadRefUpdate: (updatedRef) => {
              void this.conversationMapper.set(updatedRef);
            },
          });

          if (result.externalThreadRef) {
            await this.conversationMapper.set(result.externalThreadRef);
          }

          const responseMessage: BoardroomMessageRecord = {
            id: `msg_${randomUUID()}`,
            conversationId: broadcast.conversationId,
            role: 'assistant',
            content: result.text,
            authorBotId: botId,
            authorName: displayName,
            provider,
            parentMessageId: broadcast.parentMessageId,
            deliveryTaskId: initialTask.taskId,
            dispatchBatchId: initialTask.dispatchBatchId,
            createdAt: new Date().toISOString(),
          };

          await this.messageStore.saveMessage(responseMessage);
          this.sseHub.publish({ type: 'message:created', payload: responseMessage });

          const completedTask = this.updateTask(tasksMap, initialTask.taskId, {
            status: 'completed',
            responseMessageId: responseMessage.id,
          });
          this.sseHub.publish({ type: 'task:completed', payload: completedTask });
          this.botRegistry.updateStatus(botId, 'online', 'Ready');

          return responseMessage;
        } catch (err) {
          const bridgeError = this.normalizeError(err, botId, provider);
          const canRetry =
            bridgeError.retryable &&
            attempt <= this.maxTransientRetries &&
            !controller.signal.aborted;

          if (canRetry) {
            await this.delay(this.retryDelayMs * attempt);
            continue;
          }

          const nextTaskStatus: DeliveryTaskStatus =
            bridgeError.code === 'NEEDS_AUTH' || bridgeError.code === 'CAPTCHA_REQUIRED'
              ? 'needs_auth'
              : bridgeError.code === 'ABORTED'
                ? 'cancelled'
                : 'failed';

          const nextBotStatus =
            nextTaskStatus === 'needs_auth'
              ? 'needs_auth'
              : bridgeError.code === 'ABORTED'
                ? 'online'
                : 'error';

          this.botRegistry.updateStatus(botId, nextBotStatus, bridgeError.message);

          const failedTask = this.updateTask(tasksMap, initialTask.taskId, {
            status: nextTaskStatus,
            errorCode: bridgeError.code,
            errorMessage: bridgeError.message,
          });

          this.sseHub.publish({
            type: nextTaskStatus === 'cancelled' ? 'task:cancelled' : 'task:failed',
            payload: failedTask,
          });

          return null;
        } finally {
          clearTimeout(timer);
          this.activeControllers.delete(initialTask.taskId);
        }
      }

      return null;
    });
  }

  private updateTask(
    tasksMap: Map<string, DeliveryTaskRecord>,
    taskId: string,
    patch: Partial<DeliveryTaskRecord>,
  ): DeliveryTaskRecord {
    const existing = tasksMap.get(taskId)!;
    const updated: DeliveryTaskRecord = {
      ...existing,
      ...patch,
      updatedAt: new Date().toISOString(),
    };
    tasksMap.set(taskId, updated);
    return updated;
  }

  private normalizeError(
    err: unknown,
    botId: string,
    provider: RegisteredBotEntry['config']['provider'],
  ): BridgeError {
    if (err instanceof BridgeError) {
      return err;
    }
    if (err instanceof Error && err.name === 'AbortError') {
      return new BridgeError('ABORTED', err.message || 'Request aborted.', {
        retryable: false,
        botId,
        provider,
        cause: err,
      });
    }
    return new BridgeError(
      'PROVIDER_ERROR',
      err instanceof Error ? err.message : 'Unknown provider error.',
      { retryable: false, botId, provider, cause: err },
    );
  }

  private delay(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }
}
