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
import type {
  ViewDefinition,
  ViewInstance,
} from '../../src/contracts/viewModel.js';
import type { ViewHost } from '../../src/contracts/ViewHost.js';
import { deferred, definition, instance, selected, setup } from './fixtures.js';

it.each([{ instances: [] }, { instances: [instance()] }])(
  'keeps explicit null defaults unselected without querying (%j)',
  async ({ instances }) => {
    const { engine, paged } = setup({
      instances: { instances, defaultInstanceId: null },
    });
    await engine.load();
    expect(engine.getSnapshot()).toMatchObject({
      status: 'ready',
      selectedInstanceId: null,
      instanceIds: instances.map(item => item.id),
    });
    expect(paged).not.toHaveBeenCalled();
    engine.dispose();
  },
);

it('rejects a foreign or duplicate list before selecting or querying', async () => {
  for (const entries of [
    [instance(), { ...instance('bad'), definitionId: 'foreign' }],
    [instance(), instance()],
  ]) {
    const { engine, paged } = setup({
      instances: { instances: entries, defaultInstanceId: 'mine' },
    });
    await expect(engine.load()).rejects.toThrow();
    expect(engine.getSnapshot()).toMatchObject({
      status: 'error',
      selectedInstanceId: null,
      instanceIds: [],
    });
    expect(paged).not.toHaveBeenCalled();
  }
});

it('loads the definition and list concurrently and never rereads a complete selected instance', async () => {
  const definitionRead = deferred<ViewDefinition>();
  const listRead = deferred<{
    instances: ViewInstance[];
    defaultInstanceId: string;
  }>();
  const loadDefinition = vi.fn(() => definitionRead.promise);
  const listInstances = vi.fn(() => listRead.promise);
  const loadInstance = vi.fn();
  const { engine } = setup({
    definition: undefined,
    instances: undefined,
    host: {
      definition: { load: loadDefinition },
      instance: { list: listInstances, load: loadInstance },
    } as unknown as ViewHost,
  });
  const loading = engine.load();
  await vi.waitFor(() => {
    expect(loadDefinition).toHaveBeenCalledOnce();
    expect(listInstances).toHaveBeenCalledOnce();
  });
  definitionRead.resolve(definition);
  listRead.resolve({
    instances: [instance(), instance('shared')],
    defaultInstanceId: 'mine',
  });
  await loading;
  await engine.selectInstance('shared');
  expect(loadInstance).not.toHaveBeenCalled();
  expect(engine.getSnapshot().selectedInstanceId).toBe('shared');
});

it('ignores obsolete loads and prevents query dispatch or snapshot commits after disposal', async () => {
  const definitionRead = deferred<ViewDefinition>();
  const loadDefinition = vi
    .fn()
    .mockImplementationOnce(() => definitionRead.promise)
    .mockResolvedValue(definition);
  const { engine, paged } = setup({
    definition: undefined,
    host: { definition: { load: loadDefinition } } as unknown as ViewHost,
  });
  const first = engine.load();
  await vi.waitFor(() => expect(loadDefinition).toHaveBeenCalledOnce());
  await engine.load();
  definitionRead.reject(new Error('obsolete load'));
  await first;
  expect(engine.getSnapshot().status).toBe('ready');
  const sourceRead = deferred<unknown>();
  const delayed = setup({
    host: { resolveSource: () => sourceRead.promise } as ViewHost,
  });
  const loading = delayed.engine.load();
  await vi.waitFor(() =>
    expect(selected(delayed.engine).queryStatus).toBe('loading'),
  );
  const snapshot = delayed.engine.getSnapshot();
  const listener = vi.fn();
  delayed.engine.subscribe(listener);
  delayed.engine.dispose();
  sourceRead.resolve({ paged, cursor: vi.fn() });
  await loading;
  expect(paged).toHaveBeenCalledOnce();
  expect(delayed.engine.getSnapshot()).toBe(snapshot);
  expect(listener).not.toHaveBeenCalled();
  await expect(
    delayed.engine
      .record(delayed.engine.getSnapshot().selectedInstanceId!)
      .refresh(),
  ).rejects.toThrow();
});
