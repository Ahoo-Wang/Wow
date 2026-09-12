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
import type { ViewInstance } from '../../src/contracts/viewModel.js';
import type { ViewHost } from '../../src/contracts/ViewHost.js';
import { ViewServiceError } from '../../src/contracts/viewServiceContract.js';
import { reconcileWriteFailure } from '../../src/engine/writeRecovery.js';
import {
  deferred,
  instance,
  managementPermissions,
  selected,
  setup,
} from './fixtures.js';

it.each(['save', 'rename', 'delete'] as const)(
  'requires reconciliation for unclassified or unavailable %s failures',
  async operation => {
    for (const failure of [
      new TypeError('Network failed'),
      new ViewServiceError('UNAVAILABLE', 'Service unavailable'),
    ]) {
      const { engine, host } = setup({
        host: {
          permission: { getInstance: managementPermissions },
        } as ViewHost,
      });
      host.instance!.rename = vi.fn(async (id, title) => ({
        ...instance(id),
        title,
      }));
      host.instance!.delete = vi
        .fn()
        .mockResolvedValue({ defaultInstance: null });
      host.instance![operation] = vi.fn().mockRejectedValue(failure);
      host.instance!.load = vi.fn(async () => ({
        ...instance(),
        revision: operation === 'delete' ? 'r1' : 'r2',
      }));
      try {
        await engine.load();
        engine.setTitle('Retained edit');
        await expect(
          operation === 'save'
            ? engine.save()
            : operation === 'rename'
              ? engine.renameInstance('Rename')
              : engine.deleteInstance(),
        ).rejects.toBe(failure);
        expect(selected(engine)).toMatchObject({
          requiresReload: true,
          instance: { title: 'Retained edit' },
        });
        expect(
          engine.getCapabilitiesSnapshot().instances.mine.retryDelete,
        ).toBe(operation === 'delete');
        await expect(engine.save()).rejects.toThrow('核对');
        await expect(engine.renameInstance('Blocked')).rejects.toThrow('核对');
        if (operation !== 'delete')
          await expect(engine.deleteInstance()).rejects.toThrow('核对');
        expect(host.instance![operation]).toHaveBeenCalledOnce();
        await engine.reloadInstance();
        expect(selected(engine)).toMatchObject({
          requiresReload: false,
          instance: {
            title: 'Retained edit',
            revision: operation === 'delete' ? 'r1' : 'r2',
          },
        });
        expect(
          engine.getCapabilitiesSnapshot().instances.mine.retryDelete,
        ).toBe(false);
        if (operation === 'delete') {
          host.instance!.save = vi.fn().mockRejectedValue(failure);
          await expect(engine.save()).rejects.toBe(failure);
          await expect(engine.deleteInstance()).rejects.toThrow('核对');
          expect(host.instance!.delete).toHaveBeenCalledOnce();
        }
      } finally {
        engine.dispose();
      }
    }
  },
);

it.each(['save', 'rename'] as const)(
  'recovers an uncertain %s through a list-only host without discarding edits',
  async operation => {
    const persisted = { ...instance(), title: 'Server title', revision: 'r2' };
    const list = vi.fn(async () => ({
      instances: [instance('other'), persisted],
      defaultInstanceId: 'other',
    }));
    const failure = new ViewServiceError('UNKNOWN_OUTCOME', 'Response lost');
    const { engine, host } = setup({
      host: {
        instance: { list },
        permission: { getInstance: managementPermissions },
      } as ViewHost,
    });
    host.instance![operation] = vi.fn().mockRejectedValue(failure);
    try {
      await engine.load();
      engine.setTitle('Local title');
      await expect(
        operation === 'save' ? engine.save() : engine.renameInstance('Rename'),
      ).rejects.toBe(failure);
      engine
        .record(engine.getSnapshot().selectedInstanceId!)
        .setColumns([
          { id: 'amount', kind: 'field', field: 'state.amount', width: 321 },
        ]);
      const draft = selected(engine).instance.config;
      expect(engine.canReloadInstance()).toBe(true);
      expect(engine.getCapabilitiesSnapshot().instances.mine.reload).toBe(true);
      await engine.reloadInstance();
      expect(list).toHaveBeenCalledWith('orders', expect.any(AbortSignal));
      expect(engine.getSnapshot().selectedInstanceId).toBe('mine');
      expect(selected(engine)).toMatchObject({
        baseline: instance(),
        conflict: { remote: persisted },
        instance: { title: 'Local title', config: draft, revision: 'r1' },
        requiresReload: false,
        writeError: null,
        dirty: true,
      });
      host.instance!.save = vi.fn(async value => ({
        ...value,
        revision: 'r3',
      }));
      await expect(engine.save()).rejects.toThrow('冲突');
      expect(host.instance!.save).not.toHaveBeenCalled();
      await engine.overwriteInstance(selected(engine).conflict!, 'mine');
      expect(selected(engine).conflict).toBeUndefined();
      expect(host.instance!.save).toHaveBeenCalledWith(
        expect.objectContaining({
          revision: 'r2',
          title: 'Local title',
          config: draft,
        }),
      );
    } finally {
      engine.dispose();
    }
  },
);

it('retains the recovery requirement when a list-only host omits the current ID', async () => {
  const { engine, host } = setup({
    host: {
      instance: {
        list: async () => ({
          instances: [instance('other')],
          defaultInstanceId: null,
        }),
      },
    } as ViewHost,
  });
  host.instance!.save = vi
    .fn()
    .mockRejectedValue(
      new ViewServiceError('UNKNOWN_OUTCOME', 'Response lost'),
    );
  try {
    await engine.load();
    engine.setTitle('Local title');
    await expect(engine.save()).rejects.toThrow('Response lost');
    const baseline = selected(engine).baseline;
    await expect(engine.reloadInstance()).rejects.toThrow('实例列表未包含');
    expect(selected(engine)).toMatchObject({
      baseline,
      instance: { title: 'Local title' },
      requiresReload: true,
    });
  } finally {
    engine.dispose();
  }
});

it.each(['save', 'rename', 'delete'] as const)(
  'rejects full load during a pending %s and still processes its receipt',
  async operation => {
    for (const unknown of [false, true]) {
      const response = deferred<ViewInstance>();
      const persisted = { ...instance(), title: 'Submitted', revision: 'r2' };
      const failure = new ViewServiceError('UNKNOWN_OUTCOME', 'Response lost');
      const { engine, host } = setup({
        host: {
          permission: { getInstance: managementPermissions },
        } as ViewHost,
      });
      host.instance!.save = vi.fn(() => response.promise);
      host.instance!.rename = vi.fn(() => response.promise);
      host.instance!.delete = vi.fn(async () => {
        await response.promise;
        return { defaultInstance: null };
      });
      await engine.load();
      engine.setTitle('Submitted');
      const writing =
        operation === 'save'
          ? engine.save()
          : operation === 'rename'
            ? engine.renameInstance('Submitted')
            : engine.deleteInstance();
      try {
        expect(host.instance![operation]).toHaveBeenCalledOnce();
        const pending = engine.getSnapshot();
        await expect(engine.load()).rejects.toThrow('实例正在写入');
        expect(engine.getSnapshot()).toBe(pending);
        if (unknown) {
          response.reject(failure);
          await expect(writing).rejects.toBe(failure);
          expect(selected(engine)).toMatchObject({
            baseline: { revision: 'r1' },
            instance: { title: 'Submitted' },
            writeStatus: 'idle',
            requiresReload: true,
          });
        } else {
          response.resolve(persisted);
          await writing;
          if (operation === 'delete') {
            expect(engine.getSnapshot().instanceIds).not.toContain('mine');
            expect(engine.getSnapshot().sessions.mine).toBeUndefined();
          } else {
            expect(selected(engine)).toMatchObject({
              baseline: persisted,
              writeStatus: 'idle',
              requiresReload: false,
            });
          }
        }
      } finally {
        response.resolve(persisted);
        await writing.catch(() => {});
        engine.dispose();
      }
    }
  },
);

describe('reconcileWriteFailure', () => {
  it('runs beforePatch, then patch inside finish, then rethrows the original error', () => {
    const order: string[] = [];
    const error = new Error('写入失败');
    expect(() =>
      reconcileWriteFailure(error, {
        finish: onSettled => {
          order.push('finish');
          onSettled();
        },
        patch: () => order.push('patch'),
        beforePatch: () => order.push('beforePatch'),
      }),
    ).toThrow(error);
    expect(order).toEqual(['beforePatch', 'finish', 'patch']);
  });

  it('works without beforePatch and always rethrows', () => {
    const finish = vi.fn(onSettled => onSettled());
    expect(() =>
      reconcileWriteFailure('boom', {
        finish,
        patch: () => {},
      }),
    ).toThrow('boom');
    expect(finish).toHaveBeenCalledOnce();
  });
});
