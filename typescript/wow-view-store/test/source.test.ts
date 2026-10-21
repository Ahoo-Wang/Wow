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
import { DatasetResources } from '@ahoo-wang/wow-view-engine';
import { datasetCatalogOf, viewSourceOf } from '../src/index.js';
import type { FakeDataset } from './support/fakeViewStore.js';
import { actingAs, installFake } from './support.js';

const ROWS = [
  { id: 'a', amount: 1 },
  { id: 'b', amount: 2 },
];

const DESCRIPTOR = { version: 'sha256:1', fields: [] };

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

  it("hand every dataset the directory lists to the engine's DatasetResources to describe", async () => {
    // A V10 directory names no version; were it read as `null`, the engine
    // would take every dataset for one it cannot serve and describe none.
    const dataset = orders();
    installFake({ datasets: [dataset] });
    const engine = {
      definitions: new Map<string, unknown>(),
      register: vi.fn(),
      unregister: vi.fn(() => true),
    };
    await new DatasetResources(
      engine,
      datasetCatalogOf(new DatasetClient({ fetcher: actingAs({}) })),
    ).refresh();
    expect(dataset.source.describe).toHaveBeenCalled();
  });

  it('read a dataset without a title as untitled', async () => {
    installFake({ datasets: [{ ...orders(), title: undefined }] });
    const [entry] = await datasetCatalogOf(
      new DatasetClient({ fetcher: actingAs({}) }),
    ).list();
    expect(entry.title).toBeNull();
  });
});
