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
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import {
  newFilterNode,
  createFilterConfiguration,
} from '../../src/filter/filterCore.js';

import { afterEach, expect, it, vi } from 'vitest';
import {} from '@ahoo-wang/fetcher-wow';

import { ViewServiceError } from '../../src/contracts/viewServiceContract.js';
import { deferred, instance, selected, setup } from './fixtures.js';
import type { ViewEngine } from '../../src/engine/ViewEngine.js';
import type { ViewInstance } from '../../src/contracts/viewModel.js';

const engines: ViewEngine[] = [];
afterEach(() => engines.splice(0).forEach(engine => engine.dispose()));

it('uses authoritative scope while retaining editable title and filter draft on reload', async () => {
  const remote = {
    ...instance(),
    scope: { type: 'public', source: 'shared' } as const,
    revision: 'r2',
  };
  const { engine, host } = setup({
    host: { instance: { load: vi.fn().mockResolvedValue(remote) } },
  });
  engines.push(engine);
  await engine.load();
  engine.setTitle('Local title');
  const draft = {
    ...newFilterNode(FilterOperator.GTE, 'state.amount'),
    props: { value: 42 },
  };
  engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .setFilterDraft(createFilterConfiguration(draft));
  await engine.reloadInstance();
  expect(selected(engine).instance.scope).toEqual(remote.scope);
  expect(selected(engine).instance.title).toBe('Local title');
  expect(selected(engine).filterDraft.root).toEqual(draft);
  await engine.record(engine.getSnapshot().selectedInstanceId!).applyFilter();
  await engine.save();
  expect(vi.mocked(host.instance!.save!).mock.calls[0][0].scope).toEqual(
    remote.scope,
  );
});

it('retains an absent source only for creation recovery across repeated full loads', async () => {
  const list = vi
    .fn()
    .mockResolvedValue({ instances: [instance()], defaultInstanceId: 'mine' });
  const create = vi
    .fn()
    .mockRejectedValueOnce(
      new ViewServiceError('UNKNOWN_OUTCOME', 'response lost'),
    )
    .mockImplementation(async value => ({
      ...value,
      id: 'created',
      revision: 'r1',
    }));
  const { engine, paged } = setup({
    instances: undefined,
    host: { instance: { list, create } },
  });
  engines.push(engine);
  await engine.load();
  engine.setTitle('Local title');
  await expect(
    engine.saveAs({ title: 'Copy', scope: { type: 'personal' } }),
  ).rejects.toThrow('response lost');
  const draft = {
    ...newFilterNode(FilterOperator.GTE, 'state.amount'),
    props: { value: 42 },
  };
  engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .setFilterDraft(createFilterConfiguration(draft));
  list.mockResolvedValue({ instances: [], defaultInstanceId: null });
  await engine.load();
  await engine.load();
  expect(engine.getSnapshot().instanceIds).toEqual([]);
  expect(engine.getSnapshot().sessions.mine).toBeUndefined();
  expect(engine.getSnapshot().pendingCreates.mine.filterDraft.root).toEqual(
    draft,
  );
  expect(engine.getSnapshot().pendingCreates.mine.rows).toEqual([]);
  expect(engine.canReloadInstance('mine')).toBe(true);
  await expect(engine.record('mine').refresh()).rejects.toThrow();
  await expect(engine.save('mine')).rejects.toThrow();
  await engine.reloadInstance('mine');
  expect(create.mock.calls[1][1].requestId).toBe(
    create.mock.calls[0][1].requestId,
  );
  expect(create.mock.calls[1][0]).toEqual(create.mock.calls[0][0]);
  expect(engine.getSnapshot().pendingCreates).toEqual({});
  expect(engine.getSnapshot().instanceIds).toEqual(['created']);
  expect(selected(engine, 'created').filterDraft.root).toEqual(draft);
  expect(selected(engine, 'created').appliedFilter).toEqual({
    op: FilterOperator.MATCH_ALL,
  });
  expect(selected(engine, 'created').filterPending).toBe(true);
  expect(paged).toHaveBeenCalledOnce();
});

it('removes an absent recovery entry when the original creation is definitively rejected', async () => {
  const pending = deferred<ViewInstance>();
  const list = vi
    .fn()
    .mockResolvedValue({ instances: [instance()], defaultInstanceId: 'mine' });
  const { engine } = setup({
    instances: undefined,
    host: { instance: { list, create: () => pending.promise } },
  });
  engines.push(engine);
  await engine.load();
  const writing = engine.saveAs({ title: 'Copy', scope: { type: 'personal' } });
  list.mockResolvedValue({ instances: [], defaultInstanceId: null });
  await engine.load();
  expect(engine.canReloadInstance('mine')).toBe(true);
  pending.reject(new ViewServiceError('FORBIDDEN', 'denied'));
  await writing;
  expect(engine.getSnapshot().pendingCreates).toEqual({});
  expect(engine.canReloadInstance('mine')).toBe(false);
});

it('keeps absent recovery context on permission denial and uses current authority when access returns', async () => {
  const list = vi
    .fn()
    .mockResolvedValue({ instances: [instance()], defaultInstanceId: 'mine' });
  const create = vi
    .fn()
    .mockRejectedValueOnce(new ViewServiceError('UNKNOWN_OUTCOME', 'lost'))
    .mockImplementation(async value => ({
      ...value,
      id: 'created',
      revision: 'r1',
    }));
  let allowed = true;
  const { engine } = setup({
    instances: undefined,
    host: {
      instance: { list, create },
      permission: { getInstance: () => ({ saveAsPersonal: allowed }) },
    },
  });
  engines.push(engine);
  await engine.load();
  await expect(
    engine.saveAs({ title: 'Copy', scope: { type: 'personal' } }),
  ).rejects.toThrow('lost');
  list.mockResolvedValue({ instances: [], defaultInstanceId: null });
  await engine.load();
  allowed = false;
  await expect(engine.reloadInstance('mine')).rejects.toThrow('未允许');
  expect(create).toHaveBeenCalledOnce();
  expect(engine.getSnapshot().pendingCreates.mine.requiresReload).toBe(true);
  expect(engine.canReloadInstance('constructor')).toBe(false);
  await expect(engine.selectInstance('mine')).rejects.toThrow('待核对');
  allowed = true;
  await engine.reloadInstance('mine');
  expect(create).toHaveBeenCalledTimes(2);
  expect(engine.getSnapshot().pendingCreates).toEqual({});
});

it('adopts authoritative scope when reconciling a known created ID after its source disappears', async () => {
  const list = vi
    .fn()
    .mockResolvedValue({ instances: [instance()], defaultInstanceId: 'mine' });
  const created = {
    ...instance('created'),
    title: 'Copy',
    scope: { type: 'public', source: 'shared' } as const,
  };
  const create = vi
    .fn()
    .mockResolvedValue({ ...created, title: 'Wrong response' });
  const load = vi.fn().mockResolvedValue(created);
  const { engine } = setup({
    instances: undefined,
    host: { instance: { list, create, load } },
  });
  engines.push(engine);
  await engine.load();
  await expect(
    engine.saveAs({ title: 'Copy', scope: { type: 'personal' } }),
  ).rejects.toThrow('原样保存');
  list.mockResolvedValue({ instances: [], defaultInstanceId: null });
  await engine.load();
  await engine.reloadInstance('mine');
  expect(load.mock.calls[0][0]).toBe('created');
  expect(create).toHaveBeenCalledOnce();
  expect(selected(engine, 'created').instance.scope).toEqual(created.scope);
  expect(engine.getSnapshot().pendingCreates).toEqual({});
});

it('retains recovery editor if source reappears before reconciliation', async () => {
  const list = vi
    .fn()
    .mockResolvedValue({ instances: [instance()], defaultInstanceId: 'mine' });
  const create = vi
    .fn()
    .mockRejectedValueOnce(new ViewServiceError('UNKNOWN_OUTCOME', 'lost'))
    .mockImplementation(async value => ({
      ...value,
      id: 'created',
      revision: 'r1',
    }));
  const { engine } = setup({
    instances: undefined,
    host: { instance: { list, create } },
  });
  try {
    await engine.load();
    engine.setTitle('local title');
    await expect(
      engine.saveAs({ title: 'Copy', scope: { type: 'personal' } }),
    ).rejects.toThrow();
    const draft = structuredClone(
      engine.getSnapshot().sessions.mine.filterDraft.root,
    );
    draft.id = 'local-filter-draft';
    engine
      .record(engine.getSnapshot().selectedInstanceId!)
      .setFilterDraft(createFilterConfiguration(draft));
    list.mockResolvedValue({ instances: [], defaultInstanceId: null });
    await engine.load();
    expect(engine.getSnapshot().pendingCreates.mine.filterDraft.root.id).toBe(
      'local-filter-draft',
    );
    list.mockResolvedValue({
      instances: [instance()],
      defaultInstanceId: 'mine',
    });
    await engine.load();
    await engine.reloadInstance('mine');
    expect(engine.getSnapshot().sessions.created.filterDraft.root.id).toBe(
      'local-filter-draft',
    );
  } finally {
    engine.dispose();
  }
});
it('adopts authoritative scope when created copy is already listed', async () => {
  const list = vi
    .fn()
    .mockResolvedValue({ instances: [instance()], defaultInstanceId: 'mine' });
  const old = { ...instance('created'), title: 'Copy' };
  const authoritative = {
    ...old,
    scope: { type: 'public', source: 'shared' },
    revision: 'r2',
  };
  const create = vi.fn().mockResolvedValue({ ...old, title: 'wrong' });
  const load = vi.fn().mockResolvedValue(authoritative);
  const { engine } = setup({
    instances: undefined,
    host: { instance: { list, create, load } },
  });
  try {
    await engine.load();
    await expect(
      engine.saveAs({ title: 'Copy', scope: { type: 'personal' } }),
    ).rejects.toThrow();
    list.mockResolvedValue({ instances: [old], defaultInstanceId: 'created' });
    await engine.load();
    engine.setTitle('local copy title');
    await engine.reloadInstance('mine');
    expect(engine.getSnapshot().sessions.created.instance.scope).toEqual(
      authoritative.scope,
    );
    expect(engine.getSnapshot().sessions.created.instance.title).toBe(
      'local copy title',
    );
  } finally {
    engine.dispose();
  }
});

it('does not resurrect a copy deleted while reconciliation was in flight', async () => {
  const list = vi
    .fn()
    .mockResolvedValue({ instances: [instance()], defaultInstanceId: 'mine' });
  const old = { ...instance('created'), title: 'Copy' };
  const create = vi.fn().mockResolvedValue({ ...old, title: 'wrong' });
  let resolveLoad;
  const loaded = new Promise(resolve => {
    resolveLoad = resolve;
  });
  const load = vi.fn().mockReturnValue(loaded);
  const { engine } = setup({
    instances: undefined,
    host: {
      instance: {
        list,
        create,
        load,
        delete: vi.fn().mockResolvedValue({ defaultInstance: null }),
      },
      permission: {
        getInstance: () => ({ save: true, saveAsPersonal: true, delete: true }),
      },
    },
  });
  try {
    await engine.load();
    await expect(
      engine.saveAs({ title: 'Copy', scope: { type: 'personal' } }),
    ).rejects.toThrow();
    list.mockResolvedValue({ instances: [old], defaultInstanceId: 'created' });
    await engine.load();
    const reconciliation = engine.reloadInstance('mine');
    await engine.deleteInstance('created');
    expect(engine.getSnapshot().instanceIds).toEqual([]);
    resolveLoad(old);
    await reconciliation;
    expect(engine.getSnapshot().instanceIds).toEqual([]);
  } finally {
    engine.dispose();
  }
});
it('preserves independent copy unknown-save state during reconciliation', async () => {
  const list = vi
    .fn()
    .mockResolvedValue({ instances: [instance()], defaultInstanceId: 'mine' });
  const old = { ...instance('created'), title: 'Copy' };
  const create = vi.fn().mockResolvedValue({ ...old, title: 'wrong' });
  let resolveLoad;
  const loaded = new Promise(resolve => {
    resolveLoad = resolve;
  });
  const load = vi.fn().mockReturnValue(loaded);
  const { engine } = setup({
    instances: undefined,
    host: {
      instance: {
        list,
        create,
        load,
        save: vi
          .fn()
          .mockRejectedValue(
            new ViewServiceError('UNKNOWN_OUTCOME', 'independent save lost'),
          ),
      },
    },
  });
  try {
    await engine.load();
    await expect(
      engine.saveAs({ title: 'Copy', scope: { type: 'personal' } }),
    ).rejects.toThrow();
    list.mockResolvedValue({ instances: [old], defaultInstanceId: 'created' });
    await engine.load();
    const reconciliation = engine.reloadInstance('mine');
    engine.setTitle('new title', 'created');
    await expect(engine.save('created')).rejects.toThrow(
      'independent save lost',
    );
    expect(engine.getSnapshot().sessions.created.requiresReload).toBe(true);
    resolveLoad(old);
    await reconciliation;
    expect(engine.getSnapshot().sessions.created.requiresReload).toBe(true);
    expect(engine.getSnapshot().sessions.created.writeError).toBe(
      'independent save lost',
    );
  } finally {
    engine.dispose();
  }
});
it('does not resurrect a copy first opened and deleted during reconciliation', async () => {
  const list = vi
    .fn()
    .mockResolvedValue({ instances: [instance()], defaultInstanceId: 'mine' });
  const old = { ...instance('created'), title: 'Copy' };
  const create = vi.fn().mockResolvedValue({ ...old, title: 'wrong' });
  let resolveLoad;
  const loaded = new Promise(resolve => {
    resolveLoad = resolve;
  });
  const load = vi.fn().mockReturnValueOnce(loaded).mockResolvedValue(old);
  const { engine } = setup({
    instances: undefined,
    host: {
      instance: {
        list,
        create,
        load,
        delete: vi.fn().mockResolvedValue({ defaultInstance: null }),
      },
      permission: {
        getInstance: () => ({ save: true, saveAsPersonal: true, delete: true }),
      },
    },
  });
  try {
    await engine.load();
    await expect(
      engine.saveAs({ title: 'Copy', scope: { type: 'personal' } }),
    ).rejects.toThrow();
    const reconciliation = engine.reloadInstance('mine');
    await engine.selectInstance('created');
    await engine.deleteInstance('created');
    expect(engine.getSnapshot().instanceIds).toEqual(['mine']);
    resolveLoad(old);
    await reconciliation;
    expect(engine.getSnapshot().instanceIds).toEqual(['mine']);
  } finally {
    engine.dispose();
  }
});
it('does not resurrect a copy opened and deleted before original create receipt', async () => {
  const list = vi
    .fn()
    .mockResolvedValue({ instances: [instance()], defaultInstanceId: 'mine' });
  const old = { ...instance('created'), title: 'Copy' };
  let resolveCreate;
  const created = new Promise(resolve => {
    resolveCreate = resolve;
  });
  const create = vi.fn().mockReturnValue(created);
  const load = vi.fn().mockResolvedValue(old);
  const { engine } = setup({
    instances: undefined,
    host: {
      instance: {
        list,
        create,
        load,
        delete: vi.fn().mockResolvedValue({ defaultInstance: null }),
      },
      permission: {
        getInstance: () => ({ save: true, saveAsPersonal: true, delete: true }),
      },
    },
  });
  try {
    await engine.load();
    const saving = engine.saveAs({
      title: 'Copy',
      scope: { type: 'personal' },
    });
    await engine.selectInstance('created');
    await engine.deleteInstance('created');
    expect(engine.getSnapshot().instanceIds).toEqual(['mine']);
    resolveCreate(old);
    await saving;
    expect(engine.getSnapshot().instanceIds).toEqual(['mine']);
  } finally {
    engine.dispose();
  }
});
