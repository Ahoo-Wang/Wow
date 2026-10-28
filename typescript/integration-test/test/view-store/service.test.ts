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
 * What the service holds the store to beyond the port's conformance: the
 * service's own rules (callers, roles and its error codes,
 * `describeWowViewStoreService`, which the fake service of
 * `@ahoo-wang/wow-view-store` passes too), the store's errors that only a
 * network shows, and the engine's `ViewSource` over a query route.
 */

import { Fetcher } from '@ahoo-wang/fetcher';
import {
  asc,
  desc,
  filter,
  AggregationGroupType,
  AggregationMetricType,
} from '@ahoo-wang/wow-client/dsl';
import { SnapshotQueryClient } from '@ahoo-wang/wow-client';
import type {
  ViewConfig,
  ViewInstance,
  ViewSource,
} from '@ahoo-wang/wow-view-engine';
import { WowDatasetWordings, WowViewStore } from '@ahoo-wang/wow-view-store';
import { describe, expect, it } from 'vitest';
import { describeWowViewStoreService } from '../../../wow-view-store/test/conformance/wowViewStoreService.js';
import {
  actingAs,
  type Caller,
  serviceURL,
  SHARED_WRITER,
  SYSTEM_VIEW_DEFINITION,
  SYSTEM_WRITER,
} from './viewStoreServer';

describeWowViewStoreService({
  name: 'the view store service',
  sharedWriter: SHARED_WRITER,
  systemWriter: SYSTEM_WRITER,
  configured: {
    definitionId: SYSTEM_VIEW_DEFINITION,
    app: 'conformance',
    id: 'conformance-system-view',
    title: 'Conformance',
  },
  connect: () => caller => {
    const fetcher = actingAs({
      tenantId: caller.tenant,
      user: caller.user,
      appId: caller.app,
      roles: caller.roles,
    });
    return {
      views: new WowViewStore({ fetcher }),
      wordings: new WowDatasetWordings({ fetcher }),
    };
  },
});

const ALICE: Caller = {
  tenantId: `service-a-${Date.now()}`,
  user: 'alice',
  appId: 'console',
  roles: [SHARED_WRITER],
};

const config: ViewConfig = {
  kind: 'record',
  filter: { op: 'and', children: [] },
  filterMode: 'simple',
  refresh: { interval: null },
  sort: [],
  pageSize: 20,
  layout: 'table',
  table: { columns: [{ field: 'id' }] },
  card: { title: 'id', fields: [] },
};

function storeOf(caller: Caller): WowViewStore {
  return new WowViewStore({ fetcher: actingAs(caller) });
}

function write() {
  return { requestId: `service-${crypto.randomUUID()}` };
}

function fresh(): string {
  return `service-${crypto.randomUUID()}`;
}

// What only a network shows: where the requests go, not what the service decides.
describe('errors', () => {
  it('a service without the view store is UNSUPPORTED', async () => {
    const elsewhere = new Fetcher({ baseURL: `${serviceURL()}elsewhere/` });
    const store = new WowViewStore({ fetcher: elsewhere });
    await expect(store.list(fresh())).rejects.toMatchObject({
      code: 'UNSUPPORTED',
    });
  });

  it('a service nobody answers is UNAVAILABLE, not reached', async () => {
    const store = new WowViewStore({
      fetcher: new Fetcher({ baseURL: 'http://127.0.0.1:9/' }),
    });
    const refused = await store.list(fresh()).catch((error: unknown) => error);
    expect(refused).toMatchObject({ code: 'UNAVAILABLE' });
    expect(refused).not.toHaveProperty('reachable');
  });
});

describe('a snapshot query client as the view source', () => {
  it('pages, walks a cursor, aggregates and describes the view snapshots', async () => {
    const definitionId = fresh();
    const store = storeOf(ALICE);
    const made: ViewInstance[] = [];
    for (const title of ['A', 'B', 'C'])
      made.push(
        await store.create(
          { definitionId, title, scope: 'personal', config },
          write(),
        ),
      );
    // The client itself, with no adapter: what a host hands the engine.
    const source: ViewSource = new SnapshotQueryClient(
      { context: 'view-store', name: 'view' },
      { fetcher: actingAs(ALICE) },
    );
    const mine = filter.eq('state.definitionId', definitionId);

    const page = await source.paged({
      filter: mine,
      sort: [desc('state.title')],
      pagination: { index: 1, size: 2 },
    });
    expect(page.total).toBe(3);
    expect(
      page.list.map(row => (row as { aggregateId: string }).aggregateId),
    ).toEqual([made[2].id, made[1].id]);

    const first = await source.cursor({
      filter: mine,
      sort: [asc('aggregateId')],
      size: 2,
    });
    expect(first.list).toHaveLength(2);
    expect(first.nextCursor).not.toBeNull();

    const rows = await source.aggregate({
      filter: mine,
      groupBy: [
        {
          type: AggregationGroupType.TERMS,
          field: 'state.kind',
          alias: 'kind',
        },
      ],
      metrics: [{ type: AggregationMetricType.COUNT, alias: 'views' }],
    } as Parameters<typeof source.aggregate>[0]);
    expect(rows).toEqual([{ kind: 'record', views: 3 }]);

    const described = await source.describe!();
    if (described.notModified) throw new Error('expected a descriptor');
    expect(described.version).toBe(described.descriptor.version);
    expect(described.descriptor.fields.map(field => field.path)).toEqual(
      expect.arrayContaining(['state.title', 'state.kind']),
    );
    expect(await source.describe!(described.version)).toEqual({
      notModified: true,
      version: described.version,
    });
  });
});
