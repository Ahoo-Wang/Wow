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
  AggregationDateUnit,
  FilterOperator,
  PagingMode,
  QueryDescriptorClient,
  QueryModels,
  QueryValueKind,
  type QueryModelDescriptor,
} from '../../src';
import {
  BASE_URL,
  expectCancellable,
  expectWowErrorOnFailure,
  jsonResponse,
  stubFetch,
  testFetcher,
} from './fetchStub';

const VERSION = 'sha256:0123abcd';

const descriptor: QueryModelDescriptor = {
  model: QueryModels.SNAPSHOT,
  version: VERSION,
  timeZone: 'UTC',
  record: {
    identity: 'aggregateId',
    paging: [PagingMode.LIST, PagingMode.PAGED, PagingMode.CURSOR],
    rootOperators: [FilterOperator.ID],
  },
  limits: {
    maxListSize: 1000,
    defaultListSize: 100,
    maxPageSize: null,
    maxPageWindow: null,
    maxFilterNodes: null,
    maxFilterValues: null,
    maxSortFields: 8,
    aggregation: {
      maxGroups: 3,
      maxMetrics: 20,
      maxElements: 1,
      maxLimit: 1000,
      maxExpressionDepth: 8,
      maxExpressionNodes: 64,
    },
  },
  analysis: {
    metrics: ['COUNT', 'PERCENTILE'],
    approximate: ['PERCENTILE'],
    expressions: true,
    having: { metrics: ['COUNT'] },
    sort: { groups: true, metrics: true },
    dense: true,
    dateUnits: [AggregationDateUnit.DAY, AggregationDateUnit.MONTH],
  },
  fields: [
    {
      path: 'state.status',
      types: ['STRING'],
      kind: QueryValueKind.SCALAR,
      nullable: false,
      project: true,
      filter: { operators: [FilterOperator.EQ, FilterOperator.IN] },
      sort: { paged: true, cursor: false },
      aliases: [],
    },
  ],
  elements: [],
  dynamic: [],
  constraints: [],
};

const notModified = () => new Response(null, { status: 304 });

const MODELS = [
  {
    name: 'describeSnapshot',
    path: '/example/cart/snapshot/schema',
    call: (
      c: QueryDescriptorClient,
      previous?: string,
      abort?: AbortSignal | AbortController,
    ) => c.describeSnapshot(previous, undefined, abort),
  },
  {
    name: 'describeEventStream',
    path: '/example/cart/event/schema',
    call: (
      c: QueryDescriptorClient,
      previous?: string,
      abort?: AbortSignal | AbortController,
    ) => c.describeEventStream(previous, undefined, abort),
  },
];

const client = () =>
  new QueryDescriptorClient({
    basePath: 'example/cart',
    fetcher: testFetcher(),
  });

describe.each(MODELS)('QueryDescriptorClient.$name', ({ path, call }) => {
  it('reads the descriptor in full without a version', async () => {
    const { requests } = stubFetch(() => jsonResponse(descriptor));

    const result = await call(client());

    expect(requests).toHaveLength(1);
    expect(requests[0].method).toBe('GET');
    expect(requests[0].url).toBe(`${BASE_URL}${path}`);
    expect(requests[0].headers.has('If-None-Match')).toBe(false);
    expect(result).toStrictEqual({
      notModified: false,
      descriptor,
      version: VERSION,
    });
  });

  it.each([VERSION, `"${VERSION}"`, `W/"${VERSION}"`, ` ${VERSION} `])(
    'sends %j as the ETag and answers notModified on a 304',
    async previous => {
      const { requests } = stubFetch(notModified);

      const result = await call(client(), previous);

      expect(requests[0].headers.get('If-None-Match')).toBe(`"${VERSION}"`);
      expect(result).toStrictEqual({ notModified: true, version: VERSION });
    },
  );

  it('reads the new descriptor when the version held is stale', async () => {
    const { requests } = stubFetch(() => jsonResponse(descriptor));

    const result = await call(client(), 'sha256:stale');

    expect(requests[0].headers.get('If-None-Match')).toBe('"sha256:stale"');
    expect(result).toStrictEqual({
      notModified: false,
      descriptor,
      version: VERSION,
    });
  });

  it.each(['', '  ', '""'])(
    'sends no If-None-Match for the blank version %j',
    async previous => {
      const { requests } = stubFetch(() => jsonResponse(descriptor));

      await call(client(), previous);

      expect(requests[0].headers.has('If-None-Match')).toBe(false);
    },
  );

  it('rejects a 304 it did not ask for', async () => {
    stubFetch(notModified);

    await expect(call(client())).rejects.toThrow();
  });

  it('is cancelled by an AbortController and by an AbortSignal', () =>
    expectCancellable(abort => call(client(), VERSION, abort)));

  it('rejects on an error response with the ErrorInfo', () =>
    expectWowErrorOnFailure(() => call(client(), VERSION)));
});
