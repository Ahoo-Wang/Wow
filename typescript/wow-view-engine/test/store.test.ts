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

  it('refuses to create a system view', async () => {
    const store = new MemoryViewStore();
    await expect(
      store.create(
        {
          definitionId: 'orders',
          title: 'Base',
          scope: 'system',
          config: recordConfig(),
        },
        ctx,
      ),
    ).rejects.toMatchObject({ code: 'INVALID' });
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
      remote: { revision: '1' },
    });
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

    await expect(
      store.setPreferences('orders', emptyPreferences(), ctx),
    ).rejects.toMatchObject({ code: 'CONFLICT' });
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
      load: () => ({
        instances: [instance()],
        preferences: { orders: emptyPreferences() },
      }),
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
    expect(isViewStoreError({ code: 'CONFLICT' })).toBe(true);
    expect(isViewStoreError({ code: 'TEAPOT' })).toBe(false);
    expect(isViewStoreError(null)).toBe(false);
  });
});
