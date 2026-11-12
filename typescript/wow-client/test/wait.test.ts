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

/**
 * The wait plans of `Wow-Wait` and the scopes of `Wow-Scope`, as the headers
 * spell them.
 */

import { describe, expect, it } from 'vitest';
import {
  formatHop,
  formatScope,
  formatWaitPlan,
  formatWaitTimeout,
  type Hop,
  parseWaitPlan,
  QUERYABLE_SUBSCRIPTION,
  wait,
  WaitPlan,
} from '../src/index.js';

describe('wait plans', () => {
  it('spells every hop as Wow-Wait reads it, and reads it back', () => {
    const plan = WaitPlan.of(
      wait.accepted(),
      wait.committed(),
      wait.snapshot(),
      wait.handled('BasketCheckoutSaga.onBasketCheckedOut', 'eshop-basket'),
      wait.committed('eshop-ordering'),
      wait.snapshot('eshop-ordering'),
      wait.handled('OrderProcessSaga.onOrderSubmitted'),
      wait.accepted('eshop-inventory'),
    );
    const spelled =
      'accepted > committed > snapshot > handled(eshop-basket/BasketCheckoutSaga.onBasketCheckedOut) > committed(eshop-ordering) > snapshot(eshop-ordering) > handled(OrderProcessSaga.onOrderSubmitted) > accepted(eshop-inventory)';
    expect(formatWaitPlan(plan)).toBe(spelled);
    expect(parseWaitPlan(spelled).hops).toEqual(plan.hops);
    expect(
      parseWaitPlan(
        ' committed(ctx) >handled( wow.snapshot ) > handled(snapshot)',
      ).hops,
    ).toEqual([
      wait.committed('ctx'),
      wait.snapshot(),
      wait.handled('snapshot'),
    ]);
  });

  it('spells the queryable hop, the store the snapshot queries read, as Wow-Wait reads it', () => {
    expect(formatHop(wait.queryable())).toBe('queryable');
    expect(formatHop(wait.queryable('eshop-catalog'))).toBe(
      'queryable(eshop-catalog)',
    );
    expect(
      parseWaitPlan(
        'committed > queryable(eshop-catalog) > handled(wow.queryable)',
      ).hops,
    ).toEqual([
      wait.committed(),
      wait.queryable('eshop-catalog'),
      wait.queryable(),
    ]);
    expect(wait.queryable().subscription).toBe(QUERYABLE_SUBSCRIPTION);
  });

  it('holds Kotlin WaitPlan constants, a default timeout of 10 seconds, and within', () => {
    expect(WaitPlan.DEFAULT_TIMEOUT).toBe(10_000);
    expect(WaitPlan.DEFAULT).toMatchObject({
      hops: [wait.snapshot()],
      timeout: 10_000,
      isDefault: true,
    });
    expect(WaitPlan.SNAPSHOT.isDefault).toBe(false);
    expect(formatWaitPlan(WaitPlan.COMMITTED)).toBe('committed');
    expect(formatWaitPlan(WaitPlan.ACCEPTED)).toBe('accepted');
    const within = WaitPlan.COMMITTED.within(3000);
    expect(within.timeout).toBe(3000);
    expect(WaitPlan.COMMITTED.timeout).toBe(10_000);
    expect(WaitPlan.DEFAULT.within(1000).isDefault).toBe(true);
    expect(Object.isFrozen(within)).toBe(true);
  });

  it('refuses what the header cannot carry', () => {
    expect(() => WaitPlan.of()).toThrow(TypeError);
    expect(() => WaitPlan.COMMITTED.within(0)).toThrow(TypeError);
    expect(() => wait.committed('a b')).toThrow(TypeError);
    expect(() => wait.handled('x(y)')).toThrow(TypeError);
    expect(() =>
      formatWaitPlan(WaitPlan.of({ stage: 'handled' } as Hop)),
    ).toThrow(TypeError);
    expect(() =>
      formatWaitPlan(
        WaitPlan.of({ stage: 'committed', subscription: 'x' } as Hop),
      ),
    ).toThrow(TypeError);
    for (const value of [
      'committed(',
      'accepted(a(b))',
      '> committed',
      '',
      'shipped',
      'handled',
      'handled(a/b/c)',
      'committed(a b)',
    ])
      expect(() => parseWaitPlan(value), value).toThrow(TypeError);
  });

  it('spells timeouts in seconds when whole, else milliseconds', () => {
    expect(formatWaitTimeout(10_000)).toBe('10s');
    expect(formatWaitTimeout(1500)).toBe('1500ms');
    expect(() => formatWaitTimeout(0)).toThrow(TypeError);
    expect(() => formatWaitTimeout(1.5)).toThrow(TypeError);
  });
});

describe('scopes and segments', () => {
  it('spells Wow-Scope as an RFC 8941 dictionary', () => {
    expect(
      formatScope({ tenantId: 't1', ownerId: 'u"1', spaceId: 's\\1' }),
    ).toBe('tenant="t1", owner="u\\"1", space="s\\\\1"');
    expect(() => formatScope({})).toThrow(TypeError);
  });
});
