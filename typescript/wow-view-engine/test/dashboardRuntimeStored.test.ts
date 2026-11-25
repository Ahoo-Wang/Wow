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
 * A board stored in a shape admission refuses, opened as a view to be
 * fixed. The view store service writes no such config (its panels must be
 * an array of objects, `ViewConfigs.problemOf`), nor does `MemoryViewStore`
 * as it answers what the service answers (#7067): the store holds one only
 * as data it was seeded with, and the engine still opens it.
 */

import { describe, expect, it } from 'vitest';
import {
  type DashboardPanel,
  type DashboardViewConfig,
  MemoryViewStore,
  ViewEngine,
} from '../src/index.js';
import {
  dashboardConfig,
  nextTask,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  resourcesOf,
  testEnvironment,
  testSource,
} from './fixtures.js';

/** A panel showing the saved view `pending`. */
function panel(): DashboardPanel {
  return {
    id: 'orders',
    kind: 'view',
    instanceId: 'pending',
    bindings: [],
    layout: { x: 0, y: 0, w: 6, h: 4 },
  } as DashboardPanel;
}

/** Opens `config` as a board stored as it is, beside the view its panels show. */
async function openStored(config: DashboardViewConfig) {
  const source = testSource();
  const store = new MemoryViewStore({
    instances: [
      {
        id: 'pending',
        definitionId: 'orders',
        title: 'Pending orders',
        scope: 'shared',
        revision: 'r1',
        config: recordConfig(),
      },
      {
        id: 'stored-board',
        definitionId: 'overview',
        title: 'Overview',
        scope: 'personal',
        revision: '1',
        config,
      },
    ],
  });
  const engine = new ViewEngine({
    resources: resourcesOf(
      [ordersDefinition(), overviewDefinition()],
      () => source,
    ),
    store,
    environment: testEnvironment().environment,
  });
  const runtime = await engine.open('stored-board');
  await nextTask();
  if (runtime.kind !== 'dashboard')
    throw new Error(`expected a dashboard, got ${runtime.kind}`);
  return { runtime, source };
}

describe('DashboardViewRuntime admission of a board stored as it is', () => {
  it('opens a config whose panels are not a list as a view to be fixed', async () => {
    const { runtime } = await openStored({
      ...dashboardConfig(),
      panels: 'x' as never,
    });

    expect(runtime.getSnapshot().issues).toMatchObject([
      { code: 'dashboard.shape.invalid', path: ['panels'] },
    ]);
    expect(runtime.getSnapshot().panels).toEqual([]);
    expect(() => runtime.edit({ refresh: { interval: null } })).not.toThrow();
  });

  it('opens a config with an entry that is no panel, and runs the rest', async () => {
    const { runtime, source } = await openStored(
      dashboardConfig({ panels: [null as never, panel()] }),
    );

    expect(runtime.getSnapshot().issues).toMatchObject([
      { code: 'dashboard.shape.invalid', path: ['panels', 0] },
    ]);
    // The entry has no id to stand under; the panel beside it still runs.
    expect(runtime.getSnapshot().panels).toHaveLength(1);
    expect(runtime.getSnapshot().panels[0].runtime).not.toBeNull();
    expect(source.paged).toHaveBeenCalledTimes(1);
  });
});
