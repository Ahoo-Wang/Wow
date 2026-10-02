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
import {
  emptyDashboardConfig,
  emptyPreferences,
  isViewStoreError,
  MemoryViewStore,
  toSummary,
  ViewStoreError,
  type MemoryState,
  type ViewInstance,
} from '../src/index.js';
import { recordConfig } from './fixtures.js';

function instance(overrides: Partial<ViewInstance> = {}): ViewInstance {
  return {
    id: 'orders-1',
    definitionId: 'orders',
    title: 'Mine',
    scope: 'personal',
    revision: '1',
    config: recordConfig(),
    ...overrides,
  };
}

const ctx = { requestId: 'req-1' };

describe('MemoryViewStore', () => {
  it('lists only the definition asked for, without configs', async () => {
    const store = new MemoryViewStore({
      instances: [instance(), instance({ id: 'other-1', definitionId: 'x' })],
    });

    const summaries = await store.list('orders');

    expect(summaries).toEqual([toSummary(instance())]);
    expect(summaries[0]).not.toHaveProperty('config');
  });

  it('reports a missing instance as NOT_FOUND', async () => {
    const store = new MemoryViewStore();
    await expect(store.get('nope')).rejects.toMatchObject({
      code: 'NOT_FOUND',
    });
  });

  it('creates with a fresh id and revision 1', async () => {
    const store = new MemoryViewStore();

    const created = await store.create(
      {
        definitionId: 'orders',
        title: 'Mine',
        scope: 'personal',
        config: recordConfig(),
      },
      ctx,
    );

    expect(created).toMatchObject({ id: 'orders-1', revision: '1' });
    await expect(store.get('orders-1')).resolves.toEqual(created);
  });

  it('never issues an id a seeded instance already holds', async () => {
    const store = new MemoryViewStore({ instances: [instance()] });

    const created = await store.create(
      {
        definitionId: 'orders',
        title: 'Second',
        scope: 'personal',
        config: recordConfig(),
      },
      ctx,
    );

    expect(created.id).toBe('orders-2');
    await expect(store.list('orders')).resolves.toHaveLength(2);
  });

  it('creates a system view as one it keeps, and keeps the flag its own (D81)', async () => {
    const store = new MemoryViewStore();
    const made = await store.create(
      {
        definitionId: 'orders',
        title: 'Base',
        scope: 'system',
        config: recordConfig(),
      },
      ctx,
    );
    expect(made).toMatchObject({ scope: 'system', stored: true });
    expect((await store.list('orders'))[0]).toMatchObject({
      id: made.id,
      stored: true,
    });

    // A caller cannot make a personal or shared view "stored".
    const mine = await store.create(
      {
        definitionId: 'orders',
        title: 'Mine',
        scope: 'personal',
        config: recordConfig(),
        stored: true,
      },
      { requestId: 'r-mine' },
    );
    expect(mine).not.toHaveProperty('stored');
  });

  it('refuses to issue an id in the reserved namespace', async () => {
    const store = new MemoryViewStore();
    await expect(
      store.create(
        {
          definitionId: 'system:orders',
          title: 'Mine',
          scope: 'personal',
          config: recordConfig(),
        },
        ctx,
      ),
    ).rejects.toMatchObject({ code: 'INVALID' });
  });

  it('replays a create under the same requestId instead of writing twice', async () => {
    const store = new MemoryViewStore();
    const input = {
      definitionId: 'orders',
      title: 'Mine',
      scope: 'personal' as const,
      config: recordConfig(),
    };

    const first = await store.create(input, ctx);
    const retry = await store.create(input, ctx);

    expect(retry).toEqual(first);
    await expect(store.list('orders')).resolves.toHaveLength(1);
  });

  it('advances the revision on save and rename', async () => {
    const store = new MemoryViewStore({ instances: [instance()] });

    const saved = await store.save(
      'orders-1',
      recordConfig({ pageSize: 50 }),
      '1',
      { requestId: 'a' },
    );
    const renamed = await store.rename('orders-1', 'Renamed', '2', {
      requestId: 'b',
    });

    expect(saved.revision).toBe('2');
    expect(renamed).toMatchObject({ revision: '3', title: 'Renamed' });
    expect(renamed.config).toEqual(recordConfig({ pageSize: 50 }));
  });

  it('moves on from a revision it did not count, and still conflicts against it', async () => {
    const store = new MemoryViewStore({
      instances: [instance({ revision: 'ops-1' })],
      preferences: {
        orders: { order: [], defaultInstanceId: null, revision: 'seeded' },
      },
    });

    const saved = await store.save('orders-1', recordConfig(), 'ops-1', {
      requestId: 'a',
    });
    const renamed = await store.rename('orders-1', 'Two', saved.revision, {
      requestId: 'b',
    });

    expect(saved.revision).not.toBe('ops-1');
    expect(renamed.revision).not.toBe(saved.revision);
    for (const stale of ['ops-1', saved.revision]) {
      const failure = await store
        .rename('orders-1', 'Late', stale, { requestId: `late-${stale}` })
        .catch((error: unknown) => error);
      expect(failure).toMatchObject({ code: 'CONFLICT' });
    }
    const preferences = await store.setPreferences(
      'orders',
      { order: ['orders-1'], defaultInstanceId: null, revision: 'seeded' },
      { requestId: 'c' },
    );
    expect(preferences.revision).not.toBe('seeded');
    expect(preferences.revision).not.toBe('NaN');
  });

  it('replays a save under the same requestId without advancing again', async () => {
    const store = new MemoryViewStore({ instances: [instance()] });
    const config = recordConfig({ pageSize: 50 });

    const first = await store.save('orders-1', config, '1', ctx);
    const retry = await store.save('orders-1', config, '1', ctx);

    expect(retry).toEqual(first);
    expect(retry.revision).toBe('2');
  });

  it('rejects a stale revision with the state it holds', async () => {
    const store = new MemoryViewStore({ instances: [instance()] });

    const failure = await store
      .save('orders-1', recordConfig(), 'stale', ctx)
      .catch((error: unknown) => error);

    expect(isViewStoreError(failure)).toBe(true);
    expect(failure).toMatchObject({
      code: 'CONFLICT',
      instance: { revision: '1' },
    });
    // An instance write fills in the instance member and leaves the other
    // one alone, so nothing downstream has to tell the two apart by shape.
    expect((failure as ViewStoreError).preferences).toBeUndefined();
  });

  it('refuses to write a system view', async () => {
    const store = new MemoryViewStore({
      instances: [instance({ scope: 'system' })],
    });
    await expect(
      store.rename('orders-1', 'Nope', '1', ctx),
    ).rejects.toMatchObject({ code: 'FORBIDDEN' });
  });

  it('reports a write to a missing instance', async () => {
    const store = new MemoryViewStore();
    await expect(
      store.save('gone', recordConfig(), '1', ctx),
    ).rejects.toMatchObject({ code: 'NOT_FOUND' });
    await expect(store.delete('gone', '1', ctx)).rejects.toMatchObject({
      code: 'NOT_FOUND',
    });
  });

  it('deletes once and treats the retry as done', async () => {
    const store = new MemoryViewStore({ instances: [instance()] });

    await store.delete('orders-1', '1', ctx);
    await store.delete('orders-1', '1', ctx);

    await expect(store.list('orders')).resolves.toEqual([]);
  });

  it('refuses to delete at a stale revision', async () => {
    const store = new MemoryViewStore({ instances: [instance()] });
    await expect(store.delete('orders-1', 'stale', ctx)).rejects.toMatchObject({
      code: 'CONFLICT',
    });
  });

  it('starts from empty preferences and advances their revision', async () => {
    const store = new MemoryViewStore();

    const initial = await store.getPreferences('orders');
    const written = await store.setPreferences(
      'orders',
      { ...initial, order: ['orders-1'] },
      ctx,
    );

    expect(initial).toEqual(emptyPreferences());
    expect(written).toEqual({
      order: ['orders-1'],
      defaultInstanceId: null,
      revision: '1',
    });
  });

  it('rejects preferences written against a stale revision', async () => {
    const store = new MemoryViewStore();
    await store.setPreferences('orders', emptyPreferences(), ctx);

    const failure = await store
      .setPreferences('orders', emptyPreferences(), { requestId: 'req-2' })
      .catch((error: unknown) => error);

    // A preference write reports the preferences it conflicted with, and
    // never the instance member — that is the other half of the split.
    expect(failure).toMatchObject({
      code: 'CONFLICT',
      preferences: { revision: '1' },
    });
    expect((failure as ViewStoreError).instance).toBeUndefined();
  });

  it('replays preferences under the same requestId instead of conflicting', async () => {
    const store = new MemoryViewStore();

    // The answer to the first attempt was lost, so the caller sends the same
    // logical write again; it is not a second writer to conflict with.
    const first = await store.setPreferences('orders', emptyPreferences(), ctx);
    const replayed = await store.setPreferences(
      'orders',
      emptyPreferences(),
      ctx,
    );

    expect(replayed).toEqual(first);
    await expect(store.getPreferences('orders')).resolves.toMatchObject({
      revision: '1',
    });
  });

  it('refuses to delete a system view', async () => {
    const store = new MemoryViewStore({
      instances: [instance({ scope: 'system' })],
    });

    await expect(store.delete('orders-1', '1', ctx)).rejects.toMatchObject({
      code: 'FORBIDDEN',
    });
    await expect(store.list('orders')).resolves.toHaveLength(1);
  });

  it('hands out copies, so a caller cannot edit what is stored', async () => {
    const store = new MemoryViewStore();
    const config = recordConfig();
    const created = await store.create(
      { definitionId: 'orders', title: 'Mine', scope: 'personal', config },
      ctx,
    );

    // Both the config that went in and the instance that came back.
    (config as { pageSize: number }).pageSize = 999;
    (created.config as { pageSize: number }).pageSize = 888;
    const read = await store.get('orders-1');
    (read.config as { pageSize: number }).pageSize = 777;

    await expect(store.get('orders-1')).resolves.toMatchObject({
      config: { pageSize: recordConfig().pageSize },
    });
  });

  it('answers permissions only when the caller declared them', () => {
    const open = new MemoryViewStore();
    const guarded = new MemoryViewStore({
      permissions: () => ({
        createPersonal: true,
        createShared: false,
        reorder: true,
        setDefault: true,
        instance: () => ({ save: false, rename: false, delete: false }),
      }),
    });

    expect(open.permissions).toBeUndefined();
    expect(guarded.permissions?.('orders').createShared).toBe(false);
  });

  it('restores from a snapshot and writes every change back to it', async () => {
    const saved: MemoryState[] = [];
    const snapshot = {
      load: (): MemoryState =>
        saved[saved.length - 1] ?? {
          instances: [instance()],
          preferences: { orders: emptyPreferences() },
        },
      save: vi.fn((state: MemoryState) => saved.push(state)),
    };

    const store = new MemoryViewStore({ snapshot });
    await store.rename('orders-1', 'From disk', '1', ctx);

    expect(snapshot.save).toHaveBeenCalledTimes(1);
    expect(saved[0].instances[0].title).toBe('From disk');
    expect(saved[0].preferences.orders).toEqual(emptyPreferences());
  });

  it('recognises a store error raised by a second copy of the package', () => {
    expect(isViewStoreError(new ViewStoreError('INVALID', 'no'))).toBe(true);
    // A second copy's error is another class with the same name and code.
    const copied = Object.assign(new Error('moved'), {
      name: 'ViewStoreError',
      code: 'CONFLICT',
    });
    expect(isViewStoreError(copied)).toBe(true);
    // An HTTP library's error that happens to carry a code like one is not.
    expect(isViewStoreError({ code: 'NOT_FOUND' })).toBe(false);
    expect(isViewStoreError({ name: 'ViewStoreError', code: 'TEAPOT' })).toBe(
      false,
    );
    expect(isViewStoreError(null)).toBe(false);
  });

  it('keeps what a store mapped from: the cause and the backend code', () => {
    const cause = new Error('HTTP 400');
    const error = new ViewStoreError('INVALID', 'no app', {
      cause,
      detail: { code: 'ViewAppRequired' },
    });

    expect((error as { cause?: unknown }).cause).toBe(cause);
    expect(error.detail).toEqual({ code: 'ViewAppRequired' });
    expect(new ViewStoreError('INVALID', 'no')).not.toHaveProperty('cause');
  });
});

describe('MemoryViewStore.changeAudience reads boards as stored data', () => {
  it('finds a shared board by its panels, whatever else the stored configs hold', async () => {
    const view: ViewInstance = {
      id: 'v',
      definitionId: 'orders',
      title: 'V',
      scope: 'shared',
      revision: '1',
      config: recordConfig(),
    };
    const board = (id: string, panels: unknown): ViewInstance => ({
      id,
      definitionId: 'overview',
      title: id,
      scope: 'shared',
      revision: '1',
      config: { ...emptyDashboardConfig(), panels } as ViewInstance['config'],
    });
    const store = new MemoryViewStore({
      instances: [
        view,
        board('no-panels', 'not a list'),
        board('odd-panels', [null, 'x', { owned: { config: {} } }]),
        // A board that owns a copy is not showing the saved view.
        board('owned', [{ instanceId: 'v', owned: {} }]),
      ],
    });

    const moved = await store.changeAudience('v', 'personal', '1', {
      requestId: 'r',
    });

    expect(moved.scope).toBe('personal');
  });
});
