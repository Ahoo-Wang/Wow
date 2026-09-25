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
 * Every public method of a query client is on its interface (F14), so code
 * written against `SnapshotQueryApi`, `EventStreamQueryApi`,
 * `LoadStateAggregateApi` or `LoadOwnerStateAggregateApi` can take a test
 * double and still reach it.
 */

import { describe, expect, expectTypeOf, it, vi } from 'vitest';
import {
  EventStreamQueryClient,
  LoadOwnerStateAggregateClient,
  LoadStateAggregateClient,
  SnapshotQueryClient,
  type EventStreamQueryApi,
  type LoadOwnerStateAggregateApi,
  type LoadStateAggregateApi,
  type MaterializedSnapshot,
  type SnapshotQueryApi,
} from '../../../src';

interface Cart {
  items: string[];
}

/** The public methods a class declares on its prototype. */
function methodsOf(type: { prototype: object }): string[] {
  return Object.getOwnPropertyNames(type.prototype)
    .filter(name => name !== 'constructor')
    .sort();
}

describe('query API interfaces', () => {
  it('are implemented by their clients', () => {
    expectTypeOf<SnapshotQueryClient<Cart>>().toExtend<
      SnapshotQueryApi<Cart>
    >();
    expectTypeOf<EventStreamQueryClient<Cart>>().toExtend<
      EventStreamQueryApi<Cart>
    >();
    expectTypeOf<LoadStateAggregateClient<Cart>>().toExtend<
      LoadStateAggregateApi<Cart>
    >();
    expectTypeOf<LoadOwnerStateAggregateClient<Cart>>().toExtend<
      LoadOwnerStateAggregateApi<Cart>
    >();
  });

  it('declare every method their client has', () => {
    type Keys<T> = Extract<keyof T, string>;
    const snapshotApi: Record<Keys<SnapshotQueryApi<Cart>>, true> = {
      single: true,
      singleState: true,
      list: true,
      listState: true,
      listStream: true,
      listStateStream: true,
      paged: true,
      pagedState: true,
      cursor: true,
      cursorState: true,
      count: true,
      aggregate: true,
      aggregateStream: true,
      getById: true,
      getStateById: true,
      getByIds: true,
      getStateByIds: true,
    };
    const eventApi: Record<Keys<EventStreamQueryApi<Cart>>, true> = {
      list: true,
      listStream: true,
      paged: true,
      cursor: true,
      count: true,
      aggregate: true,
      aggregateStream: true,
      load: true,
      loadStream: true,
    };
    const loadApi: Record<Keys<LoadStateAggregateApi<Cart>>, true> = {
      load: true,
      loadVersioned: true,
      loadTimeBased: true,
    };
    const loadOwnerApi: Record<Keys<LoadOwnerStateAggregateApi<Cart>>, true> = {
      load: true,
      loadVersioned: true,
      loadTimeBased: true,
    };
    expect(methodsOf(SnapshotQueryClient)).toEqual(
      Object.keys(snapshotApi).sort(),
    );
    expect(methodsOf(EventStreamQueryClient)).toEqual(
      Object.keys(eventApi).sort(),
    );
    expect(methodsOf(LoadStateAggregateClient)).toEqual(
      Object.keys(loadApi).sort(),
    );
    expect(methodsOf(LoadOwnerStateAggregateClient)).toEqual(
      Object.keys(loadOwnerApi).sort(),
    );
  });

  it('let code written against them take a test double', async () => {
    const snapshot = { state: { items: ['a'] } } as MaterializedSnapshot<Cart>;
    const double = {
      getById: vi.fn(async () => snapshot),
      getStateByIds: vi.fn(async (ids: string[]) =>
        ids.map(() => snapshot.state),
      ),
    } as Partial<SnapshotQueryApi<Cart>> as SnapshotQueryApi<Cart>;
    const load = {
      loadVersioned: vi.fn(async () => snapshot.state),
    } as Partial<LoadStateAggregateApi<Cart>> as LoadStateAggregateApi<Cart>;

    const readCart = (api: SnapshotQueryApi<Cart>) => api.getById('cart-1');
    const readStates = (api: SnapshotQueryApi<Cart>) =>
      api.getStateByIds(['cart-1', 'cart-2']);
    const readVersion = (api: LoadStateAggregateApi<Cart>) =>
      api.loadVersioned('cart-1', 3);

    await expect(readCart(double)).resolves.toBe(snapshot);
    await expect(readStates(double)).resolves.toEqual([
      snapshot.state,
      snapshot.state,
    ]);
    await expect(readVersion(load)).resolves.toBe(snapshot.state);
    expect(load.loadVersioned).toHaveBeenCalledWith('cart-1', 3);
  });
});
