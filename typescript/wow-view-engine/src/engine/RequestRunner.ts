/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {
  QueryBudget,
  RuntimeLimitError,
  withDeadline,
} from '../lib/runtimeLimits.js';

export interface ReadTask<T> {
  key: string;
  policy: 'reject' | 'queue';
  timeoutMs: number;
  controller?: AbortController;
  onAccepted?(waiting: boolean): void;
  run(signal: AbortSignal): Promise<T>;
}
export interface ReadOperation<T> {
  completion: Promise<T>;
  cancel(): void;
}
interface PendingRead {
  key: string;
  controller: AbortController;
  start(): void;
  cancel(): void;
  release?: () => void;
}

/** Owns admission and cancellation; request/result semantics stay with the caller. */
export class RequestRunner {
  private readonly budget: QueryBudget;
  private readonly maximum: number;
  private readonly queuedMaximum: number;
  private readonly maximumTimeout: number;
  private readonly operations = new Map<string, PendingRead>();
  private readonly waiting = new Set<PendingRead>();
  private disposed = false;
  private draining = false;

  constructor(options: {
    maxConcurrent: number;
    maxQueued: number;
    maxTimeoutMs?: number;
  }) {
    const { maxConcurrent, maxQueued, maxTimeoutMs = 30000 } = options;
    for (const value of [maxConcurrent, maxQueued, maxTimeoutMs])
      if (!Number.isSafeInteger(value) || value <= 0)
        throw new TypeError('请求预算必须为正整数');
    if (maxTimeoutMs > 2147483647)
      throw new TypeError('请求超时超出定时器范围');
    this.maximum = maxConcurrent;
    this.queuedMaximum = maxQueued;
    this.maximumTimeout = maxTimeoutMs;
    this.budget = new QueryBudget(maxConcurrent);
  }

  submit<T>(task: ReadTask<T>): ReadOperation<T> {
    if (this.disposed) throw new Error('请求执行器已释放');
    if (
      !task.key ||
      typeof task.key !== 'string' ||
      typeof task.run !== 'function' ||
      (task.policy !== 'queue' && task.policy !== 'reject') ||
      !Number.isSafeInteger(task.timeoutMs) ||
      task.timeoutMs <= 0 ||
      task.timeoutMs > this.maximumTimeout
    )
      throw new TypeError('请求参数无效');
    const previous = this.operations.get(task.key);
    const active = this.budget.size - (previous?.release ? 1 : 0);
    const queued =
      this.waiting.size - (previous && this.waiting.has(previous) ? 1 : 0);
    const mustWait = active >= this.maximum || queued > 0;
    if (task.policy === 'reject' && mustWait)
      throw new RuntimeLimitError('BUSY', '查询并发已达上限，请稍后重试');
    if (
      mustWait &&
      queued - Math.max(0, this.maximum - active) >= this.queuedMaximum
    )
      throw new RuntimeLimitError('RESOURCE_LIMIT', '查询等待队列已达上限');

    const controller = task.controller ?? new AbortController();
    let settled = false;
    let resolve!: (value: T) => void;
    let reject!: (error: unknown) => void;
    const completion = new Promise<T>((yes, no) => {
      resolve = yes;
      reject = no;
    });
    const finish = (settle: () => void, abort = false) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      controller.signal.removeEventListener('abort', entry.cancel);
      this.waiting.delete(entry);
      if (this.operations.get(task.key) === entry)
        this.operations.delete(task.key);
      entry.release?.();
      settle();
      if (abort) controller.abort();
      this.drain();
    };
    const entry: PendingRead = {
      key: task.key,
      controller,
      cancel: () =>
        finish(
          () => reject(new RuntimeLimitError('CANCELLED', '操作已取消')),
          true,
        ),
      start: () => {
        if (settled) return;
        clearTimeout(timer);
        // Once started, withDeadline owns abort classification (especially TIMEOUT).
        controller.signal.removeEventListener('abort', entry.cancel);
        entry.release = this.budget.acquire(task.key, entry);
        void withDeadline(
          () => task.run(controller.signal),
          task.timeoutMs,
          controller,
        ).then(
          value => finish(() => resolve(value)),
          (error: unknown) => finish(() => reject(error), true),
        );
      },
    };
    this.operations.set(task.key, entry);
    this.waiting.add(entry);
    const timer = setTimeout(
      () =>
        finish(
          () => reject(new RuntimeLimitError('TIMEOUT', '查询等待超时')),
          true,
        ),
      Math.min(
        2147483647,
        (Math.ceil(this.queuedMaximum / this.maximum) + 1) *
          this.maximumTimeout +
          1000,
      ),
    );
    controller.signal.addEventListener('abort', entry.cancel, { once: true });
    try {
      task.onAccepted?.(mustWait);
    } catch (error) {
      entry.cancel();
      void completion.catch(() => {});
      throw error;
    }
    previous?.cancel();
    if (controller.signal.aborted) entry.cancel();
    this.drain();
    return { completion, cancel: entry.cancel };
  }

  private drain(): void {
    if (this.disposed || this.draining) return;
    this.draining = true;
    try {
      while (this.budget.size < this.maximum && this.waiting.size) {
        const entry = this.waiting.values().next().value;
        if (!entry) break;
        this.waiting.delete(entry);
        entry.start();
      }
    } finally {
      this.draining = false;
    }
  }

  cancel(key: string, controller?: AbortController): void {
    const entry = this.operations.get(key);
    if (entry && (!controller || entry.controller === controller))
      entry.cancel();
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    for (const operation of [...this.operations.values()]) operation.cancel();
  }
}
