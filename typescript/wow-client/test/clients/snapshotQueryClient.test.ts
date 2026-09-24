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

import { describe, expect, it } from 'vitest';
import {
  aggregation,
  cursorQuery,
  filter,
  listQuery,
  pagedQuery,
  QueryClientFactory,
  ResourceAttributionPathSpec,
  singleQuery,
  SnapshotQueryClient,
  type AggregationQuery,
} from '../../src';
import {
  BASE_URL,
  describeClientCases,
  expectCancellable,
  jsonResponse,
  stubFetch,
  testFetcher,
  type ClientCase,
} from './fetchStub';

interface CartState {
  items: string[];
}

type Client = SnapshotQueryClient<CartState>;

const aggregationBody: AggregationQuery = {
  filter: filter.eq('state.status', 'PAID'),
  groupBy: [aggregation.terms('productId', 'product')],
  metrics: [aggregation.count('count')],
};
const cursorBody = cursorQuery({ filter: filter.matchAll() });
const countBody = filter.eq('state.status', 'PAID');
const listBody = listQuery({ filter: filter.matchAll(), limit: 5 });
const pagedBody = pagedQuery({ filter: filter.matchAll() });
const singleBody = singleQuery({ filter: filter.aggregateId('cart-1') });
const snapshot = { aggregateId: 'cart-1', version: 1, state: { items: [] } };
const state = { items: ['p-1'] };
const rows = [
  { product: 'p-1', count: 2 },
  { product: 'p-2', count: 1 },
];
const cursorPage = { list: [snapshot], nextCursor: 'cart-1' };
const paged = { total: 1, list: [snapshot] };

const byIdBody = { filter: { op: 'AGGREGATE_ID', value: 'cart-1' } };
const byIdsBody = {
  filter: { op: 'AGGREGATE_IDS', values: ['cart-1', 'cart-2'] },
  limit: 2,
};

const cases: ClientCase<Client>[] = [
  {
    name: 'aggregate',
    call: (c, abort) => c.aggregate(aggregationBody, undefined, abort),
    method: 'POST',
    path: '/cart/snapshot/aggregation',
    body: aggregationBody,
    result: rows,
  },
  {
    name: 'aggregateStream',
    call: (c, abort) => c.aggregateStream(aggregationBody, undefined, abort),
    method: 'POST',
    path: '/cart/snapshot/aggregation',
    body: aggregationBody,
    stream: true,
    result: rows,
  },
  {
    name: 'cursor',
    call: (c, abort) => c.cursor(cursorBody, undefined, abort),
    method: 'POST',
    path: '/cart/snapshot/cursor',
    body: cursorBody,
    result: cursorPage,
  },
  {
    name: 'cursorState',
    call: (c, abort) => c.cursorState(cursorBody, undefined, abort),
    method: 'POST',
    path: '/cart/snapshot/cursor/state',
    body: cursorBody,
    result: { list: [state], nextCursor: null },
  },
  {
    name: 'count',
    call: (c, abort) => c.count(countBody, undefined, abort),
    method: 'POST',
    path: '/cart/snapshot/count',
    body: countBody,
    result: 3,
  },
  {
    name: 'list',
    call: (c, abort) => c.list(listBody, undefined, abort),
    method: 'POST',
    path: '/cart/snapshot/list',
    body: listBody,
    result: [snapshot],
  },
  {
    name: 'listStream',
    call: (c, abort) => c.listStream(listBody, undefined, abort),
    method: 'POST',
    path: '/cart/snapshot/list',
    body: listBody,
    stream: true,
    result: [snapshot, { ...snapshot, aggregateId: 'cart-2' }],
  },
  {
    name: 'listState',
    call: (c, abort) => c.listState(listBody, undefined, abort),
    method: 'POST',
    path: '/cart/snapshot/list/state',
    body: listBody,
    result: [state],
  },
  {
    name: 'listStateStream',
    call: (c, abort) => c.listStateStream(listBody, undefined, abort),
    method: 'POST',
    path: '/cart/snapshot/list/state',
    body: listBody,
    stream: true,
    result: [state, { items: [] }],
  },
  {
    name: 'paged',
    call: (c, abort) => c.paged(pagedBody, undefined, abort),
    method: 'POST',
    path: '/cart/snapshot/paged',
    body: pagedBody,
    result: paged,
  },
  {
    name: 'pagedState',
    call: (c, abort) => c.pagedState(pagedBody, undefined, abort),
    method: 'POST',
    path: '/cart/snapshot/paged/state',
    body: pagedBody,
    result: { total: 1, list: [state] },
  },
  {
    name: 'single',
    call: (c, abort) => c.single(singleBody, undefined, abort),
    method: 'POST',
    path: '/cart/snapshot/single',
    body: singleBody,
    result: snapshot,
  },
  {
    name: 'singleState',
    call: (c, abort) => c.singleState(singleBody, undefined, abort),
    method: 'POST',
    path: '/cart/snapshot/single/state',
    body: singleBody,
    result: state,
  },
  {
    name: 'getById',
    call: (c, abort) => c.getById('cart-1', undefined, abort),
    method: 'POST',
    path: '/cart/snapshot/single',
    body: byIdBody,
    result: snapshot,
  },
  {
    name: 'getStateById',
    call: (c, abort) => c.getStateById('cart-1', undefined, abort),
    method: 'POST',
    path: '/cart/snapshot/single/state',
    body: byIdBody,
    result: state,
  },
  {
    name: 'getByIds',
    call: (c, abort) => c.getByIds(['cart-1', 'cart-2'], undefined, abort),
    method: 'POST',
    path: '/cart/snapshot/list',
    body: byIdsBody,
    result: [snapshot],
  },
  {
    name: 'getStateByIds',
    call: (c, abort) => c.getStateByIds(['cart-1', 'cart-2'], undefined, abort),
    method: 'POST',
    path: '/cart/snapshot/list/state',
    body: byIdsBody,
    result: [state],
  },
];

describe('SnapshotQueryClient', () => {
  describeClientCases<Client>(
    () => new SnapshotQueryClient({ basePath: 'cart', fetcher: testFetcher() }),
    cases,
  );

  it.each(['getByIds', 'getStateByIds'] as const)(
    '%s of no ids resolves to [] without a request',
    async method => {
      const { fetchMock } = stubFetch();
      const client: Client = new SnapshotQueryClient({
        basePath: 'cart',
        fetcher: testFetcher(),
      });

      await expect(client[method]([])).resolves.toStrictEqual([]);
      await expect(
        client[method]([], undefined, new AbortController()),
      ).resolves.toStrictEqual([]);
      expect(fetchMock).not.toHaveBeenCalled();
    },
  );

  it('passes attributes to the interceptors', async () => {
    const fetcher = testFetcher();
    const seen: unknown[] = [];
    fetcher.interceptors.request.use({
      name: 'attributes-probe',
      order: 0,
      intercept(exchange) {
        seen.push(exchange.attributes.get('traceId'));
      },
    });
    stubFetch(() => jsonResponse(3));
    const client: Client = new SnapshotQueryClient({
      basePath: 'cart',
      fetcher,
    });

    await client.count(filter.matchAll(), { traceId: 't-1' });
    await client.getById('cart-1', { traceId: 't-2' });

    expect(seen).toStrictEqual(['t-1', 't-2']);
  });

  it('sends to the base path QueryClientFactory builds', async () => {
    const { requests } = stubFetch(() => jsonResponse([snapshot]));
    const factory = new QueryClientFactory<CartState>({
      contextAlias: 'example',
      aggregateName: 'cart',
      resourceAttribution: ResourceAttributionPathSpec.TENANT,
      urlParams: { path: { tenantId: 't-1' } },
      fetcher: testFetcher(),
    });

    await factory.createSnapshotQueryClient().list(listBody);
    await factory
      .createSnapshotQueryClient({ basePath: 'custom' })
      .getStateByIds(['cart-1']);

    expect(requests.map(r => r.url)).toStrictEqual([
      `${BASE_URL}/example/tenant/t-1/cart/snapshot/list`,
      `${BASE_URL}/custom/snapshot/list/state`,
    ]);
  });

  it('sends to the fetcher root when the factory names no path', async () => {
    const { requests } = stubFetch(() => jsonResponse(3));
    const factory = new QueryClientFactory<CartState>({
      fetcher: testFetcher(),
    });

    await factory.createSnapshotQueryClient().count(filter.matchAll());

    expect(requests.map(r => r.url)).toStrictEqual([
      `${BASE_URL}/snapshot/count`,
    ]);
  });

  it('cancels getById through the abort it is given', () =>
    expectCancellable(abort =>
      new SnapshotQueryClient<CartState>({
        basePath: 'cart',
        fetcher: testFetcher(),
      }).getById('cart-1', undefined, abort),
    ));
});
