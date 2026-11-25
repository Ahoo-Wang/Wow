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
 * What the V10 view store service holds `WowViewStore` and
 * `WowDatasetWordings` to beyond the port's conformance
 * (`describeViewStoreConformance`, the engine's, which every `ViewStore`
 * passes): tenants and applications kept apart, the stored and configured
 * system views (at most 1,000 stored ones listed), who writes what (the
 * service's roles, and no write without an owner), the service's error
 * codes, and what the kernel answers a retry from another owner and the HTTP
 * layer an `If-Match` that names no version. These are the service's rules, not the port's, so they live
 * in the package of the store that speaks to it.
 *
 * It runs against two subjects, which must answer alike:
 *
 * - the in-memory stand-in (`../support/fakeViewStore.ts`), from this
 *   package's `serviceConformance.test.ts`;
 * - the real service, from
 *   `typescript/integration-test/test/view-store/service.test.ts`, which
 *   imports this file by its workspace path. Nothing here is published.
 *
 * What stays network-only, in the integration test: a service without the
 * view store routes (`UNSUPPORTED`) and one nobody answers (`UNAVAILABLE`).
 * Those are about where the store's requests go, not about what the service
 * decides, and the fake is installed as the page's `fetch`, so it has no
 * second host and no closed port to stand for them; the store's own tests
 * read both from a stubbed answer. So does a dataset's query client as a view
 * source, which is the query engine's, not the view store's.
 *
 * Every case works in definitions and tenants of its own (fresh ids), so a
 * shared server needs no reset.
 */

import { describe, expect, it } from 'vitest';
import { WowHeaders } from '@ahoo-wang/wow-client';
import type {
  ViewConfig,
  ViewInstanceSummary,
} from '../../../wow-view-engine/src/model/index.js';
import { WowViewStoreErrorCodes } from '../../src/errors.js';
import type { WowDatasetWordings, WowViewStore } from '../../src/index.js';

/** Who a request comes from, as a development run of the service reads it. */
export interface ServiceCaller {
  tenant: string;
  /** The user: the owner of the caller's personal views. */
  user: string;
  /** The application; blank for a request that names none. */
  app: string;
  roles?: readonly string[];
  /**
   * Signed in as `user`, with a scope that names no owner: a tenant-level
   * caller, which writes no view or preferences.
   */
  ownerless?: boolean;
}

/** One request a store never sends, as `ServiceStores.send` takes it. */
export interface ServiceRequest {
  method: 'GET' | 'POST' | 'DELETE';
  /** From `/view-store/` on, the service's own path. */
  path: string;
  headers?: Record<string, string>;
  /** Sent as JSON. */
  body?: unknown;
}

/**
 * The stores of one caller, on the subject's service: by their methods, so a
 * store built from the published package fits as one built from source.
 */
export interface ServiceStores {
  views: Pick<
    WowViewStore,
    | 'list'
    | 'get'
    | 'create'
    | 'save'
    | 'rename'
    | 'delete'
    | 'changeAudience'
    | 'getPreferences'
    | 'setPreferences'
  >;
  wordings: Pick<WowDatasetWordings, 'list' | 'set' | 'clear'>;
  /**
   * Sends one request as the caller, outside any store, and answers the
   * response as it came: for what a store never sends (a header it never
   * writes, a retry from another owner).
   */
  send(request: ServiceRequest): Promise<Response>;
}

export interface WowViewStoreService {
  /** Named in the suite's title. */
  name: string;
  /** The role that writes the owner `(shared)` and the tenant's dataset wording. */
  sharedWriter: string;
  /** The role that writes the stored system views. */
  systemWriter: string;
  /** A system view the service is configured with, for every tenant of `app`. */
  configured: { definitionId: string; app: string; id: string; title: string };
  /** The service of one test: opens the stores of each caller on it. */
  connect():
    | ((caller: ServiceCaller) => ServiceStores)
    | Promise<(caller: ServiceCaller) => ServiceStores>;
}

const config = {
  kind: 'record',
  filter: { op: 'and', children: [] },
  filterMode: 'simple',
  refresh: { interval: null },
  sort: [],
  pageSize: 20,
  layout: 'table',
  table: { columns: [{ field: 'id' }] },
  card: { title: 'id', fields: [] },
} satisfies ViewConfig;

function write() {
  return { requestId: `service-${crypto.randomUUID()}` };
}

function fresh(): string {
  return `service-${crypto.randomUUID()}`;
}

/** Every page of a definition's list, in turn, and the pages as read. */
export async function listPages(
  views: Pick<WowViewStore, 'list'>,
  definitionId: string,
): Promise<ViewInstanceSummary[][]> {
  const pages: ViewInstanceSummary[][] = [];
  let cursor: string | null = null;
  do {
    const page = await views.list(definitionId, { cursor });
    pages.push(page.items);
    cursor = page.nextCursor;
  } while (cursor !== null && pages.length < 100);
  expect(cursor).toBeNull();
  return pages;
}

/** A definition's whole list: its pages read in turn, concatenated. */
export async function listAll(
  views: Pick<WowViewStore, 'list'>,
  definitionId: string,
): Promise<ViewInstanceSummary[]> {
  return (await listPages(views, definitionId)).flat();
}

/** Registers the suite's cases against `subject`. */
export function describeWowViewStoreService(
  subject: WowViewStoreService,
): void {
  const run = `${Date.now()}-${crypto.randomUUID().slice(0, 8)}`;
  const ALICE: ServiceCaller = {
    tenant: `service-a-${run}`,
    user: 'alice',
    app: 'console',
    roles: [subject.sharedWriter],
  };

  async function storesOf(): Promise<(caller: ServiceCaller) => ServiceStores> {
    return subject.connect();
  }

  describe(`the view store service: ${subject.name}`, () => {
    describe.each([
      ['another tenant', { ...ALICE, tenant: `service-b-${run}` }],
      ['another application', { ...ALICE, app: 'portal' }],
    ])('a caller of %s', (_, other: ServiceCaller) => {
      it('neither lists, reads nor writes the views and preferences', async () => {
        const open = await storesOf();
        const alice = open(ALICE).views;
        const stranger = open(other).views;
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

        expect(await listAll(stranger, definitionId)).toEqual([]);
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

    describe('a view by id', () => {
      it('is the caller’s own, else a shared one of its tenant; another user’s own is none', async () => {
        const open = await storesOf();
        const definitionId = fresh();
        const alice = open(ALICE).views;
        const bob = open({ ...ALICE, user: 'bob', roles: [] }).views;
        const mine = await alice.create(
          { definitionId, title: 'Mine', scope: 'personal', config },
          write(),
        );
        const ours = await alice.create(
          { definitionId, title: 'Ours', scope: 'shared', config },
          write(),
        );

        expect(await open(ALICE).views.get(mine.id)).toEqual(mine);
        expect(await bob.get(ours.id)).toEqual(ours);
        await expect(bob.get(mine.id)).rejects.toMatchObject({
          code: 'NOT_FOUND',
          detail: { code: WowViewStoreErrorCodes.NOT_FOUND },
        });
        await expect(
          open({ ...ALICE, app: '' }).views.get(mine.id),
        ).rejects.toMatchObject({
          code: 'INVALID',
          detail: { code: WowViewStoreErrorCodes.APP_REQUIRED },
        });
      });
    });

    describe('system views', () => {
      const ADMIN: ServiceCaller = {
        tenant: `system-a-${run}`,
        user: 'admin',
        app: 'system-views',
        roles: [subject.systemWriter],
      };
      const READER: ServiceCaller = {
        tenant: `system-b-${run}`,
        user: 'reader',
        app: 'system-views',
      };

      it('a stored one is created, read by every tenant, edited and deleted', async () => {
        const open = await storesOf();
        const admin = open(ADMIN).views;
        const reader = open({ ...READER, roles: [subject.systemWriter] }).views;
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
        expect(await listAll(reader, definitionId)).toEqual([
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
          await listAll(open({ ...READER, app: 'portal' }).views, definitionId),
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
          reader.save(
            id,
            { ...config, pageSize: 50 },
            created.revision,
            write(),
          ),
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
        expect(await listAll(reader, definitionId)).toEqual([]);
        await expect(reader.get(id)).rejects.toMatchObject({
          code: 'NOT_FOUND',
        });
      });

      it('a stored one is written only with the role that writes (system)', async () => {
        const open = await storesOf();
        await expect(
          open(READER).views.create(
            { definitionId: fresh(), title: 'Mine', scope: 'system', config },
            write(),
          ),
        ).rejects.toMatchObject({
          code: 'FORBIDDEN',
          detail: { code: 'wow.auth.forbidden' },
        });
      });

      it('a configured one is served and stays read-only', async () => {
        const open = await storesOf();
        const reader = open({ ...READER, app: subject.configured.app }).views;
        const [configured] = await listAll(
          reader,
          subject.configured.definitionId,
        );
        expect(configured).toMatchObject({
          id: subject.configured.id,
          scope: 'system',
          title: subject.configured.title,
        });
        expect(configured).not.toHaveProperty('stored');
        const read = await reader.get(configured!.id);
        expect(read).toMatchObject({
          id: subject.configured.id,
          definitionId: subject.configured.definitionId,
          scope: 'system',
          revision: configured!.revision,
        });
        expect(read).not.toHaveProperty('stored');
        await expect(
          reader.rename(configured!.id, 'Mine', configured!.revision, write()),
        ).rejects.toMatchObject({ code: 'FORBIDDEN' });
      });
    });

    describe('shared views', () => {
      it('are written only with the role that writes (shared)', async () => {
        const open = await storesOf();
        const definitionId = fresh();
        const writer = open(ALICE).views;
        const ours = await writer.create(
          { definitionId, title: 'Ours', scope: 'shared', config },
          write(),
        );
        const reader = open({ ...ALICE, user: 'bob', roles: [] }).views;
        const bobs = await reader.create(
          { definitionId, title: 'Mine', scope: 'personal', config },
          write(),
        );

        expect(await reader.get(ours.id)).toEqual(ours);
        for (const refused of [
          () => reader.rename(ours.id, 'Taken', ours.revision, write()),
          () =>
            reader.changeAudience(ours.id, 'personal', ours.revision, write()),
          () =>
            reader.create(
              { definitionId, title: 'Ours too', scope: 'shared', config },
              write(),
            ),
          // Sharing one's own view publishes it to the shared list (#6920).
          () =>
            reader.changeAudience(bobs.id, 'shared', bobs.revision, write()),
        ])
          await expect(refused()).rejects.toMatchObject({
            code: 'FORBIDDEN',
            detail: { code: 'wow.auth.forbidden' },
          });
        // So the shared list holds nothing of Bob's.
        await expect(open(ALICE).views.get(bobs.id)).rejects.toMatchObject({
          code: 'NOT_FOUND',
        });
        expect(await reader.get(bobs.id)).toEqual(bobs);
      });
    });

    describe('the list, a page at a time', () => {
      it('reads the system views, then the shared, then the caller’s own, on one page while it has room', async () => {
        const open = await storesOf();
        const definitionId = fresh();
        const alice = open({
          ...ALICE,
          roles: [subject.sharedWriter, subject.systemWriter],
        }).views;
        const mine = await alice.create(
          { definitionId, title: 'Mine', scope: 'personal', config },
          write(),
        );
        const ours = await alice.create(
          { definitionId, title: 'Ours', scope: 'shared', config },
          write(),
        );
        const everyone = await alice.create(
          { definitionId, title: 'Everyone', scope: 'system', config },
          write(),
        );

        const first = await alice.list(definitionId);
        expect(first.items.map(({ id }) => id)).toEqual([
          everyone.id,
          ours.id,
          mine.id,
        ]);
        expect(first.nextCursor).toBeNull();
        // Read again, a page answers the same.
        expect(await alice.list(definitionId)).toEqual(first);
      });

      it('ends with a null cursor for a definition the caller holds nothing of', async () => {
        const open = await storesOf();
        const pages = await listPages(open(ALICE).views, fresh());
        expect(pages.flat()).toEqual([]);
      });

      it('refuses a cursor it never answered as INVALID', async () => {
        const open = await storesOf();
        await expect(
          open(ALICE).views.list(fresh(), { cursor: 'not a cursor' }),
        ).rejects.toMatchObject({ code: 'INVALID' });
      });
    });

    describe('the stored system views of a definition', () => {
      // StoredSystemViews.MAX_STORED: the most one application lists of a
      // definition, the client's list budget; the oldest are served.
      it('are listed 1,000 at most, the oldest', async () => {
        const open = await storesOf();
        const admin = open({
          tenant: `system-c-${run}`,
          user: 'admin',
          app: 'system-views',
          roles: [subject.systemWriter],
        }).views;
        const definitionId = fresh();
        const created: string[] = [];
        for (let batch = 0; batch < 40; batch++)
          created.push(
            ...(
              await Promise.all(
                Array.from({ length: 25 }, (_, index) =>
                  admin.create(
                    {
                      definitionId,
                      title: `System ${batch * 25 + index}`,
                      scope: 'system',
                      config,
                    },
                    write(),
                  ),
                ),
              )
            ).map(view => view.id),
          );
        // Written after every other: the newest, the one left out.
        const newest = await admin.create(
          { definitionId, title: 'Newest', scope: 'system', config },
          write(),
        );

        const listed = (await listAll(admin, definitionId)).map(({ id }) => id);
        expect(listed).toHaveLength(1000);
        expect(new Set(listed)).toEqual(new Set(created));
        // Still read by its id.
        expect((await admin.get(newest.id)).title).toBe('Newest');
      }, 120_000);
    });

    describe('what the kernel and the authorizer answer', () => {
      it('answers a retried write as its duplicate only to a caller who reaches the view as its first attempt did', async () => {
        // KernelAnswers.duplicate, CommandReach.retries under the view's
        // access policy: from another owner the view does not exist (#6917).
        const open = await storesOf();
        const alice = open(ALICE);
        const bob = open({ ...ALICE, user: 'bob', roles: [] });
        const made = await alice.views.create(
          { definitionId: fresh(), title: 'Mine', scope: 'personal', config },
          write(),
        );
        const requestId = `service-${crypto.randomUUID()}`;
        const body = { config: { ...config, pageSize: 50 } };
        await alice.views.save(made.id, body.config, made.revision, {
          requestId,
        });
        const retry = (from: ServiceStores) =>
          from.send({
            method: 'POST',
            path: `/view-store/view/${made.id}/save-view`,
            headers: {
              [WowHeaders.REQUEST_ID]: requestId,
              'If-Match': `"${made.revision}"`,
            },
            body,
          });

        const again = await retry(alice);
        expect(again.status).toBe(200);
        expect(await again.json()).toMatchObject({ duplicate: true });
        const stranger = await retry(bob);
        expect(stranger.status).toBe(404);
        expect(await stranger.json()).toMatchObject({
          code: 'wow.aggregate.not-found',
        });
      });

      it('refuses a view or preferences write that acts as no owner, FORBIDDEN', async () => {
        // ViewStoreAuthorizer: a write in a scope without an owner would
        // reach every owner's views, so it is refused whatever its roles.
        const open = await storesOf();
        const definitionId = fresh();
        const nobody = open({ ...ALICE, ownerless: true }).views;
        for (const refused of [
          () =>
            nobody.create(
              { definitionId, title: 'Nobody’s', scope: 'personal', config },
              write(),
            ),
          () =>
            nobody.setPreferences(
              definitionId,
              { order: [], defaultInstanceId: null, revision: '0' },
              write(),
            ),
        ])
          await expect(refused()).rejects.toMatchObject({
            code: 'FORBIDDEN',
            detail: { code: 'wow.auth.forbidden' },
          });
        expect(await listAll(open(ALICE).views, definitionId)).toEqual([]);
      });

      it.each(['"NaN"', 'W/"1"', '"01"', '1'])(
        'refuses If-Match %s, which names no version, as malformed before the write is decided',
        async ifMatch => {
          // EntityTags.ifMatch: a header whose tags name no version gave
          // none, so it is malformed, not a mismatch.
          const open = await storesOf();
          const alice = open(ALICE);
          const made = await alice.views.create(
            { definitionId: fresh(), title: 'Mine', scope: 'personal', config },
            write(),
          );
          const answer = await alice.send({
            method: 'POST',
            path: `/view-store/view/${made.id}/rename-view`,
            headers: {
              [WowHeaders.REQUEST_ID]: `service-${crypto.randomUUID()}`,
              'If-Match': ifMatch,
            },
            body: { title: 'Renamed' },
          });
          expect(answer.status).toBe(400);
          expect(await answer.json()).toMatchObject({
            code: 'wow.request.malformed',
          });
          expect(await alice.views.get(made.id)).toEqual(made);
        },
      );
    });

    describe('the dataset wording', () => {
      it('is the tenant’s: set by a writer, read by everyone, cleared', async () => {
        const open = await storesOf();
        const dataset = `sales.${fresh()}`;
        const writer = open(ALICE).wordings;
        const reader = open({ ...ALICE, user: 'bob', roles: [] }).wordings;

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
        const open = await storesOf();
        await expect(
          open({ ...ALICE, app: '' }).views.create(
            { definitionId: fresh(), title: 'Mine', scope: 'personal', config },
            write(),
          ),
        ).rejects.toMatchObject({
          code: 'INVALID',
          detail: { code: WowViewStoreErrorCodes.APP_REQUIRED },
        });
      });

      it('a blank title is INVALID, by the service’s code', async () => {
        const open = await storesOf();
        await expect(
          open(ALICE).views.create(
            { definitionId: fresh(), title: ' ', scope: 'personal', config },
            write(),
          ),
        ).rejects.toMatchObject({
          code: 'INVALID',
          detail: { code: WowViewStoreErrorCodes.INVALID },
        });
      });
    });
  });
}
