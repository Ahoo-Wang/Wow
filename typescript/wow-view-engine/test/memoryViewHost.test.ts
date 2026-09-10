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

import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { MemoryViewHost } from '../src/record/MemoryViewHost.js';
import { ViewEngine } from '../src/record/ViewEngine.js';
import { definition, instance, setup } from './fixtures/viewPage.js';

const store = new Map<string, string | null>();
beforeEach(() => store.clear());
afterEach(() => vi.restoreAllMocks());
function options(scopeKey = 'developer') {
  return {
    scopeKey,
    store,
    serviceKey: 'test-service',
    definition,
    instances: {
      instances: [
        instance,
        {
          ...instance,
          id: 'system',
          title: '系统视图',
          scope: { type: 'public', source: 'system' } as const,
        },
      ],
      defaultInstanceId: instance.id,
    },
    resolveSource: setup().host.resolveSource,
  };
}

it('persists component configuration, names, creation, deletion and ordering across new hosts', async () => {
  const host = new MemoryViewHost(options());
  const edited = await host.instance!.load(instance.id);
  edited.config.filters.root.props = {
    ...edited.config.filters.root.props,
    value: 77,
  };
  const saved = await host.instance!.save(edited);
  expect(saved.revision).not.toBe(edited.revision);
  const copy = await host.instance!.create(
    { ...saved, title: '副本' },
    { requestId: 'copy' },
  );
  const renamed = await host.instance!.rename(
    copy.id,
    '本地副本',
    copy.revision,
  );
  await host.preference!.saveOrder(definition.id, [
    copy.id,
    'system',
    instance.id,
  ]);
  const restored = new MemoryViewHost(options());
  expect(
    (await restored.instance!.list(definition.id)).instances.map(
      item => item.id,
    ),
  ).toEqual([copy.id, 'system', instance.id]);
  expect(await restored.instance!.load(instance.id)).toEqual(saved);
  expect((await restored.instance!.load(copy.id)).title).toBe('本地副本');
  expect(
    JSON.parse(store.get(host.storageKey)!).instances[2].config,
  ).not.toHaveProperty('filter');
  await restored.instance!.delete(copy.id, renamed.revision);
  expect((await host.instance!.list(definition.id)).instances).toHaveLength(2);
});

it('isolates scope and definition, preserves seeds, and resets only its own key', async () => {
  const input = options();
  const host = new MemoryViewHost(input);
  input.instances.instances[0] = { ...instance, title: '外部修改' };
  const loaded = await host.instance!.load(instance.id);
  expect(loaded.title).toBe(instance.title);
  loaded.title = '保存的标题';
  await host.instance!.save(loaded);
  const other = new MemoryViewHost(options('another-user'));
  expect((await other.instance!.load(instance.id)).title).toBe(instance.title);
  const differentDefinition = new MemoryViewHost({
    ...options(),
    definition: { ...definition, id: 'other' },
    instances: { instances: [], defaultInstanceId: null },
  });
  expect(differentDefinition.storageKey).not.toBe(host.storageKey);
  store.set('unrelated', 'keep');
  await host.reset();
  expect((await host.instance!.load(instance.id)).title).toBe(instance.title);
  expect(store.get('unrelated')).toBe('keep');
});

it('rejects stale writes and protects system views even when callers bypass UI permissions', async () => {
  const host = new MemoryViewHost(options());
  const stale = await host.instance!.load(instance.id);
  await host.instance!.save({ ...stale, title: '最新版本' });
  await expect(host.instance!.save(stale)).rejects.toThrow(/重新加载/);
  await expect(
    host.instance!.rename(stale.id, '旧版本', stale.revision),
  ).rejects.toThrow(/重新加载/);
  await expect(host.instance!.delete(stale.id, stale.revision)).rejects.toThrow(
    /重新加载/,
  );
  const system = await host.instance!.load('system');
  expect(host.permission!.getInstance(system)).toMatchObject({
    save: false,
    rename: false,
    delete: false,
  });
  await expect(host.instance!.save(system)).rejects.toThrow(/系统/);
  await expect(
    host.instance!.rename('system', '改名', system.revision),
  ).rejects.toThrow(/系统/);
  await expect(
    host.instance!.delete('system', system.revision),
  ).rejects.toThrow(/系统/);
  await expect(
    host.instance!.create(system, { requestId: 'system' }),
  ).rejects.toThrow(/系统/);
  await expect(
    host.preference!.saveOrder(definition.id, [stale.id, stale.id]),
  ).rejects.toThrow();
});

it('reports corrupted data and storage failures without overwriting existing records', async () => {
  const host = new MemoryViewHost(options());
  store.set(host.storageKey, '{broken');
  await expect(host.instance!.list(definition.id)).rejects.toThrow();
  expect(store.get(host.storageKey)).toBe('{broken');
  await host.reset();
  const saved = await host.instance!.save(
    await host.instance!.load(instance.id),
  );
  const before = store.get(host.storageKey);
  vi.spyOn(store, 'set').mockImplementation(() => {
    throw new DOMException('full', 'QuotaExceededError');
  });
  await expect(
    host.instance!.save({ ...saved, title: '不会保存' }),
  ).rejects.toThrow('full');
  expect(store.get(host.storageKey)).toBe(before);
});

it('keeps the default selection valid after deletion and honors aborted reads', async () => {
  const host = new MemoryViewHost(options());
  await host.instance!.delete(
    instance.id,
    (await host.instance!.load(instance.id)).revision,
  );
  expect((await host.instance!.list(definition.id)).defaultInstanceId).toBe(
    'system',
  );
  const controller = new AbortController();
  controller.abort();
  await expect(
    host.definition!.load(definition.id, controller.signal),
  ).rejects.toThrow();
  await expect(host.instance!.list('unknown')).rejects.toThrow();
  await expect(host.instance!.load('unknown')).rejects.toThrow();
});

it('restores a saved view through a fresh engine while delegating record queries', async () => {
  const { host: source, paged } = setup();
  const input = { ...options(), resolveSource: source.resolveSource };
  const first = new ViewEngine({
    definitionId: definition.id,
    host: new MemoryViewHost(input),
  });
  await first.load();
  first.setTitle('持久化视图');
  await first.save();
  first.dispose();
  const second = new ViewEngine({
    definitionId: definition.id,
    host: new MemoryViewHost(input),
  });
  await second.load();
  expect(second.getSnapshot().sessions.mine.instance.title).toBe('持久化视图');
  expect(second.getSnapshot().sessions.mine.rows[0].amount).toBe(42);
  expect(paged).toHaveBeenCalledTimes(2);
  second.dispose();
});

it('keeps scoped deletion idempotent without touching another users instance', async () => {
  const alice = new MemoryViewHost(options('alice'));
  const bob = new MemoryViewHost(options('bob'));
  const own = await alice.instance.load(instance.id);
  const others = await bob.instance.load(instance.id);
  await alice.instance.delete(own.id, own.revision);
  await expect(
    alice.instance.delete(own.id, own.revision),
  ).resolves.toBeUndefined();
  expect(await bob.instance.load(instance.id)).toEqual(others);
  await expect(alice.instance.delete('system')).rejects.toMatchObject({
    code: 'FORBIDDEN',
  });
});

it('persists both layouts and restores each configuration after reloading', async () => {
  const host = new MemoryViewHost(options());
  const first = new ViewEngine({ definitionId: definition.id, host });
  try {
    await first.load();
    first.setLayout('card');
    first.setCardConfig({
      title: { id: 'title', field: 'amount' },
      fields: [],
      actions: { visible: false, renderer: { name: 'custom' } },
    });
    const savedPresentation =
      first.getSnapshot().sessions[instance.id].instance.config.presentation;
    await first.save();
    const second = new ViewEngine({
      definitionId: definition.id,
      host: new MemoryViewHost(options()),
    });
    try {
      await second.load();
      expect(
        second.getSnapshot().sessions[instance.id].instance.config.presentation,
      ).toEqual(savedPresentation);
      second.setLayout('table');
      second.setLayout('card');
      expect(
        second.getSnapshot().sessions[instance.id].instance.config.presentation,
      ).toEqual(savedPresentation);
    } finally {
      second.dispose();
    }
  } finally {
    first.dispose();
  }
});

it('keeps default stores independent even for the same service identity', async () => {
  const input = { ...options(), store: undefined };
  const left = new MemoryViewHost(input);
  const right = new MemoryViewHost(input);
  const created = await left.instance.create(
    { ...instance, title: 'only left' },
    { requestId: 'private-store' },
  );
  await expect(right.instance.load(created.id)).rejects.toMatchObject({
    code: 'NOT_FOUND',
  });
});
