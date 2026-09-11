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

import { beforeEach, expect, it, vi } from 'vitest';
import { MemoryViewHost } from '../src/record/MemoryViewHost.js';
import type { ViewInstance } from '../src/contracts/viewModel.js';
import { definition, instance } from './engine/fixtures.js';

const store = new Map<string, string | null>();
beforeEach(() => store.clear());
function options() {
  return {
    serviceKey: 'boundary-service',
    scopeKey: 'alice',
    definition,
    instances: { instances: [instance()], defaultInstanceId: 'mine' },
    store,
    resolveSource: vi.fn(() => ({
      paged: async () => ({ list: [], total: 0 }),
    })),
  };
}

it('rejects an unusable service identity or seed default before writing storage', () => {
  const seed = options();
  for (const patch of [
    { scopeKey: '' },
    { serviceKey: ' ' },
    { instances: { ...seed.instances, defaultInstanceId: 'missing' } },
  ]) {
    expect(() => new MemoryViewHost({ ...seed, ...patch })).toThrow(
      expect.objectContaining({ code: 'INVALID_ARGUMENT' }),
    );
    expect(store.size).toBe(0);
  }
  expect(() => new MemoryViewHost(seed)).not.toThrow();
});

it('routes only the configured record source without invoking an unrelated resolver', () => {
  const seed = options();
  const host = new MemoryViewHost(seed);
  expect(() => host.resolveSource('another-source')).toThrow(
    expect.objectContaining({ code: 'NOT_FOUND' }),
  );
  expect(seed.resolveSource).not.toHaveBeenCalled();
  expect(host.resolveSource(definition.sourceId)).toHaveProperty('paged');
  expect(seed.resolveSource).toHaveBeenCalledWith(definition.sourceId);
});

it('rejects a missing create request identity without inserting a view or receipt', async () => {
  const host = new MemoryViewHost(options());
  const before = await host.instance.list(definition.id);
  const raw = store.get(host.storageKey);
  for (const requestId of ['', ' ']) {
    await expect(
      host.instance.create(instance(), { requestId }),
    ).rejects.toMatchObject({ code: 'INVALID_ARGUMENT' });
    expect(store.get(host.storageKey)).toBe(raw);
  }
  const created = await host.instance.create(instance(), {
    requestId: 'valid',
  });
  expect(
    (await host.instance.list(definition.id)).instances.map(item => item.id),
  ).toEqual([...before.instances.map(item => item.id), created.id]);
});

it('enforces creation permissions at dispatch and permits the same request after access is granted', async () => {
  let allowed = false;
  const host = new MemoryViewHost({
    ...options(),
    instancePermissions: () => ({
      saveAsPersonal: allowed,
      saveAsShared: allowed,
    }),
  });
  await host.instance.list(definition.id);
  const before = store.get(host.storageKey);
  for (const scope of [
    { type: 'personal' },
    { type: 'public', source: 'shared' },
  ] as const) {
    await expect(
      host.instance.create({ ...instance(), scope }, { requestId: scope.type }),
    ).rejects.toMatchObject({ code: 'FORBIDDEN' });
    expect(store.get(host.storageKey)).toBe(before);
  }
  allowed = true;
  const created = await host.instance.create(instance(), {
    requestId: 'personal',
  });
  expect(await host.instance.load(created.id)).toEqual(created);
});

it('rejects visibility changes and invalid renames without consuming the current revision', async () => {
  const host = new MemoryViewHost(options());
  const saved = await host.instance.load('mine');
  const raw = store.get(host.storageKey);
  await expect(
    host.instance.save({
      ...saved,
      scope: { type: 'public', source: 'shared' },
    }),
  ).rejects.toMatchObject({ code: 'INVALID_ARGUMENT' });
  await expect(
    host.instance.rename(saved.id, ' ', saved.revision),
  ).rejects.toMatchObject({ code: 'INVALID_ARGUMENT' });
  expect(store.get(host.storageKey)).toBe(raw);
  const renamed = await host.instance.rename(
    saved.id,
    'Valid name',
    saved.revision,
  );
  expect(renamed.title).toBe('Valid name');
  expect(renamed.revision).not.toBe(saved.revision);
});

it('rejects stale ordering membership and accepts a refreshed complete order', async () => {
  const host = new MemoryViewHost(options());
  const first = await host.instance.list(definition.id);
  const created = await host.instance.create(instance(), {
    requestId: 'new-view',
  });
  const raw = store.get(host.storageKey);
  for (const stale of [
    first.instances.map(item => item.id),
    ['mine', 'missing'],
  ]) {
    await expect(
      host.preference.saveOrder(definition.id, stale),
    ).rejects.toMatchObject({ code: 'CONFLICT' });
    expect(store.get(host.storageKey)).toBe(raw);
  }
  await host.preference.saveOrder(definition.id, [created.id, 'mine']);
  expect(
    (await host.instance.list(definition.id)).instances.map(item => item.id),
  ).toEqual([created.id, 'mine']);
});

it('reports unavailable reads without rewriting storage and succeeds after the adapter recovers', async () => {
  const seed = options();
  const original = new MemoryViewHost(seed);
  const saved = await original.instance.load('mine');
  const raw = store.get(original.storageKey);
  vi.spyOn(store, 'get').mockImplementationOnce(() => {
    throw new Error('storage offline');
  });
  const set = vi.spyOn(store, 'set');
  const host = new MemoryViewHost(seed);
  await expect(host.instance.load('mine')).rejects.toMatchObject({
    code: 'UNAVAILABLE',
    message: 'storage offline',
  });
  expect(set).not.toHaveBeenCalled();
  expect(store.get(host.storageKey)).toBe(raw);
  expect(await host.instance.load('mine')).toEqual(saved);
  expect(set).not.toHaveBeenCalled();
});

it.each([
  'invalid instance collection',
  'invalid user dictionary',
  'invalid create dictionary',
  'missing revision',
  'wrong private owner',
  'wrong public owner',
  'duplicate user order',
  'invalid default type',
  'mismatched receipt',
  'visible ID collision',
])(
  'preserves corrupt storage with %s and recovers when valid persisted state is restored',
  async problem => {
    const host = new MemoryViewHost(options());
    const created = await host.instance.create(instance(), {
      requestId: 'receipt',
    });
    const valid = store.get(host.storageKey)!;
    const state = JSON.parse(valid);
    const mine = state.instances.find(
      (item: ViewInstance) => item.id === 'mine',
    );
    const key = JSON.stringify(['alice', 'receipt']);
    const invalid = {
      'invalid instance collection': { ...state, instances: {} },
      'invalid user dictionary': { ...state, users: [] },
      'invalid create dictionary': { ...state, creates: [] },
      'missing revision': { ...state, instances: [{ ...mine, revision: '' }] },
      'wrong private owner': {
        ...state,
        instances: [{ ...mine, ownerKey: null }],
      },
      'wrong public owner': {
        ...state,
        instances: [{ ...mine, scope: { type: 'public', source: 'shared' } }],
      },
      'duplicate user order': {
        ...state,
        users: {
          alice: { order: ['mine', 'mine'], defaultInstanceId: 'mine' },
        },
      },
      'invalid default type': {
        ...state,
        users: { alice: { order: ['mine'], defaultInstanceId: 1 } },
      },
      'mismatched receipt': {
        ...state,
        creates: {
          [key]: {
            ...state.creates[key],
            result: { ...created, title: 'Different content' },
          },
        },
      },
      'visible ID collision': {
        ...state,
        instances: [
          ...state.instances,
          {
            ...mine,
            ownerKey: null,
            scope: { type: 'public', source: 'shared' },
          },
        ],
      },
    }[problem];
    const corrupt = JSON.stringify(invalid);
    store.set(host.storageKey, corrupt);
    await expect(host.instance.list(definition.id)).rejects.toMatchObject({
      code: 'CORRUPT_STATE',
    });
    expect(store.get(host.storageKey)).toBe(corrupt);
    store.set(host.storageKey, valid);
    expect(await host.instance.load(created.id)).toEqual(created);
    expect(
      await host.instance.create(instance(), { requestId: 'receipt' }),
    ).toEqual(created);
    expect((await host.instance.list(definition.id)).instances).toHaveLength(2);
  },
);
