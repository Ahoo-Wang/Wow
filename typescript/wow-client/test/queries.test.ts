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
 * The query clients: single and paged queries, streams, the events model,
 * descriptors and datasets.
 */

import { afterEach, describe, expect, expectTypeOf, it, vi } from 'vitest';
import { aggregation, filter, type FieldSortDescriptor } from '../src/dsl.js';
import {
  DatasetClient,
  EventQueryClient,
  FrameworkErrorCodes,
  SnapshotQueryClient,
  WowProblemError,
} from '../src/index.js';
import {
  BASE_URL,
  jsonResponse,
  readAll,
  sseResponse,
  stubFetch,
} from './fixtures/fetchStub';
import { client, PRODUCT, problemResponse } from './fixtures/clients';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('query clients', () => {
  it('answers a single query that matches nothing with undefined, and any other 404 with its problem', async () => {
    const products = new SnapshotQueryClient<{ name: string }, 'state.name'>(
      PRODUCT,
      client(),
    );
    const query = { filter: filter.eq('state.name', 'n') };
    stubFetch(() => problemResponse(404, FrameworkErrorCodes.QUERY_NOT_FOUND));
    expect(await products.single(query)).toBeUndefined();
    stubFetch(() =>
      problemResponse(404, FrameworkErrorCodes.QUERY_DATASET_NOT_FOUND),
    );
    await expect(products.single(query)).rejects.toMatchObject({
      status: 404,
      code: FrameworkErrorCodes.QUERY_DATASET_NOT_FOUND,
    });
    // A route that does not exist answers a 404 without a Wow code.
    stubFetch(() => problemResponse(404, undefined));
    await expect(products.single(query)).rejects.toMatchObject({
      status: 404,
    });
  });

  it('posts each operation with its kind', async () => {
    const { requests } = stubFetch(() => jsonResponse([]));
    const products = new SnapshotQueryClient<{ name: string }, 'state.name'>(
      PRODUCT,
      client(),
    );
    const query = { filter: filter.eq('state.name', 'n') };
    await products.list({ ...query, limit: 5 });
    await products.paged({ ...query, total: 'exact' });
    await products.cursor({ ...query, size: 2 });
    await products.single(query);
    await products.count({ ...query, limit: 10 });
    await products.aggregate({ metrics: [aggregation.count('n')] });
    expect(requests.map(r => (r.body as { kind: string }).kind)).toEqual([
      'list',
      'paged',
      'cursor',
      'single',
      'count',
      'aggregate',
    ]);
    expect(new Set(requests.map(r => r.url))).toEqual(
      new Set([`${BASE_URL}/eshop-catalog/product/snapshot/query`]),
    );
    expect(requests[0].body).toEqual({
      kind: 'list',
      filter: { op: 'eq', field: 'state.name', value: 'n' },
      limit: 5,
    });
  });

  it('streams rows until an error event', async () => {
    const { requests } = stubFetch(() =>
      sseResponse(
        'id:0\nevent:row\ndata:{"version":1}\n\n' +
          'event:error\ndata:{"type":"urn:wow:error:wow.query.failed","title":"t","status":500,"code":"wow.query.failed"}\n\n',
      ),
    );
    const events = new EventQueryClient(PRODUCT, client());
    const stream = await events.stream({
      filter: filter.eq('aggregateId', 'p1'),
    });
    const rows: unknown[] = [];
    const failure = await (async () => {
      for await (const row of stream) rows.push(row);
    })().catch(e => e);
    expect(rows).toEqual([{ version: 1 }]);
    expect(failure).toBeInstanceOf(WowProblemError);
    expect(failure.code).toBe('wow.query.failed');
    expect(requests[0].url).toBe(
      `${BASE_URL}/eshop-catalog/product/events/query`,
    );
    expect(requests[0].headers.get('Accept')).toBe('text/event-stream');
  });

  it('queries the events model on the unified engine (decision D235)', async () => {
    const { requests } = stubFetch(request =>
      request.method === 'GET'
        ? new Response(JSON.stringify({ model: 'events', version: 'v2' }), {
            status: 200,
            headers: { ETag: '"v2"', 'Content-Type': 'application/json' },
          })
        : jsonResponse({ count: 2, exact: true }),
    );
    const events = new EventQueryClient(PRODUCT, client());
    const added = filter.elementMatch(
      'events',
      filter.eq('name', 'eshop-catalog.product.ProductAdded'),
    );
    expect(await events.count({ filter: added })).toEqual({
      count: 2,
      exact: true,
    });
    await events.cursor({ filter: filter.eq('commandType', 'x'), size: 10 });
    await events.aggregate({
      elements: [aggregation.element('events')],
      groupBy: [aggregation.terms('name', 'event')],
      metrics: [aggregation.count('n')],
    });
    const described = await events.describe();
    expect(described).toMatchObject({ notModified: false, version: 'v2' });
    expect(new Set(requests.map(r => r.url))).toEqual(
      new Set([`${BASE_URL}/eshop-catalog/product/events/query`]),
    );
    expect(requests[0].body).toEqual({
      kind: 'count',
      filter: {
        op: 'element-match',
        field: 'events',
        predicate: {
          op: 'eq',
          field: 'name',
          value: 'eshop-catalog.product.ProductAdded',
        },
      },
    });
    expect(requests[2].body).toMatchObject({
      kind: 'aggregate',
      elements: [{ path: 'events' }],
    });
  });

  it('ends a stream whose error event is no problem with a 500', async () => {
    stubFetch(() => sseResponse('event:error\ndata:{}\n\n'));
    const stream = await new EventQueryClient(PRODUCT, client()).stream({
      filter: filter.eq('aggregateId', 'p1'),
    });
    await expect(readAll(stream)).rejects.toMatchObject({ status: 500 });
  });

  it('describes a model, conditionally on the version held', async () => {
    const { requests } = stubFetch(request =>
      request.headers.get('If-None-Match') === '"v1"'
        ? new Response(null, { status: 304, headers: { ETag: '"v1"' } })
        : new Response(JSON.stringify({ model: 'snapshot' }), {
            status: 200,
            headers: { ETag: '"v1"', 'Content-Type': 'application/json' },
          }),
    );
    const products = new SnapshotQueryClient(PRODUCT, client());
    expect(await products.describe()).toEqual({
      notModified: false,
      version: 'v1',
      descriptor: { model: 'snapshot' },
    });
    expect(await products.describe('v1')).toEqual({
      notModified: true,
      version: 'v1',
    });
    expect(requests[0].method).toBe('GET');
  });

  it("keeps a field's sort as the service describes it, its index fact included", async () => {
    const sort = { paged: true, cursor: false, indexed: false };
    stubFetch(() =>
      jsonResponse({
        model: 'snapshot',
        fields: [{ path: 'state.name', sort }],
      }),
    );
    const answer = await new SnapshotQueryClient(PRODUCT, client()).describe();
    const described = answer.notModified ? undefined : answer.descriptor;
    expect(described?.fields[0].sort).toEqual(sort);
    expectTypeOf(sort).toExtend<FieldSortDescriptor>();
  });

  it('answers a descriptor without an ETag', async () => {
    stubFetch(() => jsonResponse({ model: 'events' }));
    expect(await new EventQueryClient(PRODUCT, client()).describe()).toEqual({
      notModified: false,
      descriptor: { model: 'events' },
    });
  });

  it('lists and queries datasets', async () => {
    const { requests } = stubFetch(() => jsonResponse([]));
    const datasets = new DatasetClient(client());
    await datasets.list();
    await datasets.dataset('eshop-ordering', 'sales').list({
      filter: filter.matchAll(),
    });
    expect(requests.map(r => [r.method, r.url])).toEqual([
      ['GET', `${BASE_URL}/wow/dataset`],
      ['POST', `${BASE_URL}/wow/dataset/eshop-ordering/sales/query`],
    ]);
  });
});
