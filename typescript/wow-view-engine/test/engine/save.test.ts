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
import type { ViewInstance } from '../../src/record/recordModel.js';
import type { ViewHost } from '../../src/record/ViewHost.js';
import { ViewServiceError } from '../../src/record/viewServiceContract.js';
import { deferred, instance, selected, setup } from './fixtures.js';

it('denies unavailable writes and pending filter drafts', async () => {
  const { engine, host } = setup({
    host: { permission: { getInstance: undefined } } as unknown as ViewHost,
  });
  await engine.load();
  expect(engine.getPermissions()).toEqual({
    save: false,
    saveAsPersonal: false,
    saveAsShared: false,
    delete: false,
    rename: false,
  });
  await expect(engine.save()).rejects.toThrow();
  expect(host.instance!.save).not.toHaveBeenCalled();
  expect(selected(engine).requiresReload).toBe(false);
  const pending = setup();
  await pending.engine.load();
  pending.engine.setFilterValidity(false);
  await expect(pending.engine.save()).rejects.toThrow();
  await expect(
    pending.engine.saveAs({ title: 'New', scope: { type: 'personal' } }),
  ).rejects.toThrow();
  expect(pending.host.instance!.save).not.toHaveBeenCalled();
  expect(pending.host.instance!.create).not.toHaveBeenCalled();
  expect(selected(pending.engine).writeError).toBeTruthy();
  expect(selected(pending.engine).requiresReload).toBe(false);
});

it('captures the submitted snapshot and revision while retaining subsequent edits', async () => {
  const response = deferred<ViewInstance>();
  const saveInstance = vi.fn(() => response.promise);
  const { engine } = setup({
    host: { instance: { save: saveInstance } } as unknown as ViewHost,
  });
  await engine.load();
  engine.setTitle('Submitted');
  const saving = engine.save();
  engine.setTitle('Latest');
  expect(saveInstance.mock.calls[0][0]).toMatchObject({
    title: 'Submitted',
    revision: 'r1',
  });
  response.resolve({ ...instance(), title: 'Submitted', revision: 'r2' });
  await saving;
  expect(selected(engine)).toMatchObject({
    baseline: { title: 'Submitted', revision: 'r2' },
    instance: { title: 'Latest', revision: 'r2' },
    dirty: true,
    writeStatus: 'idle',
  });
  engine.setTitle('Submitted');
  expect(selected(engine).dirty).toBe(false);
});

it('accepts host metadata changes while requiring exact persisted title, scope and config', async () => {
  const { engine } = setup({
    host: {
      instance: {
        save: async value => ({
          ...value,
          revision: 'r2',
          updatedAt: 'today',
        }),
        create: async value => ({
          ...value,
          id: 'created',
          createdAt: 'today',
        }),
      },
    } as unknown as ViewHost,
  });
  await engine.load();
  engine.setTitle('Submitted');
  await engine.save();
  expect(selected(engine)).toMatchObject({
    dirty: false,
    requiresReload: false,
    instance: { updatedAt: 'today' },
    baseline: { updatedAt: 'today' },
  });
  await engine.saveAs({ title: 'Copy', scope: { type: 'personal' } });
  expect(selected(engine)).toMatchObject({
    dirty: false,
    requiresReload: false,
    instance: { createdAt: 'today' },
    baseline: { createdAt: 'today' },
  });
});

it('blocks same-instance concurrent writes and keeps ordinary failures visible without retrying', async () => {
  const response = deferred<ViewInstance>();
  const saveInstance = vi.fn(() => response.promise);
  const { engine, host } = setup({
    host: { instance: { save: saveInstance } } as unknown as ViewHost,
  });
  await engine.load();
  engine.setTitle('Keep draft');
  const saving = engine.save();
  await expect(
    engine.saveAs({ title: 'Duplicate', scope: { type: 'personal' } }),
  ).rejects.toThrow();
  expect(host.instance!.create).not.toHaveBeenCalled();
  response.reject(new ViewServiceError('REVISION_CONFLICT', 'conflict'));
  await expect(saving).rejects.toThrow('conflict');
  expect(selected(engine)).toMatchObject({
    writeError: 'conflict',
    requiresReload: false,
    dirty: true,
    instance: { title: 'Keep draft' },
    baseline: { title: 'mine' },
  });
  expect(saveInstance).toHaveBeenCalledOnce();
});

it('requires explicit reload after a malformed or changed echo, preserving local edits against the loaded baseline', async () => {
  for (const response of [
    { ...instance(), title: 'normalized' },
    { ...instance(), definitionId: 'foreign' },
    { ...instance(), id: 'other' },
    { ...instance(), kind: 'dashboard' },
    null,
  ]) {
    const saveInstance = vi.fn().mockResolvedValue(response);
    const loadInstance = vi.fn(async () => ({
      ...instance(),
      title: 'Server title',
      revision: 'r9',
    }));
    const { engine } = setup({
      host: {
        instance: { save: saveInstance, load: loadInstance },
      } as unknown as ViewHost,
    });
    await engine.load();
    engine.setTitle('My draft');
    await expect(engine.save()).rejects.toThrow();
    expect(selected(engine)).toMatchObject({
      requiresReload: true,
      instance: { title: 'My draft' },
      baseline: { title: 'mine' },
    });
    await expect(engine.save()).rejects.toThrow();
    expect(saveInstance).toHaveBeenCalledOnce();
    const draft = newFilterNode(FilterOperator.EQ, 'state.amount');
    engine.setFilterDraft(createFilterConfiguration(draft));
    engine.setFilterValidity(false);
    await engine.reloadInstance();
    expect(selected(engine)).toMatchObject({
      requiresReload: false,
      dirty: true,
      filterDraft: { root: draft },
      filterPending: true,
      instance: { title: 'My draft', revision: 'r9' },
      baseline: { title: 'Server title', revision: 'r9' },
    });
  }
});

it('does not let a host mutate the write request to validate a changed echo', async () => {
  const { engine } = setup({
    host: {
      instance: {
        save: async value => {
          value.title = 'Mutated';
          return value;
        },
      },
    } as unknown as ViewHost,
  });
  await engine.load();
  engine.setTitle('Submitted');
  await expect(engine.save()).rejects.toThrow();
  expect(selected(engine)).toMatchObject({
    requiresReload: true,
    baseline: { title: 'mine' },
    instance: { title: 'Submitted' },
  });
});

it('reload adopts server scope while preserving locally editable content', async () => {
  const saveInstance = vi.fn(async (value: ViewInstance) => ({
    ...value,
    revision: 'r10',
  }));
  const { engine } = setup({
    host: {
      instance: {
        load: async () => ({
          ...instance(),
          scope: { type: 'public', source: 'shared' },
          revision: 'r9',
        }),
        save: saveInstance,
      },
    } as unknown as ViewHost,
  });
  await engine.load();
  engine.setTitle('My draft');
  await engine.reloadInstance();
  expect(selected(engine)).toMatchObject({
    dirty: true,
    instance: {
      scope: { type: 'public', source: 'shared' },
      title: 'My draft',
      revision: 'r9',
    },
    baseline: { scope: { type: 'public', source: 'shared' }, revision: 'r9' },
  });
  await engine.save();
  expect(saveInstance.mock.calls[0][0].scope).toEqual({
    type: 'public',
    source: 'shared',
  });
  engine.dispose();
});
