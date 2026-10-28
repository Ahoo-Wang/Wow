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
  type ReferenceId,
} from '../src/runtime/referenceLabels.js';
import { deferred, nextTask } from './fixtures.js';

/** A customer's name, as the source answers it. */
const named = (id: ReferenceId): FieldOption => ({
  value: id,
  label: `Customer ${id}`,
});

/**
 * A source that answers every id it is asked, or what `answer` says, and
 * keeps each request's ids and signal.
 */
function source(
  answer: (
    ids: ReferenceId[],
    signal: AbortSignal | undefined,
  ) => Promise<FieldOption[]> = ids => Promise.resolve(ids.map(named)),
) {
  const asked: ReferenceId[][] = [];
  const signals: (AbortSignal | undefined)[] = [];
  const options: OptionSource = {
    search: () => Promise.resolve({ items: [], nextCursor: null }),
    resolve: (ids, { signal } = {}) => {
      asked.push([...ids]);
      signals.push(signal);
      return answer(ids, signal);
    },
  };
  return { options, asked, signals };
}

function labelsOver(
  options: OptionSource | null,
  batch = 200,
): { labels: ReferenceLabels; failed: ReturnType<typeof vi.fn> } {
  const failed = vi.fn();
  const labels = new ReferenceLabels({
    resolveOptions: options ? () => options : undefined,
    batch,
    failed,
  });
  return { labels, failed };
}

describe('ReferenceLabels asks each id once', () => {
  it('asks one resolve for a set of ids, duplicates once', async () => {
    const customers = source();
    const { labels } = labelsOver(customers.options);

    await labels.want('customers', ['c1', 'c2', 'c1', 3, '3', '', 'c2']);

    expect(customers.asked).toEqual([['c1', 'c2', 3]]);
    expect(labels.text('customers', 'c1')).toBe('Customer c1');
    // A row holding `3` and one holding `'3'` read the same name.
    expect(labels.text('customers', '3')).toBe('Customer 3');
    expect(labels.label('customers', 'c2')).toEqual({
      status: 'resolved',
      option: named('c2'),
    });
  });

  it('asks only the ids it does not know yet', async () => {
    const customers = source();
    const { labels } = labelsOver(customers.options);

    await labels.want('customers', ['c1', 'c2']);
    await labels.want('customers', ['c2', 'c3']);
    await labels.want('customers', ['c1', 'c3']);

    expect(customers.asked).toEqual([['c1', 'c2'], ['c3']]);
  });

  it('keeps remotes apart', async () => {
    const customers = source();
    const { labels } = labelsOver(customers.options);

    await labels.want('customers', ['x']);
    await labels.want('suppliers', ['x']);

    expect(customers.asked).toEqual([['x'], ['x']]);
  });

  it('joins ids already in flight rather than asking them again', async () => {
    const answer = deferred<FieldOption[]>();
    const customers = source(() => answer.promise);
    const { labels } = labelsOver(customers.options);

    const first = labels.want('customers', ['c1', 'c2']);
    expect(labels.label('customers', 'c1')).toEqual({ status: 'pending' });
    const second = labels.want('customers', ['c2', 'c1']);
    expect(customers.asked).toHaveLength(1);

    answer.resolve([named('c1'), named('c2')]);
    await Promise.all([first, second]);
    expect(customers.asked).toHaveLength(1);
    expect(labels.text('customers', 'c2')).toBe('Customer c2');
  });

  it('asks in requests of at most `batch` ids', async () => {
    const customers = source();
    const { labels } = labelsOver(customers.options, 2);

    await labels.want('customers', [1, 2, 3, 4, 5]);

    expect(customers.asked).toEqual([[1, 2], [3, 4], [5]]);
    expect(labels.text('customers', 5)).toBe('Customer 5');
  });

  it('reads an id the answer leaves out as itself, and never asks it again', async () => {
    const customers = source(ids =>
      Promise.resolve(ids.filter(id => id !== 'gone').map(named)),
    );
    const { labels } = labelsOver(customers.options);

    await labels.want('customers', ['c1', 'gone']);
    await labels.want('customers', ['gone']);

    expect(customers.asked).toEqual([['c1', 'gone']]);
    expect(labels.label('customers', 'gone')).toEqual({ status: 'unresolved' });
    expect(labels.text('customers', 'gone')).toBe('gone');
  });
});

describe('ReferenceLabels on a failed read', () => {
  it('tells the host, caches nothing and asks again next time', async () => {
    const boom = new Error('customers are down');
    let fail = true;
    const customers = source(ids =>
      fail ? Promise.reject(boom) : Promise.resolve(ids.map(named)),
    );
    const { labels, failed } = labelsOver(customers.options);

    await expect(labels.want('customers', ['c1'])).resolves.toBeUndefined();
    expect(failed).toHaveBeenCalledExactlyOnceWith('customers', boom);
    expect(labels.label('customers', 'c1')).toEqual({ status: 'unresolved' });
    expect(labels.text('customers', 'c1')).toBe('c1');

    fail = false;
    await labels.want('customers', ['c1']);
    expect(customers.asked).toEqual([['c1'], ['c1']]);
    expect(labels.text('customers', 'c1')).toBe('Customer c1');
  });

  it('fails the same way when the source throws rather than rejects', async () => {
    const boom = new Error('not wired');
    const customers = source(() => {
      throw boom;
    });
    const { labels, failed } = labelsOver(customers.options);

    await expect(labels.want('customers', ['c1'])).resolves.toBeUndefined();
    expect(failed).toHaveBeenCalledExactlyOnceWith('customers', boom);
    await labels.want('customers', ['c1']);
    expect(customers.asked).toHaveLength(2);
  });
});

describe('ReferenceLabels called off', () => {
  it('keeps a request running while one of its callers still waits', async () => {
    const answer = deferred<FieldOption[]>();
    const customers = source(() => answer.promise);
    const { labels, failed } = labelsOver(customers.options);
    const one = new AbortController();
    const other = new AbortController();

    const left = labels.want('customers', ['c1'], { signal: one.signal });
    const stays = labels.want('customers', ['c1'], { signal: other.signal });
    one.abort(new Error('the first page is gone'));

    await expect(left).rejects.toThrow('the first page is gone');
    expect(customers.signals[0]?.aborted).toBe(false);
    answer.resolve([named('c1')]);
    await stays;
    expect(labels.text('customers', 'c1')).toBe('Customer c1');
    expect(failed).not.toHaveBeenCalled();
  });

  it('calls the source off once every caller has', async () => {
    const answer = deferred<FieldOption[]>();
    const customers = source(() => answer.promise);
    const { labels, failed } = labelsOver(customers.options);
    const one = new AbortController();
    const other = new AbortController();

    const first = labels.want('customers', ['c1'], { signal: one.signal });
    const second = labels.want('customers', ['c1'], { signal: other.signal });
    one.abort();
    other.abort();

    await expect(first).rejects.toBeDefined();
    await expect(second).rejects.toBeDefined();
    expect(customers.signals[0]?.aborted).toBe(true);
    // What lands after it was called off is not taken, nor is it a failure.
    answer.resolve([named('c1')]);
    await nextTask();
    expect(labels.label('customers', 'c1')).toEqual({ status: 'unresolved' });
    expect(failed).not.toHaveBeenCalled();
  });

  it('asks afresh for a caller arriving after everyone called off', async () => {
    const customers = source(
      (ids, signal) =>
        new Promise((resolve, reject) => {
          if (customers.asked.length > 1) resolve(ids.map(named));
          else signal?.addEventListener('abort', () => reject(signal.reason));
        }),
    );
    const { labels } = labelsOver(customers.options);
    const gone = new AbortController();

    const first = labels.want('customers', ['c1'], { signal: gone.signal });
    gone.abort();
    await expect(first).rejects.toBeDefined();
    await labels.want('customers', ['c1']);

    expect(customers.asked).toEqual([['c1'], ['c1']]);
    expect(labels.text('customers', 'c1')).toBe('Customer c1');
  });

  it('rejects at once, asking nothing, for a signal already aborted', async () => {
    const customers = source();
    const { labels } = labelsOver(customers.options);
    const controller = new AbortController();
    controller.abort(new Error('closed'));

    await expect(
      labels.want('customers', ['c1'], { signal: controller.signal }),
    ).rejects.toThrow('closed');
    expect(customers.asked).toEqual([]);
  });

  it('settles a caller whose ids all landed, its later abort a no-op', async () => {
    const customers = source();
    const { labels } = labelsOver(customers.options);
    const controller = new AbortController();

    await labels.want('customers', ['c1'], { signal: controller.signal });
    const version = labels.version;
    controller.abort();

    expect(labels.version).toBe(version);
    expect(labels.text('customers', 'c1')).toBe('Customer c1');
  });
});

describe('ReferenceLabels invalidated', () => {
  async function known() {
    const customers = source();
    const { labels } = labelsOver(customers.options);
    await labels.want('customers', ['c1', 'c2']);
    await labels.want('suppliers', ['s1']);
    customers.asked.length = 0;
    return { labels, customers };
  }

  it('forgets one remote', async () => {
    const { labels, customers } = await known();

    labels.invalidate('customers');
    expect(labels.text('customers', 'c1')).toBe('c1');
    expect(labels.text('suppliers', 's1')).toBe('Customer s1');
    await labels.want('customers', ['c1', 'c2']);
    await labels.want('suppliers', ['s1']);

    expect(customers.asked).toEqual([['c1', 'c2']]);
  });

  it('forgets some ids of one remote', async () => {
    const { labels, customers } = await known();

    labels.invalidate('customers', ['c2']);
    await labels.want('customers', ['c1', 'c2']);

    expect(customers.asked).toEqual([['c2']]);
  });

  it('forgets everything', async () => {
    const { labels, customers } = await known();

    labels.invalidate();
    await labels.want('customers', ['c1']);
    await labels.want('suppliers', ['s1']);

    expect(customers.asked).toEqual([['c1'], ['s1']]);
  });
});

describe('ReferenceLabels tells its readers', () => {
  it('moves its version and calls listeners on every change', async () => {
    const answer = deferred<FieldOption[]>();
    const customers = source(() => answer.promise);
    const { labels } = labelsOver(customers.options);
    const listener = vi.fn();
    const stop = labels.subscribe(listener);
    const start = labels.version;

    const wanted = labels.want('customers', ['c1']);
    // Asked: the id reads pending.
    expect(listener).toHaveBeenCalledTimes(1);
    expect(labels.version).toBe(start + 1);
    answer.resolve([named('c1')]);
    await wanted;
    expect(listener).toHaveBeenCalledTimes(2);
    expect(labels.version).toBe(start + 2);

    labels.invalidate('customers');
    expect(listener).toHaveBeenCalledTimes(3);

    stop();
    labels.invalidate();
    expect(listener).toHaveBeenCalledTimes(3);
    expect(labels.version).toBe(start + 4);
  });

  it('says nothing for a want with nothing to ask', async () => {
    const customers = source();
    const { labels } = labelsOver(customers.options);
    await labels.want('customers', ['c1']);
    const listener = vi.fn();
    labels.subscribe(listener);

    await labels.want('customers', ['c1', null as never, undefined as never]);

    expect(listener).not.toHaveBeenCalled();
  });
});

describe('ReferenceLabels without a source', () => {
  it('reads every id as itself and asks nothing with no resolveOptions', async () => {
    const { labels } = labelsOver(null);

    await expect(labels.want('customers', ['c1'])).resolves.toBeUndefined();
    expect(labels.sourceOf('customers')).toBeNull();
    expect(labels.text('customers', 'c1')).toBe('c1');
    expect(labels.label('customers', 'c1')).toEqual({ status: 'unresolved' });
  });

  it('reads ids as themselves for a remote resolveOptions throws on, asked once', async () => {
    const resolveOptions = vi.fn((): OptionSource => {
      throw new Error('no such remote');
    });
    const failed = vi.fn();
    const labels = new ReferenceLabels({ resolveOptions, batch: 200, failed });

    await labels.want('nowhere', ['x']);
    await labels.want('nowhere', ['y']);

    expect(labels.text('nowhere', 'x')).toBe('x');
    expect(resolveOptions).toHaveBeenCalledTimes(1);
    expect(failed).not.toHaveBeenCalled();
  });
});
