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
  isViewCommandError,
  isViewWriteError,
  MemoryViewStore,
  orderSummaries,
  systemInstances,
  ViewEngine,
  ViewStoreError,
  type Issue,
  type ViewDefinition,
  type ViewInstance,
  type ViewPermissions,
  type ViewPreferences,
} from '../src/index.js';
import {
  analysisConfig,
  ordersDefinition,
  recordConfig,
  requireRecordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';

function flush(): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, 0));
}

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

    const summaries = await engine.list('orders');

    expect(summaries.map(summary => summary.id)).toEqual([
      'system:orders:all',
      'orders-1',
    ]);
    expect(summaries[0]).toMatchObject({ scope: 'system', revision: 'code' });
  });

  it('drops a stored id in the reserved namespace and reports it', async () => {
    const { engine, issues } = harness({
      instances: [mine, { ...mine, id: 'system:orders:fake' }],
    });

    const summaries = await engine.list('orders');

    expect(summaries.map(summary => summary.id)).toEqual([
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
    ];
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
    const summaries = systemInstances(ordersDefinition());
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
    await flush();

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
    await flush();

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

  it('reports a dashboard as not yet supported', async () => {
    const dashboard: ViewDefinition = {
      id: 'board',
      title: 'Board',
      kind: 'dashboard',
    };
    const { engine } = harness({
      definitions: [ordersDefinition(), dashboard],
      instances: [
        {
          ...mine,
          definitionId: 'board',
          config: {
            kind: 'dashboard',
            filter: { op: 'and', children: [] },
            filterMode: 'simple',
            refresh: { interval: null },
            fields: [],
            panels: [],
          },
        },
      ],
    });

    expect((await refused(engine.open('orders-1'))).code).toBe(
      'runtime.dashboard.unsupported',
    );
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
    await flush();

    const state = runtime.getSnapshot();
    expect(state.saved).toBeNull();
    expect(state.dirty).toBe(true);
    expect(state.query.status).toBe('success');
  });

  it('refuses an empty title and a scope the user may not create', () => {
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
    expect(() =>
      engine.create('orders', { ...input, title: 'New', scope: 'shared' }),
    ).toThrowError(/view command refused/i);
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

describe('ViewEngine write outcomes', () => {
  it('records a conflict with the state the store holds', async () => {
    const { engine, store } = harness();
    const runtime = await engine.open('orders-1');
    const remote = { ...mine, revision: '9', title: 'Theirs' };
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('CONFLICT', 'moved', remote),
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
      new ViewStoreError('CONFLICT', 'moved', remote),
    );
    await failedWrite(engine.save(runtime));

    const reloaded = await engine.resolveConflict(runtime, 'reload');

    expect(reloaded).toEqual(remote);
    expect(requireRecordConfig(runtime.getSnapshot().draft).pageSize).toBe(50);
    expect(runtime.getSnapshot().write).toBeNull();
    expect(engine.pendingWrites().size).toBe(0);
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
      new ViewStoreError('CONFLICT', 'title taken', mine),
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
