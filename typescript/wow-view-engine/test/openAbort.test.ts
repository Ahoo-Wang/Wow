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

/**
 * An open the caller no longer wants (`ViewEngine.open(id, { signal })`):
 * the hooks abort theirs when they unmount or the id changes. Called off at
 * any step, it rejects with the abort and leaves no runtime registered —
 * nobody was handed one, so nobody could close it.
 */

import { describe, expect, it, vi } from 'vitest';
import type { DescriptorResult } from '@ahoo-wang/wow-client/dsl';
import {
  emptyDashboardConfig,
  MemoryViewStore,
  ViewEngine,
  type ViewInstance,
  type ViewStore,
} from '../src/index.js';
import {
  deferred,
  nextTask,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  resourcesOf,
  testEnvironment,
  testSource,
} from './fixtures.js';
import { ordersDescriptor, read } from './fixtures/descriptor.js';

const mine: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'Mine',
  scope: 'personal',
  revision: '1',
  config: recordConfig(),
};

describe('an open called off', () => {
  it('hands the signal to the store, and rejects an open called off while its source describes itself', async () => {
    const described = deferred<DescriptorResult>();
    const source = testSource({ describe: vi.fn(() => described.promise) });
    const store = new MemoryViewStore({ instances: [mine] });
    const get = vi.spyOn(store, 'get');
    const engine = new ViewEngine({
      resources: resourcesOf([ordersDefinition()], () => source),
      store,
      environment: testEnvironment().environment,
    });
    const controller = new AbortController();

    const opening = engine.open('orders-1', { signal: controller.signal });
    await nextTask();
    expect(get).toHaveBeenCalledWith('orders-1', { signal: controller.signal });
    expect(source.describe).toHaveBeenCalled();
    controller.abort();
    described.resolve(read(ordersDescriptor()));

    await expect(opening).rejects.toMatchObject({ name: 'AbortError' });
    expect(engine.openRuntimes()).toEqual([]);
    expect(source.paged).not.toHaveBeenCalled();
  });

  it('disposes a board called off while it waits for its panels, and keeps none of it', async () => {
    const board: ViewInstance = {
      ...mine,
      id: 'overview-1',
      definitionId: 'overview',
      config: {
        ...emptyDashboardConfig(),
        panels: [
          {
            id: 'orders',
            kind: 'view',
            instanceId: 'orders-1',
            bindings: [],
            layout: { x: 0, y: 0, w: 6, h: 4 },
          },
        ],
      },
    };
    const store = new MemoryViewStore({ instances: [mine, board] });
    const panel = deferred<ViewInstance>();
    const port: ViewStore = store;
    const get = port.get.bind(port);
    vi.spyOn(port, 'get').mockImplementation((id, options) =>
      id === 'orders-1' ? panel.promise : get(id, options),
    );
    const engine = new ViewEngine({
      resources: resourcesOf([ordersDefinition(), overviewDefinition()], () =>
        testSource(),
      ),
      store,
      environment: testEnvironment().environment,
    });
    const controller = new AbortController();

    const opening = engine.open('overview-1', { signal: controller.signal });
    await nextTask();
    // Attached, and waiting for the view its panel shows.
    expect(engine.openRuntimes()).toHaveLength(1);
    controller.abort();
    panel.resolve(mine);

    await expect(opening).rejects.toMatchObject({ name: 'AbortError' });
    expect(engine.openRuntimes()).toEqual([]);
  });
});
