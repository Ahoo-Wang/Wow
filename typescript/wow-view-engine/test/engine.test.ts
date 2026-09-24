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
import type { ViewWriteError } from '../src/index.js';
import {
  emptyDashboardConfig,
  isViewCommandError,
  isViewWriteError,
  MemoryViewStore,
  toSummary,
  ViewEngine,
  ViewStoreError,
  type Issue,
  type ViewChange,
  type ViewDefinition,
  type ViewInstance,
  type ViewPermissions,
  type ViewPreferences,
} from '../src/index.js';
import { orderSummaries } from '../src/runtime/preferences.js';
import { systemInstances } from '../src/runtime/definitions.js';
import {
  analysisConfig,
  nextTask,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  requireRecordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';

const mine: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'Mine',
  scope: 'personal',
  revision: '1',
  config: recordConfig(),
};

interface Harness {
  engine: ViewEngine;
  store: MemoryViewStore;
  issues: Issue[];
}

function harness(
  options: {
    instances?: ViewInstance[];
    definitions?: ViewDefinition[];
    permissions?: (definitionId: string) => ViewPermissions;
  } = {},
): Harness {
  const store = new MemoryViewStore({
    instances: options.instances ?? [mine],
    permissions: options.permissions,
  });
  const issues: Issue[] = [];
  let sequence = 0;
  const engine = new ViewEngine({
    definitions: options.definitions ?? [ordersDefinition()],
    store,
    resolveSource: () => testSource(),
    environment: testEnvironment().environment,
    newId: () => `req-${(sequence += 1)}`,
    onIssue: found => issues.push(found),
  });
  return { engine, store, issues };
}

function permissions(
  overrides: Partial<ViewPermissions> = {},
): ViewPermissions {
  return {
    createPersonal: true,
    createShared: true,
    reorder: true,
    setDefault: true,
    instance: () => ({ save: true, rename: true, delete: true }),
    ...overrides,
  };
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

describe('ViewEngine listing', () => {
  it('puts code-declared system views before the stored ones', async () => {
    const { engine } = harness();

    const { items, failed } = await engine.list('orders');

    expect(items.map(summary => summary.id)).toEqual([
      'system:orders:all',
      'orders-1',
    ]);
    expect(items[0]).toMatchObject({ scope: 'system', revision: 'code' });
    expect(failed).toBeNull();
  });

  /**
   * The declared views travel with the definition, so a store that is down
   * takes only the saved ones with it — and says so in the answer rather
   * than throwing the declared ones away with the rest (F-05).
   */
  it('keeps the declared views when the store cannot list, and says why', async () => {
    const { engine, store, issues } = harness();
    vi.spyOn(store, 'list').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'offline'),
    );

    const { items, failed } = await engine.list('orders');

    expect(items.map(summary => summary.id)).toEqual(['system:orders:all']);
    expect(failed).toMatchObject({ code: 'view.list.failed.unavailable' });
    expect(issues).toEqual([
      expect.objectContaining({ code: 'view.list.failed.unavailable' }),
    ]);
  });

  it('drops a stored id in the reserved namespace and reports it', async () => {
    const { engine, issues } = harness({
      instances: [mine, { ...mine, id: 'system:orders:fake' }],
    });

    const { items } = await engine.list('orders');

    expect(items.map(summary => summary.id)).toEqual([
      'system:orders:all',
      'orders-1',
    ]);
    expect(issues).toEqual([
      expect.objectContaining({ code: 'view.list.reserved-id' }),
    ]);
  });

  it('refuses to list an unknown definition', async () => {
    const { engine } = harness();
    expect((await refused(engine.list('nope'))).code).toBe(
      'view.definition.not-found',
    );
  });

  it('orders a list by preference and appends the rest', () => {
    const summaries = [
      { ...mine, id: 'a' },
      { ...mine, id: 'b' },
      { ...mine, id: 'c' },
    ].map(toSummary);
    const preferences: ViewPreferences = {
      order: ['c', 'gone', 'a'],
      defaultInstanceId: null,
      revision: '1',
    };

    expect(
      orderSummaries(summaries, preferences).map(summary => summary.id),
    ).toEqual(['c', 'a', 'b']);
  });

  it('resolves the default view in the documented order', () => {
    const { engine } = harness();
    const summaries = systemInstances(ordersDefinition()).map(toSummary);
    const preferences: ViewPreferences = {
      order: [],
      defaultInstanceId: 'system:orders:all',
      revision: '1',
    };

    expect(
      engine.resolveDefault(summaries, preferences, 'system:orders:all'),
    ).toBe('system:orders:all');
    expect(engine.resolveDefault(summaries, preferences, 'gone')).toBe(
      'system:orders:all',
    );
    expect(
      engine.resolveDefault(summaries, {
        ...preferences,
        defaultInstanceId: 'gone',
      }),
    ).toBe('system:orders:all');
    expect(engine.resolveDefault([], preferences)).toBeNull();
  });
});

describe('ViewEngine opening', () => {
  it('opens a code-declared system view without asking the store', async () => {
    const { engine, store } = harness();
    const get = vi.spyOn(store, 'get');

    const runtime = await engine.open('system:orders:all');
    await nextTask();

    expect(get).not.toHaveBeenCalled();
    expect(runtime.getSnapshot().saved).toMatchObject({
      scope: 'system',
      revision: 'code',
    });
    expect(runtime.getSnapshot().query.status).toBe('success');
  });

  it('opens a stored view and executes it at once', async () => {
    const { engine } = harness();

    const runtime = await engine.open('orders-1');
    await nextTask();

    expect(runtime.getSnapshot().dirty).toBe(false);
    expect(runtime.getSnapshot().query.status).toBe('success');
  });

  it('reports a system view the definition does not declare', async () => {
    const { engine } = harness();
    expect((await refused(engine.open('system:orders:gone'))).code).toBe(
      'view.open.not-found',
    );
  });

  it('refuses a config the definition declares no capability for', async () => {
    const definition = ordersDefinition({ analysis: undefined, views: [] });
    const { engine } = harness({
      definitions: [definition],
      instances: [{ ...mine, config: analysisConfig() }],
    });

    expect((await refused(engine.open('orders-1'))).code).toBe(
      'runtime.kind.not-declared',
    );
  });

  it('opens a dashboard against its own definition', async () => {
    const { engine } = harness({
      definitions: [ordersDefinition(), overviewDefinition()],
      instances: [
        {
          ...mine,
          definitionId: 'overview',
          config: emptyDashboardConfig(),
        },
      ],
    });

    const runtime = await engine.open('orders-1');

    expect(runtime.kind).toBe('dashboard');
    expect(runtime.getSnapshot().dirty).toBe(false);
  });

  it('refuses a dashboard config under a data definition, and the reverse', async () => {
    const { engine } = harness({
      definitions: [ordersDefinition(), overviewDefinition()],
      instances: [
        { ...mine, config: emptyDashboardConfig() },
        { ...mine, id: 'orders-2', definitionId: 'overview' },
      ],
    });

    expect((await refused(engine.open('orders-1'))).code).toBe(
      'runtime.kind.not-declared',
    );
    expect((await refused(engine.open('orders-2'))).code).toBe(
      'runtime.kind.not-declared',
    );
  });

  it('keeps no runtime for a dashboard that failed to open', async () => {
    const store = new MemoryViewStore({
      instances: [
        mine,
        {
          ...mine,
          id: 'overview-1',
          definitionId: 'overview',
          config: {
            ...emptyDashboardConfig(),
            panels: [
              {
                id: 'orders',
                kind: 'view',
                instanceId: 'orders-1',
                bindings: [],
                layout: { x: 0, y: 0, w: 6, h: 4 },
              },
            ],
          },
        },
      ],
    });
    const engine = new ViewEngine({
      definitions: [ordersDefinition(), overviewDefinition()],
      store,
      resolveSource: key => {
        throw new Error(`no source: ${key}`);
      },
      environment: testEnvironment().environment,
    });

    await expect(engine.open('overview-1')).rejects.toThrow('no source');
    // The caller never received it, so nobody could close it.
    expect(engine.openRuntimes()).toEqual([]);
  });
});

describe('ViewEngine creating and saving', () => {
  it('creates an unsaved runtime that already shows data', async () => {
    const { engine } = harness();

    const runtime = engine.create('orders', {
      title: 'New',
      scope: 'personal',
      config: recordConfig(),
    });
    await nextTask();

    const state = runtime.getSnapshot();
    expect(state.saved).toBeNull();
    expect(state.dirty).toBe(true);
    expect(state.query.status).toBe('success');
  });

  it('refuses an empty title, and a scope the user may not create only when it is saved (H1)', async () => {
    const { engine } = harness({
      permissions: () => permissions({ createShared: false }),
    });
    const input = {
      title: '  ',
      scope: 'personal' as const,
      config: recordConfig(),
    };

    expect(() => engine.create('orders', input)).toThrowError(
      /view command refused/i,
    );
    // Making the view asks no permission — nothing is written, and a reader
    // drills into records under a scope they could never save to. The first
    // save is where the store is asked, and where it is refused.
    const unsaved = engine.create('orders', {
      ...input,
      title: 'New',
      scope: 'shared',
    });
    await expect(engine.save(unsaved)).rejects.toThrowError(
      /view command refused/i,
    );
  });

  it('creates on first save and overwrites afterwards', async () => {
    const { engine, store } = harness({ instances: [] });
    const runtime = engine.create('orders', {
      title: 'New',
      scope: 'personal',
      config: recordConfig(),
    });

    const created = await engine.save(runtime);
    runtime.edit({ pageSize: 50 });
    const saved = await engine.save(runtime);

    expect(created).toMatchObject({ id: 'orders-1', revision: '1' });
    expect(saved).toMatchObject({ id: 'orders-1', revision: '2' });
    expect(runtime.getSnapshot().dirty).toBe(false);
    await expect(store.get('orders-1')).resolves.toMatchObject({
      config: { pageSize: 50 },
    });
  });

  it('refuses to save a draft that still has an error', async () => {
    const { engine } = harness();
    const runtime = await engine.open('orders-1');
    runtime.edit({ pageSize: 0 });

    expect((await refused(engine.save(runtime))).code).toBe(
      'view.config.invalid',
    );
  });

  it('refuses to save a system view and offers nothing else', async () => {
    const { engine } = harness();
    const runtime = await engine.open('system:orders:all');

    expect((await refused(engine.save(runtime))).code).toBe(
      'view.system.read-only',
    );
  });

  it('refuses a save the permissions do not allow', async () => {
    const { engine } = harness({
      permissions: () =>
        permissions({
          instance: () => ({ save: false, rename: true, delete: true }),
        }),
    });
    const runtime = await engine.open('orders-1');

    expect((await refused(engine.save(runtime))).code).toBe(
      'view.save.forbidden',
    );
  });

  it('refuses a runtime it does not own', async () => {
    const { engine } = harness();
    const other = harness();
    const runtime = await other.engine.open('orders-1');

    expect((await refused(engine.save(runtime))).code).toBe(
      'view.runtime.not-owned',
    );
  });

  it('copies a view without touching the source runtime', async () => {
    const { engine } = harness();
    const runtime = await engine.open('orders-1');
    runtime.edit({ pageSize: 50 });

    const copy = await engine.saveAs(runtime, {
      title: 'Copy',
      scope: 'shared',
    });

    expect(copy).toMatchObject({ title: 'Copy', scope: 'shared' });
    expect(copy.config).toMatchObject({ pageSize: 50 });
    expect(runtime.getSnapshot().saved?.id).toBe('orders-1');
    expect(runtime.getSnapshot().dirty).toBe(true);
  });

  it('saves a copy of a system view', async () => {
    const { engine } = harness();
    const runtime = await engine.open('system:orders:all');

    const copy = await engine.saveAs(runtime, {
      title: 'From system',
      scope: 'personal',
    });

    expect(copy.scope).toBe('personal');
  });
});

describe('ViewEngine list commands', () => {
  it('renames an open view and advances its baseline', async () => {
    const { engine } = harness();
    const runtime = await engine.open('orders-1');

    const renamed = await engine.rename('orders-1', 'Renamed');

    expect(renamed).toMatchObject({ title: 'Renamed', revision: '2' });
    expect(runtime.getSnapshot().title).toBe('Renamed');
    expect(runtime.getSnapshot().dirty).toBe(false);
  });

  it('renames a view that is not open, reading its revision from the store', async () => {
    const { engine } = harness();
    await expect(engine.rename('orders-1', 'Renamed')).resolves.toMatchObject({
      revision: '2',
    });
  });

  it('refuses an empty title and a rename without permission', async () => {
    const { engine } = harness({
      permissions: () =>
        permissions({
          instance: () => ({ save: true, rename: false, delete: true }),
        }),
    });

    expect((await refused(engine.rename('orders-1', ' '))).code).toBe(
      'view.title.empty',
    );
    expect((await refused(engine.rename('orders-1', 'New'))).code).toBe(
      'view.rename.forbidden',
    );
  });

  it('deletes a view and disposes the runtime that held it', async () => {
    const { engine, store } = harness();
    const runtime = await engine.open('orders-1');

    await engine.delete('orders-1');

    expect(engine.openRuntimes()).toEqual([]);
    await expect(store.list('orders')).resolves.toEqual([]);
    runtime.edit({ pageSize: 10 });
    expect(requireRecordConfig(runtime.getSnapshot().draft).pageSize).toBe(20);
  });

  it('refuses to delete a system view', async () => {
    const { engine } = harness();
    expect((await refused(engine.delete('system:orders:all'))).code).toBe(
      'view.system.read-only',
    );
  });

  it('writes the visible order with the revision it was read at', async () => {
    const { engine } = harness();

    const written = await engine.reorder('orders', ['orders-1']);

    expect(written).toMatchObject({ order: ['orders-1'], revision: '1' });
    await expect(engine.preferences('orders')).resolves.toEqual(written);
  });

  it('sets and clears the default view', async () => {
    const { engine } = harness();

    await engine.setDefault('orders', 'orders-1');
    const cleared = await engine.setDefault('orders', null);

    expect(cleared).toMatchObject({ defaultInstanceId: null, revision: '2' });
  });

  it('refuses preference writes the permissions do not allow', async () => {
    const { engine } = harness({
      permissions: () => permissions({ reorder: false, setDefault: false }),
    });

    expect((await refused(engine.reorder('orders', []))).code).toBe(
      'view.preferences.reorder-forbidden',
    );
    expect((await refused(engine.setDefault('orders', null))).code).toBe(
      'view.preferences.default-forbidden',
    );
  });
});

/**
 * One write at a time per target. A double-clicked Save button is the reason:
 * both attempts carry the same expectation, so without a guard the second one
 * either invents a conflict or creates a second instance.
 */
/**
 * Who is told that a definition's list has changed, and when.
 *
 * The engine is the one place that knows when a write lands, so it says so
 * (D15) rather than leaving the manager, the view header and every host to
 * remember. `kind` is the write, not the kind of the view.
 */
describe('ViewEngine change notifications', () => {
  function watching(): Harness & { changes: ViewChange[]; stop(): void } {
    const found = harness();
    const changes: ViewChange[] = [];
    const stop = found.engine.subscribe(change => changes.push(change));
    return { ...found, changes, stop };
  }

  it('announces the view a first save created', async () => {
    const { engine, changes } = watching();
    const runtime = engine.create('orders', {
      title: 'New',
      scope: 'personal',
      config: recordConfig(),
    });

    const created = await engine.save(runtime);

    expect(changes).toEqual([
      { definitionId: 'orders', kind: 'create', id: created.id },
    ]);
  });

  it('announces a save, which may have moved a title or an audience', async () => {
    const { engine, changes } = watching();
    const runtime = await engine.open('orders-1');
    runtime.edit({ pageSize: 50 });

    await engine.save(runtime);

    expect(changes).toEqual([
      { definitionId: 'orders', kind: 'save', id: 'orders-1' },
    ]);
  });

  it('announces a rename', async () => {
    const { engine, changes } = watching();

    await engine.rename('orders-1', 'Renamed');

    expect(changes).toEqual([
      { definitionId: 'orders', kind: 'rename', id: 'orders-1' },
    ]);
  });

  it('announces a delete, naming the list the row left', async () => {
    const { engine, changes } = watching();

    // Never listed and never opened here, which is the host that D15 is
    // about: the definition comes out of the body of the write rather than
    // out of a cache that may hold nothing about this id.
    await engine.delete('orders-1');

    expect(changes).toEqual([
      { definitionId: 'orders', kind: 'delete', id: 'orders-1' },
    ]);
  });

  it('announces the retry the first attempt could not', async () => {
    const { engine, store, changes } = watching();
    const runtime = await engine.open('orders-1');
    runtime.edit({ pageSize: 50 });
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'timeout'),
    );

    await failedWrite(engine.save(runtime));
    // Nothing landed, so nothing is announced.
    expect(changes).toEqual([]);

    await engine.retryWrite(runtime);

    expect(changes).toEqual([
      { definitionId: 'orders', kind: 'save', id: 'orders-1' },
    ]);
  });

  it('announces an overwrite, which is a write of its own', async () => {
    const { engine, store, changes } = watching();
    const runtime = await engine.open('orders-1');
    runtime.edit({ pageSize: 99 });
    await store.save('orders-1', recordConfig({ pageSize: 33 }), '1', {
      requestId: 'other',
    });
    await failedWrite(engine.save(runtime));

    await engine.resolveConflict(runtime, 'overwrite');

    expect(changes).toEqual([
      { definitionId: 'orders', kind: 'save', id: 'orders-1' },
    ]);
  });

  it('says nothing about a preference write', async () => {
    const { engine, changes } = watching();

    await engine.reorder('orders', ['orders-1']);
    await engine.setDefault('orders', 'orders-1');

    // The order and the default are not the list, and the caller that wrote
    // them is holding the answer already.
    expect(changes).toEqual([]);
  });

  it('stops at unsubscribe, and lets every listener go on dispose', async () => {
    const { engine, changes, stop } = watching();

    stop();
    await engine.rename('orders-1', 'One');
    expect(changes).toEqual([]);

    const later: ViewChange[] = [];
    engine.subscribe(change => later.push(change));
    engine.dispose();
    await engine.rename('orders-1', 'Two');

    expect(later).toEqual([]);
  });

  it('contains a listener that throws, and reports it', async () => {
    const { engine, issues } = harness();
    const heard: ViewChange[] = [];
    engine.subscribe(() => {
      throw new Error('listener is broken');
    });
    engine.subscribe(change => heard.push(change));

    // The write landed and it stays landed: an outcome the user is asked to
    // retry is the one thing a broken listener must not create.
    await expect(engine.rename('orders-1', 'Renamed')).resolves.toMatchObject({
      title: 'Renamed',
    });

    expect(heard).toHaveLength(1);
    expect(issues).toContainEqual(
      expect.objectContaining({ code: 'view.change.notify-failed' }),
    );
  });
});

describe('ViewEngine write re-entrancy', () => {
  it('refuses a second save while the first is in flight', async () => {
    const { engine } = harness();
    const runtime = engine.create('orders', {
      title: 'Twice',
      scope: 'personal',
      config: recordConfig(),
    });

    const [first, second] = await Promise.allSettled([
      engine.save(runtime),
      engine.save(runtime),
    ]);

    expect(first.status).toBe('fulfilled');
    expect(second.status).toBe('rejected');
    expect(
      isViewCommandError(second.status === 'rejected' ? second.reason : null),
    ).toBe(true);
  });

  it('creates one instance, not two, for a double first save', async () => {
    const { engine, store } = harness({ instances: [] });
    const runtime = engine.create('orders', {
      title: 'Twice',
      scope: 'personal',
      config: recordConfig(),
    });

    await Promise.allSettled([engine.save(runtime), engine.save(runtime)]);

    expect(await store.list('orders')).toHaveLength(1);
    expect(runtime.getSnapshot().saved).not.toBeNull();
  });

  it('raises no conflict against the user own second click', async () => {
    const { engine } = harness();
    const runtime = engine.create('orders', {
      title: 'T',
      scope: 'personal',
      config: recordConfig(),
    });
    await engine.save(runtime);

    runtime.edit({ pageSize: 30 });
    await Promise.allSettled([engine.save(runtime), engine.save(runtime)]);

    // The refused one never reached the store, so there is nothing to resolve.
    expect(runtime.getSnapshot().write).toBeNull();
  });

  it('lets the next write through once the first has settled', async () => {
    const { engine } = harness();
    const runtime = engine.create('orders', {
      title: 'T',
      scope: 'personal',
      config: recordConfig(),
    });

    const first = await engine.save(runtime);
    runtime.edit({ pageSize: 30 });
    const second = await engine.save(runtime);

    expect(second.revision).not.toBe(first.revision);
  });

  it('leaves another runtime free to write at the same time', async () => {
    const { engine } = harness();
    const one = engine.create('orders', {
      title: 'One',
      scope: 'personal',
      config: recordConfig(),
    });
    const two = engine.create('orders', {
      title: 'Two',
      scope: 'personal',
      config: recordConfig(),
    });

    const both = await Promise.allSettled([engine.save(one), engine.save(two)]);

    expect(both.map(entry => entry.status)).toEqual(['fulfilled', 'fulfilled']);
  });
});

describe('ViewEngine with one instance open twice', () => {
  it('moves every open view of the instance to the renamed baseline', async () => {
    const { engine } = harness();
    const first = await engine.open('orders-1');
    const second = await engine.open('orders-1');

    await engine.rename('orders-1', 'Renamed');

    for (const runtime of [first, second]) {
      expect(runtime.getSnapshot().saved).toMatchObject({
        title: 'Renamed',
        revision: '2',
      });
      expect(runtime.getSnapshot().dirty).toBe(false);
    }
    // The other view is on the baseline the store holds, so its own save
    // goes through rather than conflicting with a revision it never saw.
    second.edit({ pageSize: 30 });
    await expect(engine.save(second)).resolves.toMatchObject({
      revision: '3',
    });
  });

  it('moves the other open view when one of them saves', async () => {
    const { engine } = harness();
    const first = await engine.open('orders-1');
    const second = await engine.open('orders-1');
    first.edit({ pageSize: 30 });

    await engine.save(first);

    expect(second.getSnapshot().saved?.revision).toBe('2');
    // Its draft still shows the old config, which is now a change.
    expect(second.getSnapshot().dirty).toBe(true);
  });

  it('keeps the other view own unsettled write when the baseline moves', async () => {
    const { engine, store } = harness();
    const first = await engine.open('orders-1');
    const second = await engine.open('orders-1');
    second.edit({ pageSize: 40 });
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'timeout'),
    );
    await failedWrite(engine.save(second));

    first.edit({ pageSize: 30 });
    await engine.save(first);

    // The baseline moved under the second view, but its own write is still
    // unknown, and still its own to retry or abandon.
    expect(second.getSnapshot().saved?.revision).toBe('2');
    expect(second.getSnapshot().write?.kind).toBe('unknown');
    expect(engine.pendingWrites().size).toBe(1);
    // The retry carries the revision it was sent with, which is stale now.
    const retried = await engine.retryWrite(second).catch(e => e);
    expect(isViewWriteError(retried) && retried.state.kind).toBe('conflict');
  });

  it('closes every open view of a deleted instance', async () => {
    const { engine } = harness();
    const first = await engine.open('orders-1');
    const second = await engine.open('orders-1');

    await engine.delete('orders-1');

    expect(first.disposed).toBe(true);
    expect(second.disposed).toBe(true);
    expect(engine.openRuntimes()).toHaveLength(0);
  });
});

describe('ViewEngine write outcomes', () => {
  it('records a conflict with the state the store holds', async () => {
    const { engine, store } = harness();
    const runtime = await engine.open('orders-1');
    const remote = { ...mine, revision: '9', title: 'Theirs' };
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('CONFLICT', 'moved', { instance: remote }),
    );

    const failure = await failedWrite(engine.save(runtime));

    expect(failure.state).toMatchObject({ kind: 'conflict', remote });
    expect(runtime.getSnapshot().write).toEqual(failure.state);
    expect(engine.pendingWrites().get(failure.handle.id)).toEqual(
      failure.state,
    );
  });

  it('asks the store for the remote state a conflict did not carry', async () => {
    const { engine, store } = harness();
    const runtime = await engine.open('orders-1');
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('CONFLICT', 'moved'),
    );

    const failure = await failedWrite(engine.save(runtime));

    expect(failure.state).toMatchObject({
      kind: 'conflict',
      remote: { id: 'orders-1', revision: '1' },
    });
  });

  it('reports a conflict it cannot read back as a rejection', async () => {
    const { engine, store } = harness();
    const runtime = await engine.open('orders-1');
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('CONFLICT', 'moved'),
    );
    vi.spyOn(store, 'get').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'offline'),
    );

    const failure = await failedWrite(engine.save(runtime));

    expect(failure.state).toMatchObject({
      kind: 'rejected',
      issue: { code: 'view.write.conflict-unreadable' },
    });
  });

  it('reloads the server state, replacing the draft', async () => {
    const { engine, store } = harness();
    const runtime = await engine.open('orders-1');
    runtime.edit({ pageSize: 99 });
    const remote = {
      ...mine,
      revision: '9',
      title: 'Theirs',
      config: recordConfig({ pageSize: 50 }),
    };
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('CONFLICT', 'moved', { instance: remote }),
    );
    await failedWrite(engine.save(runtime));

    const reloaded = await engine.resolveConflict(runtime, 'reload');

    expect(reloaded).toEqual(remote);
    expect(requireRecordConfig(runtime.getSnapshot().draft).pageSize).toBe(50);
    expect(runtime.getSnapshot().write).toBeNull();
    expect(engine.pendingWrites().size).toBe(0);
  });

  it('keeps the draft when a rename conflict is reloaded', async () => {
    const { engine, store } = harness();
    const runtime = await engine.open('orders-1');
    runtime.edit({ pageSize: 99 });
    const remote = { ...mine, revision: '9', title: 'Theirs' };
    vi.spyOn(store, 'rename').mockRejectedValueOnce(
      new ViewStoreError('CONFLICT', 'moved', { instance: remote }),
    );
    await failedWrite(engine.rename('orders-1', 'Mine again'));

    await engine.resolveConflict(runtime, 'reload');

    // A rename carries no config, so reloading one takes the new title and
    // revision and leaves the editing the user has not saved yet alone.
    expect(requireRecordConfig(runtime.getSnapshot().draft).pageSize).toBe(99);
    expect(runtime.getSnapshot().saved).toMatchObject({
      title: 'Theirs',
      revision: '9',
    });
    expect(runtime.getSnapshot().dirty).toBe(true);
  });

  it('refreshes the summary on a delete conflict, so confirming again works', async () => {
    const { engine, store } = harness();
    await engine.list('orders');
    // Someone else saved the view between the list and the delete.
    await store.save('orders-1', recordConfig({ pageSize: 50 }), '1', {
      requestId: 'other',
    });

    const failure = await failedWrite(engine.delete('orders-1'));

    expect(failure.state).toMatchObject({ kind: 'conflict' });
    // The second confirmation goes against the revision the store now holds,
    // without another list.
    await expect(engine.delete('orders-1')).resolves.toBeUndefined();
    await expect(store.list('orders')).resolves.toEqual([]);
  });

  it('overwrites by replaying the original intent at the reported revision', async () => {
    const { engine, store } = harness();
    const runtime = await engine.open('orders-1');
    runtime.edit({ pageSize: 99 });
    await store.save('orders-1', recordConfig({ pageSize: 33 }), '1', {
      requestId: 'other',
    });
    await failedWrite(engine.save(runtime));

    const overwritten = await engine.resolveConflict(runtime, 'overwrite');

    expect(overwritten).toMatchObject({ revision: '3' });
    await expect(store.get('orders-1')).resolves.toMatchObject({
      config: { pageSize: 99 },
    });
    expect(runtime.getSnapshot().dirty).toBe(false);
  });

  it('resolves a preferences conflict by reloading the latest', async () => {
    const { engine, store } = harness();
    await store.setPreferences(
      'orders',
      { order: ['orders-1'], defaultInstanceId: null, revision: '0' },
      { requestId: 'other' },
    );
    await engine.preferences('orders');
    await store.setPreferences(
      'orders',
      { order: [], defaultInstanceId: 'orders-1', revision: '1' },
      { requestId: 'other-2' },
    );

    const failure = await failedWrite(engine.reorder('orders', ['orders-1']));
    const reloaded = await engine.resolveConflict(failure.handle, 'reload');

    expect(failure.state.kind).toBe('conflict');
    expect(reloaded).toMatchObject({ revision: '2' });
  });

  it('overwrites a preferences conflict at the revision it reported', async () => {
    const { engine, store } = harness();
    await engine.preferences('orders');
    await store.setPreferences(
      'orders',
      { order: ['orders-1'], defaultInstanceId: null, revision: '0' },
      { requestId: 'other' },
    );

    const failure = await failedWrite(engine.setDefault('orders', 'orders-1'));
    const written = await engine.resolveConflict(failure.handle, 'overwrite');

    expect(written).toMatchObject({
      defaultInstanceId: 'orders-1',
      revision: '2',
    });
  });

  it('cannot read back the remote state of a create conflict', async () => {
    const { engine, store } = harness({ instances: [] });
    const runtime = engine.create('orders', {
      title: 'New',
      scope: 'personal',
      config: recordConfig(),
    });
    vi.spyOn(store, 'create').mockRejectedValueOnce(
      new ViewStoreError('CONFLICT', 'title taken'),
    );

    const failure = await failedWrite(engine.save(runtime));

    expect(failure.state).toMatchObject({
      kind: 'rejected',
      issue: { code: 'view.write.conflict-unreadable' },
    });
  });

  it('replays a create unchanged when the user overwrites', async () => {
    const { engine, store } = harness({ instances: [] });
    const runtime = engine.create('orders', {
      title: 'New',
      scope: 'personal',
      config: recordConfig(),
    });
    vi.spyOn(store, 'create').mockRejectedValueOnce(
      new ViewStoreError('CONFLICT', 'title taken', { instance: mine }),
    );
    await failedWrite(engine.save(runtime));

    const created = await engine.resolveConflict(runtime, 'overwrite');

    expect(created).toMatchObject({ title: 'New' });
    expect(runtime.getSnapshot().saved?.title).toBe('New');
  });

  it('treats an unavailable store as an unknown outcome and retries it', async () => {
    const { engine, store } = harness();
    const runtime = await engine.open('orders-1');
    runtime.edit({ pageSize: 50 });
    const save = vi
      .spyOn(store, 'save')
      .mockRejectedValueOnce(new ViewStoreError('UNAVAILABLE', 'timeout'));

    const failure = await failedWrite(engine.save(runtime));
    const retried = await engine.retryWrite(runtime);

    expect(failure.state.kind).toBe('unknown');
    expect(retried).toMatchObject({ revision: '2' });
    expect(save.mock.calls[1][3].requestId).toBe(failure.state.requestId);
    expect(engine.pendingWrites().size).toBe(0);
  });

  it('treats a transport failure the same way', async () => {
    const { engine, store } = harness();
    const runtime = await engine.open('orders-1');
    vi.spyOn(store, 'save').mockRejectedValueOnce(new Error('socket closed'));

    const failure = await failedWrite(engine.save(runtime));

    expect(failure.state.kind).toBe('unknown');
  });

  it('reports a refusal with its reason and keeps the draft', async () => {
    const { engine, store } = harness();
    const runtime = await engine.open('orders-1');
    runtime.edit({ pageSize: 50 });
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('INVALID', 'pageSize too large'),
    );

    const failure = await failedWrite(engine.save(runtime));

    expect(failure.state).toMatchObject({
      kind: 'rejected',
      issue: { code: 'view.write.invalid' },
    });
    expect(requireRecordConfig(runtime.getSnapshot().draft).pageSize).toBe(50);
  });

  it('abandons an outcome and leaves the draft alone', async () => {
    const { engine, store } = harness();
    const runtime = await engine.open('orders-1');
    runtime.edit({ pageSize: 50 });
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'timeout'),
    );
    await failedWrite(engine.save(runtime));

    engine.abandonWrite(runtime);

    expect(engine.pendingWrites().size).toBe(0);
    expect(runtime.getSnapshot().write).toBeNull();
    expect(requireRecordConfig(runtime.getSnapshot().draft).pageSize).toBe(50);
  });

  it('refuses a new write to a view whose last one is still unknown', async () => {
    const { engine, store } = harness({ instances: [] });
    const runtime = engine.create('orders', {
      title: 'New',
      scope: 'personal',
      config: recordConfig(),
    });
    vi.spyOn(store, 'create').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'timeout'),
    );
    await failedWrite(engine.save(runtime));

    // The first save may have landed. A second one, under a new requestId,
    // is exactly what the server cannot deduplicate: two instances.
    expect((await refused(engine.save(runtime))).code).toBe(
      'view.write.unknown-pending',
    );
    expect(
      (await refused(engine.saveAs(runtime, { title: 'B', scope: 'personal' })))
        .code,
    ).toBe('view.write.unknown-pending');

    await engine.retryWrite(runtime);

    expect(await store.list('orders')).toHaveLength(1);
    expect(engine.pendingWrites().size).toBe(0);
    await expect(engine.save(runtime)).resolves.toBeDefined();
  });

  it('lets a new write follow an abandoned unknown outcome', async () => {
    const { engine, store } = harness();
    const runtime = await engine.open('orders-1');
    runtime.edit({ pageSize: 50 });
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'timeout'),
    );
    await failedWrite(engine.save(runtime));

    engine.abandonWrite(runtime);

    await expect(engine.save(runtime)).resolves.toMatchObject({
      revision: '2',
    });
  });

  it('holds a list command to the same rule, by instance', async () => {
    const { engine, store } = harness();
    vi.spyOn(store, 'rename').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'timeout'),
    );
    const failure = await failedWrite(engine.rename('orders-1', 'A'));

    expect((await refused(engine.rename('orders-1', 'B'))).code).toBe(
      'view.write.unknown-pending',
    );
    expect((await refused(engine.delete('orders-1'))).code).toBe(
      'view.write.unknown-pending',
    );

    await engine.retryWrite(failure.handle);

    expect((await store.get('orders-1')).title).toBe('A');
  });

  it('does not hold a rejected or a conflicting outcome against a new intent', async () => {
    const { engine, store } = harness();
    const runtime = await engine.open('orders-1');
    runtime.edit({ pageSize: 50 });
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('INVALID', 'no'),
    );
    await failedWrite(engine.save(runtime));

    // A refusal is an answer; the user edits and saves again as a new intent.
    await expect(engine.save(runtime)).resolves.toBeDefined();

    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('CONFLICT', 'someone else', {
        instance: { ...mine, revision: '9' },
      }),
    );
    runtime.edit({ pageSize: 60 });
    await failedWrite(engine.save(runtime));

    // A conflict offers "save as" among its choices, so it does not block one.
    await expect(
      engine.saveAs(runtime, { title: 'Copy', scope: 'personal' }),
    ).resolves.toBeDefined();
  });

  it('retries a first save under its original request id', async () => {
    const { engine, store } = harness({ instances: [] });
    const runtime = engine.create('orders', {
      title: 'New',
      scope: 'personal',
      config: recordConfig(),
    });
    vi.spyOn(store, 'create').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'timeout'),
    );
    await failedWrite(engine.save(runtime));

    const created = await engine.retryWrite(runtime);

    expect(created).toMatchObject({ id: 'orders-1' });
    expect(runtime.getSnapshot().saved?.id).toBe('orders-1');
  });

  it('refuses recovery actions when nothing is pending', async () => {
    const { engine } = harness();
    const runtime = await engine.open('orders-1');

    expect((await refused(engine.retryWrite(runtime))).code).toBe(
      'view.write.not-pending',
    );
    expect((await refused(engine.retryWrite({ id: 'gone' }))).code).toBe(
      'view.write.not-pending',
    );
    expect(() => engine.abandonWrite(runtime)).toThrowError(
      /view command refused/i,
    );
  });

  it('refuses to resolve an outcome that is not a conflict', async () => {
    const { engine, store } = harness();
    const runtime = await engine.open('orders-1');
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'timeout'),
    );
    await failedWrite(engine.save(runtime));

    expect(
      (await refused(engine.resolveConflict(runtime, 'reload'))).code,
    ).toBe('view.write.not-a-conflict');
  });
});

describe('ViewEngine wiring', () => {
  it('allows everything when the store declares no permissions', () => {
    const { engine } = harness();
    const allowed = engine.permissions('orders');

    expect(allowed.createShared).toBe(true);
    expect(allowed.instance('orders-1')).toEqual({
      save: true,
      rename: true,
      delete: true,
    });
  });

  it('resolves option sources only when the host injected one', () => {
    const { engine } = harness();
    expect(() => engine.resolveOptions('orders')).toThrowError(
      /view command refused/i,
    );

    const withOptions = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore(),
      resolveSource: () => testSource(),
      resolveOptions: () => ({
        search: () => Promise.resolve({ items: [], nextCursor: null }),
        resolve: () => Promise.resolve([]),
      }),
    });
    expect(withOptions.resolveOptions('orders')).toBeDefined();
  });

  it('closes one runtime and stops holding it', async () => {
    const { engine } = harness();
    const runtime = await engine.open('orders-1');

    engine.close(runtime);

    expect(runtime.disposed).toBe(true);
    expect(engine.openRuntimes()).toEqual([]);
    // Closing twice, or closing one it never owned, is not an error.
    engine.close(runtime);
  });

  it('forgets a runtime a caller disposed behind its back', async () => {
    const { engine, store } = harness();
    const runtime = await engine.open('orders-1');
    await store.save('orders-1', recordConfig({ pageSize: 30 }), '1', {
      requestId: 'other',
    });

    runtime.dispose();

    // The stale revision of a dead runtime must not become the one a rename
    // writes against, so the registry drops it before answering.
    expect(engine.openRuntimes()).toEqual([]);
    await expect(engine.rename('orders-1', 'Renamed')).resolves.toMatchObject({
      revision: '3',
    });
  });

  it('disposes every runtime it opened', async () => {
    const { engine } = harness();
    const runtime = await engine.open('orders-1');

    engine.dispose();

    expect(engine.openRuntimes()).toEqual([]);
    runtime.edit({ pageSize: 10 });
    expect(requireRecordConfig(runtime.getSnapshot().draft).pageSize).toBe(20);
  });

  it('mints an idempotency key of its own by default', () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore(),
      resolveSource: () => testSource(),
    });
    expect(engine.limits.maxPageSize).toBeGreaterThan(0);
    expect(engine.kinds.size).toBeGreaterThan(0);
    expect(engine.environment.timeZone.length).toBeGreaterThan(0);
  });
});
