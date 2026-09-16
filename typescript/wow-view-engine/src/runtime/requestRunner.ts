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

import { DEFAULT_RUNTIME_LIMITS, type RuntimeLimits } from '../model/index.js';

export type RequestTask<T> = (controller: AbortController) => Promise<T>;

/** A newer request took this key; the caller drops the result silently. */
export class RequestSupersededError extends Error {
  readonly key: string;

  constructor(key: string) {
    super(`Request superseded: ${key}`);
    this.name = 'RequestSupersededError';
    this.key = key;
  }
}

/** The queue is full; the caller reports it rather than waiting longer. */
export class RequestQueueFullError extends Error {
  readonly key: string;

  constructor(key: string, limit: number) {
    super(`Queue is full (${limit}); dropped request for ${key}`);
    this.name = 'RequestQueueFullError';
    this.key = key;
  }
}

export function isRequestSuperseded(
  error: unknown,
): error is RequestSupersededError {
  return error instanceof RequestSupersededError;
}

interface Entry<T = unknown> {
  key: string;
  task: RequestTask<T>;
  controller: AbortController;
  resolve(value: T): void;
  reject(reason: unknown): void;
  started: boolean;
}

/**
 * The one scheduler every data query passes through.
 *
 * Two rules, both from `RuntimeLimits`. A key holds at most one request, so a
 * new page or a new filter aborts the one it replaces instead of racing it;
 * and the whole engine runs a bounded number at once, with a bounded queue
 * behind it, so a dashboard of twenty panels opens without twenty parallel
 * requests.
 */
export class RequestRunner {
  private readonly maxConcurrent: number;
  private readonly maxQueued: number;
  private readonly byKey = new Map<string, Entry>();
  private readonly queue: Entry[] = [];
  private running = 0;

  constructor(limits: RuntimeLimits = DEFAULT_RUNTIME_LIMITS) {
    this.maxConcurrent = limits.maxConcurrentQueries;
    this.maxQueued = limits.maxQueuedQueries;
  }

  /** Requests started and not yet settled. */
  get active(): number {
    return this.running;
  }

  /** Requests admitted and waiting for a slot. */
  get queued(): number {
    return this.queue.length;
  }

  /**
   * Runs `task` under `key`, superseding whatever that key was doing. The
   * returned promise rejects with `RequestSupersededError` in that case, and
   * with `RequestQueueFullError` when the queue has no room left.
   */
  run<T>(key: string, task: RequestTask<T>): Promise<T> {
    this.cancel(key);
    if (this.queue.length >= this.maxQueued)
      return Promise.reject(new RequestQueueFullError(key, this.maxQueued));

    return new Promise<T>((resolve, reject) => {
      const entry: Entry<T> = {
        key,
        task,
        controller: new AbortController(),
        resolve,
        reject,
        started: false,
      };
      this.byKey.set(key, entry);
      this.queue.push(entry);
      this.pump();
    });
  }

  /** Aborts the request under `key`, if any. Safe to call when there is none. */
  cancel(key: string): void {
    const entry = this.byKey.get(key);
    if (!entry) return;
    this.byKey.delete(key);
    if (!entry.started) {
      const at = this.queue.indexOf(entry);
      if (at >= 0) this.queue.splice(at, 1);
    }
    entry.controller.abort();
    entry.reject(new RequestSupersededError(key));
  }

  /** Aborts everything; the engine calls it when it is disposed. */
  cancelAll(): void {
    for (const key of [...this.byKey.keys()]) this.cancel(key);
  }

  private pump(): void {
    while (this.running < this.maxConcurrent && this.queue.length > 0) {
      const entry = this.queue.shift()!;
      entry.started = true;
      this.running += 1;
      entry.task(entry.controller).then(
        value => this.settle(entry, () => entry.resolve(value)),
        error => this.settle(entry, () => entry.reject(error)),
      );
    }
  }

  private settle(entry: Entry, deliver: () => void): void {
    this.running -= 1;
    // A superseded entry was already rejected and replaced in the map.
    if (this.byKey.get(entry.key) === entry) this.byKey.delete(entry.key);
    deliver();
    this.pump();
  }
}
