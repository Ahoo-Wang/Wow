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
 * Leaving a board (D26 Q30, Q33), end to end through a host's route: the
 * four ways off it — 「在工作台中打开」 on a saved view, a click's view
 * destination, the follow-up menu, a board's own analysis — hand the
 * workbench the same two parts. What the page holds (here, a locked
 * filter) is the opened view's scope, on the applied bar with no ✕; what
 * the reader set on the board is the view's own condition, with one. And
 * the workbench draws the way back to the board, which the host routes.
 */

import { useState } from 'react';
import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type DashboardFilters,
  type DashboardPanel,
  type DashboardViewConfig,
  type ViewInstance,
  type ViewNavigation,
} from '../src/index.js';
import { DashboardViewRuntime } from '../src/runtime/dashboardRuntime.js';
import type { DataViewRuntime } from '../src/runtime/viewRuntime.js';
import { DataWorkbench, EmbeddedDashboard } from '../src/ui/index.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

const views: ViewInstance[] = [
  {
    id: 'by-warehouse',
    definitionId: 'orders',
    title: 'By warehouse',
    scope: 'shared',
    revision: 'r1',
    config: analysisConfig({ layout: 'table' }),
  },
  {
    id: 'list',
    definitionId: 'orders',
    title: 'Order list',
    scope: 'shared',
    revision: 'r1',
    config: recordConfig(),
  },
];

const BINDINGS = [
  { globalField: 'region', panelField: 'warehouse' },
  { globalField: 'state', panelField: 'status' },
];

function panel(
  id: string,
  title: string,
  at: { x: number; y: number },
  extra: Partial<DashboardPanel>,
): DashboardPanel {
  return {
    id,
    kind: 'view',
    title,
    bindings: BINDINGS,
    layout: { ...at, w: 12, h: 4 },
    ...extra,
  } as DashboardPanel;
}

/**
 * A region the page locks and a state the reader sets, over the four
 * panels a way off the board starts from.
 */
function board(): DashboardViewConfig {
  return dashboardConfig({
    fields: [
      { name: 'region', label: 'Region', kind: 'string' },
      { name: 'state', label: 'State', kind: 'string' },
    ],
    panels: [
      panel(
        'menu',
        'By warehouse',
        { x: 0, y: 0 },
        { instanceId: 'by-warehouse' },
      ),
      panel(
        'go',
        'To the list',
        { x: 12, y: 0 },
        {
          instanceId: 'by-warehouse',
          click: { kind: 'view', instanceId: 'list' },
        },
      ),
      panel('list', 'Order list', { x: 0, y: 4 }, { instanceId: 'list' }),
      panel(
        'mine',
        'Mine',
        { x: 12, y: 4 },
        {
          owned: {
            definitionId: 'orders',
            config: analysisConfig({ layout: 'table' }),
          },
        },
      ),
    ],
  });
}

/**
 * A host with a route: the board on its page, locked to one region, and
 * the workbench wherever a way off it goes; the way back opens the board
 * again on the filters it carries.
 */
function Host({
  engine,
  routed,
}: {
  engine: ViewEngine;
  routed(to: ViewNavigation): void;
}) {
  const [away, setAway] = useState<ViewNavigation | null>(null);
  const [filters, setFilters] = useState<DashboardFilters | undefined>();
  const go = (to: ViewNavigation) => {
    routed(to);
    if (to.kind === 'dashboard') {
      setFilters(to.filters);
      setAway(null);
    } else setAway(to);
  };
  if (away?.kind === 'view' || away?.kind === 'unsaved')
    return (
      <DataWorkbench
        engine={engine}
        definitionId={away.definitionId}
        handOver={away}
        onNavigate={go}
      />
    );
  return (
    <EmbeddedDashboard
      engine={engine}
      instanceId="board"
      interaction="interactive"
      filterModes={{ region: 'locked' }}
      pageValues={{ values: { region: ['CN'] } }}
      initialFilters={filters}
      onNavigate={go}
    />
  );
}

function setup() {
  const engine = new ViewEngine({
    definitions: [ordersDefinition(), overviewDefinition()],
    store: new MemoryViewStore({
      instances: [
        ...views,
        {
          id: 'board',
          definitionId: 'overview',
          title: 'Operations',
          scope: 'shared',
          revision: 'r1',
          config: board(),
        },
      ],
    }),
    resolveSource: () =>
      testSource({
        aggregate: vi.fn(() =>
          Promise.resolve([
            { warehouse: 'CN', orders: 2 },
            { warehouse: 'EU', orders: 1 },
          ]),
        ),
      }),
  });
  const routed = vi.fn<(to: ViewNavigation) => void>();
  render(<Host engine={engine} routed={routed} />);
  const runtime = () => {
    const found = engine
      .openRuntimes()
      .find(entry => entry instanceof DashboardViewRuntime);
    if (!(found instanceof DashboardViewRuntime))
      throw new Error('no board open');
    return found;
  };
  return {
    engine,
    routed,
    runtime,
    user: userEvent.setup({ pointerEventsCheck: 0 }),
  };
}

/** The reader sets the state on the board, and the panels run under it. */
async function readerSetsState(runtime: () => DashboardViewRuntime) {
  await screen.findByRole('group', { name: 'Order list' });
  runtime().setFilterValue('state', ['PENDING']);
  await waitFor(() =>
    expect(
      JSON.stringify(runtime().panelRuntime('list')?.scopeFilter),
    ).toContain('PENDING'),
  );
}

/**
 * A row of a table, once its answer is on screen: a panel's on the board,
 * named by the panel's frame, or the workbench's own result.
 */
async function rowOf(
  panelName: string | null,
  text: string,
): Promise<HTMLElement> {
  const frame = panelName
    ? await screen.findByRole('group', { name: panelName })
    : await screen.findByRole('table');
  return waitFor(() => {
    const row = within(frame)
      .getAllByRole('row')
      .find(entry => entry.textContent?.startsWith(text));
    if (!row) throw new Error(`no row ${text}`);
    return row;
  });
}

/** 「⋯ → 在工作台中打开」 on a panel. */
async function openInWorkbench(
  user: ReturnType<typeof userEvent.setup>,
  panelName: string,
) {
  await user.click(
    await screen.findByRole('button', { name: `Actions for “${panelName}”` }),
  );
  await user.click(
    await screen.findByRole('menuitem', { name: 'Open in the workbench' }),
  );
}

/**
 * What the opened view shows on 「正在显示」: the page's region as its
 * scope, with no ✕ — and the reader's state as its own, with one, which
 * takes it off. A way off that started from a press on the CN group
 * (`pressed`) carries the group's own condition too, which is the reader's
 * and so removable, although it names the same value the page holds.
 */
async function expectHandedOver(
  user: ReturnType<typeof userEvent.setup>,
  { pressed = false }: { pressed?: boolean } = {},
) {
  const bar = await waitFor(() => {
    const found = document.querySelector<HTMLElement>(
      '[data-slot="applied-bar"]',
    );
    expect(found?.querySelector('[data-scoped]')).not.toBeNull();
    return found!;
  });
  const scoped = [...bar.querySelectorAll<HTMLElement>('[data-scoped]')];
  expect(scoped.map(item => item.textContent)).toEqual([
    expect.stringMatching(/^Warehouse .*CN/),
  ]);
  expect(scoped[0]!.querySelector('button')).toBeNull();
  expect(
    within(bar).queryAllByRole('button', { name: /^Unset Warehouse .*CN/ }),
  ).toHaveLength(pressed ? 1 : 0);

  const unset = await within(bar).findByRole('button', {
    name: /^Unset Status .*PENDING/,
  });
  await user.click(unset);
  await waitFor(() =>
    expect(
      within(bar).queryByRole('button', { name: /^Unset Status/ }),
    ).toBeNull(),
  );
  // The page's hold stays where it was.
  expect(bar.querySelectorAll('[data-scoped]')).toHaveLength(1);
}

describe('leaving a board through the host’s route (D26 Q30)', () => {
  it('在工作台中打开 a saved view: the page’s hold its scope, the reader’s value its own, opened 「已修改」', async () => {
    const { routed, runtime, user } = setup();
    await readerSetsState(runtime);
    await openInWorkbench(user, 'Order list');

    expect(routed).toHaveBeenCalledWith(
      expect.objectContaining({
        kind: 'view',
        definitionId: 'orders',
        instanceId: 'list',
      }),
    );
    await screen.findByRole('heading', { level: 2, name: 'Order list' });
    // Saved as it was: what the board added is a change to it.
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="view-unsaved"]'),
      ).not.toBeNull(),
    );
    await expectHandedOver(user);
  });

  it('a click that opens another view hands it over the same way', async () => {
    const { routed, runtime, user } = setup();
    await readerSetsState(runtime);
    await user.click(await rowOf('To the list', 'CN'));

    await waitFor(() =>
      expect(routed).toHaveBeenCalledWith(
        expect.objectContaining({ kind: 'view', instanceId: 'list' }),
      ),
    );
    await screen.findByRole('heading', { level: 2, name: 'Order list' });
    await expectHandedOver(user, { pressed: true });
  });

  it('a follow-up on a group hands it over the same way', async () => {
    const { routed, runtime, user } = setup();
    await readerSetsState(runtime);
    await user.click(await rowOf('By warehouse', 'CN'));
    await user.click(
      await screen.findByRole('menuitem', { name: /See these records/ }),
    );

    expect(routed).toHaveBeenCalledWith(
      expect.objectContaining({ kind: 'unsaved', definitionId: 'orders' }),
    );
    await screen.findByRole('heading', { level: 2, name: /^Orders · / });
    await expectHandedOver(user, { pressed: true });
  });

  it('an analysis the board owns hands it over the same way', async () => {
    const { routed, runtime, user } = setup();
    await readerSetsState(runtime);
    await openInWorkbench(user, 'Mine');

    expect(routed).toHaveBeenCalledWith(
      expect.objectContaining({ kind: 'unsaved', title: 'Mine' }),
    );
    await screen.findByRole('heading', { level: 2, name: 'Mine' });
    await expectHandedOver(user);
  });
});

/** The saved view the workbench opened by its id, as its runtime has it. */
function openedList(engine: ViewEngine) {
  const found = engine
    .openRuntimes()
    .find(
      entry =>
        entry.kind === 'record' && entry.getSnapshot().saved?.id === 'list',
    );
  if (!found) throw new Error('the list is not open');
  return found as unknown as DataViewRuntime;
}

describe('taking the board’s conditions off again (D26 Q30)', () => {
  it('is back to the saved view, clean, once every handed condition is off', async () => {
    const { engine, runtime, user } = setup();
    await readerSetsState(runtime);
    await openInWorkbench(user, 'Order list');
    await screen.findByRole('heading', { level: 2, name: 'Order list' });
    const bar = await waitFor(() => {
      const found = document.querySelector<HTMLElement>(
        '[data-slot="applied-bar"]',
      );
      expect(found?.querySelector('[data-scoped]')).not.toBeNull();
      return found!;
    });
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="view-unsaved"]'),
      ).not.toBeNull(),
    );

    await user.click(
      await within(bar).findByRole('button', { name: /^Unset Status/ }),
    );
    // Taken off, not left behind as a field without a value: the view is
    // what it was saved as, and says so.
    await waitFor(() =>
      expect(document.querySelector('[data-slot="view-unsaved"]')).toBeNull(),
    );
    const list = openedList(engine);
    expect(list.getSnapshot().dirty).toBe(false);
    expect(list.getSnapshot().draft).toEqual(list.getSnapshot().saved?.config);
    // The page's hold is untouched.
    expect(bar.querySelectorAll('[data-scoped]')).toHaveLength(1);
  });

  it('clears a condition of the view’s own as ever, leaving its field for the next question', async () => {
    const { engine, runtime, user } = setup();
    await readerSetsState(runtime);
    await openInWorkbench(user, 'Order list');
    await screen.findByRole('heading', { level: 2, name: 'Order list' });
    const list = openedList(engine);
    await waitFor(() =>
      expect(JSON.stringify(list.getSnapshot().draft.filter)).toContain(
        'PENDING',
      ),
    );
    // A condition the reader adds here is theirs: ✕ blanks it.
    act(() => {
      const draft = list.getSnapshot().draft;
      if (draft.kind !== 'record') throw new Error('not a record view');
      (list as unknown as DataViewRuntime).edit({
        filter: {
          op: 'and',
          children: [
            ...draft.filter.children,
            { field: 'warehouse', operator: 'EQ', value: 'EU' },
          ],
        },
      });
      (list as unknown as DataViewRuntime).apply();
    });
    const bar = document.querySelector<HTMLElement>(
      '[data-slot="applied-bar"]',
    )!;
    await user.click(
      await within(bar).findByRole('button', { name: /^Unset Warehouse .*EU/ }),
    );
    await waitFor(() =>
      expect(JSON.stringify(list.getSnapshot().draft.filter)).not.toContain(
        '"EU"',
      ),
    );
    expect(JSON.stringify(list.getSnapshot().draft.filter)).toContain(
      '"warehouse"',
    );
  });
});

describe('the way back to the board (D26 Q33)', () => {
  it('draws 「返回〈仪表盘〉」 and routes the board as it was left', async () => {
    const { routed, runtime, user } = setup();
    await readerSetsState(runtime);
    await openInWorkbench(user, 'Order list');
    await screen.findByRole('heading', { level: 2, name: 'Order list' });

    const back = await screen.findByRole('region', {
      name: 'Opened from a dashboard',
    });
    await user.click(
      within(back).getByRole('button', { name: 'Back to Operations' }),
    );
    // Untouched since it was handed over, so nothing is asked.
    expect(screen.queryByRole('alertdialog')).toBeNull();
    const to = routed.mock.lastCall?.[0];
    expect(to).toMatchObject({
      kind: 'dashboard',
      definitionId: 'overview',
      instanceId: 'board',
      filters: { values: { region: ['CN'], state: ['PENDING'] } },
    });
    // The host opened the board again, the reader's value still set.
    await screen.findByRole('group', { name: 'Order list' });
    await waitFor(() =>
      expect(runtime().getSnapshot().filters.values.state).toEqual(['PENDING']),
    );
  });

  it('asks nothing while the draft still says what was handed over, whatever object holds it', async () => {
    const { engine, routed, runtime, user } = setup();
    await readerSetsState(runtime);
    await openInWorkbench(user, 'Order list');
    await screen.findByRole('heading', { level: 2, name: 'Order list' });
    const list = openedList(engine) as unknown as DataViewRuntime;
    await waitFor(() =>
      expect(JSON.stringify(list.getSnapshot().draft.filter)).toContain(
        'PENDING',
      ),
    );
    // The same draft, rebuilt: nothing the reader did.
    act(() => {
      list.edit({ filter: structuredClone(list.getSnapshot().draft.filter) });
    });
    await user.click(
      await screen.findByRole('button', { name: 'Back to Operations' }),
    );
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(routed.mock.lastCall?.[0]).toMatchObject({ kind: 'dashboard' });
  });

  it('asks before the way back once the reader changed the view beyond what was handed', async () => {
    const { engine, routed, runtime, user } = setup();
    await readerSetsState(runtime);
    await openInWorkbench(user, 'Order list');
    await screen.findByRole('heading', { level: 2, name: 'Order list' });
    const list = openedList(engine) as unknown as DataViewRuntime;
    await waitFor(() =>
      expect(JSON.stringify(list.getSnapshot().draft.filter)).toContain(
        'PENDING',
      ),
    );
    act(() => {
      list.edit({ pageSize: 50 });
    });
    const calls = routed.mock.calls.length;
    await user.click(
      await screen.findByRole('button', { name: 'Back to Operations' }),
    );
    const confirm = await screen.findByRole('alertdialog');
    expect(routed.mock.calls).toHaveLength(calls);
    await user.click(within(confirm).getByRole('button', { name: 'Leave' }));
    await waitFor(() =>
      expect(routed.mock.lastCall?.[0]).toMatchObject({ kind: 'dashboard' }),
    );
  });

  it('shows the way back under a view drilled out of the handed one only once back on it', async () => {
    const { runtime, user } = setup();
    await readerSetsState(runtime);
    await openInWorkbench(user, 'Mine');
    await screen.findByRole('heading', { level: 2, name: 'Mine' });
    await screen.findByRole('region', { name: 'Opened from a dashboard' });

    // A drill out of it here: its own way back is the one on screen.
    await user.click(await rowOf(null, 'CN'));
    await user.click(
      await screen.findByRole('menuitem', { name: /See these records/ }),
    );
    await screen.findByRole('region', { name: 'Opened from another view' });
    expect(
      screen.queryByRole('region', { name: 'Opened from a dashboard' }),
    ).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Back to Mine' }));
    await screen.findByRole('region', { name: 'Opened from a dashboard' });
  });

  it('draws no way back without a route', async () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: views }),
      resolveSource: () => testSource(),
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        handOver={{
          kind: 'view',
          definitionId: 'orders',
          instanceId: 'list',
          scopeFilter: null,
          filter: null,
          from: {
            title: 'Operations',
            back: {
              kind: 'dashboard',
              definitionId: 'overview',
              instanceId: 'board',
              filters: { values: {} },
            },
          },
        }}
      />,
    );
    await screen.findByRole('heading', { level: 2, name: 'Order list' });
    expect(
      screen.queryByRole('region', { name: 'Opened from a dashboard' }),
    ).toBeNull();
  });
});
