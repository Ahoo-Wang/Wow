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

import { createFilterConfiguration } from '../../src/filter/filterCore.js';
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import { expect, it, vi } from 'vitest';
import { newFilterNode } from '../../src/filter/filterCore.js';
import type { ViewInstance } from '../../src/contracts/viewModel.js';
import type { ViewHost } from '../../src/contracts/ViewHost.js';
import {
  deferred,
  instance,
  managementPermissions as permissions,
  selected,
  setup,
} from './fixtures.js';

it('renames persisted metadata without saving draft filters, columns or newer edits', async () => {
  const response = deferred<ViewInstance>();
  const renameInstance = vi.fn(() => response.promise);
  const { engine, paged } = setup({
    host: {
      instance: { rename: renameInstance },
      permission: { getInstance: permissions },
    } as unknown as ViewHost,
  });
  await engine.load();
  engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .setColumns([
      { id: 'amount', kind: 'field', field: 'state.amount', width: 240 },
    ]);
  const draft = newFilterNode(FilterOperator.GTE, 'state.amount');
  engine.record(engine.getSnapshot().selectedInstanceId!).setFilterDraft(
    createFilterConfiguration({
      ...draft,
      props: { ...draft.props, value: 50 },
    }),
  );
  engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .setFilterValidity(false);
  const before = selected(engine);
  const renaming = engine.renameInstance('  New name  ');
  expect(renameInstance).toHaveBeenCalledWith('mine', 'New name', 'r1');
  expect(selected(engine).writeStatus).toBe('renaming');
  await expect(engine.deleteInstance()).rejects.toThrow();
  response.resolve({ ...instance(), title: 'New name', revision: 'r2' });
  await renaming;
  expect(selected(engine)).toMatchObject({
    baseline: {
      title: 'New name',
      revision: 'r2',
      config: instance().config,
    },
    instance: { title: 'New name', config: before.instance.config },
    dirty: true,
    filterPending: true,
    filterDraft: before.filterDraft,
  });
  expect(paged).toHaveBeenCalledTimes(1);
  engine.dispose();
});

it('protects system names even when the host grants every permission', async () => {
  const renameInstance = vi.fn();
  const { engine } = setup({
    instances: {
      instances: [
        { ...instance(), scope: { type: 'public', source: 'system' } },
      ],
      defaultInstanceId: 'mine',
    },
    host: {
      instance: { rename: renameInstance },
      permission: { getInstance: permissions },
    } as unknown as ViewHost,
  });
  await engine.load();
  expect(engine.getPermissions().rename).toBe(false);
  expect(() => engine.setTitle('Changed')).toThrow();
  await expect(engine.renameInstance('Changed')).rejects.toThrow();
  expect(renameInstance).not.toHaveBeenCalled();
  expect(selected(engine).instance.title).toBe('mine');
  engine.dispose();
});

it('rejects changed content from a rename response and requires reconciliation', async () => {
  const { engine } = setup({
    host: {
      instance: {
        rename: async () => ({
          ...instance(),
          title: 'New',
          config: {
            ...instance().config,
            filters: createFilterConfiguration({
              ...newFilterNode(FilterOperator.GTE, 'state.amount'),
              props: { value: 99 },
            }),
          },
        }),
      },
      permission: { getInstance: permissions },
    } as unknown as ViewHost,
  });
  await engine.load();
  await expect(engine.renameInstance('New')).rejects.toThrow();
  expect(selected(engine)).toMatchObject({
    requiresReload: true,
    writeStatus: 'idle',
    instance: { title: 'mine' },
  });
  engine.dispose();
});

it('persists personal ordering without changing selection or querying', async () => {
  const response = deferred<void>();
  const saveInstanceOrder = vi
    .fn()
    .mockReturnValueOnce(response.promise)
    .mockResolvedValue(undefined);
  const { engine, paged } = setup({
    host: {
      preference: { saveOrder: saveInstanceOrder },
    } as unknown as ViewHost,
  });
  await engine.load();
  const ordering = engine.reorderInstances(['shared', 'mine']);
  expect(engine.getSnapshot().instanceIds).toEqual(['mine', 'shared']);
  await expect(engine.reorderInstances(['shared', 'mine'])).rejects.toThrow();
  response.reject(new Error('order failed'));
  await expect(ordering).rejects.toThrow('order failed');
  expect(engine.getSnapshot().instanceIds).toEqual(['mine', 'shared']);
  await engine.reorderInstances(['shared', 'mine']);
  expect(saveInstanceOrder).toHaveBeenLastCalledWith('orders', [
    'shared',
    'mine',
  ]);
  expect(engine.getSnapshot().instanceIds).toEqual(['shared', 'mine']);
  expect(engine.getSnapshot().selectedInstanceId).toBe('mine');
  expect(paged).toHaveBeenCalledTimes(1);
  for (const ids of [['mine'], ['mine', 'mine'], ['mine', 'unknown']])
    await expect(engine.reorderInstances(ids)).rejects.toThrow();
  expect(saveInstanceOrder).toHaveBeenCalledTimes(2);
  engine.dispose();
});

it('does not resurrect a deleted view or drop a new one when order persistence finishes late', async () => {
  const response = deferred<void>();
  const { engine } = setup({
    host: {
      preference: { saveOrder: () => response.promise },
      instance: { delete: async () => ({ defaultInstance: null }) },
      permission: { getInstance: permissions },
    } as unknown as ViewHost,
  });
  await engine.load();
  const ordering = engine.reorderInstances(['shared', 'mine']);
  await engine.saveAs({ title: 'Copy', scope: { type: 'personal' } });
  await engine.deleteInstance('mine');
  response.resolve();
  await ordering;
  expect(engine.getSnapshot().instanceIds).toEqual(['shared', 'created']);
  expect(engine.getSnapshot().selectedInstanceId).toBe('created');
  engine.dispose();
});
