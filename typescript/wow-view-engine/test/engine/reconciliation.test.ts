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
import { deferred, instance, selected, setup } from './fixtures.js';

it('reconciles a changed create echo by reading the created ID and preserving both drafts', async () => {
  const persisted = {
    ...instance('created-1'),
    title: 'Normalized',
    scope: { type: 'public', source: 'shared' } as const,
    revision: 'r2',
  };
  const loadInstance = vi
    .fn<(id: string) => Promise<ViewInstance>>()
    .mockResolvedValue(persisted);
  const { engine, host } = setup({
    host: {
      instance: { create: async () => persisted, load: loadInstance },
    } as unknown as ViewHost,
  });
  await engine.load();
  const source = selected(engine);
  await expect(
    engine.saveAs({ title: 'My copy', scope: { type: 'personal' } }),
  ).rejects.toThrow();
  engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .setColumns([
      { id: 'amount', kind: 'field', field: 'state.amount', width: 200 },
    ]);
  const draft = newFilterNode(FilterOperator.GTE, 'state.amount');
  engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .setFilterDraft(createFilterConfiguration(draft));
  engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .setFilterValidity(false);
  await engine.reloadInstance();
  expect(loadInstance.mock.calls[0][0]).toBe('created-1');
  expect(engine.getSnapshot().selectedInstanceId).toBe('created-1');
  expect(selected(engine)).toMatchObject({
    baseline: persisted,
    dirty: true,
    instance: { title: 'My copy', scope: persisted.scope, revision: 'r2' },
    filterDraft: { root: draft },
    filterPending: true,
  });
  expect(
    selected(engine).instance.config.presentation.table.columns[0].width,
  ).toBe(200);
  expect(selected(engine, 'mine').baseline).toEqual(source.baseline);
  expect(selected(engine, 'mine').instance.title).toBe(source.instance.title);
  expect(selected(engine, 'mine').filterDraft.root).toEqual(draft);
  expect(host.instance!.save).not.toHaveBeenCalled();
  engine.dispose();
});

it('never treats matching list content as confirmation of a malformed creation', async () => {
  const listInstances = vi.fn(async () => ({
    instances: [instance(), { ...instance('other-request'), title: 'My copy' }],
    defaultInstanceId: 'mine',
  }));
  const create = vi.fn().mockResolvedValue(null);
  const { engine } = setup({
    host: { instance: { create, list: listInstances } } as unknown as ViewHost,
  });
  await engine.load();
  await expect(
    engine.saveAs({ title: 'My copy', scope: { type: 'personal' } }),
  ).rejects.toThrow();
  await expect(engine.reloadInstance()).rejects.toThrow('视图实例必须是对象');
  expect(engine.getSnapshot().selectedInstanceId).toBe('mine');
  expect(selected(engine).requiresReload).toBe(true);
  expect(listInstances).not.toHaveBeenCalled();
  expect(create).toHaveBeenCalledTimes(2);
  expect(create.mock.calls[0][1].requestId).toBe(
    create.mock.calls[1][1].requestId,
  );
  await expect(
    engine.saveAs({ title: 'Changed copy', scope: { type: 'personal' } }),
  ).rejects.toThrow('原配置重试');
  engine.dispose();
});

it('does not roll back an opened copy when its save finishes before an older reconciliation read', async () => {
  const persisted = { ...instance('created'), title: 'Normalized' };
  const oldRead = deferred<ViewInstance>();
  const loadInstance = vi
    .fn()
    .mockResolvedValueOnce(persisted)
    .mockReturnValueOnce(oldRead.promise);
  const { engine } = setup({
    host: {
      instance: { create: async () => persisted, load: loadInstance },
    } as unknown as ViewHost,
  });
  await engine.load();
  await expect(
    engine.saveAs({ title: 'Copy', scope: { type: 'personal' } }),
  ).rejects.toThrow();
  await engine.selectInstance('created');
  const reload = engine.reloadInstance('mine');
  engine.setTitle('Saved by user');
  await engine.save();
  const saved = selected(engine);
  oldRead.resolve(persisted);
  await reload;
  expect(selected(engine).instance).toEqual(saved.instance);
  expect(selected(engine).baseline).toEqual(saved.baseline);
  expect(selected(engine).baseline.revision).toBe('r2');
  expect(selected(engine, 'mine').requiresReload).toBe(false);
  engine.dispose();
});

it('can inspect a returned created ID using a list-only host without matching content', async () => {
  const persisted = { ...instance('created-known'), title: 'Normalized' };
  const list = vi.fn(async () => ({
    instances: [instance(), persisted],
    defaultInstanceId: 'mine',
  }));
  const create = vi.fn(async () => persisted);
  const { engine } = setup({
    host: { instance: { create, list } } as unknown as ViewHost,
  });
  await engine.load();
  await expect(
    engine.saveAs({ title: 'My copy', scope: { type: 'personal' } }),
  ).rejects.toThrow();
  await engine.reloadInstance();
  expect(engine.getSnapshot().selectedInstanceId).toBe(persisted.id);
  expect(selected(engine).baseline).toEqual(persisted);
  expect(selected(engine).instance.title).toBe('My copy');
  expect(create).toHaveBeenCalledOnce();
  expect(list).toHaveBeenCalledOnce();
  engine.dispose();
});
