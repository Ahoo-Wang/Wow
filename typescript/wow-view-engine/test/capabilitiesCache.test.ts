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
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
import { DEFAULT_RUNTIME_LIMITS } from '../src/index.js';
import {
  DESCRIPTOR_MAX_AGE_MS,
  DescriptorCache,
  sourceLimits,
  type Describe,
} from '../src/capabilities/index.js';
import { deferred } from './fixtures.js';
import { notModified, ordersDescriptor, read } from './fixtures/descriptor.js';

function limited(
  limits: Partial<QueryModelDescriptor['limits']>,
): QueryModelDescriptor {
  const base = ordersDescriptor();
  return { ...base, limits: { ...base.limits, ...limits } };
}

describe('4.5 the limits a source says', () => {
  it("are the defaults, or the host's over them, without a descriptor", () => {
    expect(sourceLimits(undefined, null)).toBe(DEFAULT_RUNTIME_LIMITS);
    expect(sourceLimits({ maxPageSize: 50 }, null)).toEqual({
      ...DEFAULT_RUNTIME_LIMITS,
      maxPageSize: 50,
    });
  });

  it("are the descriptor's where it has one, a raised guard included", () => {
    const limits = sourceLimits(
      undefined,
      limited({
        maxPageSize: 500,
        maxPageWindow: 50_000,
        maxFilterNodes: 512,
        aggregation: {
          ...ordersDescriptor().limits.aggregation,
          maxLimit: 5_000,
        },
      }),
    );

    expect(limits).toMatchObject({
      maxPageSize: 500,
      maxPageWindow: 50_000,
      maxFilterNodes: 512,
      maxAnalysisRows: 5_000,
    });
    // The engine's own budgets are untouched.
    expect(limits.maxConcurrentQueries).toBe(
      DEFAULT_RUNTIME_LIMITS.maxConcurrentQueries,
    );
  });

  it('only lower where the host sets one', () => {
    const descriptor = limited({ maxPageSize: 500, maxPageWindow: 5_000 });

    expect(
      sourceLimits({ maxPageSize: 50, maxPageWindow: 20_000 }, descriptor),
    ).toMatchObject({ maxPageSize: 50, maxPageWindow: 5_000 });
  });

  it('read an unbounded one as no bound, a page still asked for in one piece', () => {
    const limits = sourceLimits(
      { pageSizes: [10, 200] },
      limited({ maxPageSize: null, maxPageWindow: null, maxFilterNodes: null }),
    );

    expect(limits.maxPageWindow).toBe(Number.POSITIVE_INFINITY);
    expect(limits.maxFilterNodes).toBe(Number.POSITIVE_INFINITY);
    // The largest rung the engine offers, ladders and default together.
    expect(limits.maxPageSize).toBe(200);
  });
});

describe('the descriptor cache', () => {
  function clocked() {
    let now = 0;
    return {
      now: () => now,
      advance: (ms: number) => {
        now += ms;
      },
    };
  }

  it('waits for the first read, and answers from what it holds after', async () => {
    const clock = clocked();
    const describe = vi.fn<Describe>(() =>
      Promise.resolve(read(ordersDescriptor())),
    );
    const cache = new DescriptorCache({ now: clock.now });

    expect(cache.current('orders')).toBeNull();
    await expect(cache.load('orders', describe)).resolves.toMatchObject({
      version: 'sha256:orders-1',
    });
    await cache.load('orders', describe);

    expect(describe).toHaveBeenCalledTimes(1);
    expect(describe).toHaveBeenCalledWith(undefined);
    expect(cache.current('orders')?.version).toBe('sha256:orders-1');
  });

  it('shares one request among reads asked for at once', async () => {
    const answer = deferred<ReturnType<typeof read>>();
    const describe = vi.fn<Describe>(() => answer.promise);
    const cache = new DescriptorCache({ now: () => 0 });

    const first = cache.load('orders', describe);
    const second = cache.load('orders', describe);
    const forced = cache.revalidate('orders', true);
    answer.resolve(read(ordersDescriptor()));
    await Promise.all([first, second, forced]);

    expect(describe).toHaveBeenCalledTimes(1);
  });

  it('checks again only past the maximum age, sending the version held, and keeps it on 304', async () => {
    const clock = clocked();
    const describe = vi
      .fn<Describe>()
      .mockResolvedValueOnce(read(ordersDescriptor()))
      .mockResolvedValue(notModified('sha256:orders-1'));
    const cache = new DescriptorCache({ now: clock.now });

    await cache.load('orders', describe);
    clock.advance(DESCRIPTOR_MAX_AGE_MS - 1);
    await cache.revalidate('orders');
    expect(describe).toHaveBeenCalledTimes(1);

    clock.advance(1);
    await cache.revalidate('orders');
    expect(describe).toHaveBeenCalledTimes(2);
    expect(describe).toHaveBeenLastCalledWith('sha256:orders-1');
    expect(cache.current('orders')).toMatchObject({
      version: 'sha256:orders-1',
      fields: ordersDescriptor().fields,
    });

    // Checked just now, so fresh again.
    await cache.revalidate('orders');
    expect(describe).toHaveBeenCalledTimes(2);
  });

  it('revalidates in the background when a stale one is loaded', async () => {
    const clock = clocked();
    const next = { ...ordersDescriptor(), version: 'sha256:orders-2' };
    const answer = deferred<ReturnType<typeof read>>();
    const describe = vi
      .fn<Describe>()
      .mockResolvedValueOnce(read(ordersDescriptor()))
      .mockReturnValueOnce(answer.promise);
    const cache = new DescriptorCache({ now: clock.now });

    await cache.load('orders', describe);
    clock.advance(DESCRIPTOR_MAX_AGE_MS);
    // Answered with what is held; the check runs behind it.
    await expect(cache.load('orders', describe)).resolves.toMatchObject({
      version: 'sha256:orders-1',
    });
    expect(describe).toHaveBeenCalledTimes(2);

    answer.resolve(read(next));
    await cache.revalidate('orders');
    expect(cache.current('orders')).toBe(next);
  });

  it('keeps what it holds when a read fails, says so, and tries again past the maximum age', async () => {
    const clock = clocked();
    const failed = vi.fn();
    const describe = vi
      .fn<Describe>()
      .mockResolvedValueOnce(read(ordersDescriptor()))
      .mockRejectedValueOnce(new Error('503'))
      .mockResolvedValue(notModified('sha256:orders-1'));
    const cache = new DescriptorCache({ now: clock.now, failed });

    await cache.load('orders', describe);
    clock.advance(DESCRIPTOR_MAX_AGE_MS);
    await cache.revalidate('orders');

    expect(failed).toHaveBeenCalledWith('orders', new Error('503'));
    expect(cache.current('orders')?.version).toBe('sha256:orders-1');

    await cache.revalidate('orders');
    expect(describe).toHaveBeenCalledTimes(2);
    clock.advance(DESCRIPTOR_MAX_AGE_MS);
    cache.revalidateStale();
    expect(describe).toHaveBeenCalledTimes(3);
  });

  it('answers null for a source it never read, and does nothing for one it does not know', async () => {
    const describe = vi.fn<Describe>(() => Promise.reject(new Error('404')));
    const cache = new DescriptorCache({ now: () => 0 });

    await expect(cache.load('orders', describe)).resolves.toBeNull();
    await cache.revalidate('unknown');
    expect(describe).toHaveBeenCalledTimes(1);
  });
});
