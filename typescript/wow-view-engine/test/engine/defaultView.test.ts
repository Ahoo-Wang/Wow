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

import { expect, it, vi } from 'vitest';
import type { ViewHost } from '../../src/contracts/ViewHost.js';
import {
  deferred,
  instance,
  managementPermissions,
  setup,
  selected,
} from './fixtures.js';

it('changes only the default after the host confirms the write', async () => {
  const response = deferred<void>();
  const saveDefault = vi.fn(() => response.promise);
  const { engine, paged } = setup({
    host: { preference: { saveDefault } } as ViewHost,
  });
  expect(engine.getSnapshot().defaultInstanceId).toBe(null);
  await engine.load();
  engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .setColumns([
      { id: 'amount', kind: 'field', field: 'state.amount', width: 240 },
    ]);
  const before = selected(engine);
  const pending = engine.setDefaultInstance('shared');
  expect(engine.getSnapshot().defaultInstanceId).toBe('mine');
  await expect(engine.setDefaultInstance(null)).rejects.toThrow(/正在保存/);
  response.resolve();
  await pending;
  expect(saveDefault).toHaveBeenCalledWith('orders', 'shared');
  expect(engine.getSnapshot()).toMatchObject({
    defaultInstanceId: 'shared',
    selectedInstanceId: 'mine',
  });
  expect(selected(engine)).toBe(before);
  expect(paged).toHaveBeenCalledTimes(1);
  engine.dispose();
});

it('keeps the previous default on failure and permits saving that same value again', async () => {
  const response = deferred<void>();
  const saveDefault = vi
    .fn()
    .mockReturnValueOnce(response.promise)
    .mockResolvedValue(undefined);
  const { engine } = setup({
    host: { preference: { saveDefault } } as ViewHost,
  });
  await engine.load();
  const pending = engine.setDefaultInstance('shared');
  const rejected = expect(pending).rejects.toThrow(/offline.*重试或重新加载/);
  response.reject(new Error('offline'));
  await rejected;
  expect(engine.getSnapshot().defaultInstanceId).toBe('mine');
  await engine.setDefaultInstance('mine');
  expect(saveDefault).toHaveBeenCalledTimes(2);
  expect(saveDefault).toHaveBeenLastCalledWith('orders', 'mine');
  engine.dispose();
});

it.each(['load', 'dispose'] as const)(
  'ignores late success and failure after %s',
  async action => {
    for (const outcome of ['resolve', 'reject'] as const) {
      const response = deferred<void>();
      const { engine } = setup({
        host: {
          preference: { saveDefault: () => response.promise },
        } as ViewHost,
      });
      await engine.load();
      const pending = engine.setDefaultInstance('shared');
      await engine[action]();
      const before = engine.getSnapshot();
      if (outcome === 'resolve') response.resolve();
      else response.reject(new Error('offline'));
      await pending;
      expect(engine.getSnapshot()).toBe(before);
      expect(engine.getSnapshot().defaultInstanceId).toBe('mine');
      engine.dispose();
    }
  },
);

it('keeps default writes serialized across reload until the previous host write settles', async () => {
  const first = deferred<void>();
  let persisted: string | null = 'mine';
  const saveDefault = vi.fn(
    async (_definitionId: string, id: string | null) => {
      if (id === 'shared') await first.promise;
      persisted = id;
    },
  );
  const { engine } = setup({
    instances: undefined,
    host: {
      instance: {
        list: async () => ({
          instances: [instance(), instance('shared')],
          defaultInstanceId: persisted,
        }),
        delete: vi.fn(),
      },
      permission: { getInstance: managementPermissions },
      preference: { saveDefault },
    } as unknown as ViewHost,
  });
  await engine.load();
  const oldWrite = engine.setDefaultInstance('shared');
  await engine.load();
  try {
    await expect(engine.setDefaultInstance(null)).rejects.toThrow(/正在保存/);
    await expect(engine.deleteInstance('mine')).rejects.toThrow(/正在保存/);
    expect(saveDefault).toHaveBeenCalledTimes(1);
  } finally {
    first.resolve();
    await oldWrite;
  }
  expect(persisted).toBe('shared');
  expect(engine.getSnapshot().defaultInstanceId).toBe('mine');
  await engine.setDefaultInstance(null);
  expect(persisted).toBe(null);
  expect(engine.getSnapshot().defaultInstanceId).toBe(null);
  expect(saveDefault).toHaveBeenCalledTimes(2);
  engine.dispose();
});

it('releases the completed default write before notifying synchronous observers', async () => {
  const second = deferred<void>();
  const saveDefault = vi
    .fn()
    .mockResolvedValueOnce(undefined)
    .mockReturnValueOnce(second.promise);
  const { engine } = setup({
    host: { preference: { saveDefault } } as ViewHost,
  });
  await engine.load();
  let next: Promise<void> | undefined;
  const unsubscribe = engine.subscribe(() => {
    if (engine.getSnapshot().defaultInstanceId === 'shared' && !next)
      next = engine.setDefaultInstance(null);
  });
  await engine.setDefaultInstance('shared');
  expect(saveDefault).toHaveBeenCalledTimes(2);
  await expect(engine.setDefaultInstance('mine')).rejects.toThrow(/正在保存/);
  second.resolve();
  await next;
  expect(engine.getSnapshot().defaultInstanceId).toBe(null);
  unsubscribe();
  engine.dispose();
});

it.each(['default', 'delete'] as const)(
  'rejects conflicting target writes when %s starts first',
  async first => {
    const response = deferred<void>();
    const remove = vi.fn(async () => {
      if (first === 'delete') await response.promise;
      return { defaultInstance: instance() };
    });
    const saveDefault = vi.fn(() =>
      first === 'default' ? response.promise : Promise.resolve(),
    );
    const { engine } = setup({
      host: {
        preference: { saveDefault },
        instance: { delete: remove },
        permission: { getInstance: managementPermissions },
      } as unknown as ViewHost,
    });
    await engine.load();
    const pending =
      first === 'default'
        ? engine.setDefaultInstance('shared')
        : engine.deleteInstance('shared');
    await expect(
      first === 'default'
        ? engine.deleteInstance('shared')
        : engine.setDefaultInstance('shared'),
    ).rejects.toThrow(/正在保存|正在写入/);
    expect(first === 'default' ? remove : saveDefault).not.toHaveBeenCalled();
    response.resolve();
    await pending;
    expect(engine.getSnapshot().defaultInstanceId).toBe(
      first === 'default' ? 'shared' : 'mine',
    );
    engine.dispose();
  },
);

it('serializes default writes with deletion of other instances', async () => {
  const pendingDefault = deferred<void>();
  const { engine } = setup({
    host: {
      preference: { saveDefault: () => pendingDefault.promise },
      instance: { delete: async () => ({ defaultInstance: null }) },
      permission: { getInstance: managementPermissions },
    } as unknown as ViewHost,
  });
  await engine.load();
  const saving = engine.setDefaultInstance('shared');
  try {
    await expect(engine.deleteInstance('mine')).rejects.toThrow(/正在保存/);
  } finally {
    pendingDefault.resolve();
    await saving;
    engine.dispose();
  }
});

it('serializes deletions and prevents a later default from being overwritten by a receipt', async () => {
  const response = deferred<{ defaultInstance: null }>();
  const { engine } = setup({
    host: {
      preference: { saveDefault: async () => {} },
      instance: {
        delete: async (id: string) =>
          id === 'mine' ? response.promise : { defaultInstance: null },
      },
      permission: { getInstance: managementPermissions },
    } as unknown as ViewHost,
  });
  await engine.load();
  const deleting = engine.deleteInstance('mine');
  try {
    await expect(engine.setDefaultInstance('shared')).rejects.toThrow(
      /正在删除/,
    );
    await expect(engine.deleteInstance('shared')).rejects.toThrow(/正在删除/);
  } finally {
    response.resolve({ defaultInstance: null });
    await deleting;
    engine.dispose();
  }
});

it('keeps a nondeleted default and falls back in user order when deleting the default', async () => {
  const { engine } = setup({
    host: {
      preference: { saveDefault: async () => {}, saveOrder: async () => {} },
      instance: {
        delete: vi
          .fn()
          .mockResolvedValueOnce({ defaultInstance: instance() })
          .mockResolvedValueOnce({ defaultInstance: instance('created') })
          .mockResolvedValueOnce({ defaultInstance: instance('shared') })
          .mockResolvedValueOnce({ defaultInstance: null }),
      },
      permission: { getInstance: managementPermissions },
    } as unknown as ViewHost,
  });
  await engine.load();
  await engine.deleteInstance('shared');
  expect(engine.getSnapshot().defaultInstanceId).toBe('mine');
  await engine.load();
  await engine.saveAs({ title: 'Copy', scope: { type: 'personal' } });
  await engine.reorderInstances(['created', 'shared', 'mine']);
  await engine.deleteInstance('mine');
  expect(engine.getSnapshot().defaultInstanceId).toBe('created');
  await engine.deleteInstance('created');
  expect(engine.getSnapshot().defaultInstanceId).toBe('shared');
  await engine.deleteInstance('shared');
  expect(engine.getSnapshot().defaultInstanceId).toBe(null);
  engine.dispose();
});

it('reloads an explicitly cleared default without selecting or querying a fallback', async () => {
  let defaultInstanceId: string | null = 'mine';
  const { engine, paged } = setup({
    instances: undefined,
    host: {
      instance: {
        list: async () => ({
          instances: [instance(), instance('shared')],
          defaultInstanceId,
        }),
      },
      preference: {
        saveDefault: async (_definitionId, id) => {
          defaultInstanceId = id;
        },
      },
    } as ViewHost,
  });
  await engine.load();
  await engine.setDefaultInstance(null);
  expect(engine.getSnapshot().selectedInstanceId).toBe('mine');
  await engine.load();
  expect(engine.getSnapshot()).toMatchObject({
    defaultInstanceId: null,
    selectedInstanceId: null,
  });
  expect(paged).toHaveBeenCalledTimes(1);
  engine.dispose();
});

it('rejects invalid or unknown targets before dispatching, including undefined with a selected instance', async () => {
  const saveDefault = vi.fn();
  const { engine } = setup({
    host: { preference: { saveDefault } } as ViewHost,
  });
  await expect(engine.setDefaultInstance('mine')).rejects.toThrow(/尚未加载/);
  await engine.load();
  for (const id of [undefined, '', '  ', 42, {}, 'unknown'])
    await expect(engine.setDefaultInstance(id as string)).rejects.toThrow(
      /有效/,
    );
  expect(saveDefault).not.toHaveBeenCalled();
  engine.dispose();
  await expect(engine.setDefaultInstance(null)).rejects.toThrow(/已释放/);
});

it('allows visible system and shared defaults independently of edit permissions or selection', async () => {
  const saveDefault = vi.fn(async () => {});
  const { engine } = setup({
    instances: {
      instances: [
        { ...instance(), scope: { type: 'public', source: 'system' } },
        { ...instance('shared'), scope: { type: 'public', source: 'shared' } },
      ],
      defaultInstanceId: 'mine',
    },
    host: {
      preference: { saveDefault, saveOrder: async () => {} },
      permission: {
        getInstance: () => ({
          save: false,
          saveAsPersonal: false,
          saveAsShared: false,
          rename: false,
          delete: false,
        }),
      },
    } as ViewHost,
  });
  await engine.load();
  await engine.selectInstance('shared');
  await engine.reorderInstances(['shared', 'mine']);
  expect(engine.getSnapshot().defaultInstanceId).toBe('mine');
  await engine.setDefaultInstance('shared');
  await engine.setDefaultInstance('mine');
  expect(engine.getSnapshot()).toMatchObject({
    defaultInstanceId: 'mine',
    selectedInstanceId: 'shared',
  });
  expect(saveDefault).toHaveBeenCalledTimes(2);
  engine.dispose();
});

it('loads default preferences from a host without default writing capability', async () => {
  const { engine } = setup();
  await engine.load();
  expect(engine.canSetDefaultInstance()).toBe(false);
  expect(engine.getSnapshot().defaultInstanceId).toBe('mine');
  await expect(engine.setDefaultInstance(null)).rejects.toThrow(
    /未提供默认视图保存接口/,
  );
  engine.dispose();
});
