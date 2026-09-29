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
 * 设为共享／设为个人 through the engine (D18 item 10, view-store-backend.md
 * 6.1): one more write action on the same ledger — asked before it is
 * sent, recorded when it does not land, recovered the same three ways.
 */

import { describe, expect, it, vi } from 'vitest';
import {
  isViewCommandError,
  isViewWriteError,
  MemoryViewStore,
  ViewEngine,
  ViewStoreError,
  type Issue,
  type ViewChange,
  type ViewInstance,
  type ViewPermissions,
  type ViewStore,
  type ViewWriteError,
} from '../src/index.js';
import {
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  resourcesOf,
  testEnvironment,
  testSource,
} from './fixtures.js';
import { withoutAudience } from './fixtures/writes.js';

const mine: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'Mine',
  scope: 'personal',
  revision: '1',
  config: recordConfig(),
};

const ours: ViewInstance = {
  ...mine,
  id: 'orders-2',
  title: 'Ours',
  scope: 'shared',
};

/** A shared board whose one panel shows `ours`. */
const teamBoard: ViewInstance = {
  id: 'overview-1',
  definitionId: 'overview',
  title: 'Team board',
  scope: 'shared',
  revision: '1',
  config: dashboardConfig({
    panels: [
      {
        id: 'p1',
        kind: 'view',
        title: 'Ours',
        instanceId: 'orders-2',
        layout: { x: 0, y: 0, w: 12, h: 6 },
        bindings: [],
      },
    ],
  }),
};

function harness(
  options: {
    instances?: ViewInstance[];
    permissions?: (definitionId: string) => ViewPermissions;
    store?: (memory: MemoryViewStore) => ViewStore;
  } = {},
) {
  const store = new MemoryViewStore({
    instances: options.instances ?? [mine, ours],
    permissions: options.permissions,
  });
  let sequence = 0;
  const changes: ViewChange[] = [];
  const engine = new ViewEngine({
    resources: resourcesOf([ordersDefinition(), overviewDefinition()], () =>
      testSource(),
    ),
    store: options.store?.(store) ?? store,
    environment: testEnvironment().environment,
    newId: () => `req-${(sequence += 1)}`,
    onIssue: () => undefined,
  });
  engine.subscribe(change => changes.push(change));
  return { engine, store, changes };
}

function permitting(overrides: Partial<ViewPermissions> = {}) {
  return (): ViewPermissions => ({
    createPersonal: true,
    createShared: true,
    reorder: true,
    setDefault: true,
    instance: () => ({ save: true, rename: true, delete: true }),
    ...overrides,
  });
}

async function refused(run: Promise<unknown>): Promise<Issue> {
  const error = await run.catch((caught: unknown) => caught);
  if (!isViewCommandError(error)) throw new Error(`not refused: ${error}`);
  return error.issue;
}

async function failedWrite(run: Promise<unknown>): Promise<ViewWriteError> {
  const error = await run.catch((caught: unknown) => caught);
  if (!isViewWriteError(error))
    throw new Error(`not a write failure: ${error}`);
  return error;
}

describe('ViewEngine.changeAudience', () => {
  it('moves a view in place, id kept, and says the list changed', async () => {
    const { engine, store, changes } = harness();

    const shared = await engine.changeAudience('orders-1', 'shared');

    expect(shared).toMatchObject({ id: 'orders-1', scope: 'shared' });
    expect((await store.get('orders-1')).scope).toBe('shared');
    expect(changes).toEqual([
      { definitionId: 'orders', kind: 'changeAudience', id: 'orders-1' },
    ]);
    const { items } = await engine.list('orders');
    expect(items.find(item => item.id === 'orders-1')?.scope).toBe('shared');
  });

  it('moves the baseline of every open view and leaves the draft alone', async () => {
    const { engine } = harness();
    const runtime = await engine.open('orders-1');
    const twin = await engine.open('orders-1');
    runtime.edit({ pageSize: 50 });

    await engine.changeAudience('orders-1', 'shared');

    for (const open of [runtime, twin]) {
      expect(open.getSnapshot().scope).toBe('shared');
      expect(open.getSnapshot().saved?.scope).toBe('shared');
    }
    // It carries no config: the unsaved edit is still the user's.
    expect(runtime.getSnapshot().dirty).toBe(true);
    expect(runtime.getSnapshot().write).toBeNull();
  });

  it('answers the audience a view already has without writing', async () => {
    const { engine, store, changes } = harness();
    const write = vi.spyOn(store, 'changeAudience');

    const same = await engine.changeAudience('orders-1', 'personal');

    expect(same).toMatchObject({ id: 'orders-1', revision: '1' });
    expect(write).not.toHaveBeenCalled();
    expect(changes).toEqual([]);
  });

  describe('refused before anything is sent', () => {
    it('when the store has no such move, and the manager offers none', async () => {
      const { engine } = harness({ store: withoutAudience });

      expect(
        (await refused(engine.changeAudience('orders-1', 'shared'))).code,
      ).toBe('view.changeAudience.unsupported');
      expect(
        engine.permissions('orders').instance('orders-1').changeAudience,
      ).toBe(false);
    });

    it('when the store refuses it for the view', async () => {
      const { engine } = harness({
        permissions: permitting({
          instance: () => ({
            save: true,
            rename: true,
            delete: true,
            changeAudience: false,
          }),
        }),
      });

      expect(
        (await refused(engine.changeAudience('orders-1', 'shared'))).code,
      ).toBe('view.changeAudience.forbidden');
    });

    it('when the user may not create in the audience it goes to', async () => {
      const { engine, store } = harness({
        permissions: permitting({ createShared: false }),
      });
      const write = vi.spyOn(store, 'changeAudience');

      expect(
        (await refused(engine.changeAudience('orders-1', 'shared'))).code,
      ).toBe('view.changeAudience.forbidden');
      // Taking a shared one back needs `createPersonal`, which is granted.
      await engine.changeAudience('orders-2', 'personal');
      expect(write).toHaveBeenCalledTimes(1);
    });

    it('for a system view, declared in code or served by the store', async () => {
      const { engine } = harness({
        instances: [mine, { ...ours, id: 'ops-1', scope: 'system' }],
      });
      const [declared] = (await engine.list('orders')).items.filter(
        item => item.scope === 'system' && item.id.startsWith('system:'),
      );

      for (const id of [declared.id, 'ops-1'])
        expect(
          (await refused(engine.changeAudience(id, 'personal'))).code,
        ).toBe('view.system.read-only');
    });
  });

  describe('outcomes, on the same ledger as every write', () => {
    it('conflicts on a stale revision, and a reload moves the baseline only', async () => {
      const { engine, store } = harness();
      const runtime = await engine.open('orders-1');
      runtime.edit({ pageSize: 50 });
      await store.rename('orders-1', 'Theirs', '1', { requestId: 'other' });

      const failure = await failedWrite(
        engine.changeAudience('orders-1', 'shared'),
      );

      expect(failure.state.kind).toBe('conflict');
      expect(runtime.getSnapshot().write?.kind).toBe('conflict');
      await engine.resolveConflict(runtime, 'reload');
      expect(runtime.getSnapshot().saved).toMatchObject({
        title: 'Theirs',
        scope: 'personal',
        revision: '2',
      });
      expect(runtime.getSnapshot().dirty).toBe(true);
    });

    it('overwrites against the revision the conflict reported', async () => {
      const { engine, store } = harness();
      await engine.list('orders');
      await store.rename('orders-1', 'Theirs', '1', { requestId: 'other' });
      const failure = await failedWrite(
        engine.changeAudience('orders-1', 'shared'),
      );

      const landed = await engine.resolveConflict(failure.handle, 'overwrite');

      expect(landed).toMatchObject({ scope: 'shared', title: 'Theirs' });
    });

    it('holds an unknown outcome for a retry under the same requestId', async () => {
      const { engine, store } = harness();
      const write = vi
        .spyOn(store, 'changeAudience')
        .mockRejectedValueOnce(new ViewStoreError('UNAVAILABLE', 'timeout'));

      const failure = await failedWrite(
        engine.changeAudience('orders-1', 'shared'),
      );
      expect(failure.state.kind).toBe('unknown');

      // A new intent on the same view waits for this one to be settled.
      expect((await refused(engine.rename('orders-1', 'Other'))).code).toBe(
        'view.write.unknown-pending',
      );

      const landed = await engine.retryWrite(failure.handle);
      expect(landed).toMatchObject({ scope: 'shared' });
      expect(write.mock.calls.map(call => call[3].requestId)).toEqual([
        failure.state.requestId,
        failure.state.requestId,
      ]);
      expect(engine.pendingWrites().size).toBe(0);
    });

    it('says the store’s reason when a shared board keeps the view shared', async () => {
      const { engine, store } = harness({ instances: [mine, ours, teamBoard] });

      const failure = await failedWrite(
        engine.changeAudience('orders-2', 'personal'),
      );

      expect(failure.state).toMatchObject({
        kind: 'rejected',
        issue: { code: 'view.changeAudience.invalid' },
      });
      if (failure.state.kind === 'rejected')
        expect(failure.state.issue.params?.reason).toContain('Team board');
      expect((await store.get('orders-2')).scope).toBe('shared');
    });
  });

  it('shares a board over a personal view, as a shared board’s save is allowed (D22 B)', async () => {
    const board: ViewInstance = {
      ...teamBoard,
      scope: 'personal',
      config: dashboardConfig({
        panels: [
          {
            id: 'p1',
            kind: 'view',
            title: 'Mine',
            instanceId: 'orders-1',
            layout: { x: 0, y: 0, w: 12, h: 6 },
            bindings: [],
          },
        ],
      }),
    };
    const { engine } = harness({ instances: [mine, board] });
    const runtime = await engine.open('overview-1');
    await vi.waitFor(() =>
      expect(runtime.getSnapshot().saved?.id).toBe('overview-1'),
    );
    expect(runtime.getSnapshot().issues.map(found => found.code)).not.toContain(
      'dashboard.panel.scope-too-narrow',
    );

    await engine.changeAudience('overview-1', 'shared');

    // The board is judged where it now is: the panel is blank for other
    // readers, and says so — a warning, as on a save.
    await vi.waitFor(() =>
      expect(runtime.getSnapshot().issues.map(found => found.code)).toContain(
        'dashboard.panel.scope-too-narrow',
      ),
    );
  });
});
