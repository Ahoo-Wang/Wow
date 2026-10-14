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
 * What the V10 service holds the store to beyond the port's conformance:
 * tenants and applications kept apart, the stored and configured system
 * views, who writes what (the service's roles), the tenant's dataset
 * wording, the store's errors as the service words them, and the engine's
 * `ViewSource` over a V10 query route.
 */

import { Fetcher } from '@ahoo-wang/fetcher';
import { asc, desc, filter } from '@ahoo-wang/wow-client';
import { SnapshotQueryClient } from '@ahoo-wang/wow-client/v10';
import type { ViewConfig, ViewInstance } from '@ahoo-wang/wow-view-engine';
import {
  viewSourceOf,
  WowDatasetWordings,
  WowViewStore,
  WowViewStoreErrorCodes,
} from '@ahoo-wang/wow-view-store/v10';
import { describe, expect, it } from 'vitest';
import {
  actingAs,
  type Caller,
  serviceURL,
  SHARED_WRITER,
  SYSTEM_VIEW_DEFINITION,
  SYSTEM_WRITER,
} from './v10ViewStoreServer';

const run = Date.now();
const ALICE: Caller = {
  tenantId: `service-a-${run}`,
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

describe.each([
  ['another tenant', { ...ALICE, tenantId: `service-b-${run}` }],
  ['another application', { ...ALICE, appId: 'portal' }],
])('a caller of %s', (_, other: Caller) => {
  it('neither lists, reads nor writes the views and preferences', async () => {
    const alice = storeOf(ALICE);
    const stranger = storeOf(other);
    const definitionId = fresh();
    const mine = await alice.create(
      { definitionId, title: 'Mine', scope: 'personal', config },
      write(),
    );
    const ours = await alice.create(
      { definitionId, title: 'Ours', scope: 'shared', config },
      write(),
    );
    const preferences = await alice.setPreferences(
      definitionId,
      { order: [ours.id], defaultInstanceId: ours.id, revision: '0' },
      write(),
    );

    expect(await stranger.list(definitionId)).toEqual([]);
    for (const view of [mine, ours]) {
      await expect(stranger.get(view.id)).rejects.toMatchObject({
        code: 'NOT_FOUND',
      });
      await expect(
        stranger.rename(view.id, 'Taken', view.revision, write()),
      ).rejects.toMatchObject({ code: 'NOT_FOUND' });
    }
    await expect(
      stranger.changeAudience(ours.id, 'personal', ours.revision, write()),
    ).rejects.toMatchObject({ code: 'NOT_FOUND' });
    expect(await stranger.getPreferences(definitionId)).toMatchObject({
      order: [],
      revision: '0',
    });

    expect(await alice.get(mine.id)).toEqual(mine);
    expect(await alice.get(ours.id)).toEqual(ours);
    expect(await alice.getPreferences(definitionId)).toEqual(preferences);
  });
});

describe('system views', () => {
  const ADMIN: Caller = {
    tenantId: `system-a-${run}`,
    user: 'admin',
    appId: 'system-views',
    roles: [SYSTEM_WRITER],
  };
  const READER: Caller = {
    tenantId: `system-b-${run}`,
    user: 'reader',
    appId: 'system-views',
  };

  it('a stored one is created, read by every tenant, edited and deleted', async () => {
    const admin = storeOf(ADMIN);
    const reader = storeOf({ ...READER, roles: [SYSTEM_WRITER] });
    const definitionId = fresh();

    const created = await admin.create(
      { definitionId, title: 'Everyone', scope: 'system', config },
      write(),
    );
    expect(created).toMatchObject({
      definitionId,
      title: 'Everyone',
      scope: 'system',
      stored: true,
    });
    const { id } = created;
    expect(await reader.list(definitionId)).toEqual([
      {
        id,
        definitionId,
        title: 'Everyone',
        scope: 'system',
        kind: 'record',
        revision: created.revision,
        stored: true,
      },
    ]);
    expect(await reader.get(id)).toEqual(created);
    expect(
      await storeOf({ ...READER, appId: 'portal' }).list(definitionId),
    ).toEqual([]);

    // Edited at its revision (the content hash); a stale one conflicts.
    const renamed = await admin.rename(
      id,
      'Everyone, renamed',
      created.revision,
      write(),
    );
    expect(renamed).toMatchObject({ id, title: 'Everyone, renamed' });
    expect(renamed.revision).not.toBe(created.revision);
    await expect(
      reader.save(id, { ...config, pageSize: 50 }, created.revision, write()),
    ).rejects.toMatchObject({ code: 'CONFLICT', instance: renamed });
    const saved = await reader.save(
      id,
      { ...config, pageSize: 50 },
      renamed.revision,
      write(),
    );
    expect(saved.config).toMatchObject({ pageSize: 50 });

    await expect(
      admin.changeAudience(id, 'shared', saved.revision, write()),
    ).rejects.toMatchObject({ code: 'FORBIDDEN' });

    await admin.delete(id, saved.revision, write());
    expect(await reader.list(definitionId)).toEqual([]);
    await expect(reader.get(id)).rejects.toMatchObject({ code: 'NOT_FOUND' });
  });

  it('a stored one is written only with the role that writes (system)', async () => {
    await expect(
      storeOf(READER).create(
        { definitionId: fresh(), title: 'Mine', scope: 'system', config },
        write(),
      ),
    ).rejects.toMatchObject({
      code: 'FORBIDDEN',
      detail: { code: 'wow.auth.forbidden' },
    });
  });

  it('a configured one is served and stays read-only', async () => {
    const reader = storeOf({ ...READER, appId: 'conformance' });
    const [configured] = await reader.list(SYSTEM_VIEW_DEFINITION);
    expect(configured).toMatchObject({
      id: 'conformance-system-view',
      scope: 'system',
      title: 'Conformance',
    });
    expect(configured).not.toHaveProperty('stored');
    await expect(
      reader.rename(configured!.id, 'Mine', configured!.revision, write()),
    ).rejects.toMatchObject({ code: 'FORBIDDEN' });
  });
});

describe('shared views', () => {
  it('are written only with the role that writes (shared)', async () => {
    const definitionId = fresh();
    const writer = storeOf(ALICE);
    const ours = await writer.create(
      { definitionId, title: 'Ours', scope: 'shared', config },
      write(),
    );
    const reader = storeOf({ ...ALICE, user: 'bob', roles: [] });

    expect(await reader.get(ours.id)).toEqual(ours);
    for (const refused of [
      () => reader.rename(ours.id, 'Taken', ours.revision, write()),
      () => reader.changeAudience(ours.id, 'personal', ours.revision, write()),
      () =>
        reader.create(
          { definitionId, title: 'Ours too', scope: 'shared', config },
          write(),
        ),
    ])
      await expect(refused()).rejects.toMatchObject({
        code: 'FORBIDDEN',
        detail: { code: 'wow.auth.forbidden' },
      });
  });
});

describe('the dataset wording', () => {
  it('is the tenant’s: set by a writer, read by everyone, cleared', async () => {
    const dataset = `sales.${fresh()}`;
    const writer = new WowDatasetWordings({ fetcher: actingAs(ALICE) });
    const reader = new WowDatasetWordings({
      fetcher: actingAs({ ...ALICE, user: 'bob', roles: [] }),
    });

    const first = await writer.set(
      dataset,
      {
        title: 'Sales',
        recordNoun: 'sale',
        fields: { amount: { label: 'Amount' } },
      },
      '0',
      write(),
    );
    expect(first).toBe('1');
    expect((await reader.list()).get(dataset)).toEqual({
      wording: {
        title: 'Sales',
        recordNoun: 'sale',
        fields: { amount: { label: 'Amount' } },
      },
      revision: '1',
    });

    await expect(
      reader.set(dataset, { title: 'Mine' }, first, write()),
    ).rejects.toMatchObject({ code: 'FORBIDDEN' });
    await expect(
      writer.set(dataset, { title: 'Stale' }, '0', write()),
    ).rejects.toMatchObject({ code: 'CONFLICT' });

    expect(await writer.clear(dataset, first, write())).toBe('2');
    expect((await reader.list()).get(dataset)).toEqual({
      wording: {},
      revision: '2',
    });
  });
});

describe('errors', () => {
  it('a request without an application is INVALID, by the service’s code', async () => {
    const store = new WowViewStore({
      fetcher: actingAs({ ...ALICE, appId: '' }),
    });
    await expect(
      store.create(
        { definitionId: fresh(), title: 'Mine', scope: 'personal', config },
        write(),
      ),
    ).rejects.toMatchObject({
      code: 'INVALID',
      detail: { code: WowViewStoreErrorCodes.APP_REQUIRED },
    });
  });

  it('a blank title is INVALID, by the service’s code', async () => {
    await expect(
      storeOf(ALICE).create(
        { definitionId: fresh(), title: ' ', scope: 'personal', config },
        write(),
      ),
    ).rejects.toMatchObject({
      code: 'INVALID',
      detail: { code: WowViewStoreErrorCodes.INVALID },
    });
  });

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

describe('viewSourceOf over a V10 query route', () => {
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
    const source = viewSourceOf(
      new SnapshotQueryClient(
        { context: 'view-store', name: 'view' },
        { fetcher: actingAs(ALICE) },
      ),
    );
    const mine = filter.eq('state.definitionId', definitionId);

    const page = await source.paged({
      filter: mine,
      sort: [desc('state.title')],
      pagination: { index: 1, size: 2 },
    });
    expect(page.total).toBe(3);
    expect(page.list.map(row => row.aggregateId)).toEqual([
      made[2].id,
      made[1].id,
    ]);

    const first = await source.cursor({
      filter: mine,
      sort: [asc('aggregateId')],
      size: 2,
    });
    expect(first.list).toHaveLength(2);
    expect(first.nextCursor).not.toBeNull();

    const rows = await source.aggregate({
      filter: mine,
      groupBy: [{ type: 'TERMS', field: 'state.kind', alias: 'kind' }],
      metrics: [{ type: 'COUNT', alias: 'views' }],
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
