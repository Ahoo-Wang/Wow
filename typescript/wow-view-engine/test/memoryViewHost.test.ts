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
import { ViewEngine } from '../src/engine/ViewEngine.js';
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

it('uses the deletion receipt after another client changes the fallback order', async () => {
  const input = options();
  input.instances.instances.push({ ...structuredClone(instance), id: 'other' });
  const host = new MemoryViewHost(input);
  const otherClient = new MemoryViewHost(input);
  const engine = new ViewEngine({ definitionId: definition.id, host });
  await engine.load();
  engine
    .record('system')
    .setColumns([{ id: 'amount', kind: 'field', field: 'amount', width: 240 }]);
  const draft = engine.getSnapshot().sessions.system;
  await otherClient.preference.saveOrder(definition.id, [
    'mine',
    'other',
    'system',
  ]);
  vi.spyOn(host.instance, 'list').mockRejectedValue(
    new Error('list unavailable'),
  );
  await engine.deleteInstance('mine');
  expect(engine.getSnapshot().defaultInstanceId).toBe('other');
  expect(engine.getSnapshot().sessions.system).toMatchObject({
    instance: draft.instance,
    dirty: true,
    filterDraft: draft.filterDraft,
  });
  expect(
    (await otherClient.instance.list(definition.id)).defaultInstanceId,
  ).toBe('other');
  engine.dispose();
});

it.each([true, false])(
  'adopts an unloaded default with other local views: %s',
  async withSystem => {
    const input = options();
    if (!withSystem) input.instances.instances = [instance];
    const host = new MemoryViewHost(input);
    const otherClient = new MemoryViewHost(input);
    const engine = new ViewEngine({ definitionId: definition.id, host });
    await engine.load();
    const created = await otherClient.instance.create(
      { ...instance, title: 'New default' },
      { requestId: 'new-default' },
    );
    await otherClient.preference.saveDefault(definition.id, created.id);
    await engine.deleteInstance('mine');
    expect(engine.getSnapshot().defaultInstanceId).toBe(created.id);
    expect(engine.getSnapshot().sessions[created.id].instance).toEqual(created);
    expect(engine.getSnapshot().instanceIds).toContain(created.id);
    if (withSystem) {
      expect(engine.getSnapshot().sessions[created.id].queryStatus).toBe(
        'idle',
      );
    } else {
      expect(engine.getSnapshot().selectedInstanceId).toBe(created.id);
      await vi.waitFor(() =>
        expect(engine.getSnapshot().sessions[created.id].queryStatus).toBe(
          'success',
        ),
      );
    }
    const replay = await host.instance.delete('mine');
    expect(replay).toEqual({ defaultInstance: created });
    engine.dispose();
  },
);

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

it('persists a personal default without editing views or another user', async () => {
  const host = new MemoryViewHost(options());
  const before = await host.instance.list(definition.id);
  await host.preference.saveDefault(definition.id, 'system');
  const restored = await new MemoryViewHost(options()).instance.list(
    definition.id,
  );
  expect(restored.defaultInstanceId).toBe('system');
  expect(restored.instances).toEqual(before.instances);
  const other = await new MemoryViewHost(options('bob')).instance.list(
    definition.id,
  );
  expect(other.defaultInstanceId).toBe(instance.id);
  await host.preference.saveDefault(definition.id, null);
  expect(
    (await host.instance.list(definition.id)).defaultInstanceId,
  ).toBeNull();
  await expect(
    host.preference.saveDefault(definition.id, 'unknown'),
  ).rejects.toMatchObject({ code: 'NOT_FOUND' });
  expect(
    (await host.instance.list(definition.id)).defaultInstanceId,
  ).toBeNull();
});

it.each([undefined, 123, '', '.'])(
  'rejects invalid default instance ID %j',
  async value => {
    const host = new MemoryViewHost(options());
    await expect(
      host.preference.saveDefault(
        definition.id,
        value as unknown as string | null,
      ),
    ).rejects.toMatchObject({ code: 'INVALID_ARGUMENT' });
  },
);

it('allows visible personal and shared defaults without edit rights', async () => {
  const input = options();
  const shared = {
    ...structuredClone(instance),
    id: 'shared',
    title: '共享视图',
    scope: { type: 'public', source: 'shared' } as const,
  };
  const host = new MemoryViewHost({
    ...input,
    instances: {
      instances: [...input.instances.instances, shared],
      defaultInstanceId: instance.id,
    },
    instancePermissions: () => ({
      save: false,
      rename: false,
      delete: false,
      saveAsPersonal: false,
      saveAsShared: false,
    }),
  });
  for (const id of [instance.id, 'shared']) {
    expect(host.permission.getInstance(await host.instance.load(id)).save).toBe(
      false,
    );
    await host.preference.saveDefault(definition.id, id);
    expect((await host.instance.list(definition.id)).defaultInstanceId).toBe(
      id,
    );
  }
});

it('rejects another definition and another users personal instance', async () => {
  const alice = new MemoryViewHost(options('alice'));
  const bob = new MemoryViewHost(options('bob'));
  const bobOnly = await bob.instance.create(
    { ...instance, title: 'Bob 私有视图' },
    { requestId: 'bob-private' },
  );
  await expect(
    alice.preference.saveDefault(definition.id, bobOnly.id),
  ).rejects.toMatchObject({ code: 'NOT_FOUND' });
  await expect(
    alice.preference.saveDefault('unknown', 'system'),
  ).rejects.toMatchObject({ code: 'NOT_FOUND' });

  const otherInput = options('alice');
  const otherDefinition = { ...definition, id: 'other' };
  const other = new MemoryViewHost({
    ...otherInput,
    definition: otherDefinition,
    instances: {
      instances: otherInput.instances.instances.map(item => ({
        ...structuredClone(item),
        definitionId: otherDefinition.id,
      })),
      defaultInstanceId: instance.id,
    },
  });
  expect(
    (await other.instance.list(otherDefinition.id)).defaultInstanceId,
  ).toBe(instance.id);
  await alice.preference.saveDefault(definition.id, 'system');
  expect(
    (await other.instance.list(otherDefinition.id)).defaultInstanceId,
  ).toBe(instance.id);
});

it('falls back after deleting a personal default and preserves repeated null', async () => {
  const host = new MemoryViewHost(options());
  await host.preference.saveDefault(definition.id, instance.id);
  const selected = await host.instance.load(instance.id);
  await host.instance.delete(selected.id, selected.revision);
  expect((await host.instance.list(definition.id)).defaultInstanceId).toBe(
    'system',
  );
  await host.preference.saveDefault(definition.id, null);
  await host.preference.saveDefault(definition.id, null);
  expect(
    (await host.instance.list(definition.id)).defaultInstanceId,
  ).toBeNull();
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
  await expect(host.instance.delete(saved.id, saved.revision)).rejects.toThrow(
    'full',
  );
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
  ).resolves.toMatchObject({ defaultInstance: { id: 'system' } });
  expect(await bob.instance.load(instance.id)).toEqual(others);
  expect(JSON.parse(store.get(alice.storageKey)!).users).toMatchObject({
    alice: { defaultInstanceId: 'system' },
    bob: { defaultInstanceId: instance.id },
  });
  await expect(alice.instance.delete('system')).rejects.toMatchObject({
    code: 'FORBIDDEN',
  });
});

it('persists both layouts and restores each configuration after reloading', async () => {
  const host = new MemoryViewHost(options());
  const first = new ViewEngine({ definitionId: definition.id, host });
  try {
    await first.load();
    first.record(first.getSnapshot().selectedInstanceId!).setLayout('card');
    first.record(first.getSnapshot().selectedInstanceId!).setCardConfig({
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
      second
        .record(second.getSnapshot().selectedInstanceId!)
        .setLayout('table');
      second.record(second.getSnapshot().selectedInstanceId!).setLayout('card');
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

it('persists the deletion fallback before reordering and loading a fresh engine', async () => {
  const input = options();
  input.instances.instances.push({ ...instance, id: 'other' });
  const host = new MemoryViewHost(input);
  await host.preference.saveDefault(definition.id, instance.id);
  await host.preference.saveOrder(definition.id, [
    instance.id,
    'other',
    'system',
  ]);
  const selected = await host.instance.load(instance.id);
  await host.instance.delete(selected.id, selected.revision);
  expect((await host.instance.list(definition.id)).defaultInstanceId).toBe(
    'other',
  );
  expect(
    JSON.parse(store.get(host.storageKey)!).users.developer.defaultInstanceId,
  ).toBe('other');
  await host.preference.saveOrder(definition.id, ['system', 'other']);
  const engine = new ViewEngine({
    definitionId: definition.id,
    host: new MemoryViewHost(input),
  });
  try {
    await engine.load();
    expect(engine.getSnapshot()).toMatchObject({
      defaultInstanceId: 'other',
      selectedInstanceId: 'other',
    });
  } finally {
    engine.dispose();
  }
});

it('normalizes shared default deletion to each users order while preserving null and unrelated defaults', async () => {
  const input = options();
  input.instances.instances.push({
    ...instance,
    id: 'shared',
    scope: { type: 'public', source: 'shared' },
  });
  const alice = new MemoryViewHost({ ...input, scopeKey: 'alice' });
  const bob = new MemoryViewHost({ ...input, scopeKey: 'bob' });
  const cleared = new MemoryViewHost({ ...input, scopeKey: 'cleared' });
  const unrelated = new MemoryViewHost({ ...input, scopeKey: 'unrelated' });
  await alice.preference.saveDefault(definition.id, 'shared');
  await bob.preference.saveDefault(definition.id, 'shared');
  await cleared.preference.saveDefault(definition.id, null);
  await unrelated.preference.saveDefault(definition.id, 'system');
  await alice.preference.saveOrder(definition.id, [
    'shared',
    'system',
    instance.id,
  ]);
  await bob.preference.saveOrder(definition.id, [
    'shared',
    instance.id,
    'system',
  ]);
  const selected = await alice.instance.load('shared');
  await alice.instance.delete(selected.id, selected.revision);
  expect(JSON.parse(store.get(alice.storageKey)!).users).toMatchObject({
    alice: {
      defaultInstanceId: 'system',
      order: ['shared', 'system', instance.id],
    },
    bob: {
      defaultInstanceId: instance.id,
      order: ['shared', instance.id, 'system'],
    },
    cleared: { defaultInstanceId: null },
    unrelated: { defaultInstanceId: 'system' },
  });
});

it('clears the default when its last visible instance is deleted', async () => {
  const input = options();
  input.instances.instances = [instance];
  const host = new MemoryViewHost(input);
  const selected = await host.instance.load(instance.id);
  await host.instance.delete(selected.id, selected.revision);
  expect(
    JSON.parse(store.get(host.storageKey)!).users.developer.defaultInstanceId,
  ).toBeNull();
  expect(await host.instance.list(definition.id)).toEqual({
    instances: [],
    defaultInstanceId: null,
  });
});

it('normalizes a new users seeded default when the shared seed was already deleted', async () => {
  const input = options();
  input.instances.instances.push({
    ...instance,
    id: 'shared',
    scope: { type: 'public', source: 'shared' },
  });
  input.instances.defaultInstanceId = 'shared';
  const alice = new MemoryViewHost({ ...input, scopeKey: 'alice' });
  const shared = await alice.instance.load('shared');
  await alice.instance.delete(shared.id, shared.revision);
  const bobOptions = { ...input, scopeKey: 'bob' };
  const bob = new MemoryViewHost(bobOptions);
  expect((await bob.instance.list(definition.id)).defaultInstanceId).toBe(
    instance.id,
  );
  expect(
    JSON.parse(store.get(bob.storageKey)!).users.bob.defaultInstanceId,
  ).toBe(instance.id);
  await bob.preference.saveOrder(definition.id, ['system', instance.id]);
  expect(
    (await new MemoryViewHost(bobOptions).instance.list(definition.id))
      .defaultInstanceId,
  ).toBe(instance.id);
});
