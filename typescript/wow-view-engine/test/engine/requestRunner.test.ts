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

import { afterEach, expect, it, vi } from 'vitest';
import { RequestRunner } from '../../src/engine/RequestRunner.js';
import { deferred } from './fixtures.js';

afterEach(() => vi.useRealTimers());

it('queues reads, cancels waiting work and releases the slot on completion', async () => {
  const runner = new RequestRunner({ maxConcurrent: 1, maxQueued: 2 });
  const first = deferred<number>();
  const waiting = vi.fn(async () => 2);
  const last = vi.fn(async () => 3);
  const a = runner.submit({
    key: 'a',
    policy: 'queue',
    timeoutMs: 1000,
    run: () => first.promise,
  });
  const b = runner.submit({
    key: 'b',
    policy: 'queue',
    timeoutMs: 1000,
    run: waiting,
  });
  const c = runner.submit({
    key: 'c',
    policy: 'queue',
    timeoutMs: 1000,
    run: last,
  });
  const cancelled = expect(b.completion).rejects.toMatchObject({
    code: 'CANCELLED',
  });
  b.cancel();
  await cancelled;
  expect(waiting).not.toHaveBeenCalled();
  expect(last).not.toHaveBeenCalled();
  first.resolve(1);
  await expect(a.completion).resolves.toBe(1);
  await expect(c.completion).resolves.toBe(3);
  runner.dispose();
});

it('rejects busy reads and queue overflow without replacing accepted work', async () => {
  const runner = new RequestRunner({ maxConcurrent: 1, maxQueued: 1 });
  const held = deferred<number>();
  const a = runner.submit({
    key: 'a',
    policy: 'queue',
    timeoutMs: 1000,
    run: () => held.promise,
  });
  expect(() =>
    runner.submit({
      key: 'b',
      policy: 'reject',
      timeoutMs: 1000,
      run: async () => 2,
    }),
  ).toThrow('并发');
  const b = runner.submit({
    key: 'b',
    policy: 'queue',
    timeoutMs: 1000,
    run: async () => 2,
  });
  expect(() =>
    runner.submit({
      key: 'c',
      policy: 'queue',
      timeoutMs: 1000,
      run: async () => 3,
    }),
  ).toThrow('等待');
  held.resolve(1);
  await expect(a.completion).resolves.toBe(1);
  await expect(b.completion).resolves.toBe(2);
  runner.dispose();
});

it('moves replacement work to the tail and rejects the replaced operation', async () => {
  const runner = new RequestRunner({ maxConcurrent: 1, maxQueued: 2 });
  const held = deferred<void>();
  const calls: string[] = [];
  const a = runner.submit({
    key: 'a',
    policy: 'queue',
    timeoutMs: 1000,
    run: () => held.promise,
  });
  const b = runner.submit({
    key: 'b',
    policy: 'queue',
    timeoutMs: 1000,
    run: async () => calls.push('old'),
  });
  const c = runner.submit({
    key: 'c',
    policy: 'queue',
    timeoutMs: 1000,
    run: async () => calls.push('c'),
  });
  const replaced = expect(b.completion).rejects.toMatchObject({
    code: 'CANCELLED',
  });
  const next = runner.submit({
    key: 'b',
    policy: 'queue',
    timeoutMs: 1000,
    run: async () => calls.push('new'),
  });
  await replaced;
  held.resolve();
  await Promise.all([a.completion, c.completion, next.completion]);
  expect(calls).toEqual(['c', 'new']);
  runner.dispose();
});

it('does not let late completion release the replacement slot', async () => {
  const runner = new RequestRunner({ maxConcurrent: 1, maxQueued: 2 });
  const old = deferred<number>();
  const next = deferred<number>();
  const a = runner.submit({
    key: 'a',
    policy: 'queue',
    timeoutMs: 1000,
    run: () => old.promise,
  });
  const cancelled = expect(a.completion).rejects.toMatchObject({
    code: 'CANCELLED',
  });
  const replacement = runner.submit({
    key: 'a',
    policy: 'queue',
    timeoutMs: 1000,
    run: () => next.promise,
  });
  await cancelled;
  const later = vi.fn(async () => 3);
  const b = runner.submit({
    key: 'b',
    policy: 'queue',
    timeoutMs: 1000,
    run: later,
  });
  old.resolve(1);
  await Promise.resolve();
  expect(later).not.toHaveBeenCalled();
  next.resolve(2);
  await expect(replacement.completion).resolves.toBe(2);
  await expect(b.completion).resolves.toBe(3);
  runner.dispose();
});

it('bounds execution, ignores late success and settles disposal exactly once', async () => {
  vi.useFakeTimers();
  const runner = new RequestRunner({ maxConcurrent: 1, maxQueued: 2 });
  const held = deferred<number>();
  let signal: AbortSignal | undefined;
  const a = runner.submit({
    key: 'a',
    policy: 'queue',
    timeoutMs: 10,
    run: s => {
      signal = s;
      return held.promise;
    },
  });
  const timeout = expect(a.completion).rejects.toMatchObject({
    code: 'TIMEOUT',
  });
  await vi.advanceTimersByTimeAsync(11);
  await timeout;
  expect(signal?.aborted).toBe(true);
  held.resolve(1);
  const b = runner.submit({
    key: 'b',
    policy: 'queue',
    timeoutMs: 10,
    run: () => new Promise(() => {}),
  });
  const c = runner.submit({
    key: 'c',
    policy: 'queue',
    timeoutMs: 10,
    run: async () => 3,
  });
  const bCancelled = expect(b.completion).rejects.toMatchObject({
    code: 'CANCELLED',
  });
  const cCancelled = expect(c.completion).rejects.toMatchObject({
    code: 'CANCELLED',
  });
  runner.dispose();
  runner.dispose();
  await Promise.all([bCancelled, cCancelled]);
  expect(vi.getTimerCount()).toBe(0);
  expect(() =>
    runner.submit({
      key: 'd',
      policy: 'queue',
      timeoutMs: 10,
      run: async () => 4,
    }),
  ).toThrow('释放');
});

it('preserves non-Error rejections and observes cancellation while waiting', async () => {
  const runner = new RequestRunner({ maxConcurrent: 1, maxQueued: 2 });
  const reason = { status: 503 };
  const failed = runner.submit({
    key: 'failed',
    policy: 'reject',
    timeoutMs: 1000,
    run: () => Promise.reject(reason),
  });
  await expect(failed.completion).rejects.toBe(reason);
  const held = deferred<void>();
  const a = runner.submit({
    key: 'a',
    policy: 'queue',
    timeoutMs: 1000,
    run: () => held.promise,
  });
  const controller = new AbortController();
  const run = vi.fn(async () => 2);
  const b = runner.submit({
    key: 'b',
    policy: 'queue',
    timeoutMs: 1000,
    controller,
    run,
  });
  const cancelled = expect(b.completion).rejects.toMatchObject({
    code: 'CANCELLED',
  });
  controller.abort();
  await cancelled;
  held.resolve();
  await a.completion;
  expect(run).not.toHaveBeenCalled();
  runner.dispose();
});

it('admits replacement of active work when the waiting queue is full', async () => {
  const runner = new RequestRunner({ maxConcurrent: 1, maxQueued: 1 });
  const held = deferred<number>();
  const a = runner.submit({
    key: 'a',
    policy: 'queue',
    timeoutMs: 1000,
    run: () => held.promise,
  });
  const b = runner.submit({
    key: 'b',
    policy: 'queue',
    timeoutMs: 1000,
    run: async () => 2,
  });
  const cancelled = expect(a.completion).rejects.toMatchObject({
    code: 'CANCELLED',
  });
  try {
    const replacement = runner.submit({
      key: 'a',
      policy: 'queue',
      timeoutMs: 1000,
      run: async () => 3,
    });
    await cancelled;
    await expect(b.completion).resolves.toBe(2);
    await expect(replacement.completion).resolves.toBe(3);
  } finally {
    runner.dispose();
    await cancelled;
    await b.completion.catch(() => {});
  }
});

it.each([12, 24])(
  'finishes %i slow reads without exceeding the concurrency budget',
  async count => {
    vi.useFakeTimers();
    const runner = new RequestRunner({ maxConcurrent: 4, maxQueued: 48 });
    let active = 0;
    let peak = 0;
    const operations = Array.from({ length: count }, (_, index) =>
      runner.submit({
        key: String(index),
        policy: 'queue',
        timeoutMs: 30000,
        run: () => {
          peak = Math.max(peak, ++active);
          return new Promise<number>(resolve =>
            setTimeout(() => {
              active--;
              resolve(index);
            }, 20000),
          );
        },
      }),
    );
    const completed = Promise.all(
      operations.map(operation => operation.completion),
    );
    await vi.advanceTimersByTimeAsync(Math.ceil(count / 4) * 20000);
    await expect(completed).resolves.toEqual(
      Array.from({ length: count }, (_, i) => i),
    );
    expect(peak).toBe(4);
    expect(active).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
    runner.dispose();
  },
);

it('caps long queue deadlines within the native timer range', async () => {
  vi.useFakeTimers();
  const timer = vi.spyOn(globalThis, 'setTimeout');
  const runner = new RequestRunner({
    maxConcurrent: 1,
    maxQueued: 48,
    maxTimeoutMs: 2147483647,
  });
  const first = deferred<number>();
  const a = runner.submit({
    key: 'a',
    policy: 'queue',
    timeoutMs: 2147483647,
    run: () => first.promise,
  });
  const run = vi.fn(async () => 2);
  const b = runner.submit({
    key: 'b',
    policy: 'queue',
    timeoutMs: 2147483647,
    run,
  });
  try {
    expect(timer.mock.calls.every(([, delay]) => delay! <= 2147483647)).toBe(
      true,
    );
    await vi.advanceTimersByTimeAsync(1000);
    expect(run).not.toHaveBeenCalled();
    first.resolve(1);
    await expect(a.completion).resolves.toBe(1);
    await expect(b.completion).resolves.toBe(2);
  } finally {
    runner.dispose();
    timer.mockRestore();
  }
});
