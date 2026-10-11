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

import { describe, expect, it } from 'vitest';
import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  type DashboardViewConfig,
  type ViewInstance,
} from '../src/index.js';
import {
  dashboardConfig,
  mine,
  ordersDefinition,
  overviewDefinition,
  resourcesOf,
  testSource,
} from './fixtures.js';
import { panel, pending } from './fixtures/dashboard.js';

const board: ViewInstance = {
  id: 'board-1',
  definitionId: 'overview',
  title: 'Daily',
  scope: 'personal',
  revision: 'r1',
  config: dashboardConfig({ panels: [panel()] }),
};

function engineWith(
  maxDashboardPanels = DEFAULT_RUNTIME_LIMITS.maxDashboardPanels,
) {
  const store = new MemoryViewStore({ instances: [mine, pending, board] });
  const engine = new ViewEngine({
    resources: resourcesOf([ordersDefinition(), overviewDefinition()], () =>
      testSource(),
    ),
    store,
    limits: { ...DEFAULT_RUNTIME_LIMITS, maxDashboardPanels },
  });
  return { engine, store };
}

const panelsOf = (instance: ViewInstance) =>
  (instance.config as DashboardViewConfig).panels;

describe('放进仪表盘: engine.addToDashboard (R2-98)', () => {
  it('adds the view as a panel at the first free place, as the board’s save', async () => {
    const { engine, store } = engineWith();

    const saved = await engine.addToDashboard('board-1', mine.id);

    expect(saved.revision).not.toBe('r1');
    const added = panelsOf(saved)[panelsOf(saved).length - 1]!;
    expect(added).toMatchObject({ kind: 'view', instanceId: mine.id });
    // Beside the panel already there, at the size a record view starts at.
    expect(added.layout).toEqual({ x: 0, y: 4, w: 24, h: 5 });
    expect(panelsOf(await store.get('board-1'))).toHaveLength(2);
  });

  it('refuses what is no board, and a board already full', async () => {
    await expect(
      engineWith().engine.addToDashboard(mine.id, pending.id),
    ).rejects.toMatchObject({
      issue: { code: 'view.addToDashboard.not-board' },
    });
    await expect(
      engineWith(1).engine.addToDashboard('board-1', mine.id),
    ).rejects.toMatchObject({
      issue: { code: 'view.addToDashboard.full', params: { max: 1 } },
    });
  });

  it('refuses a board open here with edits, and moves one open without', async () => {
    const { engine } = engineWith();
    const open = await engine.open('board-1');

    const saved = await engine.addToDashboard('board-1', mine.id);
    // The open board takes it as it landed: the panel in, nothing unsaved.
    expect(panelsOf({ ...board, config: open.getSnapshot().draft })).toEqual(
      panelsOf(saved),
    );
    expect(open.getSnapshot().dirty).toBe(false);

    open.edit({ refresh: { interval: 300 } });
    expect(open.getSnapshot().dirty).toBe(true);
    await expect(
      engine.addToDashboard('board-1', pending.id),
    ).rejects.toMatchObject({ issue: { code: 'view.addToDashboard.unsaved' } });
  });
});
