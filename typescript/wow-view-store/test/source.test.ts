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
import { DatasetClient, EventQueryClient } from '@ahoo-wang/wow-client';
import {
  DatasetResources,
  MemoryViewStore,
  ViewEngine,
} from '@ahoo-wang/wow-view-engine';
import {
  PagingMode,
  QueryValueKind,
  type QueryModelDescriptor,
} from '@ahoo-wang/wow-client/dsl';
import { datasetCatalogOf, viewSourceOf } from '../src/index.js';
import type { FakeDataset } from './support/fakeViewStore.js';
import { actingAs, installFake } from './support.js';

const ROWS = [
  { id: 'a', amount: 1 },
  { id: 'b', amount: 2 },
];

const DESCRIPTOR = { version: 'sha256:1', fields: [] };

/** A dataset's descriptor as the V10 service answers it: two scalar columns. */
const DATASET_DESCRIPTOR = {
  model: 'DATASET',
  version: 'sha256:1',
  timeZone: 'UTC',
  record: {
    identity: 'order_id',
    paging: [PagingMode.PAGED],
    rootOperators: [],
  },
  limits: {
    maxListSize: 1000,
    defaultListSize: 10,
    maxPageSize: 100,
    maxPageWindow: 10_000,
    maxFilterNodes: 256,
    maxFilterValues: 1000,
    maxSortFields: 8,
    aggregation: {
      maxGroups: 8,
      maxMetrics: 16,
      maxElements: 4,
      maxLimit: 1000,
      maxExpressionDepth: 8,
      maxExpressionNodes: 64,
    },
  },
  analysis: {
    metrics: [],
    expressions: false,
    having: { metrics: [] },
    sort: { groups: true, metrics: true },
    dense: false,
    approximate: [],
    dateUnits: [],
    dateParts: [],
    dateDiffUnits: [],
  },
  fields: ['order_id', 'city'].map(path => ({
    path,
    types: ['STRING'],
    kind: QueryValueKind.SCALAR,
    nullable: false,
    project: true,
    filter: { operators: [] },
    sort: { paged: true, cursor: true },
    aliases: [],
  })),
  elements: [],
  dynamic: [],
  constraints: [],
} as unknown as QueryModelDescriptor;

function orders(): FakeDataset {
  return {
    context: 'sales',
    dataset: 'orders',
    title: 'Orders',
    source: {
      paged: vi.fn(async () => ({ total: 2, list: ROWS })),
      cursor: vi.fn(async () => ({ list: ROWS, nextCursor: null })),
      aggregate: vi.fn(async () => [{ total: 3 }]),
      describe: vi.fn(async () => ({
        notModified: false,
        descriptor: DESCRIPTOR,
      })),
    },
  };
}

describe('the V10 data sources', () => {
  it('list the datasets as the engine’s directory reads them, each a source', async () => {
    const dataset = orders();
    installFake({ datasets: [dataset] });
    const catalog = datasetCatalogOf(
      new DatasetClient({ fetcher: actingAs({}) }),
    );

    const entries = await catalog.list();
    expect(entries).toEqual([
      {
        contextName: 'sales',
        name: 'orders',
        title: 'Orders',
        version: 'listed',
      },
    ]);
    const source = catalog.source(entries[0]);
    const abort = new AbortController();

    expect(
      await source.paged(
        { filter: { op: 'MATCH_ALL' } } as never,
        undefined,
        abort,
      ),
    ).toEqual({ total: 2, list: ROWS });
    expect(dataset.source.paged).toHaveBeenCalledWith({
      filter: { op: 'MATCH_ALL' },
    });
    expect(await source.cursor({} as never)).toEqual({
      list: ROWS,
      nextCursor: null,
    });
    expect(await source.aggregate({ metrics: [] } as never)).toEqual([
      { total: 3 },
    ]);
    expect(await source.describe!()).toEqual({
      notModified: false,
      descriptor: DESCRIPTOR,
      version: 'sha256:1',
    });
  });

  it('serve an aggregate type’s events as a source (decision D235)', async () => {
    const fake = installFake();
    const source = viewSourceOf(
      new EventQueryClient(
        { context: 'sales', name: 'order' },
        { fetcher: actingAs({}) },
      ),
    );
    const streams = [{ aggregateId: 'o1', version: 1, events: [] }];
    fake.failNext(
      /\/sales\/order\/events\/query$/,
      () =>
        new Response(JSON.stringify({ total: 1, exact: true, list: streams }), {
          headers: { 'Content-Type': 'application/json' },
        }),
    );
    expect(
      await source.paged({ filter: { op: 'MATCH_ALL' } } as never),
    ).toEqual({ total: 1, list: streams });
    fake.failNext(
      /\/sales\/order\/events\/query$/,
      () =>
        new Response(
          JSON.stringify([{ event: 'sales.order.OrderPaid', n: 2 }]),
          {
            headers: { 'Content-Type': 'application/json' },
          },
        ),
    );
    expect(
      await source.aggregate({
        elements: [{ path: 'events' }],
        groupBy: [{ type: 'TERMS', field: 'name', alias: 'event' }],
        metrics: [{ type: 'COUNT', alias: 'n' }],
      } as never),
    ).toEqual([{ event: 'sales.order.OrderPaid', n: 2 }]);
  });

  it('read a page whose total the service did not count as its rows', async () => {
    const fake = installFake();
    fake.failNext(
      /query$/,
      () =>
        new Response(
          JSON.stringify({ total: null, exact: false, list: ROWS }),
          { headers: { 'Content-Type': 'application/json' } },
        ),
    );
    const source = viewSourceOf(
      new DatasetClient({ fetcher: actingAs({}) }).dataset('sales', 'orders'),
    );
    expect(await source.paged({} as never)).toEqual({ total: 2, list: ROWS });
  });

  it('answer a descriptor still current as not modified, and one without an entity tag by its own version', async () => {
    const fake = installFake();
    const source = viewSourceOf(
      new DatasetClient({ fetcher: actingAs({}) }).dataset('sales', 'orders'),
    );
    fake.failNext(
      /query$/,
      () =>
        new Response(null, { status: 304, headers: { ETag: '"sha256:1"' } }),
    );
    expect(await source.describe!('sha256:1')).toEqual({
      notModified: true,
      version: 'sha256:1',
    });
    fake.failNext(
      /query$/,
      () =>
        new Response(JSON.stringify(DESCRIPTOR), {
          headers: { 'Content-Type': 'application/json' },
        }),
    );
    expect(await source.describe!()).toMatchObject({
      notModified: false,
      version: 'sha256:1',
    });
  });

  it('serve every dataset a V10 directory lists in an engine, end to end, its version never null', async () => {
    // The V10 directory names no version. Read as `null`, the engine took
    // every dataset for one it cannot serve and registered none (7.2c-2's
    // rows page); nothing here is stubbed between the store's catalog and the
    // engine's registry.
    installFake({
      datasets: [
        {
          ...orders(),
          source: {
            ...orders().source,
            describe: vi.fn(async () => ({
              notModified: false,
              descriptor: DATASET_DESCRIPTOR,
              version: DATASET_DESCRIPTOR.version,
            })),
          },
        },
      ],
    });
    const catalog = datasetCatalogOf(
      new DatasetClient({ fetcher: actingAs({}) }),
    );
    for (const entry of await catalog.list())
      expect(entry.version).not.toBeNull();
    const engine = new ViewEngine({
      resources: [],
      store: new MemoryViewStore(),
    });
    const errors: unknown[] = [];
    const served = await new DatasetResources(engine, catalog, (_, error) =>
      errors.push(error),
    ).refresh();
    expect(errors).toEqual([]);
    expect(served.map(({ id }) => id)).toEqual(['sales.orders']);
    expect(engine.definitions.get('sales.orders')?.title).toBe('Orders');
  });

  it('read a dataset without a title as untitled', async () => {
    installFake({ datasets: [{ ...orders(), title: undefined }] });
    const [entry] = await datasetCatalogOf(
      new DatasetClient({ fetcher: actingAs({}) }),
    ).list();
    expect(entry.title).toBeNull();
  });
});
