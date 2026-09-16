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

import { describe, expect, it, vi } from 'vitest';
import {
  DEFAULT_RUNTIME_LIMITS,
  isRequestSuperseded,
  RequestQueueFullError,
  RequestRunner,
  type RuntimeLimits,
} from '../src/index.js';
import { deferred } from './fixtures.js';

function limits(overrides: Partial<RuntimeLimits> = {}): RuntimeLimits {
  return { ...DEFAULT_RUNTIME_LIMITS, ...overrides };
}

describe('RequestRunner', () => {
  it('runs a task and returns its value', async () => {
    const runner = new RequestRunner();
    await expect(runner.run('a', () => Promise.resolve(7))).resolves.toBe(7);
    expect(runner.active).toBe(0);
  });

  it('passes a controller the task can hand to its source', async () => {
    const runner = new RequestRunner();
    const seen = await runner.run('a', controller =>
      Promise.resolve(controller.signal.aborted),
    );
    expect(seen).toBe(false);
  });

  it('propagates a failure', async () => {
    const runner = new RequestRunner();
    await expect(
      runner.run('a', () => Promise.reject(new Error('boom'))),
    ).rejects.toThrow('boom');
  });

  it('supersedes the request under the same key and aborts it', async () => {
    const runner = new RequestRunner();
    const first = deferred<string>();
    let aborted = false;

    const superseded = runner.run('view', controller => {
      controller.signal.addEventListener('abort', () => {
        aborted = true;
      });
      return first.promise;
    });
    const winner = runner.run('view', () => Promise.resolve('second'));

    await expect(superseded).rejects.toSatisfy(isRequestSuperseded);
    await expect(winner).resolves.toBe('second');
    expect(aborted).toBe(true);
  });

  it('keeps separate keys independent', async () => {
    const runner = new RequestRunner();
    const [a, b] = await Promise.all([
      runner.run('a', () => Promise.resolve('a')),
      runner.run('b', () => Promise.resolve('b')),
    ]);
    expect([a, b]).toEqual(['a', 'b']);
  });

  it('runs at most the configured number at once', async () => {
    const runner = new RequestRunner(limits({ maxConcurrentQueries: 2 }));
    const gates = [deferred<number>(), deferred<number>(), deferred<number>()];
    const started: number[] = [];

    const all = gates.map((gate, index) =>
      runner.run(`key-${index}`, () => {
        started.push(index);
        return gate.promise;
      }),
    );

    expect(started).toEqual([0, 1]);
    expect(runner.queued).toBe(1);

    gates[0].resolve(0);
    await all[0];
    expect(started).toEqual([0, 1, 2]);

    gates[1].resolve(1);
    gates[2].resolve(2);
    await Promise.all(all);
    expect(runner.active).toBe(0);
  });

  it('rejects rather than buffering once the queue is full', async () => {
    const runner = new RequestRunner(
      limits({ maxConcurrentQueries: 1, maxQueuedQueries: 1 }),
    );
    const gate = deferred<string>();

    const running = runner.run('a', () => gate.promise);
    const queued = runner.run('b', () => Promise.resolve('b'));
    const rejected = runner.run('c', () => Promise.resolve('c'));

    await expect(rejected).rejects.toBeInstanceOf(RequestQueueFullError);
    gate.resolve('a');
    await expect(running).resolves.toBe('a');
    await expect(queued).resolves.toBe('b');
  });

  it('drops a queued request when its key is superseded', async () => {
    const runner = new RequestRunner(limits({ maxConcurrentQueries: 1 }));
    const gate = deferred<string>();
    const blocking = runner.run('a', () => gate.promise);
    const task = vi.fn(() => Promise.resolve('never'));

    const first = runner.run('b', task);
    const second = runner.run('b', () => Promise.resolve('second'));

    await expect(first).rejects.toSatisfy(isRequestSuperseded);
    expect(task).not.toHaveBeenCalled();
    expect(runner.queued).toBe(1);

    gate.resolve('a');
    await blocking;
    await expect(second).resolves.toBe('second');
  });

  it('aborts a request that has already started', async () => {
    const runner = new RequestRunner();
    const gate = deferred<string>();
    let aborted = false;

    const running = runner.run('a', controller => {
      controller.signal.addEventListener('abort', () => {
        aborted = true;
      });
      return gate.promise;
    });
    runner.cancel('a');

    await expect(running).rejects.toSatisfy(isRequestSuperseded);
    expect(aborted).toBe(true);
    expect(runner.queued).toBe(0);
  });

  it('ignores a cancel for a key it is not running', () => {
    const runner = new RequestRunner();
    expect(() => runner.cancel('unknown')).not.toThrow();
  });

  it('cancels everything at once', async () => {
    const runner = new RequestRunner();
    const a = runner.run('a', () => deferred<string>().promise);
    const b = runner.run('b', () => deferred<string>().promise);

    runner.cancelAll();

    await expect(a).rejects.toSatisfy(isRequestSuperseded);
    await expect(b).rejects.toSatisfy(isRequestSuperseded);
  });

  it('does not recognise an unrelated error as superseded', () => {
    expect(isRequestSuperseded(new Error('other'))).toBe(false);
  });
});
