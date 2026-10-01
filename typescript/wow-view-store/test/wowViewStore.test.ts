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
 * What `WowViewStore` sends and how it reads the answers, against a fake
 * server: the paths and headers of each method, where it looks a view up,
 * and the edges the port's conformance suite does not reach on a healthy
 * server — a view moved by another tab, an answer read back after another
 * writer, a claim refused by boards without titles, a retried preferences
 * write. The suite itself runs against the real server in
 * `typescript/integration-test/test/view-store/`.
 */

import { describe, expect, it } from 'vitest';
import { Fetcher } from '@ahoo-wang/fetcher';
import type {
  ViewConfig,
  ViewPermissions,
  WriteContext,
} from '@ahoo-wang/wow-view-engine';
import { SHARED_OWNER_ID, WowViewStore } from '../src/index.js';
import {
  at,
  fakeServer,
  json,
  wowError,
  type ServedRequest,
} from './support/fakeServer.js';

const ALICE = '/view-store/tenant/t1/owner/alice';
const SHARED = `/view-store/tenant/t1/owner/${SHARED_OWNER_ID}`;

const config = { kind: 'record', pageSize: 20 } as unknown as ViewConfig;

function snapshot(
  id: string,
  version: number,
  state: Partial<{
    definitionId: string;
    title: string;
    audience: 'personal' | 'shared';
    config: ViewConfig;
  }> = {},
) {
  return {
    aggregateId: id,
    version,
    state: {
      definitionId: 'orders',
      title: 'Mine',
      audience: 'personal',
      config,
      ...state,
    },
  };
}

function instance(
  id: string,
  revision: string,
  scope: 'personal' | 'shared' = 'personal',
  title = 'Mine',
) {
  return { id, definitionId: 'orders', title, scope, revision, config };
}

function commandResult(aggregateId: string, aggregateVersion: number) {
  return json({ errorCode: 'Ok', aggregateId, aggregateVersion });
}

/** The single-snapshot route at `scope`, answering views by id. */
function singles(
  scope: string,
  views: Record<string, () => ReturnType<typeof snapshot> | undefined>,
) {
  return at('POST', `${scope}/view/snapshot/single`, request => {
    const id = (request.body as { filter: { value: string } }).filter.value;
    const view = views[id]?.();
    return view ? json(view) : wowError('NotFound', `${id}`, 404);
  });
}

let sequence = 0;
function write(): WriteContext {
  sequence += 1;
  return { requestId: `request-${sequence}` };
}

function sent(requests: ServedRequest[]): string[] {
  return requests.map(request => `${request.method} ${request.path}`);
}

describe('WowViewStore', () => {
  describe('list', () => {
    it("merges the caller's, the shared and the system views, projected without their configs", async () => {
      const server = fakeServer(
        at('POST', `${ALICE}/view/snapshot/list`, () =>
          json([
            {
              ...snapshot('v1', 2),
              state: {
                definitionId: 'orders',
                title: 'Mine',
                audience: 'personal',
                config: { kind: 'record' },
              },
            },
          ]),
        ),
        at('POST', `${SHARED}/view/snapshot/list`, () =>
          json([
            {
              ...snapshot('v2', 1),
              state: {
                definitionId: 'orders',
                title: 'Ours',
                audience: 'shared',
                config: { kind: 'dashboard' },
              },
            },
            // A host whose owner is `(shared)` asks the same path twice.
            {
              ...snapshot('v1', 2),
              state: {
                definitionId: 'orders',
                title: 'Mine',
                audience: 'personal',
                config: { kind: 'record' },
              },
            },
          ]),
        ),
        at('GET', `${SHARED}/system-views`, request =>
          json(
            request.query.get('definitionId') === 'orders'
              ? [
                  {
                    id: 'open',
                    definitionId: 'orders',
                    title: 'Open',
                    kind: 'analysis',
                    revision: 'abc',
                    config: { kind: 'analysis' },
                    scope: 'system',
                  },
                ]
              : [],
          ),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });

      expect(await store.list('orders')).toEqual([
        {
          id: 'v1',
          definitionId: 'orders',
          title: 'Mine',
          scope: 'personal',
          kind: 'record',
          revision: '2',
        },
        {
          id: 'v2',
          definitionId: 'orders',
          title: 'Ours',
          scope: 'shared',
          kind: 'dashboard',
          revision: '1',
        },
        {
          id: 'open',
          definitionId: 'orders',
          title: 'Open',
          scope: 'system',
          kind: 'analysis',
          revision: 'abc',
        },
      ]);
      const query = server.requests[0]!.body as {
        filter: unknown;
        projection: { include: string[] };
        limit: number;
      };
      expect(query.filter).toEqual({
        field: 'state.definitionId',
        op: 'EQ',
        value: 'orders',
      });
      expect(query.projection.include).toContain('state.config.kind');
      expect(query.projection.include).not.toContain('state.config');
      expect(query.limit).toBe(1000);

      // Each is remembered where it was listed: a write goes there at once.
      server.on(
        at('PUT', `${SHARED}/view/v2/rename`, () => commandResult('v2', 2)),
      );
      server.on(
        singles(SHARED, {
          v2: () => snapshot('v2', 2, { title: 'Renamed', audience: 'shared' }),
        }),
      );
      const before = server.requests.length;
      expect(await store.rename('v2', 'Renamed', '1', write())).toEqual(
        instance('v2', '2', 'shared', 'Renamed'),
      );
      expect(sent(server.requests.slice(before))).toEqual([
        `PUT ${SHARED}/view/v2/rename`,
        `POST ${SHARED}/view/snapshot/single`,
      ]);
      await expect(
        store.save('open', config, 'abc', write()),
      ).rejects.toMatchObject({ code: 'FORBIDDEN' });
    });
  });

  describe('get', () => {
    it("looks an unknown view up on the caller's path, the shared path, then the system views", async () => {
      const server = fakeServer(
        at('GET', `${SHARED}/system-views/open`, () =>
          json({
            id: 'open',
            definitionId: 'orders',
            title: 'Open',
            kind: 'record',
            revision: 'abc',
            config,
            scope: 'system',
          }),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });

      expect(await store.get('open')).toEqual({
        id: 'open',
        definitionId: 'orders',
        title: 'Open',
        scope: 'system',
        revision: 'abc',
        config,
      });
      expect(sent(server.requests)).toEqual([
        `POST ${ALICE}/view/snapshot/single`,
        `POST ${SHARED}/view/snapshot/single`,
        `GET ${SHARED}/system-views/open`,
      ]);
      expect(server.requests[0]!.body).toMatchObject({
        filter: { op: 'ID', value: 'open' },
      });
    });

    it('answers NOT_FOUND where none has it, and never asks for an id of the code namespace', async () => {
      const server = fakeServer();
      const store = new WowViewStore({ fetcher: server.fetcher });

      await expect(store.get('missing')).rejects.toMatchObject({
        code: 'NOT_FOUND',
      });
      expect(server.requests).toHaveLength(3);
      await expect(store.get('system:orders:open')).rejects.toMatchObject({
        code: 'NOT_FOUND',
      });
      expect(server.requests).toHaveLength(3);
    });

    it('stops at a failure that is not a missing view', async () => {
      const server = fakeServer(
        at('POST', `${ALICE}/view/snapshot/single`, () =>
          wowError('InternalServerError', 'boom', 500),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });

      await expect(store.get('v1')).rejects.toMatchObject({
        name: 'ViewStoreError',
        code: 'UNAVAILABLE',
      });
      expect(server.requests).toHaveLength(1);
    });
  });

  describe('create', () => {
    it('posts to the path of its audience with the request id and the wait, then reads it back', async () => {
      const server = fakeServer(
        at('POST', `${SHARED}/view`, () => commandResult('new-1', 1)),
        singles(SHARED, {
          'new-1': () =>
            snapshot('new-1', 1, { title: 'Ours', audience: 'shared' }),
        }),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });
      const context = write();

      const made = await store.create(
        { definitionId: 'orders', title: 'Ours', scope: 'shared', config },
        context,
      );

      expect(made).toEqual(instance('new-1', '1', 'shared', 'Ours'));
      const [post] = server.requests;
      expect(post!.body).toEqual({
        definitionId: 'orders',
        title: 'Ours',
        config,
      });
      expect(post!.headers.get('Command-Request-Id')).toBe(context.requestId);
      expect(post!.headers.get('Command-Wait-Stage')).toBe('SNAPSHOT');
      expect(post!.headers.has('Command-Aggregate-Version')).toBe(false);
      // A host's interceptors never replace the owner the store names.
      expect(post!.path).toBe(`${SHARED}/view`);
    });

    it('answers a retry of its own create from the replay route, without posting again', async () => {
      const context = write();
      let posts = 0;
      const server = fakeServer(
        at('POST', `${ALICE}/view`, () => {
          posts += 1;
          // Landed, and the answer lost on the way back.
          throw new TypeError('fetch failed');
        }),
        at('GET', `${ALICE}/view/requests/${context.requestId}`, () =>
          json(snapshot('new-1', 1, { title: 'Once' })),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });
      const input = {
        definitionId: 'orders',
        title: 'Once',
        scope: 'personal' as const,
        config,
      };

      await expect(store.create(input, context)).rejects.toMatchObject({
        code: 'UNAVAILABLE',
      });
      expect(await store.create(input, context)).toEqual(
        instance('new-1', '1', 'personal', 'Once'),
      );
      expect(posts).toBe(1);
      // Remembered where it landed: a write goes there without a lookup.
      server.on(
        at('PUT', `${ALICE}/view/new-1/rename`, () =>
          commandResult('new-1', 2),
        ),
      );
      server.on(singles(ALICE, { 'new-1': () => snapshot('new-1', 2) }));
      await store.rename('new-1', 'Mine', '1', write());
    });

    it('posts a retry again when its first attempt never landed, and never asks for a first try', async () => {
      const context = write();
      let posts = 0;
      const server = fakeServer(
        at('POST', `${SHARED}/view`, () => {
          posts += 1;
          if (posts === 1) throw new TypeError('fetch failed');
          return commandResult('new-2', 1);
        }),
        singles(SHARED, {
          'new-2': () => snapshot('new-2', 1, { audience: 'shared' }),
        }),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });
      const input = {
        definitionId: 'orders',
        title: 'Mine',
        scope: 'shared' as const,
        config,
      };

      await expect(store.create(input, context)).rejects.toMatchObject({
        code: 'UNAVAILABLE',
      });
      expect(await store.create(input, context)).toEqual(
        instance('new-2', '1', 'shared'),
      );
      expect(sent(server.requests)).toEqual([
        `POST ${SHARED}/view`,
        `GET ${SHARED}/view/requests/${context.requestId}`,
        `POST ${SHARED}/view`,
        `POST ${SHARED}/view/snapshot/single`,
      ]);
      // A create sent once is not probed.
      await store.create(input, write());
      expect(
        sent(server.requests).filter(line => line.includes('/requests/')),
      ).toHaveLength(1);
    });

    it('refuses a system view before sending anything', async () => {
      const server = fakeServer();
      const store = new WowViewStore({ fetcher: server.fetcher });

      await expect(
        store.create(
          { definitionId: 'orders', title: 'Sys', scope: 'system', config },
          write(),
        ),
      ).rejects.toMatchObject({ code: 'INVALID' });
      expect(server.requests).toEqual([]);
    });

    it('reads a refusal by its code', async () => {
      const server = fakeServer(
        at('POST', `${ALICE}/view`, () =>
          wowError('ViewInvalid', "A view's title must not be blank."),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });

      await expect(
        store.create(
          { definitionId: 'orders', title: ' ', scope: 'personal', config },
          write(),
        ),
      ).rejects.toMatchObject({
        code: 'INVALID',
        message: "[ViewInvalid] A view's title must not be blank.",
      });
    });
  });

  describe('writes', () => {
    it('send the expected version and the bodies the routes read', async () => {
      let version = 1;
      const server = fakeServer(
        singles(ALICE, { v1: () => snapshot('v1', version) }),
        at('PUT', `${ALICE}/view/v1/save`, () =>
          commandResult('v1', ++version),
        ),
        at('DELETE', `${ALICE}/view/v1`, () => commandResult('v1', ++version)),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });
      await store.get('v1');

      await store.save('v1', config, '1', write());
      await store.delete('v1', '2', write());

      const [, save, , remove] = server.requests;
      expect(save!.headers.get('Command-Aggregate-Version')).toBe('1');
      expect(save!.body).toEqual({ config });
      expect(remove!.headers.get('Command-Aggregate-Version')).toBe('2');
      expect(remove!.body).toEqual({});
    });

    it("share on the path the view is at with an empty body, and claim on the caller's own path with none", async () => {
      let audience: 'personal' | 'shared' = 'personal';
      const server = fakeServer(
        singles(ALICE, {
          v1: () =>
            audience === 'personal'
              ? snapshot('v1', audience === 'personal' ? 1 : 3)
              : undefined,
        }),
        singles(SHARED, {
          v1: () =>
            audience === 'shared' ? snapshot('v1', 2, { audience }) : undefined,
        }),
        at('PUT', `${ALICE}/view/v1/share`, () => {
          audience = 'shared';
          return commandResult('v1', 2);
        }),
        at('PUT', `${ALICE}/view/v1/claim`, () => {
          audience = 'personal';
          return commandResult('v1', 1);
        }),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });
      await store.get('v1');

      expect(await store.changeAudience('v1', 'shared', '1', write())).toEqual(
        instance('v1', '2', 'shared'),
      );
      expect(
        (await store.changeAudience('v1', 'personal', '2', write())).scope,
      ).toBe('personal');
      const share = server.requests.find(request =>
        request.path.endsWith('/share'),
      )!;
      const claim = server.requests.find(request =>
        request.path.endsWith('/claim'),
      )!;
      expect(share.body).toEqual({});
      expect(claim.body).toBeUndefined();
    });

    it('go once more to where a view went when it left the path it was remembered at', async () => {
      let audience: 'personal' | 'shared' = 'personal';
      const server = fakeServer(
        singles(ALICE, {
          v1: () => (audience === 'personal' ? snapshot('v1', 1) : undefined),
        }),
        singles(SHARED, {
          v1: () =>
            audience === 'shared'
              ? snapshot('v1', 2, { audience: 'shared', title: audience })
              : undefined,
        }),
        at('PUT', `${ALICE}/view/v1/rename`, () =>
          wowError('IllegalAccessOwnerAggregate', 'not yours', 403),
        ),
        at('PUT', `${SHARED}/view/v1/rename`, () => commandResult('v1', 3)),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });
      await store.get('v1');
      // Another tab shares it.
      audience = 'shared';
      server.on(
        singles(SHARED, {
          v1: () => snapshot('v1', 3, { audience: 'shared', title: 'Renamed' }),
        }),
      );

      expect(await store.rename('v1', 'Renamed', '2', write())).toEqual(
        instance('v1', '3', 'shared', 'Renamed'),
      );
      expect(
        sent(server.requests).filter(line => line.startsWith('PUT')),
      ).toEqual([
        `PUT ${ALICE}/view/v1/rename`,
        `PUT ${SHARED}/view/v1/rename`,
      ]);
    });

    it('keep the refusal when the view is still where the write went', async () => {
      const server = fakeServer(
        singles(ALICE, { v1: () => snapshot('v1', 1) }),
        at('PUT', `${ALICE}/view/v1/rename`, () =>
          wowError('IllegalAccessOwnerAggregate', 'not yours', 403),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });
      await store.get('v1');

      await expect(
        store.rename('v1', 'Mine', '1', write()),
      ).rejects.toMatchObject({ code: 'FORBIDDEN' });
    });

    it('answer a stale version with the view as it is now, after the replay route knows no first attempt', async () => {
      const server = fakeServer(
        singles(ALICE, { v1: () => snapshot('v1', 4, { title: 'Moved' }) }),
        at('PUT', `${ALICE}/view/v1/save`, () =>
          wowError('CommandExpectVersionConflict', 'stale', 409),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });
      await store.get('v1');

      const failure = await store
        .save('v1', config, '1', write())
        .catch(error => error);

      expect(failure).toMatchObject({
        code: 'CONFLICT',
        instance: instance('v1', '4', 'personal', 'Moved'),
      });
      expect(sent(server.requests)).toContain(
        `GET ${ALICE}/view/requests/request-${sequence}`,
      );
      expect(sent(server.requests)).toContain(
        `GET ${SHARED}/view/requests/request-${sequence}`,
      );
    });

    it('answer a stale version as CONFLICT when the other path cannot be asked', async () => {
      const server = fakeServer(
        singles(ALICE, { v1: () => snapshot('v1', 4, { title: 'Moved' }) }),
        at('PUT', `${ALICE}/view/v1/save`, () =>
          wowError('CommandExpectVersionConflict', 'stale', 409),
        ),
        // A caller without the shared role is refused there.
        at('GET', `${SHARED}/view/requests/request-${sequence + 1}`, () =>
          wowError('Forbidden', 'no', 403),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });
      await store.get('v1');

      await expect(
        store.save('v1', config, '1', write()),
      ).rejects.toMatchObject({
        code: 'CONFLICT',
        instance: instance('v1', '4', 'personal', 'Moved'),
      });
    });

    it('answer UNAVAILABLE when the path a refused write went to cannot be asked', async () => {
      const server = fakeServer(
        singles(ALICE, { v1: () => snapshot('v1', 4) }),
        at('PUT', `${ALICE}/view/v1/save`, () =>
          wowError('CommandExpectVersionConflict', 'stale', 409),
        ),
        at('GET', `${ALICE}/view/requests/request-${sequence + 1}`, () =>
          wowError('InternalServerError', 'down', 503),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });
      await store.get('v1');

      await expect(
        store.save('v1', config, '1', write()),
      ).rejects.toMatchObject({ code: 'UNAVAILABLE' });
    });

    it('send a claim once, wherever the view turns out to be', async () => {
      let audience: 'personal' | 'shared' = 'shared';
      const server = fakeServer(
        singles(SHARED, {
          v1: () =>
            audience === 'shared'
              ? snapshot('v1', 1, { audience: 'shared' })
              : undefined,
        }),
        singles(ALICE, {
          v1: () => (audience === 'personal' ? snapshot('v1', 2) : undefined),
        }),
        at('PUT', `${ALICE}/view/v1/claim`, () =>
          wowError('IllegalAccessOwnerAggregate', 'not yours', 403),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });
      await store.get('v1');
      // Another tab made it personal.
      audience = 'personal';

      await expect(
        store.changeAudience('v1', 'personal', '1', write()),
      ).rejects.toMatchObject({ code: 'FORBIDDEN' });
      expect(
        sent(server.requests).filter(line => line.startsWith('PUT')),
      ).toEqual([`PUT ${ALICE}/view/v1/claim`]);
    });

    it('answer a stale version on a view that is gone as NOT_FOUND', async () => {
      let gone = false;
      const server = fakeServer(
        singles(ALICE, { v1: () => (gone ? undefined : snapshot('v1', 1)) }),
        at('PUT', `${ALICE}/view/v1/save`, () => {
          gone = true;
          return wowError('EventVersionConflict', 'stale', 409);
        }),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });
      await store.get('v1');

      await expect(
        store.save('v1', config, '1', write()),
      ).rejects.toMatchObject({ code: 'NOT_FOUND' });
    });

    it('answer a retried write with what its first attempt wrote', async () => {
      const context = write();
      const server = fakeServer(
        singles(ALICE, { v1: () => snapshot('v1', 1) }),
        at('PUT', `${ALICE}/view/v1/save`, () =>
          wowError('DuplicateRequestId', 'again', 400),
        ),
        at('GET', `${ALICE}/view/requests/${context.requestId}`, () =>
          json(snapshot('v1', 2, { title: 'First' })),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });
      await store.get('v1');

      expect(await store.save('v1', config, '1', context)).toEqual(
        instance('v1', '2', 'personal', 'First'),
      );
    });

    it('answer a retried delete as done once the replay route says it deleted', async () => {
      const context = write();
      const server = fakeServer(
        singles(ALICE, { v1: () => snapshot('v1', 1) }),
        at('DELETE', `${ALICE}/view/v1`, () =>
          wowError('IllegalAccessDeletedAggregate', 'deleted', 410),
        ),
        at(
          'GET',
          `${ALICE}/view/requests/${context.requestId}`,
          () => new Response(null, { status: 204 }),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });
      await store.get('v1');

      await expect(store.delete('v1', '1', context)).resolves.toBeUndefined();
    });

    it('do not take a replay of another write for this one', async () => {
      const context = write();
      const server = fakeServer(
        singles(ALICE, { v1: () => snapshot('v1', 3) }),
        at('PUT', `${ALICE}/view/v1/save`, () =>
          wowError('CommandExpectVersionConflict', 'stale', 409),
        ),
        at('DELETE', `${ALICE}/view/v1`, () =>
          wowError('CommandExpectVersionConflict', 'stale', 409),
        ),
        at('GET', `${ALICE}/view/requests/${context.requestId}`, () =>
          json(snapshot('other', 2)),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });
      await store.get('v1');

      await expect(
        store.save('v1', config, '1', context),
      ).rejects.toMatchObject({ code: 'CONFLICT' });
      await expect(store.delete('v1', '1', context)).rejects.toMatchObject({
        code: 'CONFLICT',
      });
      server.on(
        at(
          'GET',
          `${ALICE}/view/requests/${context.requestId}`,
          () => new Response(null, { status: 204 }),
        ),
      );
      await expect(
        store.save('v1', config, '1', context),
      ).rejects.toMatchObject({ code: 'CONFLICT' });
    });

    it('answer the view as the write left it when another writer moved it before the read back', async () => {
      const context = write();
      const server = fakeServer(
        singles(ALICE, { v1: () => snapshot('v1', 1) }),
        at('PUT', `${ALICE}/view/v1/rename`, () => {
          server.on(
            singles(ALICE, { v1: () => snapshot('v1', 3, { title: 'Later' }) }),
          );
          return commandResult('v1', 2);
        }),
        at('GET', `${ALICE}/view/requests/${context.requestId}`, () =>
          json(snapshot('v1', 2, { title: 'Mine now' })),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });
      await store.get('v1');

      expect(await store.rename('v1', 'Mine now', '1', context)).toEqual(
        instance('v1', '2', 'personal', 'Mine now'),
      );
    });

    it('answer what it read when another writer moved it and the replay route cannot be asked', async () => {
      const context = write();
      const server = fakeServer(
        singles(ALICE, { v1: () => snapshot('v1', 1) }),
        at('PUT', `${ALICE}/view/v1/save`, () => {
          server.on(
            singles(ALICE, { v1: () => snapshot('v1', 3, { title: 'Later' }) }),
          );
          return commandResult('v1', 2);
        }),
        at('GET', `${ALICE}/view/requests/${context.requestId}`, () =>
          wowError('InternalServerError', 'down', 503),
        ),
        at('GET', `${SHARED}/view/requests/${context.requestId}`, () =>
          wowError('Forbidden', 'no', 403),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });
      await store.get('v1');

      expect(await store.save('v1', config, '1', context)).toEqual(
        instance('v1', '3', 'personal', 'Later'),
      );
    });

    it('fall back to what it read when the replay route has nothing, and to its failure when it read nothing', async () => {
      let read: 'later' | 'gone' = 'later';
      const server = fakeServer(
        singles(ALICE, {
          v1: () =>
            read === 'later'
              ? snapshot('v1', 3, { title: 'Later' })
              : undefined,
        }),
        at('PUT', `${ALICE}/view/v1/rename`, () => commandResult('v1', 2)),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });
      await store.get('v1');

      expect((await store.rename('v1', 'Mine', '1', write())).title).toBe(
        'Later',
      );
      read = 'gone';
      await expect(
        store.rename('v1', 'Mine', '2', write()),
      ).rejects.toMatchObject({ code: 'NOT_FOUND' });
      // A write that landed is never sent again for its read back.
      expect(
        server.requests.filter(request => request.method === 'PUT'),
      ).toHaveLength(2);
    });

    it('look a view up before writing to one never seen, and refuse a system view there', async () => {
      const server = fakeServer(
        at('GET', `${SHARED}/system-views/open`, () =>
          json({
            id: 'open',
            definitionId: 'orders',
            title: 'Open',
            kind: 'record',
            revision: 'abc',
            config,
            scope: 'system',
          }),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });

      await expect(store.delete('open', 'abc', write())).rejects.toMatchObject({
        code: 'FORBIDDEN',
      });
      await expect(
        store.save('missing', config, '1', write()),
      ).rejects.toMatchObject({ code: 'NOT_FOUND' });
      expect(
        server.requests.every(
          request => request.method !== 'PUT' && request.method !== 'DELETE',
        ),
      ).toBe(true);
    });
  });

  describe('a claim a shared board refuses', () => {
    const refused = (boards: { name: string; msg: string; code?: string }[]) =>
      at('PUT', `${ALICE}/view/v1/claim`, () =>
        wowError(
          'ViewInvalid',
          `The view is referenced by shared dashboards.`,
          400,
          boards,
        ),
      );

    it("names the boards by their titles, keys kept for the reader's words", async () => {
      const keyed = '\uE000orders.board\uE001';
      const server = fakeServer(
        singles(SHARED, {
          v1: () => snapshot('v1', 1, { audience: 'shared' }),
        }),
        refused([
          {
            name: 'b1',
            msg: 'Team board',
            code: 'referenced-by-shared-dashboard',
          },
          { name: 'b2', msg: keyed, code: 'referenced-by-shared-dashboard' },
        ]),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });

      const failure = await store
        .changeAudience('v1', 'personal', '1', write())
        .catch(error => error);

      expect(failure).toMatchObject({
        code: 'INVALID',
        boards: ['Team board', keyed],
        // The server's words, for logs; the engine says the refusal.
        message: '[ViewInvalid] The view is referenced by shared dashboards.',
      });
    });

    it('reads a board the server gives no title for, and names it by id when it cannot', async () => {
      const server = fakeServer(
        singles(SHARED, {
          v1: () => snapshot('v1', 1, { audience: 'shared' }),
          b1: () =>
            snapshot('b1', 1, { audience: 'shared', title: 'Read board' }),
        }),
        refused([
          { name: 'b1', msg: '', code: 'referenced-by-shared-dashboard' },
          { name: 'b2', msg: ' ', code: 'referenced-by-shared-dashboard' },
        ]),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });

      await expect(
        store.changeAudience('v1', 'personal', '1', write()),
      ).rejects.toMatchObject({
        code: 'INVALID',
        boards: ['Read board', 'b2'],
      });
    });

    it("keeps the server's words for any other invalid claim", async () => {
      const server = fakeServer(
        singles(SHARED, {
          v1: () => snapshot('v1', 1, { audience: 'shared' }),
        }),
        refused([{ name: 'title', msg: 'blank' }]),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });

      await expect(
        store.changeAudience('v1', 'personal', '1', write()),
      ).rejects.toMatchObject({
        code: 'INVALID',
        message: '[ViewInvalid] The view is referenced by shared dashboards.',
      });
      const failure = await store
        .changeAudience('v1', 'personal', '1', write())
        .catch(error => error);
      expect(failure.boards).toBeUndefined();
    });
  });

  describe('preferences', () => {
    const PREFERENCES = `${ALICE}/definitions/orders/preferences`;

    it('read as the port has them, nulls left out', async () => {
      const server = fakeServer(
        at('GET', PREFERENCES, () =>
          json({
            definitionId: 'orders',
            order: null,
            defaultInstanceId: null,
            autoRun: null,
            lastTabs: null,
            version: 0,
          }),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });

      expect(await store.getPreferences('orders')).toEqual({
        order: [],
        defaultInstanceId: null,
        revision: '0',
      });
    });

    it('write against the revision read and answer the version the write left', async () => {
      const server = fakeServer(
        at('PUT', PREFERENCES, () => commandResult('p', 1)),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });
      const context = write();
      const preferences = {
        order: ['a'],
        defaultInstanceId: 'a',
        autoRun: false,
        lastTabs: { board: 'tab-2' },
        revision: '0',
      };

      const set = await store.setPreferences('orders', preferences, context);

      expect(set).toEqual({ ...preferences, revision: '1' });
      const [put] = server.requests;
      expect(put!.body).toEqual({
        order: ['a'],
        defaultInstanceId: 'a',
        autoRun: false,
        lastTabs: { board: 'tab-2' },
      });
      expect(put!.headers.get('Command-Aggregate-Version')).toBe('0');
      expect(put!.headers.get('Command-Request-Id')).toBe(context.requestId);
      // A replay answers the first outcome without a second request.
      expect(
        await store.setPreferences('orders', preferences, context),
      ).toEqual(set);
      expect(server.requests).toHaveLength(1);
    });

    it('read the stored ones back when the answer names no version', async () => {
      const server = fakeServer(
        at('PUT', PREFERENCES, () =>
          json({ errorCode: 'Ok', aggregateId: 'p' }),
        ),
        at('GET', PREFERENCES, () =>
          json({ definitionId: 'orders', order: ['a'], version: 4 }),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });

      expect(
        await store.setPreferences(
          'orders',
          { order: ['a'], defaultInstanceId: null, revision: '3' },
          write(),
        ),
      ).toEqual({ order: ['a'], defaultInstanceId: null, revision: '4' });
    });

    it('refuse a stale write, carrying what is stored', async () => {
      const server = fakeServer(
        at('PUT', PREFERENCES, () =>
          wowError('CommandExpectVersionConflict', 'stale', 409),
        ),
        at('GET', PREFERENCES, () =>
          json({ definitionId: 'orders', order: ['b'], version: 2 }),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });

      await expect(
        store.setPreferences(
          'orders',
          { order: ['a'], defaultInstanceId: null, revision: '1' },
          write(),
        ),
      ).rejects.toMatchObject({
        code: 'CONFLICT',
        preferences: { order: ['b'], defaultInstanceId: null, revision: '2' },
        instance: undefined,
      });
    });

    it('take a retry whose first answer was lost as landed when what is stored is what it wrote', async () => {
      let attempts = 0;
      const server = fakeServer(
        at('PUT', PREFERENCES, () => {
          attempts += 1;
          return attempts === 1
            ? new Response('gateway timeout', { status: 504 })
            : wowError('CommandExpectVersionConflict', 'stale', 409);
        }),
        at('GET', PREFERENCES, () =>
          json({
            definitionId: 'orders',
            order: ['a'],
            defaultInstanceId: null,
            lastTabs: { y: '2', x: '1' },
            version: 1,
          }),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });
      const context = write();
      const preferences = {
        order: ['a'],
        defaultInstanceId: null,
        lastTabs: { x: '1', y: '2' },
        revision: '0',
      };

      await expect(
        store.setPreferences('orders', preferences, context),
      ).rejects.toMatchObject({ code: 'UNAVAILABLE' });
      expect(
        await store.setPreferences('orders', preferences, context),
      ).toEqual({
        ...preferences,
        lastTabs: { y: '2', x: '1' },
        revision: '1',
      });
    });

    it('take a repeated request id as landed only when what is stored is what it wrote', async () => {
      let stored = ['a'];
      const server = fakeServer(
        at('PUT', PREFERENCES, () =>
          wowError('DuplicateRequestId', 'again', 400),
        ),
        at('GET', PREFERENCES, () =>
          json({ definitionId: 'orders', order: stored, version: 5 }),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });

      expect(
        (
          await store.setPreferences(
            'orders',
            { order: ['a'], defaultInstanceId: null, revision: '4' },
            write(),
          )
        ).revision,
      ).toBe('5');
      stored = ['b'];
      await expect(
        store.setPreferences(
          'orders',
          { order: ['a'], defaultInstanceId: null, revision: '4' },
          write(),
        ),
      ).rejects.toMatchObject({ code: 'CONFLICT' });
    });

    it('pass any other refusal on', async () => {
      const server = fakeServer(
        at('PUT', PREFERENCES, () =>
          wowError('ViewAppRequired', 'no app', 400),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });

      await expect(
        store.setPreferences(
          'orders',
          { order: [], defaultInstanceId: null, revision: '0' },
          write(),
        ),
      ).rejects.toMatchObject({ code: 'INVALID' });
    });
  });

  describe('failures', () => {
    it('are all ViewStoreErrors: a network failure and an unreadable answer are UNAVAILABLE', async () => {
      const server = fakeServer(
        at('POST', `${ALICE}/view/snapshot/single`, () => {
          throw new TypeError('fetch failed');
        }),
        at(
          'GET',
          `${ALICE}/definitions/orders/preferences`,
          () => new Response('<html>', { status: 200 }),
        ),
      );
      const store = new WowViewStore({ fetcher: server.fetcher });

      await expect(store.get('v1')).rejects.toMatchObject({
        name: 'ViewStoreError',
        code: 'UNAVAILABLE',
        message: expect.stringContaining('fetch failed'),
      });
      await expect(store.getPreferences('orders')).rejects.toMatchObject({
        name: 'ViewStoreError',
        code: 'UNAVAILABLE',
      });
    });

    it('say a path variable the interceptors never filled as INVALID, and send nothing', async () => {
      const server = fakeServer();
      const fetcher = new Fetcher({ baseURL: 'https://views.example.test/' });
      const store = new WowViewStore({ fetcher });

      await expect(store.getPreferences('orders')).rejects.toMatchObject({
        name: 'ViewStoreError',
        code: 'INVALID',
        message: expect.stringContaining('{tenantId}'),
      });
      expect(server.requests).toEqual([]);
    });

    it('carry an aborted read as UNAVAILABLE', async () => {
      const server = fakeServer();
      const store = new WowViewStore({ fetcher: server.fetcher });
      const controller = new AbortController();
      controller.abort();

      await expect(
        store.list('orders', controller.signal),
      ).rejects.toMatchObject({ code: 'UNAVAILABLE' });
    });
  });

  it('answers the permissions the host gives, and declares none without them', () => {
    const permissions = (): ViewPermissions => ({
      createPersonal: true,
      createShared: false,
      reorder: true,
      setDefault: true,
      instance: () => ({
        save: true,
        rename: true,
        delete: true,
        changeAudience: false,
      }),
    });
    const { fetcher } = fakeServer();

    expect(
      new WowViewStore({ fetcher, permissions }).permissions?.('orders')
        .createShared,
    ).toBe(false);
    expect(new WowViewStore({ fetcher }).permissions).toBeUndefined();
  });
});
