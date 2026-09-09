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
import { deferred, instance, selected, setup } from './fixtures.js';

it('selects a saved copy using the latest source config, leaving the source draft untouched', async () => {
  const response = deferred<ViewInstance>();
  const createInstance = vi.fn(() => response.promise);
  const { engine } = setup({
    host: { instance: { create: createInstance } } as unknown as ViewHost,
  });
  await engine.load();
  engine.setTitle('Source draft');
  const saving = engine.saveAs({
    title: 'Copy',
    scope: { type: 'public', source: 'shared' },
  });
  engine.setColumns([
    { id: 'amount', kind: 'field', field: 'state.amount', width: 300 },
  ]);
  const latestConfig = selected(engine).instance.config;
  response.resolve({
    ...instance('created'),
    title: 'Copy',
    scope: { type: 'public', source: 'shared' },
  });
  await saving;
  expect(engine.getSnapshot().selectedInstanceId).toBe('created');
  expect(selected(engine)).toMatchObject({
    dirty: true,
    baseline: { title: 'Copy' },
    instance: { title: 'Copy', config: latestConfig },
  });
  expect(selected(engine, 'mine')).toMatchObject({
    dirty: true,
    baseline: { title: 'mine' },
    instance: { title: 'Source draft', config: latestConfig },
  });
});

it('adds a saved copy without stealing selection after navigation', async () => {
  const response = deferred<ViewInstance>();
  const { engine } = setup({
    host: {
      instance: { create: () => response.promise },
    } as unknown as ViewHost,
  });
  await engine.load();
  const saving = engine.saveAs({
    title: 'Copy',
    scope: { type: 'personal' },
  });
  await engine.selectInstance('shared');
  response.resolve({ ...instance('created'), title: 'Copy' });
  await saving;
  expect(engine.getSnapshot().selectedInstanceId).toBe('shared');
  expect(selected(engine, 'created')).toMatchObject({
    dirty: false,
    queryStatus: 'idle',
    instance: { title: 'Copy' },
  });
});

it('does not let save-as completion cancel a newer pending navigation', async () => {
  const write = deferred<ViewInstance>();
  const read = deferred<ViewInstance>();
  const { engine } = setup({
    host: {
      instance: { create: () => write.promise, load: () => read.promise },
    } as unknown as ViewHost,
  });
  await engine.load();
  const saving = engine.saveAs({
    title: 'Copy',
    scope: { type: 'personal' },
  });
  const navigating = engine.selectInstance('remote');
  write.resolve({ ...instance('created'), title: 'Copy' });
  await saving;
  expect(engine.getSnapshot().selectedInstanceId).toBe('mine');
  read.resolve(instance('remote'));
  await navigating;
  expect(engine.getSnapshot().selectedInstanceId).toBe('remote');
  expect(selected(engine, 'created').queryStatus).toBe('idle');
});

it('rejects system-copy scopes, duplicate new IDs and mismatched copy content', async () => {
  const { engine, host } = setup();
  await engine.load();
  await expect(
    engine.saveAs({
      title: 'Copy',
      scope: { type: 'public', source: 'system' } as never,
    }),
  ).rejects.toThrow();
  expect(host.instance!.create).not.toHaveBeenCalled();
  for (const result of [
    { ...instance(), title: 'Copy' },
    { ...instance('created'), title: 'Changed' },
  ]) {
    const invalid = setup({
      host: { instance: { create: async () => result } } as unknown as ViewHost,
    });
    await invalid.engine.load();
    await expect(
      invalid.engine.saveAs({ title: 'Copy', scope: { type: 'personal' } }),
    ).rejects.toThrow();
    expect(selected(invalid.engine).requiresReload).toBe(true);
    expect(invalid.engine.getSnapshot().instanceIds).toEqual([
      'mine',
      'shared',
    ]);
  }
});

it('ignores write completion after disposal without adding or selecting the created instance', async () => {
  const response = deferred<ViewInstance>();
  const { engine } = setup({
    host: {
      instance: { create: () => response.promise },
    } as unknown as ViewHost,
  });
  await engine.load();
  const saving = engine.saveAs({
    title: 'Copy',
    scope: { type: 'personal' },
  });
  const snapshot = engine.getSnapshot();
  engine.dispose();
  response.resolve({ ...instance('created'), title: 'Copy' });
  await saving;
  expect(engine.getSnapshot()).toBe(snapshot);
  expect(engine.getSnapshot().instanceIds).toEqual(['mine', 'shared']);
});
