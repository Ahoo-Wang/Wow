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
  EventStreamQueryClient,
  filter,
  listQuery,
  pagedQuery,
  QueryClientFactory,
  ResourceAttributionPathSpec,
  type AggregationQuery,
} from '../../src';
import {
  BASE_URL,
  describeClientCases,
  jsonResponse,
  stubFetch,
  testFetcher,
  type ClientCase,
} from './fetchStub';

type Client = EventStreamQueryClient;

const aggregationBody: AggregationQuery = {
  filter: filter.matchAll(),
  groupBy: [aggregation.terms('body.name', 'eventName')],
  metrics: [aggregation.count('count')],
};
const cursorBody = cursorQuery({ filter: filter.aggregateId('cart-1') });
const countBody = filter.aggregateId('cart-1');
const listBody = listQuery({ filter: filter.aggregateId('cart-1'), limit: 2 });
const pagedBody = pagedQuery({ filter: filter.aggregateId('cart-1') });
const eventStream = (version: number) => ({
  id: `event-stream-${version}`,
  aggregateId: 'cart-1',
  version,
  body: [{ name: 'cart_item_added', body: { productId: 'p-1' } }],
});
const rows = [
  { eventName: 'cart_item_added', count: 2 },
  { eventName: 'cart_item_removed', count: 1 },
];

const cases: ClientCase<Client>[] = [
  {
    name: 'aggregate',
    call: (c, abort) => c.aggregate(aggregationBody, undefined, abort),
    method: 'POST',
    path: '/cart/event/aggregation',
    body: aggregationBody,
    result: rows,
  },
  {
    name: 'aggregateStream',
    call: (c, abort) => c.aggregateStream(aggregationBody, undefined, abort),
    method: 'POST',
    path: '/cart/event/aggregation',
    body: aggregationBody,
    stream: true,
    result: rows,
  },
  {
    name: 'cursor',
    call: (c, abort) => c.cursor(cursorBody, undefined, abort),
    method: 'POST',
    path: '/cart/event/cursor',
    body: cursorBody,
    result: { list: [eventStream(1)], nextCursor: 'event-stream-1' },
  },
  {
    name: 'count',
    call: (c, abort) => c.count(countBody, undefined, abort),
    method: 'POST',
    path: '/cart/event/count',
    body: countBody,
    result: 2,
  },
  {
    name: 'list',
    call: (c, abort) => c.list(listBody, undefined, abort),
    method: 'POST',
    path: '/cart/event/list',
    body: listBody,
    result: [eventStream(1), eventStream(2)],
  },
  {
    name: 'listStream',
    call: (c, abort) => c.listStream(listBody, undefined, abort),
    method: 'POST',
    path: '/cart/event/list',
    body: listBody,
    stream: true,
    result: [eventStream(1), eventStream(2)],
  },
  {
    name: 'paged',
    call: (c, abort) => c.paged(pagedBody, undefined, abort),
    method: 'POST',
    path: '/cart/event/paged',
    body: pagedBody,
    result: { total: 1, list: [eventStream(1)] },
  },
  {
    name: 'load',
    call: (c, abort) => c.load('cart-1', 1, 10, undefined, abort),
    method: 'GET',
    path: '/cart/cart-1/event/1/10',
    result: [eventStream(1), eventStream(2)],
  },
  {
    name: 'loadStream',
    call: (c, abort) => c.loadStream('cart-1', 1, 10, undefined, abort),
    method: 'GET',
    path: '/cart/cart-1/event/1/10',
    stream: true,
    result: [eventStream(1), eventStream(2)],
  },
];

describe('EventStreamQueryClient', () => {
  describeClientCases<Client>(
    () =>
      new EventStreamQueryClient({ basePath: 'cart', fetcher: testFetcher() }),
    cases,
  );

  it('sends to the base path QueryClientFactory builds', async () => {
    const { requests } = stubFetch(() => jsonResponse([]));
    const factory = new QueryClientFactory({
      contextAlias: 'example',
      aggregateName: 'cart',
      resourceAttribution: ResourceAttributionPathSpec.TENANT,
      urlParams: { path: { tenantId: 't-1' } },
      fetcher: testFetcher(),
    });

    await factory.createEventStreamQueryClient().load('cart-1', 1, 10);
    await factory
      .createEventStreamQueryClient({ basePath: 'custom' })
      .list(listBody);

    expect(requests.map(r => r.url)).toStrictEqual([
      `${BASE_URL}/example/tenant/t-1/cart/cart-1/event/1/10`,
      `${BASE_URL}/custom/event/list`,
    ]);
  });
});
