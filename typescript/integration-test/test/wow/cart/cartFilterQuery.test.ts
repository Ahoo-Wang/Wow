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

import { beforeAll, describe, expect, it } from 'vitest';
import { HttpMethod } from '@ahoo-wang/fetcher';
import { idGenerator } from '@ahoo-wang/fetcher-cosec';
import {
  AggregationDatePart,
  aggregation,
  asc,
  CommandClient,
  commandHeaders,
  CommandStage,
  cursorQuery,
  type CursorPage,
  type CursorQuery,
  type DomainEventStream,
  ErrorCodes,
  filter,
  type FilterExpression,
  listQuery,
  type MaterializedSnapshot,
  pagedQuery,
  ResourceAttributionPathSpec,
  singleQuery,
  waitStrategy,
} from '@ahoo-wang/wow-client';
import { exampleFetcher } from '../../../src/wow';
import {
  type AddCartItem,
  type CartAggregatedFields,
  cartQueryClientFactory,
  type CartState,
} from '../../../src/generated';

/** The fields a cart query names: the values of the generated field enum. */
type CartFields = `${CartAggregatedFields}`;

/*
 * The current `filter.*` API against a real Wow 9 server. A cart is an
 * owner-level aggregate (its id is the owner id), so each cart is created
 * under its own owner, and the queries go through the unscoped routes
 * (`/cart/snapshot/...`, `/cart/event/...`) narrowed to this run's carts.
 */

const runId = idGenerator.generateId();
const cartA = `${runId}A`;
const cartB = `${runId}B`;
const cartC = `${runId}C`;
const cartIds = [cartA, cartB, cartC];
const productOne = `${runId}P1`;
const productTwo = `${runId}P2`;

async function addCartItem(
  ownerId: string,
  item: AddCartItem,
  aggregateVersion?: number,
) {
  const client = new CommandClient({
    fetcher: exampleFetcher,
    basePath: `owner/${ownerId}/cart`,
  });
  const result = await client.send({
    path: 'add_cart_item',
    method: HttpMethod.POST,
    headers: {
      ...commandHeaders({
        requestId: idGenerator.generateId(),
        aggregateVersion,
      }),
      ...waitStrategy({ stage: CommandStage.SNAPSHOT, timeoutMs: 10_000 }),
    },
    body: item,
  });
  expect(result.errorCode).toBe(ErrorCodes.SUCCEEDED);
  expect(result.stage).toBe(CommandStage.SNAPSHOT);
  expect(result.aggregateId).toBe(ownerId);
  return result;
}

// Cart A: P1 x2 (version 1, size 1)
// Cart B: P1 x1, then P2 x3 (version 2, size 2)
// Cart C: P1 x4 (version 1, size 1)
beforeAll(async () => {
  await addCartItem(cartA, { productId: productOne, quantity: 2 });
  await addCartItem(cartB, { productId: productOne, quantity: 1 });
  await addCartItem(cartB, { productId: productTwo, quantity: 3 }, 1);
  await addCartItem(cartC, { productId: productOne, quantity: 4 });
});

const snapshotClient = cartQueryClientFactory.createSnapshotQueryClient({
  contextAlias: '',
  resourceAttribution: ResourceAttributionPathSpec.NONE,
  fetcher: exampleFetcher,
});

const eventStreamClient = cartQueryClientFactory.createEventStreamQueryClient({
  contextAlias: '',
  resourceAttribution: ResourceAttributionPathSpec.NONE,
  fetcher: exampleFetcher,
});

const scope = filter.aggregateIds(cartIds);
const byAggregateId = [asc('aggregateId')];

async function collect<T>(stream: ReadableStream<T>): Promise<T[]> {
  const rows: T[] = [];
  for await (const row of stream) {
    rows.push(row);
  }
  return rows;
}

async function walk<T>(
  fetchPage: (query: CursorQuery<CartFields>) => Promise<CursorPage<T>>,
  size: number,
): Promise<{ pages: number; rows: T[] }> {
  const rows: T[] = [];
  let pages = 0;
  let cursor: string | null = null;
  do {
    const page = await fetchPage(
      cursorQuery<CartFields>({
        filter: scope,
        sort: byAggregateId,
        size,
        cursor,
      }),
    );
    pages++;
    expect(page.list.length).toBeLessThanOrEqual(size);
    rows.push(...page.list);
    cursor = page.nextCursor;
  } while (cursor !== null && pages < 10);
  return { pages, rows };
}

function expectSnapshot(
  snapshot: MaterializedSnapshot<CartState>,
  id: string,
  version: number,
) {
  expect(snapshot.aggregateId).toBe(id);
  expect(snapshot.aggregateName).toBe('cart');
  expect(snapshot.ownerId).toBe(id);
  expect(snapshot.version).toBe(version);
  expect(snapshot.deleted).toBe(false);
  expect(snapshot.state.id).toBe(id);
}

function expectCartB(state: CartState) {
  expect(state.id).toBe(cartB);
  expect(state.size).toBe(2);
  expect(state.items).toEqual([
    { productId: productOne, quantity: 1 },
    { productId: productTwo, quantity: 3 },
  ]);
}

describe('cart snapshot query through filter.*', () => {
  it('should list', async () => {
    const list = await snapshotClient.list(
      listQuery({ filter: scope, sort: byAggregateId }),
    );
    expect(list.map(snapshot => snapshot.aggregateId)).toEqual(cartIds);
    expectSnapshot(list[0], cartA, 1);
    expectSnapshot(list[1], cartB, 2);
    expectSnapshot(list[2], cartC, 1);
  });

  it('should list with a limit', async () => {
    const list = await snapshotClient.list(
      listQuery({ filter: scope, sort: byAggregateId, limit: 2 }),
    );
    expect(list.map(snapshot => snapshot.aggregateId)).toEqual([cartA, cartB]);
  });

  it('should list state', async () => {
    const states = await snapshotClient.listState(
      listQuery({
        filter: filter.and([scope, filter.gte('state.size', 2)]),
      }),
    );
    expect(states).toHaveLength(1);
    expectCartB(states[0]);
  });

  it('should list stream', async () => {
    const stream = await snapshotClient.listStream(
      listQuery({ filter: scope, sort: byAggregateId }),
    );
    const list = await collect(stream);
    expect(list.map(snapshot => snapshot.aggregateId)).toEqual(cartIds);
    expectSnapshot(list[1], cartB, 2);
  });

  it('should list state stream', async () => {
    const stream = await snapshotClient.listStateStream(
      listQuery({
        filter: filter.and([
          scope,
          filter.elementMatch(
            'state.items',
            filter.eq('productId', productTwo),
          ),
        ]),
      }),
    );
    const states = await collect(stream);
    expect(states).toHaveLength(1);
    expectCartB(states[0]);
  });

  it('should paged', async () => {
    const paged = await snapshotClient.paged(
      pagedQuery({
        filter: scope,
        sort: byAggregateId,
        pagination: { index: 1, size: 2 },
      }),
    );
    expect(paged.total).toBe(3);
    expect(paged.list.map(snapshot => snapshot.aggregateId)).toEqual([
      cartA,
      cartB,
    ]);
  });

  it('should paged state', async () => {
    const paged = await snapshotClient.pagedState(
      pagedQuery({
        filter: scope,
        sort: byAggregateId,
        pagination: { index: 2, size: 2 },
      }),
    );
    expect(paged.total).toBe(3);
    expect(paged.list.map(state => state.id)).toEqual([cartC]);
  });

  it('should single', async () => {
    const snapshot = await snapshotClient.single(
      singleQuery({ filter: filter.and([scope, filter.eq('state.size', 2)]) }),
    );
    expectSnapshot(snapshot, cartB, 2);
    expectCartB(snapshot.state);
  });

  it('should single state with a projection', async () => {
    const state = await snapshotClient.singleState<Partial<CartState>>(
      singleQuery({
        filter: filter.and([
          scope,
          filter.elementMatch(
            'state.items',
            filter.and([
              filter.eq('productId', productOne),
              filter.gt('quantity', 3),
            ]),
          ),
        ]),
        projection: { include: ['state.id'] },
      }),
    );
    expect(state.id).toBe(cartC);
    expect(state.items).toBeUndefined();
  });

  it('should count', async () => {
    expect(await snapshotClient.count(scope)).toBe(3);
    expect(
      await snapshotClient.count(
        filter.and([scope, filter.eq('state.size', 1)]),
      ),
    ).toBe(2);
    expect(
      await snapshotClient.count(filter.aggregateIds([`${runId}Missing`])),
    ).toBe(0);
  });

  // BEFORE_NOW / AFTER_NOW compare with the server's clock, read once per
  // query: the carts were written moments ago, so every snapshot lies
  // between an hour back and an hour ahead of the server's now.
  it('should count relative to the server clock', async () => {
    const count = (relative: FilterExpression<CartFields>) =>
      snapshotClient.count(filter.and([scope, relative]));
    expect(await count(filter.beforeNow('snapshotTime', 'PT1H'))).toBe(3);
    expect(await count(filter.afterNow('snapshotTime', '-PT1H'))).toBe(3);
    expect(await count(filter.afterNow('snapshotTime', 'PT1H'))).toBe(0);
    expect(await count(filter.beforeNow('firstEventTime', '-PT1H'))).toBe(0);
  });

  it('should walk cursor pages', async () => {
    const { pages, rows } = await walk(
      query => snapshotClient.cursor(query),
      2,
    );
    expect(pages).toBeGreaterThanOrEqual(2);
    expect(rows.map(snapshot => snapshot.aggregateId)).toEqual(cartIds);
    expectSnapshot(rows[1], cartB, 2);
  });

  it('should walk cursor state pages', async () => {
    const { pages, rows } = await walk(
      query => snapshotClient.cursorState(query),
      1,
    );
    expect(pages).toBeGreaterThanOrEqual(3);
    expect(rows.map(state => state.id)).toEqual(cartIds);
    expectCartB(rows[1]);
  });

  it('should aggregate by group', async () => {
    const rows = await snapshotClient.aggregate<{
      size: number;
      carts: number;
      versions: number;
    }>(
      aggregation.query({
        filter: scope,
        groupBy: [aggregation.terms('state.size', 'size')],
        metrics: [
          aggregation.count('carts'),
          aggregation.sum(aggregation.field('version'), 'versions'),
        ],
      }),
    );
    const bySize = [...rows].sort((left, right) => left.size - right.size);
    expect(bySize).toEqual([
      { size: 1, carts: 2, versions: 2 },
      { size: 2, carts: 1, versions: 2 },
    ]);
  });

  it('should group by weekday and hour, with integer keys', async () => {
    const rows = await snapshotClient.aggregate<{
      weekday: number;
      hour: number;
      carts: number;
    }>(
      aggregation.query({
        filter: scope,
        groupBy: [
          aggregation.datePart('firstEventTime', 'weekday', {
            part: AggregationDatePart.DAY_OF_WEEK,
          }),
          aggregation.datePart('firstEventTime', 'hour', {
            part: AggregationDatePart.HOUR_OF_DAY,
          }),
        ],
        metrics: [aggregation.count('carts')],
      }),
    );
    expect(rows.length).toBeGreaterThan(0);
    for (const row of rows) {
      expect(Number.isInteger(row.weekday)).toBe(true);
      expect(row.weekday).toBeGreaterThanOrEqual(1);
      expect(row.weekday).toBeLessThanOrEqual(7);
      expect(Number.isInteger(row.hour)).toBe(true);
      expect(row.hour).toBeGreaterThanOrEqual(0);
      expect(row.hour).toBeLessThanOrEqual(23);
    }
    expect(rows.reduce((sum, row) => sum + row.carts, 0)).toBe(cartIds.length);
  });

  it('should fill the whole ISO weekday domain when dense', async () => {
    const rows = await snapshotClient.aggregate<{
      weekday: number;
      carts: number;
    }>(
      aggregation.query({
        filter: scope,
        groupBy: [
          aggregation.datePart('firstEventTime', 'weekday', {
            part: AggregationDatePart.DAY_OF_WEEK,
            dense: true,
          }),
        ],
        metrics: [aggregation.count('carts')],
      }),
    );
    expect(rows.map(row => row.weekday).sort((a, b) => a - b)).toEqual([
      1, 2, 3, 4, 5, 6, 7,
    ]);
    expect(rows.reduce((sum, row) => sum + row.carts, 0)).toBe(cartIds.length);
  });

  it('should aggregate over array elements', async () => {
    const rows = await snapshotClient.aggregate<{
      product: string;
      lines: number;
      quantity: number;
      large: number;
    }>(
      aggregation.query({
        filter: scope,
        elements: [aggregation.element('state.items')],
        groupBy: [aggregation.terms('productId', 'product')],
        metrics: [
          aggregation.count('lines'),
          aggregation.sum(aggregation.field('quantity'), 'quantity'),
          aggregation.count('large', { filter: filter.gte('quantity', 3) }),
        ],
      }),
    );
    const byProduct = [...rows].sort((left, right) =>
      left.product.localeCompare(right.product),
    );
    expect(byProduct).toEqual([
      { product: productOne, lines: 3, quantity: 7, large: 1 },
      { product: productTwo, lines: 1, quantity: 3, large: 1 },
    ]);
  });

  it('should aggregate stream', async () => {
    const stream = await snapshotClient.aggregateStream<{
      carts: number;
      items: number;
    }>(
      aggregation.query({
        filter: scope,
        metrics: [
          aggregation.count('carts'),
          aggregation.sum(aggregation.field('state.size'), 'items'),
        ],
      }),
    );
    expect(await collect(stream)).toEqual([{ carts: 3, items: 4 }]);
  });

  it('should getById', async () => {
    const snapshot = await snapshotClient.getById(cartB);
    expectSnapshot(snapshot, cartB, 2);
    expectCartB(snapshot.state);
  });

  it('should getStateById', async () => {
    expectCartB(await snapshotClient.getStateById(cartB));
  });

  it('should getByIds', async () => {
    const snapshots = await snapshotClient.getByIds([cartA, cartC]);
    expect(snapshots.map(snapshot => snapshot.aggregateId).sort()).toEqual([
      cartA,
      cartC,
    ]);
    expect(await snapshotClient.getByIds([])).toEqual([]);
  });

  it('should getStateByIds', async () => {
    const states = await snapshotClient.getStateByIds([
      cartB,
      `${runId}Missing`,
    ]);
    expect(states).toHaveLength(1);
    expectCartB(states[0]);
    expect(await snapshotClient.getStateByIds([])).toEqual([]);
  });
});

function expectEventStream(
  eventStream: DomainEventStream,
  id: string,
  version: number,
) {
  expect(eventStream.aggregateId).toBe(id);
  expect(eventStream.aggregateName).toBe('cart');
  expect(eventStream.ownerId).toBe(id);
  expect(eventStream.version).toBe(version);
  expect(eventStream.body).toHaveLength(1);
  expect(eventStream.body[0].name).toBe('cart_item_added');
}

describe('cart event stream query through filter.*', () => {
  const eventOrder = [asc('aggregateId'), asc('version')];

  it('should list', async () => {
    const list = await eventStreamClient.list(
      listQuery({ filter: scope, sort: eventOrder }),
    );
    expect(list.map(stream => [stream.aggregateId, stream.version])).toEqual([
      [cartA, 1],
      [cartB, 1],
      [cartB, 2],
      [cartC, 1],
    ]);
    expectEventStream(list[2], cartB, 2);
    expect(list[2].body[0].body).toEqual({
      added: { productId: productTwo, quantity: 3 },
    });
  });

  it('should list stream', async () => {
    const stream = await eventStreamClient.listStream(
      listQuery({ filter: filter.aggregateId(cartB), sort: [asc('version')] }),
    );
    const list = await collect(stream);
    expect(list.map(eventStream => eventStream.version)).toEqual([1, 2]);
  });

  it('should paged', async () => {
    const paged = await eventStreamClient.paged(
      pagedQuery({
        filter: scope,
        sort: eventOrder,
        pagination: { index: 2, size: 3 },
      }),
    );
    expect(paged.total).toBe(4);
    expect(paged.list).toHaveLength(1);
    expectEventStream(paged.list[0], cartC, 1);
  });

  it('should count', async () => {
    expect(await eventStreamClient.count(scope)).toBe(4);
    expect(await eventStreamClient.count(filter.aggregateId(cartB))).toBe(2);
  });

  it('should walk cursor pages', async () => {
    const rows: Partial<DomainEventStream>[] = [];
    let pages = 0;
    let cursor: string | null = null;
    do {
      const page: CursorPage<Partial<DomainEventStream>> =
        await eventStreamClient.cursor(
          cursorQuery({ filter: scope, sort: eventOrder, size: 3, cursor }),
        );
      pages++;
      rows.push(...page.list);
      cursor = page.nextCursor;
    } while (cursor !== null && pages < 10);
    expect(pages).toBeGreaterThanOrEqual(2);
    expect(rows.map(stream => [stream.aggregateId, stream.version])).toEqual([
      [cartA, 1],
      [cartB, 1],
      [cartB, 2],
      [cartC, 1],
    ]);
  });

  it('should aggregate', async () => {
    const rows = await eventStreamClient.aggregate<{
      cart: string;
      events: number;
      latest: number;
    }>(
      aggregation.query({
        filter: scope,
        groupBy: [aggregation.terms('aggregateId', 'cart')],
        metrics: [
          aggregation.count('events'),
          aggregation.max(aggregation.field('version'), 'latest'),
        ],
      }),
    );
    const byCart = [...rows].sort((left, right) =>
      left.cart.localeCompare(right.cart),
    );
    expect(byCart).toEqual([
      { cart: cartA, events: 1, latest: 1 },
      { cart: cartB, events: 2, latest: 2 },
      { cart: cartC, events: 1, latest: 1 },
    ]);
  });

  it('should aggregate stream', async () => {
    const stream = await eventStreamClient.aggregateStream<{ events: number }>(
      aggregation.query({
        filter: scope,
        metrics: [aggregation.count('events')],
      }),
    );
    expect(await collect(stream)).toEqual([{ events: 4 }]);
  });

  it('should load a version range', async () => {
    const all = await eventStreamClient.load(cartB, 1, 2);
    expect(all).toHaveLength(2);
    expectEventStream(all[0], cartB, 1);
    expectEventStream(all[1], cartB, 2);

    const first = await eventStreamClient.load(cartB, 1, 1);
    expect(first.map(eventStream => eventStream.version)).toEqual([1]);

    const second = await eventStreamClient.load(cartB, 2, 10);
    expect(second.map(eventStream => eventStream.version)).toEqual([2]);
  });

  it('should load an unknown aggregate as an empty list', async () => {
    expect(await eventStreamClient.load(`${runId}Missing`, 1, 10)).toEqual([]);
  });

  it('should load stream', async () => {
    const stream = await eventStreamClient.loadStream(cartB, 1, 2);
    const list = await collect(stream);
    expect(list.map(eventStream => eventStream.version)).toEqual([1, 2]);
    expectEventStream(list[1], cartB, 2);
  });
});
