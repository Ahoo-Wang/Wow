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
 * The `ViewStore` port's conformance suite (view-store-backend.md 7「端口一致性测试」,
 * 9): one set of cases every implementation passes — `MemoryViewStore`
 * here, **WowViewStore** from `typescript/integration-test` (V3).
 *
 * It is a test file, not a published entry: it lives beside the engine's
 * tests and is imported by workspace path, never through `/testing`, which
 * would carry a test framework into a published entry (9). From
 * `typescript/integration-test/test/…`:
 *
 * ```ts
 * import { describeViewStoreConformance } from '../../wow-view-engine/test/conformance/viewStoreConformance.js';
 *
 * describeViewStoreConformance({
 *   name: 'WowViewStore',
 *   capabilities: {
 *     owners: true,
 *     personalViews: true,
 *     changeAudience: true,
 *     idempotentCreate: true,
 *     systemViews: { definitionId: 'conformance-system' },
 *   },
 *   // One server for the whole file is fine: every test uses fresh ids.
 *   connect: () => ({ owner }) => new WowViewStore({ fetcher: actingAs(owner) }),
 * });
 * ```
 *
 * **V3 must widen `rootDir`.** `typescript/integration-test`'s tsconfig pins
 * `rootDir` to its own directory, so `tsc` there refuses this file with
 * TS6059 until its test tsconfig (`tsconfig.test.json`) sets `rootDir` to
 * `..` (or drops it); vitest itself resolves the path as it is.
 *
 * Its imports are kept to what that path can reach: `vitest`, and from the
 * engine's source only the model's structural `isViewStoreError` and the
 * store's limits on a title and a config (modules with no runtime imports)
 * and types — no build of the engine and no `exports` entry are needed, and
 * nothing is published.
 *
 * **What it assumes of the backend, and nothing more.** Every test works in a
 * definition of its own (a fresh id), so a shared server needs no reset.
 * Revisions are compared for change and no change only — never ordered or
 * parsed — except that untouched preferences answer `'0'`, which the port
 * fixes so the first `setPreferences` is the same request everywhere; and
 * that a refused `changeAudience` (a shared board shows the view) says the
 * boards **by title** in its `message`, which is the port's contract since
 * the reader is shown it. A
 * capability the subject does not declare skips its cases, by name.
 */

import { describe, expect, it } from 'vitest';
import { isViewStoreError } from '../../src/model/storeError.js';
import {
  MAX_VIEW_CONFIG_BYTES,
  MAX_VIEW_TITLE_LENGTH,
} from '../../src/model/instance.js';
import type { ViewStoreErrorCode } from '../../src/model/storeError.js';
import type {
  DashboardViewConfig,
  DashboardViewPanel,
  ViewAudience,
  ViewConfig,
  ViewInstance,
  ViewInstanceSummary,
  ViewPreferences,
} from '../../src/model/index.js';
import type { ViewStore, WriteContext } from '../../src/store/ViewStore.js';

/** Who a store acts as: the owner of the personal views it makes and reads. */
export interface ConformanceContext {
  owner: string;
}

/** Opens the backend of one test as a given owner. */
export type ConformanceStoreFactory = (
  context: ConformanceContext,
) => ViewStore;

export interface ConformanceCapabilities {
  /**
   * Stores opened for two owners are two users: each sees the other's
   * shared views and not their personal ones, and keeps preferences of their
   * own. False for a single-user store, which reads every owner as the same
   * user — the cases about two users are skipped.
   */
  owners: boolean;
  /** A personal view can be created at all (a host nobody signs in to has none). */
  personalViews: boolean;
  /** The store implements the optional `changeAudience`. */
  changeAudience: boolean;
  /**
   * A `create` replayed under its `requestId` answers the first outcome
   * rather than making a second view, retried through the same store. The
   * port asks it of every write, and `MemoryViewStore` keeps it; the Wow
   * server does not deduplicate `create` (its request-id index cannot be
   * scoped to the view aggregates, a decision taken 2026-09-29), and
   * `WowViewStore` keeps it within one store by asking the replay route
   * before posting a retry of its own create again. A store that keeps it
   * not even so declares `false`, and the create replay case is skipped.
   * Every other write's replay stays mandatory.
   */
  idempotentCreate: boolean;
  /**
   * A definition the backend serves at least one read-only system view for
   * (`scope: 'system'`); left out when it serves none, and the case that
   * every write to one is refused is skipped.
   */
  systemViews?: { definitionId: string };
}

export interface ViewStoreConformance {
  /** Named in the suite's title. */
  name: string;
  capabilities: ConformanceCapabilities;
  /**
   * The backend of one test. Every store the factory opens shares it: two
   * owners are two users of one server, and two stores for one owner are two
   * clients (two tabs) of that user.
   */
  connect(): ConformanceStoreFactory | Promise<ConformanceStoreFactory>;
}

const ALICE: ConformanceContext = { owner: 'conformance-alice' };
const BOB: ConformanceContext = { owner: 'conformance-bob' };

/** A config the port stores as it is; its semantics are the engine's. */
function recordConfig(pageSize = 20): ViewConfig {
  return {
    kind: 'record',
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    sort: [],
    pageSize,
    layout: 'table',
    table: { columns: [{ field: 'id' }] },
    card: { title: 'id', fields: [] },
  };
}

/**
 * How a board's panel references a saved view: the three members the view
 * store server reads (`ViewConfigs.PANEL_REFERENCES`) — the view it shows,
 * the view 「在工作台中打开」 opens, the view a press opens.
 */
type Reference = 'instanceId' | 'opens' | 'click';

/**
 * A board whose one panel references the saved view `instanceId` through
 * `through` alone: typed as the engine's own config, so a reference renamed
 * or moved in the model fails to compile here, and the server is asked
 * about the name the engine stores.
 */
function boardShowing(
  instanceId: string,
  through: Reference = 'instanceId',
): DashboardViewConfig {
  const base = {
    id: 'panel-1',
    kind: 'view',
    title: 'Shown',
    layout: { x: 0, y: 0, w: 12, h: 6 },
    bindings: [],
  } as const;
  // A panel that references the view by `opens` or by its press shows
  // another one: a view of no store, whose id nothing else matches.
  const elsewhere = `elsewhere-${crypto.randomUUID()}`;
  const panel: DashboardViewPanel =
    through === 'instanceId'
      ? { ...base, bindings: [], instanceId }
      : through === 'opens'
        ? { ...base, bindings: [], instanceId: elsewhere, opens: instanceId }
        : {
            ...base,
            bindings: [],
            instanceId: elsewhere,
            click: { kind: 'view', instanceId },
          };
  return {
    kind: 'dashboard',
    refresh: { interval: null },
    columns: 24,
    width: 'fixed',
    fixed: { op: 'and', children: [] },
    tabs: [],
    fields: [],
    panels: [panel],
  };
}

function write(): WriteContext {
  return { requestId: `conformance-${crypto.randomUUID()}` };
}

function freshDefinition(): string {
  return `conformance-${crypto.randomUUID()}`;
}

/** What a promise rejected with, asserted to be the port's error with `code`. */
async function refusal(
  pending: Promise<unknown>,
  ...codes: ViewStoreErrorCode[]
): Promise<
  Error & {
    code: ViewStoreErrorCode;
    instance?: ViewInstance;
    boards?: readonly string[];
    preferences?: ViewPreferences;
  }
> {
  let error: unknown;
  try {
    await pending;
  } catch (thrown) {
    error = thrown;
  }
  expect(error, 'the store should have refused').toBeDefined();
  expect(isViewStoreError(error), 'every failure is a ViewStoreError').toBe(
    true,
  );
  const failure = error as Error & {
    code: ViewStoreErrorCode;
    instance?: ViewInstance;
    boards?: readonly string[];
    preferences?: ViewPreferences;
  };
  expect(codes).toContain(failure.code);
  return failure;
}

function ids(summaries: readonly ViewInstanceSummary[]): string[] {
  return summaries.map(summary => summary.id);
}

/**
 * Registers the suite for one implementation. Call it at the top level of a
 * test file; it opens one `describe` named after the subject.
 */
export function describeViewStoreConformance(
  subject: ViewStoreConformance,
): void {
  const { capabilities } = subject;
  const personal = capabilities.personalViews;
  /** The audience a test's own view is made in: personal where there is one. */
  const own: ViewAudience = personal ? 'personal' : 'shared';

  async function setup() {
    const open = await subject.connect();
    const definitionId = freshDefinition();
    const alice = open(ALICE);
    const create = (
      title: string,
      scope: ViewAudience = own,
      store: ViewStore = alice,
      config: ViewConfig = recordConfig(),
    ) => store.create({ definitionId, title, scope, config }, write());
    return { open, definitionId, alice, create };
  }

  describe(`ViewStore conformance: ${subject.name}`, () => {
    describe('list and get', () => {
      it('lists only the definition asked for, as summaries without a config', async () => {
        const { alice, definitionId, create } = await setup();
        const mine = await create('Mine');
        const other = await alice.create(
          {
            definitionId: freshDefinition(),
            title: 'Elsewhere',
            scope: own,
            config: recordConfig(),
          },
          write(),
        );

        const listed = await alice.list(definitionId);

        expect(ids(listed)).toContain(mine.id);
        expect(ids(listed)).not.toContain(other.id);
        for (const summary of listed) {
          expect(summary.definitionId).toBe(definitionId);
          expect(summary).not.toHaveProperty('config');
        }
        expect(listed.find(summary => summary.id === mine.id)).toEqual({
          id: mine.id,
          definitionId,
          title: 'Mine',
          scope: own,
          kind: 'record',
          revision: mine.revision,
        });
      });

      it('never issues or lists an id in the reserved system: namespace', async () => {
        const { alice, definitionId, create } = await setup();
        const made = await create('Mine');

        expect(made.id.startsWith('system:')).toBe(false);
        for (const summary of await alice.list(definitionId))
          expect(summary.id.startsWith('system:')).toBe(false);
      });

      it('reads back what it created, config and all', async () => {
        const { alice, create } = await setup();
        const made = await create('Mine', own, alice, recordConfig(50));

        expect(await alice.get(made.id)).toEqual(made);
        expect(made.config).toEqual(recordConfig(50));
      });

      it('answers a missing view as NOT_FOUND', async () => {
        const { alice } = await setup();
        await refusal(alice.get(`missing-${crypto.randomUUID()}`), 'NOT_FOUND');
      });

      it.skipIf(!personal)(
        'lists shared views before personal ones, each oldest first',
        async () => {
          const { alice, definitionId, create } = await setup();
          const made: ViewInstance[] = [];
          // One at a time: the order made is the order asked of the list.
          for (const [title, scope] of [
            ['First mine', 'personal'],
            ['First ours', 'shared'],
            ['Second mine', 'personal'],
            ['Second ours', 'shared'],
          ] as const)
            made.push(await create(title, scope));
          const [mine1, ours1, mine2, ours2] = made.map(view => view.id);

          const listed = ids(await alice.list(definitionId)).filter(id =>
            made.some(view => view.id === id),
          );

          expect(listed).toEqual([ours1, ours2, mine1, mine2]);
        },
      );

      it.skipIf(capabilities.systemViews === undefined)(
        'lists the system views before every saved one',
        async () => {
          const open = await subject.connect();
          const alice = open(ALICE);
          const definitionId = capabilities.systemViews!.definitionId;
          const made = await alice.create(
            {
              definitionId,
              title: 'Saved',
              scope: own,
              config: recordConfig(),
            },
            write(),
          );
          try {
            const scopes = (await alice.list(definitionId)).map(
              summary => summary.scope,
            );
            expect(scopes[0]).toBe('system');
            expect(scopes.lastIndexOf('system')).toBeLessThan(
              scopes.findIndex(scope => scope !== 'system'),
            );
          } finally {
            await alice.delete(made.id, made.revision, write());
          }
        },
      );
    });

    describe('who sees what', () => {
      it.skipIf(!personal)(
        "keeps a personal view in its owner's list",
        async () => {
          const { alice, definitionId, create } = await setup();
          const mine = await create('Mine', 'personal');

          expect(mine.scope).toBe('personal');
          expect(ids(await alice.list(definitionId))).toContain(mine.id);
        },
      );

      it.skipIf(!capabilities.owners || !personal)(
        "shows another user one's shared views and not one's personal ones",
        async () => {
          const { open, definitionId, create } = await setup();
          const mine = await create('Mine', 'personal');
          const ours = await create('Ours', 'shared');
          const bob = open(BOB);

          const seen = ids(await bob.list(definitionId));

          expect(seen).toContain(ours.id);
          expect(seen).not.toContain(mine.id);
          expect((await bob.get(ours.id)).title).toBe('Ours');
          await refusal(bob.get(mine.id), 'NOT_FOUND', 'FORBIDDEN');
        },
      );
    });

    describe('instance writes', () => {
      it('saves, renames and deletes, each answering a new revision', async () => {
        const { alice, definitionId, create } = await setup();
        const made = await create('Mine');

        const saved = await alice.save(
          made.id,
          recordConfig(50),
          made.revision,
          write(),
        );
        expect(saved.config).toEqual(recordConfig(50));
        expect(saved.revision).not.toBe(made.revision);

        const renamed = await alice.rename(
          made.id,
          'Renamed',
          saved.revision,
          write(),
        );
        expect(renamed.title).toBe('Renamed');
        expect(renamed.config).toEqual(recordConfig(50));
        expect(renamed.revision).not.toBe(saved.revision);
        expect(
          (await alice.list(definitionId)).find(item => item.id === made.id)
            ?.title,
        ).toBe('Renamed');

        await alice.delete(made.id, renamed.revision, write());
        expect(ids(await alice.list(definitionId))).not.toContain(made.id);
        await refusal(alice.get(made.id), 'NOT_FOUND');
      });

      it('refuses a stale revision as CONFLICT, carrying the view it holds', async () => {
        const { alice, create } = await setup();
        const made = await create('Mine');
        const moved = await alice.rename(
          made.id,
          'Moved',
          made.revision,
          write(),
        );

        // One at a time: a write sent before the previous refusal is read
        // would reject with nobody awaiting it yet, over a network.
        for (const stale of [
          () => alice.save(made.id, recordConfig(50), made.revision, write()),
          () => alice.rename(made.id, 'Late', made.revision, write()),
          () => alice.delete(made.id, made.revision, write()),
        ]) {
          const failure = await refusal(stale(), 'CONFLICT');
          expect(failure.instance?.id).toBe(made.id);
          expect(failure.instance?.revision).toBe(moved.revision);
          expect(failure.preferences).toBeUndefined();
        }
        expect(await alice.get(made.id)).toEqual(moved);
      });

      it('stores a title trimmed, and refuses one blank or too long as INVALID', async () => {
        const { alice, definitionId, create } = await setup();
        const made = await create('  Padded  ');
        expect(made.title).toBe('Padded');
        expect(
          (await alice.list(definitionId)).find(item => item.id === made.id)
            ?.title,
        ).toBe('Padded');

        const longest = 'x'.repeat(MAX_VIEW_TITLE_LENGTH);
        const renamed = await alice.rename(
          made.id,
          ` ${longest} `,
          made.revision,
          write(),
        );
        expect(renamed.title).toBe(longest);

        await refusal(create('   '), 'INVALID');
        await refusal(create(`${longest}x`), 'INVALID');
        await refusal(
          alice.rename(made.id, ' ', renamed.revision, write()),
          'INVALID',
        );
        await refusal(
          alice.rename(made.id, `${longest}x`, renamed.revision, write()),
          'INVALID',
        );
        expect(await alice.get(made.id)).toEqual(renamed);
      });

      it('refuses a config larger than the store keeps as INVALID', async () => {
        const { alice, create } = await setup();
        const made = await create('Mine');
        // A card title long enough to take the config past the limit.
        const large = recordConfig();
        if (large.kind === 'record')
          large.card.title = 'x'.repeat(MAX_VIEW_CONFIG_BYTES);

        await refusal(create('Large', own, alice, large), 'INVALID');
        await refusal(
          alice.save(made.id, large, made.revision, write()),
          'INVALID',
        );
        expect(await alice.get(made.id)).toEqual(made);
      });

      it('answers a write to a view another client deleted as NOT_FOUND', async () => {
        const { open, alice, create } = await setup();
        const made = await create('Mine');
        // Read by this client first, so it knows where the view was.
        await alice.get(made.id);
        await open(ALICE).delete(made.id, made.revision, write());

        await refusal(
          alice.save(made.id, recordConfig(50), made.revision, write()),
          'NOT_FOUND',
        );
        await refusal(
          alice.rename(made.id, 'Late', made.revision, write()),
          'NOT_FOUND',
        );
        if (alice.changeAudience)
          await refusal(
            alice.changeAudience(
              made.id,
              personal ? 'shared' : own,
              made.revision,
              write(),
            ),
            'NOT_FOUND',
          );
        await refusal(
          alice.delete(made.id, made.revision, write()),
          'NOT_FOUND',
        );
      });

      it('answers a write to a missing view as NOT_FOUND', async () => {
        const { alice } = await setup();
        const missing = `missing-${crypto.randomUUID()}`;

        await refusal(
          alice.save(missing, recordConfig(), '1', write()),
          'NOT_FOUND',
        );
        await refusal(
          alice.rename(missing, 'Nobody', '1', write()),
          'NOT_FOUND',
        );
        await refusal(alice.delete(missing, '1', write()), 'NOT_FOUND');
      });

      it.skipIf(capabilities.systemViews === undefined)(
        'refuses every write to a system view',
        async () => {
          const open = await subject.connect();
          const alice = open(ALICE);
          const definitionId = capabilities.systemViews!.definitionId;
          const system = (await alice.list(definitionId)).find(
            summary => summary.scope === 'system',
          );
          expect(system, 'the backend serves a system view').toBeDefined();
          const { id, revision } = system!;

          await refusal(
            alice.save(id, recordConfig(50), revision, write()),
            'FORBIDDEN',
          );
          await refusal(
            alice.rename(id, 'Mine now', revision, write()),
            'FORBIDDEN',
          );
          await refusal(alice.delete(id, revision, write()), 'FORBIDDEN');
          if (alice.changeAudience)
            await refusal(
              alice.changeAudience(id, 'personal', revision, write()),
              'FORBIDDEN',
            );
          const after = (await alice.list(definitionId)).find(
            summary => summary.id === id,
          );
          expect(after).toEqual(system);
        },
      );
    });

    describe('a replayed requestId', () => {
      it.skipIf(!capabilities.idempotentCreate)(
        'answers a create once, whatever the retry',
        async () => {
          const { alice, definitionId } = await setup();
          const context = write();
          const input = {
            definitionId,
            title: 'Once',
            scope: own,
            config: recordConfig(),
          };

          const first = await alice.create(input, context);
          const again = await alice.create(input, context);

          expect(again).toEqual(first);
          expect(
            (await alice.list(definitionId)).filter(
              item => item.title === 'Once',
            ),
          ).toHaveLength(1);
        },
      );

      it('answers the first outcome after another writer moved the view', async () => {
        const { open, alice, create } = await setup();
        const other = open(ALICE);
        const made = await create('Mine');
        const context = write();

        const first = await alice.save(
          made.id,
          recordConfig(50),
          made.revision,
          context,
        );
        await other.rename(made.id, 'Moved on', first.revision, write());
        const again = await alice.save(
          made.id,
          recordConfig(50),
          made.revision,
          context,
        );

        expect(again).toEqual(first);
      });

      it('answers a rename and a delete once', async () => {
        const { alice, create } = await setup();
        const made = await create('Mine');
        const renaming = write();

        const renamed = await alice.rename(
          made.id,
          'Renamed',
          made.revision,
          renaming,
        );
        expect(
          await alice.rename(made.id, 'Renamed', made.revision, renaming),
        ).toEqual(renamed);

        const deleting = write();
        await alice.delete(made.id, renamed.revision, deleting);
        await expect(
          alice.delete(made.id, renamed.revision, deleting),
        ).resolves.toBeUndefined();
      });
    });

    describe('preferences', () => {
      it("start at revision '0' with nothing ordered and no default", async () => {
        const { alice, definitionId } = await setup();

        expect(await alice.getPreferences(definitionId)).toMatchObject({
          order: [],
          defaultInstanceId: null,
          revision: '0',
        });
      });

      it('take a write against the revision read, and refuse a stale one', async () => {
        const { alice, definitionId, create } = await setup();
        const made = await create('Mine');
        const start = await alice.getPreferences(definitionId);

        const set = await alice.setPreferences(
          definitionId,
          { ...start, order: [made.id], defaultInstanceId: made.id },
          write(),
        );
        expect(set).toMatchObject({
          order: [made.id],
          defaultInstanceId: made.id,
        });
        expect(set.revision).not.toBe(start.revision);
        expect(await alice.getPreferences(definitionId)).toEqual(set);

        const failure = await refusal(
          alice.setPreferences(
            definitionId,
            { ...start, defaultInstanceId: null },
            write(),
          ),
          'CONFLICT',
        );
        expect(failure.preferences).toEqual(set);
        expect(failure.instance).toBeUndefined();
      });

      it('answer a replayed write with its first outcome', async () => {
        const { open, alice, definitionId } = await setup();
        const context = write();
        const start = await alice.getPreferences(definitionId);
        const next = { ...start, order: ['a'] };

        const first = await alice.setPreferences(definitionId, next, context);
        await open(ALICE).setPreferences(
          definitionId,
          { ...first, order: ['b'] },
          write(),
        );

        expect(await alice.setPreferences(definitionId, next, context)).toEqual(
          first,
        );
      });

      it.skipIf(!capabilities.owners)('are kept per owner', async () => {
        const { open, alice, definitionId } = await setup();
        const start = await alice.getPreferences(definitionId);
        await alice.setPreferences(
          definitionId,
          { ...start, order: ['a'] },
          write(),
        );

        expect(await open(BOB).getPreferences(definitionId)).toMatchObject({
          order: [],
          revision: '0',
        });
      });
    });

    describe.skipIf(!capabilities.changeAudience)('changeAudience', () => {
      /** The store's method, which a subject declaring the capability has. */
      const change = (
        store: ViewStore,
        id: string,
        audience: ViewAudience,
        revision: string,
        context: WriteContext = write(),
      ) => {
        expect(store.changeAudience).toBeTypeOf('function');
        return store.changeAudience!(id, audience, revision, context);
      };

      it.skipIf(!personal)(
        'moves a view between the audiences in place, id kept',
        async () => {
          const { alice, definitionId, create } = await setup();
          const made = await create('Mine', 'personal');

          const shared = await change(alice, made.id, 'shared', made.revision);
          expect(shared).toMatchObject({
            id: made.id,
            scope: 'shared',
            title: 'Mine',
            config: made.config,
          });
          expect(shared.revision).not.toBe(made.revision);
          expect(await alice.get(made.id)).toEqual(shared);
          expect(
            (await alice.list(definitionId)).find(item => item.id === made.id)
              ?.scope,
          ).toBe('shared');

          const back = await change(
            alice,
            made.id,
            'personal',
            shared.revision,
          );
          expect(back.scope).toBe('personal');
          expect(back.revision).not.toBe(shared.revision);
        },
      );

      it.skipIf(!capabilities.owners || !personal)(
        'shows a view to other users once shared, and hides it once personal',
        async () => {
          const { open, alice, definitionId, create } = await setup();
          const bob = open(BOB);
          const made = await create('Mine', 'personal');

          const shared = await change(alice, made.id, 'shared', made.revision);
          expect(ids(await bob.list(definitionId))).toContain(made.id);

          await change(alice, made.id, 'personal', shared.revision);
          expect(ids(await bob.list(definitionId))).not.toContain(made.id);
          expect(ids(await alice.list(definitionId))).toContain(made.id);
        },
      );

      it('answers the audience a view already has with the view as it is', async () => {
        const { alice, create } = await setup();
        const made = await create('Mine');

        const same = await change(alice, made.id, own, made.revision);

        expect(same).toEqual(made);
        expect(await alice.get(made.id)).toEqual(made);
      });

      it('refuses a stale revision as CONFLICT, carrying the view it holds', async () => {
        const { alice, create } = await setup();
        const made = await create('Mine');
        const moved = await alice.rename(
          made.id,
          'Moved',
          made.revision,
          write(),
        );

        const failure = await refusal(
          change(alice, made.id, personal ? 'shared' : own, made.revision),
          'CONFLICT',
        );

        expect(failure.instance?.revision).toBe(moved.revision);
        expect(await alice.get(made.id)).toEqual(moved);
      });

      it('answers a missing view as NOT_FOUND', async () => {
        const { alice } = await setup();
        await refusal(
          change(alice, `missing-${crypto.randomUUID()}`, 'shared', '1'),
          'NOT_FOUND',
        );
      });

      it.skipIf(!personal)(
        'answers a replay with the first outcome, after another writer moved the view',
        async () => {
          const { open, alice, create } = await setup();
          const made = await create('Mine', 'personal');
          const context = write();

          const first = await change(
            alice,
            made.id,
            'shared',
            made.revision,
            context,
          );
          await open(ALICE).rename(
            made.id,
            'Moved on',
            first.revision,
            write(),
          );
          const again = await change(
            alice,
            made.id,
            'shared',
            made.revision,
            context,
          );

          expect(again).toEqual(first);
        },
      );

      it.skipIf(!personal).each<Reference>(['instanceId', 'opens', 'click'])(
        'keeps shared a view a shared board references by %s, and names the board',
        async through => {
          const { alice, definitionId, create } = await setup();
          const shown = await create('Shown', 'shared');
          const board = await alice.create(
            {
              definitionId: `${definitionId}-boards`,
              title: 'Team board',
              scope: 'shared',
              config: boardShowing(shown.id, through),
            },
            write(),
          );

          const failure = await refusal(
            change(alice, shown.id, 'personal', shown.revision),
            'INVALID',
          );
          // By title, not id, as stored: the engine says the refusal around
          // them, and the reader is shown the titles.
          expect(failure.boards).toEqual([board.title]);
          expect(await alice.get(shown.id)).toEqual(shown);

          // Deleting it stays allowed: the panel alone breaks (9).
          await alice.delete(shown.id, shown.revision, write());
          await refusal(alice.get(shown.id), 'NOT_FOUND');
        },
      );

      it.skipIf(!personal)(
        'lets a view go personal when only a personal board shows it',
        async () => {
          const { alice, definitionId, create } = await setup();
          const shown = await create('Shown', 'shared');
          await alice.create(
            {
              definitionId: `${definitionId}-boards`,
              title: 'My board',
              scope: 'personal',
              config: boardShowing(shown.id),
            },
            write(),
          );

          const personalAgain = await change(
            alice,
            shown.id,
            'personal',
            shown.revision,
          );

          expect(personalAgain.scope).toBe('personal');
        },
      );
    });
  });
}
