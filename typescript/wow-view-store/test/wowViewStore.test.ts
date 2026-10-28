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
 * The V10 `WowViewStore` over the in-memory stand-in for the V10 service:
 * the requests it sends and the edges the conformance suite does not reach
 * — a view another tab moved, a write whose read back fails, a service that
 * answers nothing, or nothing it knows. The real service holds the same
 * behaviour in `typescript/integration-test/test/view-store/`.
 */

import { describe, expect, it, vi } from 'vitest';
import { Fetcher } from '@ahoo-wang/fetcher';
import { WowHeaders } from '@ahoo-wang/wow-client';
import type {
  ViewConfig,
  ViewPermissions,
  WriteOptions,
} from '@ahoo-wang/wow-view-engine';
import {
  SHARED_SCOPE,
  SYSTEM_SCOPE,
  WowViewStore,
  WowViewStoreErrorCodes,
} from '../src/index.js';
import { listAll } from './conformance/wowViewStoreService.js';
import { problem } from './support/fakeViewStore.js';
import {
  actingAs,
  BASE_URL,
  installFake,
  WRITER_ROLES,
  type Caller,
} from './support.js';

const ALICE: Caller = {
  user: 'alice',
  tenant: 't1',
  roles: WRITER_ROLES,
  app: 'console',
};

const config = {
  kind: 'record',
  pageSize: 20,
} as unknown as ViewConfig;

function storeOf(caller: Caller = ALICE): WowViewStore {
  return new WowViewStore({ fetcher: actingAs(caller) });
}

function write(requestId = `r-${crypto.randomUUID()}`): WriteOptions {
  return { requestId };
}

function personal(store: WowViewStore, title = 'Mine') {
  return store.create(
    { definitionId: 'orders', title, scope: 'personal', config },
    write(),
  );
}

describe('the V10 WowViewStore', () => {
  it('sends the request id, the expected version and the scope it acts in', async () => {
    const fake = installFake();
    const store = storeOf();
    const made = await store.create(
      { definitionId: 'orders', title: 'Ours', scope: 'shared', config },
      write('create-1'),
    );
    await store.rename(made.id, 'Ours, renamed', made.revision, write('r-2'));

    const [create, rename] = fake.requests.filter(
      request => request.method === 'POST',
    );
    expect(create).toMatchObject({
      path: '/view-store/view/create-view',
      body: { definitionId: 'orders', title: 'Ours', config },
    });
    expect(create.headers.get(WowHeaders.REQUEST_ID)).toBe('create-1');
    expect(create.headers.get(WowHeaders.SCOPE)).toBe('owner="(shared)"');
    expect(rename).toMatchObject({
      path: `/view-store/view/${made.id}/rename-view`,
      body: { title: 'Ours, renamed' },
    });
    expect(rename.headers.get('If-Match')).toBe('"1"');
    expect(rename.headers.get(WowHeaders.SCOPE)).toBe('owner="(shared)"');
  });

  it('waits for the saga that moves a view to its new owner', async () => {
    const fake = installFake();
    const store = storeOf();
    const made = await personal(store);
    const shared = await store.changeAudience(
      made.id,
      'shared',
      made.revision,
      write(),
    );
    expect(shared).toMatchObject({ scope: 'shared', revision: '3' });
    const share = fake.requests.find(request =>
      request.path.endsWith('/share-view'),
    )!;
    expect(share.headers.get(WowHeaders.WAIT)).toBe(
      'handled(view-store/ViewAudienceSaga.onAudienceChanged) > snapshot(view-store)',
    );
    expect(share.headers.has(WowHeaders.SCOPE)).toBe(false);
  });

  it('exports the scopes of the reserved owners', () => {
    expect(SHARED_SCOPE).toEqual({ ownerId: '(shared)' });
    expect(SYSTEM_SCOPE).toEqual({
      tenantId: '(platform)',
      ownerId: '(system)',
    });
    expect(WowViewStoreErrorCodes.INVALID).toBe('view-store.invalid');
  });

  it('keeps the permissions a host gives, and none it does not', () => {
    const permissions = (): ViewPermissions => ({
      createPersonal: true,
      createShared: false,
      reorder: true,
      setDefault: true,
      instance: () => ({ save: true, rename: true, delete: true }),
    });
    expect(
      new WowViewStore({ fetcher: actingAs(ALICE), permissions }).permissions,
    ).toBe(permissions);
    expect(storeOf().permissions).toBeUndefined();
  });

  describe('the list', () => {
    it('reads a definition with more views than a page across pages, system, shared, then personal, each oldest first', async () => {
      let clock = 0;
      const fake = installFake({ now: () => ++clock });
      const store = storeOf();
      const made = { system: [], shared: [], personal: [] } as Record<
        'system' | 'shared' | 'personal',
        string[]
      >;
      // Interleaved, so neither audience is listed by when it was written.
      for (let i = 0; i < 102; i++)
        for (const scope of ['personal', 'shared'] as const)
          made[scope].push(
            (
              await store.create(
                {
                  definitionId: 'orders',
                  title: `${scope} ${i}`,
                  scope,
                  config,
                },
                write(),
              )
            ).id,
          );
      for (let i = 0; i < 2; i++)
        made.system.push(
          (
            await store.create(
              {
                definitionId: 'orders',
                title: `system ${i}`,
                scope: 'system',
                config,
              },
              write(),
            )
          ).id,
        );
      fake.requests.length = 0;

      const pages: string[][] = [];
      const scopes: string[][] = [];
      let cursor: string | null = null;
      do {
        const page = await store.list('orders', { cursor });
        pages.push(page.items.map(({ id }) => id));
        scopes.push([...new Set(page.items.map(({ scope }) => scope))]);
        cursor = page.nextCursor;
      } while (cursor !== null);

      // A page reads on into the next audience while it has room.
      expect(pages.map(page => page.length)).toEqual([100, 100, 6]);
      expect(scopes).toEqual([
        ['system', 'shared'],
        ['shared', 'personal'],
        ['personal'],
      ]);
      expect(pages.flat()).toEqual([
        ...made.system,
        ...made.shared,
        ...made.personal,
      ]);
      // Each audience is read by the snapshot model's cursor, asking for
      // what the page has room for.
      const queries = fake.requests.filter(request =>
        request.path.endsWith('/snapshot/query'),
      );
      expect(queries.map(({ body }) => body)).toMatchObject(
        [98, 100, 96, 100].map(size => ({ kind: 'cursor', size })),
      );
      expect(
        queries.map(request => request.headers.get(WowHeaders.SCOPE)),
      ).toEqual(['owner="(shared)"', 'owner="(shared)"', null, null]);
    });

    it('refuses a malformed cursor as INVALID, before asking the service', async () => {
      const fake = installFake();
      const store = storeOf();
      for (const cursor of [
        'not a cursor',
        btoa(JSON.stringify({ audience: 'elsewhere', cursor: null })),
        btoa('{"audience":'),
      ])
        await expect(store.list('orders', { cursor })).rejects.toMatchObject({
          code: 'INVALID',
        });
      expect(fake.requests).toEqual([]);
    });
  });

  describe('a view by id', () => {
    it('is one read wherever it is, sent as the caller', async () => {
      const fake = installFake({
        systemViews: [
          {
            id: 'configured-1',
            definitionId: 'orders',
            title: 'Configured',
            config: { kind: 'record' },
          },
        ],
      });
      const store = storeOf();
      const mine = await personal(store);
      const ours = await store.create(
        { definitionId: 'orders', title: 'Ours', scope: 'shared', config },
        write(),
      );
      const everyone = await store.create(
        { definitionId: 'orders', title: 'Everyone', scope: 'system', config },
        write(),
      );
      for (const [view, scope] of [
        [mine, 'personal'],
        [ours, 'shared'],
        [everyone, 'system'],
      ] as const) {
        fake.requests.length = 0;
        expect(await storeOf().get(view.id)).toEqual({ ...view, scope });
        expect(fake.requests).toHaveLength(1);
        expect(fake.requests[0]).toMatchObject({
          method: 'GET',
          path: `/view-store/views/${view.id}`,
        });
        expect(fake.requests[0]!.headers.has(WowHeaders.SCOPE)).toBe(false);
      }
      const configured = await storeOf().get('configured-1');
      expect(configured).toMatchObject({
        scope: 'system',
        title: 'Configured',
      });
      expect(configured).not.toHaveProperty('stored');
    });

    it('is NOT_FOUND for none, and for a view the host declares in code without asking', async () => {
      const fake = installFake();
      await expect(storeOf().get('missing')).rejects.toMatchObject({
        code: 'NOT_FOUND',
        detail: { code: WowViewStoreErrorCodes.NOT_FOUND },
      });
      fake.requests.length = 0;
      await expect(storeOf().get('system:orders.all')).rejects.toMatchObject({
        code: 'NOT_FOUND',
      });
      expect(fake.requests).toEqual([]);
    });

    it('is written from where the read found it', async () => {
      const fake = installFake();
      const made = await storeOf().create(
        { definitionId: 'orders', title: 'Ours', scope: 'shared', config },
        write(),
      );
      fake.requests.length = 0;
      await storeOf().rename(made.id, 'Renamed', made.revision, write());
      const [read, rename] = fake.requests;
      expect(read).toMatchObject({ path: `/view-store/views/${made.id}` });
      expect(rename!.headers.get(WowHeaders.SCOPE)).toBe('owner="(shared)"');
    });
  });

  describe('a view another tab moved', () => {
    it('is written where it went', async () => {
      installFake();
      const mine = storeOf();
      const other = storeOf();
      const made = await personal(mine);
      await listAll(mine, 'orders');
      const shared = await other.changeAudience(
        made.id,
        'shared',
        made.revision,
        write(),
      );

      const renamed = await mine.rename(
        made.id,
        'Renamed',
        shared.revision,
        write(),
      );
      expect(renamed).toMatchObject({ scope: 'shared', title: 'Renamed' });
    });

    it('conflicts when it moves to where it already went, at a stale revision', async () => {
      installFake();
      const mine = storeOf();
      const made = await personal(mine);
      await storeOf().changeAudience(made.id, 'shared', made.revision, write());
      await expect(
        mine.changeAudience(made.id, 'shared', made.revision, write()),
      ).rejects.toMatchObject({
        code: 'CONFLICT',
        instance: { id: made.id, scope: 'shared' },
      });
    });
  });

  describe('the answer of a landed write', () => {
    it('is the view as it is when the read back of its version fails', async () => {
      const fake = installFake();
      const store = storeOf();
      const made = await personal(store);
      fake.failNext(/\/state$/, () =>
        problem(503, 'wow.storage.unavailable', 'Later.'),
      );
      const saved = await store.save(
        made.id,
        { ...config, pageSize: 5 } as ViewConfig,
        made.revision,
        write(),
      );
      expect(saved).toMatchObject({ revision: '2', config: { pageSize: 5 } });
    });

    it('is the view as it is when the result names no version', async () => {
      const fake = installFake();
      const store = storeOf();
      const made = await personal(store);
      const real = fake.fetch;
      // The rename lands, answered without a version.
      vi.stubGlobal(
        'fetch',
        vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
          const response = await real(input, init);
          if (!String(input).endsWith('/rename-view')) return response;
          return new Response(
            JSON.stringify({ status: 'succeeded', ref: { id: made.id } }),
            {
              headers: { 'Content-Type': 'application/json' },
            },
          );
        }),
      );
      const renamed = await store.rename(
        made.id,
        'Renamed',
        made.revision,
        write(),
      );
      expect(renamed).toMatchObject({ title: 'Renamed', revision: '2' });
    });
  });

  describe('stored system views', () => {
    it('conflict at a revision the store never saw', async () => {
      installFake();
      const store = storeOf();
      const made = await store.create(
        { definitionId: 'orders', title: 'Sys', scope: 'system', config },
        write(),
      );
      await expect(
        storeOf().rename(made.id, 'Other', 'not-its-hash', write()),
      ).rejects.toMatchObject({ code: 'CONFLICT', instance: made });
    });

    it('conflict when a saved view of the same id shadows one since read', async () => {
      installFake({
        systemViews: [
          {
            id: 'shadowed',
            definitionId: 'orders',
            title: 'Shadowed',
            config: { kind: 'record' },
          },
        ],
      });
      const store = storeOf();
      await listAll(store, 'orders');
      await actingAs(ALICE).fetch('/view-store/view/shadowed/create-view', {
        method: 'POST',
        body: { definitionId: 'orders', title: 'Mine', config },
      });
      await expect(
        store.rename('shadowed', 'Other', 'not-its-hash', write()),
      ).rejects.toMatchObject({
        code: 'CONFLICT',
        instance: { id: 'shadowed', scope: 'personal' },
      });
    });

    it('are refused at a revision that is not one of this store', async () => {
      installFake();
      const store = storeOf();
      const made = await personal(store);
      await expect(
        store.rename(made.id, 'Other', 'stale', write()),
      ).rejects.toMatchObject({ code: 'CONFLICT' });
    });
  });

  describe('preferences', () => {
    it('conflict at a stale revision, carrying the ones stored', async () => {
      installFake();
      const store = storeOf();
      const stored = await store.setPreferences(
        'orders',
        { order: ['a'], defaultInstanceId: null, revision: '0' },
        write(),
      );
      await expect(
        storeOf().setPreferences(
          'orders',
          { order: ['b'], defaultInstanceId: null, revision: '0' },
          write(),
        ),
      ).rejects.toMatchObject({ code: 'CONFLICT', preferences: stored });
    });

    it('are read back when the result names no version', async () => {
      const fake = installFake();
      const real = fake.fetch;
      vi.stubGlobal(
        'fetch',
        vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
          const response = await real(input, init);
          return String(input).endsWith('/set-view-preferences')
            ? new Response('{"status":"succeeded"}', {
                headers: { 'Content-Type': 'application/json' },
              })
            : response;
        }),
      );
      expect(
        await storeOf().setPreferences(
          'orders',
          { order: ['a'], defaultInstanceId: null, revision: '0' },
          write(),
        ),
      ).toMatchObject({ order: ['a'], revision: '1' });
    });

    it('pass a refusal other than a conflict on', async () => {
      installFake();
      await expect(
        storeOf({ ...ALICE, app: undefined }).setPreferences(
          'orders',
          { order: [], defaultInstanceId: null, revision: '0' },
          write(),
        ),
      ).rejects.toMatchObject({
        code: 'INVALID',
        detail: { code: WowViewStoreErrorCodes.APP_REQUIRED },
      });
    });
  });

  describe('a service', () => {
    it('without a view store is UNSUPPORTED, not a missing view', async () => {
      installFake();
      const store = new WowViewStore({
        fetcher: new Fetcher({ baseURL: `${BASE_URL}elsewhere/` }),
      });
      for (const refused of [
        () => store.list('orders'),
        () => store.get('v1'),
        () => store.getPreferences('orders'),
        () =>
          store.create(
            {
              definitionId: 'orders',
              title: 'Mine',
              scope: 'personal',
              config,
            },
            write(),
          ),
      ])
        await expect(refused()).rejects.toMatchObject({ code: 'UNSUPPORTED' });
    });

    it('that cannot be reached is UNAVAILABLE, and not reachable', async () => {
      vi.stubGlobal(
        'fetch',
        vi.fn(async () => {
          throw new TypeError('Failed to fetch');
        }),
      );
      const refused = await storeOf()
        .list('orders')
        .catch((error: unknown) => error);
      expect(refused).toMatchObject({
        code: 'UNAVAILABLE',
        message: expect.stringContaining('could not be reached'),
      });
      expect(refused).not.toHaveProperty('reachable');
    });

    it('that fails on its side is UNAVAILABLE and reachable', async () => {
      const fake = installFake();
      fake.failNext(/snapshot\/query$/, () =>
        problem(503, 'wow.storage.unavailable', 'The store is down.'),
      );
      await expect(storeOf().list('orders')).rejects.toMatchObject({
        code: 'UNAVAILABLE',
        reachable: true,
        detail: { code: 'wow.storage.unavailable' },
      });
    });

    it('that answers what is not JSON is UNAVAILABLE and reachable', async () => {
      const fake = installFake();
      fake.failNext(
        /system-views$/,
        () => new Response('<html>Sign in</html>'),
      );
      await expect(storeOf().list('orders')).rejects.toMatchObject({
        code: 'UNAVAILABLE',
        reachable: true,
      });
    });
  });
});
