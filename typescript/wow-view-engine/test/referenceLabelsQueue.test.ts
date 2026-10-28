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
import type { FieldOption, OptionSource } from '../src/index.js';
import {
  ReferenceLabels,
  ReferenceLabelTimeoutError,
  type ReferenceId,
} from '../src/runtime/referenceLabels.js';
import { nextTask } from './fixtures.js';

const named = (id: ReferenceId): FieldOption => ({
  value: id,
  label: `Customer ${id}`,
});

/** A source that answers only when told, oldest request first. */
function held() {
  const asked: ReferenceId[][] = [];
  const signals: AbortSignal[] = [];
  const answers: (() => void)[] = [];
  let open = 0;
  let most = 0;
  const options: OptionSource = {
    search: () => Promise.resolve({ items: [], nextCursor: null }),
    resolve: (ids, { signal } = {}) => {
      asked.push([...ids]);
      if (signal) signals.push(signal);
      open += 1;
      most = Math.max(most, open);
      // It never looks at its signal: only an answer ends it.
      return new Promise<FieldOption[]>(resolve =>
        answers.push(() => {
          open -= 1;
          resolve(ids.map(named));
        }),
      );
    },
  };
  return {
    options,
    asked,
    signals,
    land: async () => {
      answers.shift()?.();
      await nextTask();
    },
    get most() {
      return most;
    },
  };
}

/** Timers that fire only when told. */
function clock() {
  const due = new Map<number, () => void>();
  let next = 0;
  return {
    setTimeout: (callback: () => void) => {
      next += 1;
      due.set(next, callback);
      return next;
    },
    clearTimeout: (handle: unknown) => {
      due.delete(handle as number);
    },
    fire: async () => {
      for (const [handle, callback] of [...due]) {
        due.delete(handle);
        callback();
      }
      await nextTask();
    },
  };
}

function labelsOver(
  options: OptionSource,
  more: { batch?: number; concurrency?: number; timeout?: number } = {},
) {
  const failed = vi.fn();
  const timers = clock();
  const labels = new ReferenceLabels({
    resolveOptions: () => options,
    batch: more.batch ?? 1,
    concurrency: more.concurrency ?? 2,
    ...(more.timeout === undefined ? {} : { timeout: more.timeout }),
    timers,
    failed,
  });
  return { labels, failed, timers };
}

describe('ReferenceLabels puts a view ahead of an export', () => {
  it("starts a page's lookup at the next free slot, ahead of an export's queue", async () => {
    const customers = held();
    const { labels } = labelsOver(customers.options);
    const rows = Array.from({ length: 10 }, (_, n) => `e${n}`);

    const exported = labels.want('customers', rows, { lane: 'export' });
    expect(customers.asked).toEqual([['e0'], ['e1']]);
    const page = labels.want('customers', ['p1']);
    // The cap holds for both: the page waits for a slot, not for the file.
    expect(customers.asked).toHaveLength(2);

    await customers.land();
    expect(customers.asked[customers.asked.length - 1]).toEqual(['p1']);
    await customers.land();
    await customers.land();
    await page;
    expect(labels.text('customers', 'p1')).toBe('Customer p1');

    // Three of the eleven have landed; the export's other eight follow.
    for (let landed = 3; landed < 11; landed += 1) await customers.land();
    await exported;
    expect(customers.most).toBe(2);
    expect(customers.asked.flat()).toHaveLength(11);
  });

  it("takes an export's queued lookup into its own lane when a view joins it", async () => {
    const customers = held();
    const { labels } = labelsOver(customers.options, { concurrency: 1 });

    void labels.want('customers', ['e0', 'e1', 'e2'], { lane: 'export' });
    void labels.want('customers', ['p1']);
    // `e2` is the export's last; the view wants it too, so it leaves the
    // export's lane and goes in its place in the view's, ahead of `e1`.
    const page = labels.want('customers', ['e2']);
    await customers.land();
    expect(customers.asked).toEqual([['e0'], ['e2']]);
    await customers.land();
    await page;
    expect(customers.asked).toEqual([['e0'], ['e2'], ['p1']]);
    expect(labels.text('customers', 'e2')).toBe('Customer e2');
  });
});

describe('ReferenceLabels never lets a source hold a slot', () => {
  it('frees the slot the moment its last caller leaves, and drops the late answer', async () => {
    const customers = held();
    const { labels } = labelsOver(customers.options, { concurrency: 1 });
    const controller = new AbortController();

    const hung = labels.want('customers', ['h1'], {
      signal: controller.signal,
    });
    const next = labels.want('customers', ['n1']);
    expect(customers.asked).toEqual([['h1']]);

    controller.abort();
    await expect(hung).rejects.toMatchObject({ name: 'AbortError' });
    // The source never answered, yet the next lookup is out.
    expect(customers.asked).toEqual([['h1'], ['n1']]);
    expect(customers.signals[0]?.aborted).toBe(true);

    await customers.land(); // h1's late answer: nobody keeps it.
    expect(labels.label('customers', 'h1')).toEqual({ status: 'unresolved' });
    await customers.land();
    await next;
    expect(labels.text('customers', 'n1')).toBe('Customer n1');
  });

  it('fails a lookup past `timeout` as any read does, and frees its slot', async () => {
    const customers = held();
    const { labels, failed, timers } = labelsOver(customers.options, {
      concurrency: 1,
      timeout: 50,
    });

    const slow = labels.want('customers', ['s1']);
    const next = labels.want('customers', ['n1']);
    expect(customers.asked).toEqual([['s1']]);

    await timers.fire();
    // It settles rather than rejects: the id shows as itself.
    await slow;
    expect(labels.text('customers', 's1')).toBe('s1');
    expect(failed).toHaveBeenCalledTimes(1);
    expect(failed).toHaveBeenCalledWith(
      'customers',
      expect.any(ReferenceLabelTimeoutError),
    );
    expect(customers.signals[0]?.aborted).toBe(true);
    expect(customers.asked).toEqual([['s1'], ['n1']]);

    // The late answer is dropped, and nothing was remembered: s1 is asked
    // again by the next caller.
    await customers.land();
    expect(labels.label('customers', 's1')).toEqual({ status: 'unresolved' });
    await customers.land();
    await next;
    const again = labels.want('customers', ['s1']);
    expect(customers.asked[customers.asked.length - 1]).toEqual(['s1']);
    await customers.land();
    await again;
    expect(labels.text('customers', 's1')).toBe('Customer s1');
    expect(failed).toHaveBeenCalledTimes(1);
  });

  it('stops the clock when the answer comes first', async () => {
    const customers = held();
    const { labels, failed, timers } = labelsOver(customers.options, {
      timeout: 50,
    });

    const quick = labels.want('customers', ['q1']);
    await customers.land();
    await quick;
    await timers.fire();
    expect(failed).not.toHaveBeenCalled();
    expect(labels.text('customers', 'q1')).toBe('Customer q1');
  });
});
