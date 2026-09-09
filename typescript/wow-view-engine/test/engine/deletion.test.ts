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
import type { ViewInstance } from '../../src/record/recordModel.js';
import type { ViewHost } from '../../src/record/ViewHost.js';
import { ViewServiceError } from '../../src/record/viewServiceContract.js';
import {
  deferred,
  instance,
  deletionPermissions as permissions,
  selected,
  setup,
} from './fixtures.js';

it('removes only after host success, then selects a remaining instance or leaves an empty page', async () => {
  const response = deferred<void>();
  const deleteInstance = vi.fn(() => response.promise);
  const { engine } = setup({
    host: {
      instance: { delete: deleteInstance },
      permission: { getInstance: permissions },
    } as unknown as ViewHost,
  });
  await engine.load();
  engine.setTitle('Unsaved title');
  const deleting = engine.deleteInstance();
  expect(selected(engine).writeStatus).toBe('deleting');
  expect(engine.getSnapshot().instanceIds).toEqual(['mine', 'shared']);
  expect(deleteInstance).toHaveBeenCalledWith('mine', 'r1');
  response.resolve();
  await deleting;
  expect(engine.getSnapshot().selectedInstanceId).toBe('shared');
  expect(engine.getSnapshot().sessions.mine).toBeUndefined();
  await engine.deleteInstance();
  expect(engine.getSnapshot()).toMatchObject({
    status: 'ready',
    instanceIds: [],
    sessions: {},
    selectedInstanceId: null,
  });
  engine.dispose();
});

it.each(['missing-permission', 'missing-callback', 'system'] as const)(
  'denies deletion for %s before calling the host',
  async restriction => {
    const deleteInstance = vi.fn().mockResolvedValue(undefined);
    const value = instance();
    if (restriction === 'system')
      value.scope = { type: 'public', source: 'system' };
    const { engine } = setup({
      instances: { instances: [value], defaultInstanceId: 'mine' },
      host: {
        instance: {
          delete:
            restriction === 'missing-callback' ? undefined : deleteInstance,
        },
        permission: {
          getInstance:
            restriction === 'missing-permission' ? undefined : permissions,
        },
      } as unknown as ViewHost,
    });
    await engine.load();
    expect(engine.getPermissions().delete).toBe(false);
    await expect(engine.deleteInstance()).rejects.toThrow();
    expect(deleteInstance).not.toHaveBeenCalled();
    expect(engine.getSnapshot().instanceIds).toEqual(['mine']);
    engine.dispose();
  },
);

it('retains drafts on failure and blocks concurrent saves or repeated deletion', async () => {
  const response = deferred<void>();
  const deleteInstance = vi
    .fn()
    .mockReturnValueOnce(response.promise)
    .mockResolvedValue(undefined);
  const { engine } = setup({
    host: {
      instance: { delete: deleteInstance },
      permission: { getInstance: permissions },
    } as unknown as ViewHost,
  });
  await engine.load();
  engine.setTitle('Draft');
  const deleting = engine.deleteInstance();
  await expect(engine.deleteInstance()).rejects.toThrow();
  await expect(engine.save()).rejects.toThrow();
  expect(selected(engine).writeStatus).toBe('deleting');
  response.reject(new ViewServiceError('CONFLICT', 'delete denied'));
  await expect(deleting).rejects.toThrow('delete denied');
  expect(selected(engine)).toMatchObject({
    dirty: true,
    instance: { title: 'Draft' },
    writeStatus: 'idle',
    writeError: 'delete denied',
  });
  expect(deleteInstance).toHaveBeenCalledTimes(1);
  await engine.deleteInstance();
  expect(engine.getSnapshot().instanceIds).toEqual(['shared']);
  engine.dispose();
});

it('keeps a pending navigation when deleting its previously selected instance', async () => {
  const response = deferred<void>();
  const loading = deferred<ViewInstance>();
  const { engine } = setup({
    host: {
      instance: { delete: () => response.promise, load: () => loading.promise },
      permission: { getInstance: permissions },
    } as unknown as ViewHost,
  });
  await engine.load();
  const deleting = engine.deleteInstance();
  const navigating = engine.selectInstance('remote');
  response.resolve();
  await deleting;
  loading.resolve(instance('remote'));
  await navigating;
  expect(engine.getSnapshot().selectedInstanceId).toBe('remote');
  expect(engine.getSnapshot().instanceIds).toEqual(['shared', 'remote']);
  engine.dispose();
});

it('does not report deletion as failed when the next view query fails', async () => {
  const { engine, paged } = setup({
    host: {
      instance: { delete: async () => {} },
      permission: { getInstance: permissions },
    } as unknown as ViewHost,
  });
  await engine.load();
  paged.mockRejectedValue(new Error('query failed'));
  await expect(engine.deleteInstance()).resolves.toBeUndefined();
  await vi.waitFor(() => expect(selected(engine).queryStatus).toBe('error'));
  expect(engine.getSnapshot().instanceIds).toEqual(['shared']);
  expect(selected(engine).writeError).toBeNull();
  engine.dispose();
});

it('ignores a late query response after its view is deleted', async () => {
  const response = deferred<{
    list: { state: { id: string; amount: number } }[];
    total: number;
  }>();
  const { engine, paged } = setup({
    host: {
      instance: { delete: async () => {} },
      permission: { getInstance: permissions },
    } as unknown as ViewHost,
  });
  await engine.load();
  paged.mockReturnValueOnce(response.promise);
  const refreshing = engine.refresh();
  await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
  await engine.deleteInstance();
  response.resolve({
    list: [{ state: { id: 'late', amount: 99 } }],
    total: 1,
  });
  await refreshing;
  expect(engine.getSnapshot().sessions.mine).toBeUndefined();
  expect(engine.getSnapshot().selectedInstanceId).toBe('shared');
  expect(selected(engine).rows).not.toEqual([
    { state: { id: 'late', amount: 99 } },
  ]);
  engine.dispose();
});

it.each(['load', 'dispose'] as const)(
  'preserves the pending deletion lifecycle when %s is requested',
  async operation => {
    const response = deferred<void>();
    const { engine } = setup({
      host: {
        instance: { delete: () => response.promise },
        permission: { getInstance: permissions },
      } as unknown as ViewHost,
    });
    await engine.load();
    const deleting = engine.deleteInstance();
    const snapshot = engine.getSnapshot();
    if (operation === 'load')
      await expect(engine.load()).rejects.toThrow('实例正在写入');
    else engine.dispose();
    expect(engine.getSnapshot()).toBe(snapshot);
    response.resolve();
    await deleting;
    if (operation === 'load') {
      expect(engine.getSnapshot().sessions.mine).toBeUndefined();
      expect(engine.getSnapshot().instanceIds).toEqual(['shared']);
      expect(engine.getSnapshot().selectedInstanceId).toBe('shared');
    } else expect(engine.getSnapshot()).toBe(snapshot);
    engine.dispose();
  },
);
