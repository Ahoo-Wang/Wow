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
  DatasetQueryClient,
  datasetBasePath,
  filter,
  listQuery,
  pagedQuery,
  QueryModels,
  ResourceAttributionPathSpec,
  singleQuery,
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

type Client = DatasetQueryClient;

const DATASET_PATH = '/dataset/analytics/order_wide';

const aggregationBody: AggregationQuery = {
  filter: filter.eq('city', 'Shanghai'),
  groupBy: [aggregation.terms('city', 'city')],
  metrics: [aggregation.count('orders')],
};
const countBody = filter.eq('city', 'Shanghai');
const listBody = listQuery({ filter: filter.matchAll(), limit: 5 });
const pagedBody = pagedQuery({ filter: filter.matchAll() });
const cursorBody = cursorQuery({ filter: filter.matchAll() });
const singleBody = singleQuery({ filter: filter.eq('order_id', 'o-1') });
const row = { order_id: 'o-1', city: 'Shanghai', amount: 12.5 };
const rows = [
  { city: 'Shanghai', orders: 2 },
  { city: 'Beijing', orders: 1 },
];

const client = () =>
  new DatasetQueryClient({
    context: 'analytics',
    dataset: 'order_wide',
    fetcher: testFetcher(),
  });

const cases: ClientCase<Client>[] = [
  {
    name: 'single',
    call: (c, abort) => c.single(singleBody, undefined, abort),
    method: 'POST',
    path: `${DATASET_PATH}/single`,
    body: singleBody,
    result: row,
  },
  {
    name: 'list',
    call: (c, abort) => c.list(listBody, undefined, abort),
    method: 'POST',
    path: `${DATASET_PATH}/list`,
    body: listBody,
    result: [row],
  },
  {
    name: 'listStream',
    call: (c, abort) => c.listStream(listBody, undefined, abort),
    method: 'POST',
    path: `${DATASET_PATH}/list`,
    body: listBody,
    stream: true,
    result: [row, { ...row, order_id: 'o-2' }],
  },
  {
    name: 'paged',
    call: (c, abort) => c.paged(pagedBody, undefined, abort),
    method: 'POST',
    path: `${DATASET_PATH}/paged`,
    body: pagedBody,
    result: { total: 1, list: [row] },
  },
  {
    name: 'cursor',
    call: (c, abort) => c.cursor(cursorBody, undefined, abort),
    method: 'POST',
    path: `${DATASET_PATH}/cursor`,
    body: cursorBody,
    result: { list: [row], nextCursor: 'o-1' },
  },
  {
    name: 'count',
    call: (c, abort) => c.count(countBody, undefined, abort),
    method: 'POST',
    path: `${DATASET_PATH}/count`,
    body: countBody,
    result: 2,
  },
  {
    name: 'aggregate',
    call: (c, abort) => c.aggregate(aggregationBody, undefined, abort),
    method: 'POST',
    path: `${DATASET_PATH}/aggregation`,
    body: aggregationBody,
    result: rows,
  },
  {
    name: 'aggregateStream',
    call: (c, abort) => c.aggregateStream(aggregationBody, undefined, abort),
    method: 'POST',
    path: `${DATASET_PATH}/aggregation`,
    body: aggregationBody,
    stream: true,
    result: rows,
  },
];

describe('DatasetQueryClient', () => {
  describeClientCases(client, cases);

  describe('base path', () => {
    it('is the dataset path, encoded', () => {
      expect(datasetBasePath('analytics', 'order_wide')).toBe(
        'dataset/analytics/order_wide',
      );
      expect(datasetBasePath('a b', 'c/d')).toBe('dataset/a%20b/c%2Fd');
    });

    it('carries the tenant prefix, filled from its path parameters', async () => {
      const { requests } = stubFetch(() => jsonResponse(0));
      const tenantClient = new DatasetQueryClient({
        context: 'analytics',
        dataset: 'order_wide',
        resourceAttribution: ResourceAttributionPathSpec.TENANT,
        urlParams: { path: { tenantId: 't-1' } },
        fetcher: testFetcher(),
      });

      await tenantClient.count(countBody);

      expect(requests[0].url).toBe(
        `${BASE_URL}/tenant/t-1${DATASET_PATH}/count`,
      );
    });

    it('is replaced by an explicit basePath', async () => {
      const { requests } = stubFetch(() => jsonResponse(0));
      const custom = new DatasetQueryClient({
        context: 'analytics',
        dataset: 'order_wide',
        basePath: 'bi/orders',
        fetcher: testFetcher(),
      });

      await custom.count(countBody);

      expect(requests[0].url).toBe(`${BASE_URL}/bi/orders/count`);
    });
  });

  describe('describe', () => {
    const descriptor = { model: QueryModels.DATASET, version: 'sha256:abc' };

    it('reads the descriptor in full without a version', async () => {
      const { requests } = stubFetch(() => jsonResponse(descriptor));

      const result = await client().describe();

      expect(requests[0].method).toBe('GET');
      expect(requests[0].url).toBe(`${BASE_URL}${DATASET_PATH}/schema`);
      expect(requests[0].headers.has('If-None-Match')).toBe(false);
      expect(result).toStrictEqual({
        notModified: false,
        descriptor,
        version: 'sha256:abc',
      });
    });

    it('answers notModified on a 304', async () => {
      const { requests } = stubFetch(() => new Response(null, { status: 304 }));

      const result = await client().describe('sha256:abc');

      expect(requests[0].headers.get('If-None-Match')).toBe('"sha256:abc"');
      expect(result).toStrictEqual({
        notModified: true,
        version: 'sha256:abc',
      });
    });
  });
});
