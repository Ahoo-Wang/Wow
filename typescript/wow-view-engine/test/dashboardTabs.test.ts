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
 * A board's tabs in the runtime (D22 E, batch B3): only the tab on screen
 * runs; a tab shown before keeps its rows and runs again only when what it
 * asks changed or a refresh went by while it was away; a board opens on the
 * tab its host names, else the one its reader last read it on, which the
 * engine remembers among the reader's preferences.
 */

import { describe, expect, it, vi } from 'vitest';
import {
  DashboardViewRuntime,
  MemoryViewStore,
  ViewEngine,
  ViewStoreError,
  type DashboardPanel,
  type DashboardViewConfig,
  type ViewInstance,
} from '../src/index.js';
import {
  analysisConfig,
  dashboardConfig,
  nextTask,
  ordersDefinition,
  overviewDefinition,
  testEnvironment,
  testSource,
} from './fixtures.js';

const byWarehouse: ViewInstance = {
  id: 'by-warehouse',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'shared',
  revision: 'r1',
  config: analysisConfig(),
};

function panel(id: string, tab: string, x = 0): DashboardPanel {
  return {
    id,
    kind: 'view',
    instanceId: 'by-warehouse',
    bindings: [],
    layout: { x, y: 0, w: 12, h: 4 },
    tab,
  } as DashboardPanel;
}

/** Two tabs, two panels on the first, one on the second. */
const tabbed = dashboardConfig({
  tabs: [
    { id: 'overview', title: 'Overview' },
    { id: 'detail', title: 'Detail' },
  ],
  panels: [
    panel('a', 'overview'),
    panel('b', 'overview', 12),
    panel('c', 'detail'),
  ],
});

function harness(store = new MemoryViewStore({ instances: [byWarehouse] })) {
  const source = testSource();
  const engine = new ViewEngine({
    definitions: [ordersDefinition(), overviewDefinition()],
    store,
    resolveSource: () => source,
    environment: testEnvironment().environment,
  });
  let board: ViewInstance | null = null;
  return {
    engine,
    store,
    source,
    asked: () => vi.mocked(source.aggregate).mock.calls.length,
    async open(config: DashboardViewConfig = tabbed, tab?: string) {
      board ??= await store.create(
        {
          definitionId: 'overview',
          title: 'Overview',
          scope: 'shared',
          config,
        },
        { requestId: 'board' },
      );
      const runtime = await engine.open(
        board.id,
        tab === undefined ? {} : { tab },
      );
      await nextTask();
      if (!(runtime instanceof DashboardViewRuntime))
        throw new Error('expected a dashboard');
      return runtime;
    },
    get id() {
      return board?.id ?? '';
    },
  };
}

function byId(runtime: DashboardViewRuntime, id: string) {
  const found = runtime.getSnapshot().panels.find(entry => entry.id === id);
  if (!found) throw new Error(`no panel ${id}`);
  return found;
}

describe('only the tab on screen runs', () => {
  it('opens on the first tab and asks nothing for the others', async () => {
    const board = harness();
    const runtime = await board.open();

    expect(runtime.getSnapshot().tab).toBe('overview');
    expect(byId(runtime, 'a').runtime).not.toBeNull();
    expect(byId(runtime, 'b').runtime).not.toBeNull();
    // Not broken, not asked: a panel whose tab has not been shown yet.
    expect(byId(runtime, 'c')).toMatchObject({
      runtime: null,
      waiting: true,
      tab: 'detail',
      issues: [],
    });
    expect(board.asked()).toBe(2);
  });

  it('runs a tab when it is shown, and keeps the rows of the one left', async () => {
    const board = harness();
    const runtime = await board.open();
    const first = byId(runtime, 'a').runtime;

    runtime.showTab('detail');
    await nextTask();
    expect(runtime.getSnapshot().tab).toBe('detail');
    expect(byId(runtime, 'c').runtime).not.toBeNull();
    expect(byId(runtime, 'c').waiting).toBe(false);
    expect(board.asked()).toBe(3);

    // Back: the same child with the same rows, nothing asked again.
    runtime.showTab('overview');
    await nextTask();
    expect(byId(runtime, 'a').runtime).toBe(first);
    expect(first?.getSnapshot().result).not.toBeNull();
    expect(board.asked()).toBe(3);
    expect(runtime.getSnapshot().dirty).toBe(false);
  });

  it('refreshes the tab on screen, and the others when they are shown', async () => {
    const board = harness();
    const runtime = await board.open();
    runtime.showTab('detail');
    await nextTask();
    const before = board.asked();

    runtime.refresh();
    await nextTask();
    // Only panel c, on the tab shown.
    expect(board.asked()).toBe(before + 1);

    runtime.showTab('overview');
    await nextTask();
    // Both panels of the tab that missed the refresh, once each.
    expect(board.asked()).toBe(before + 3);

    runtime.showTab('detail');
    await nextTask();
    expect(board.asked()).toBe(before + 3);
  });

  it('shows the first tab for a tab the board does not have, and when the one shown is removed', async () => {
    const board = harness();
    const runtime = await board.open();

    runtime.showTab('nowhere');
    expect(runtime.getSnapshot().tab).toBe('overview');

    runtime.showTab('detail');
    runtime.setBuilding(true);
    runtime.removeTab('detail');
    await nextTask();
    expect(runtime.getSnapshot().tab).toBe('overview');
    expect(runtime.getSnapshot().panels.map(entry => entry.id)).toEqual([
      'a',
      'b',
    ]);
  });

  it('keeps a panel moved to a tab not shown as it was, and runs it there only if it must', async () => {
    const board = harness();
    const runtime = await board.open();
    const moved = byId(runtime, 'a').runtime;
    runtime.setBuilding(true);

    runtime.movePanelToTab('a', 'detail');
    await nextTask();
    expect(byId(runtime, 'a')).toMatchObject({ tab: 'detail', runtime: moved });
    const asked = board.asked();

    runtime.showTab('detail');
    await nextTask();
    // c runs for the first time; a asks nothing again.
    expect(board.asked()).toBe(asked + 1);
    expect(byId(runtime, 'a').runtime).toBe(moved);
  });

  it('runs everything on a board without tabs', async () => {
    const board = harness();
    const runtime = await board.open(
      dashboardConfig({
        panels: [panel('a', ''), panel('b', '', 12)].map(
          entry =>
            Object.fromEntries(
              Object.entries(entry).filter(([key]) => key !== 'tab'),
            ) as DashboardPanel,
        ),
      }),
    );
    expect(runtime.getSnapshot().tab).toBeNull();
    expect(board.asked()).toBe(2);
  });
});

describe('where a board opens', () => {
  it('opens on the tab the host names, without asking for the first', async () => {
    const board = harness();
    const runtime = await board.open(tabbed, 'detail');

    expect(runtime.getSnapshot().tab).toBe('detail');
    expect(byId(runtime, 'a').waiting).toBe(true);
    expect(board.asked()).toBe(1);
  });

  it('opens on the tab its reader last read it on', async () => {
    const board = harness();
    const first = await board.open();
    await board.engine.rememberTab('overview', board.id, 'detail');
    board.engine.close(first);

    expect(
      (await board.store.getPreferences('overview')).lastTabs?.[board.id],
    ).toBe('detail');
    const again = await board.open();
    expect(again.getSnapshot().tab).toBe('detail');
    // The host's route outranks the preference.
    board.engine.close(again);
    expect((await board.open(tabbed, 'overview')).getSnapshot().tab).toBe(
      'overview',
    );
  });

  it('writes a burst of switches as the last one, one write at a time', async () => {
    const store = new MemoryViewStore({ instances: [byWarehouse] });
    const writes = vi.spyOn(store, 'setPreferences');
    const board = harness(store);
    await board.open();

    await Promise.all([
      board.engine.rememberTab('overview', board.id, 'detail'),
      board.engine.rememberTab('overview', board.id, 'overview'),
      board.engine.rememberTab('overview', board.id, 'detail'),
    ]);
    expect((await store.getPreferences('overview')).lastTabs?.[board.id]).toBe(
      'detail',
    );
    expect(writes.mock.calls.length).toBeLessThanOrEqual(2);

    // Already remembered: nothing is written.
    const count = writes.mock.calls.length;
    await board.engine.rememberTab('overview', board.id, 'detail');
    expect(writes).toHaveBeenCalledTimes(count);
  });

  it('lets a failed write go, leaving nothing to hold the list’s own writes back', async () => {
    const store = new MemoryViewStore({ instances: [byWarehouse] });
    const board = harness(store);
    await board.open();
    vi.spyOn(store, 'setPreferences').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'down'),
    );

    await expect(
      board.engine.rememberTab('overview', board.id, 'detail'),
    ).resolves.toBeUndefined();
    expect(board.engine.pendingWrites().size).toBe(0);
    await expect(
      board.engine.setAutoRun('overview', false),
    ).resolves.toMatchObject({ autoRun: false });
  });

  it('opens on the first tab when the preferences cannot be read', async () => {
    const store = new MemoryViewStore({ instances: [byWarehouse] });
    const board = harness(store);
    vi.spyOn(store, 'getPreferences').mockRejectedValue(
      new ViewStoreError('UNAVAILABLE', 'down'),
    );
    const runtime = await board.open();
    expect(runtime.getSnapshot().tab).toBe('overview');
  });
});
