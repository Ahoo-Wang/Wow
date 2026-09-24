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
 * A press on a dashboard panel's group, on screen (D22 H, I, batch D): the
 * analysis view's follow-up menu, every item opening in the workbench
 * through the host's route and carrying the board's filters; none without a
 * route; cross-filtering — the badge on the panel, the value on the bar and
 * where it came from, the group marked, a second press clearing it; a
 * custom destination; 「点击时…」 setting it; and the workbench opening the
 * unsaved view a follow-up hands the host.
 */

import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  DashboardViewRuntime,
  MemoryViewStore,
  ViewEngine,
  type DashboardNavigation,
  type DashboardPanel,
  type DashboardViewConfig,
  type DashboardViewPanel,
  type ViewInstance,
} from '../src/index.js';
import { DashboardWorkbench, DataWorkbench } from '../src/ui/index.js';
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
    config: analysisConfig(),
  },
  {
    id: 'list',
    definitionId: 'orders',
    title: 'Order list',
    scope: 'shared',
    revision: 'r1',
    config: recordConfig(),
  },
  // Another board a press can open (D23 Q17).
  {
    id: 'regional',
    definitionId: 'overview',
    title: 'Regional',
    scope: 'shared',
    revision: 'r1',
    config: dashboardConfig({
      fields: [
        { name: 'area', label: 'Area', kind: 'string' },
        { name: 'period', label: 'Period', kind: 'datetime' },
      ],
      panels: [],
    }),
  },
];

function view(
  id: string,
  instanceId: string,
  extra: Partial<DashboardPanel> = {},
  x = 0,
): DashboardPanel {
  return {
    id,
    kind: 'view',
    instanceId,
    bindings: [{ globalField: 'region', panelField: 'warehouse' }],
    layout: { x, y: 0, w: 12, h: 4 },
    ...extra,
  } as DashboardPanel;
}

function board(chart: Partial<DashboardPanel> = {}): DashboardViewConfig {
  return dashboardConfig({
    fields: [{ name: 'region', label: 'Region', kind: 'string' }],
    panels: [
      view('chart', 'by-warehouse', chart),
      view('list', 'list', {}, 12),
    ],
  });
}

function setup(
  config: DashboardViewConfig = board(),
  onNavigate?: (to: DashboardNavigation) => void,
) {
  const source = testSource({
    aggregate: vi.fn(() =>
      Promise.resolve([
        { warehouse: 'CN', orders: 2 },
        { warehouse: 'EU', orders: 1 },
      ]),
    ),
  });
  const store = new MemoryViewStore({
    instances: [
      ...views,
      {
        id: 'board',
        definitionId: 'overview',
        title: 'Operations',
        scope: 'personal',
        revision: '1',
        config,
      },
    ],
  });
  const engine = new ViewEngine({
    definitions: [ordersDefinition(), overviewDefinition()],
    store,
    resolveSource: () => source,
  });
  const runtime = () =>
    engine
      .openRuntimes()
      .find(
        (open): open is DashboardViewRuntime =>
          open instanceof DashboardViewRuntime,
      ) as DashboardViewRuntime;
  render(
    <DashboardWorkbench
      engine={engine}
      definitionId="overview"
      instanceId="board"
      onNavigate={onNavigate}
    />,
  );
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  return { engine, runtime, user };
}

/** The panel's rows once its answer is on screen. */
async function rowOf(panel: string, text: string): Promise<HTMLElement> {
  const body = await screen.findByRole('group', { name: panel });
  return waitFor(() => {
    const row = within(body)
      .getAllByRole('row')
      .find(entry => entry.textContent?.startsWith(text));
    if (!row) throw new Error(`no row ${text}`);
    return row;
  });
}

describe('the follow-up menu on a panel (D22 H)', () => {
  it('opens the analysis view’s menu, each item going to the workbench with the board’s filters', async () => {
    const onNavigate = vi.fn();
    const { runtime, user } = setup(board(), onNavigate);
    await rowOf('By warehouse', 'CN');
    runtime().setFilterValue('region', ['CN']);
    // It runs a moment later (「改了就跑」): the panel is then under it.
    await waitFor(() =>
      expect(
        JSON.stringify(runtime().panelRuntime('chart')?.scopeFilter),
      ).toContain('"CN"'),
    );

    const row = await rowOf('By warehouse', 'CN');
    expect(row.getAttribute('aria-haspopup')).toBe('menu');
    await user.click(row);
    const menu = await screen.findByRole('menu');
    expect(menu.querySelector('[data-slot="drill-context"]')?.textContent).toBe(
      'Board filters: Warehouse is CN',
    );
    // Each item says it opens elsewhere.
    expect(menu.querySelectorAll('[data-slot="drill-away"]').length).toBe(2);
    await user.click(
      within(menu).getByRole('menuitem', { name: /See these records/ }),
    );
    expect(onNavigate).toHaveBeenCalledTimes(1);
    const [to] = onNavigate.mock.calls[0] as [DashboardNavigation];
    expect(to.kind).toBe('unsaved');
    if (to.kind !== 'unsaved') return;
    expect(to.definitionId).toBe('orders');
    expect(to.config.kind).toBe('record');
    expect(to.title).toMatch(/^Orders · /);
    // The group, and nothing the board’s value did not ask for.
    expect(JSON.stringify(to.config.filter)).toContain('"CN"');
  });

  it('asks the same question of the group alone as an unsaved analysis', async () => {
    const onNavigate = vi.fn();
    const { user } = setup(board(), onNavigate);
    await user.click(await rowOf('By warehouse', 'EU'));
    await user.click(
      await screen.findByRole('menuitem', { name: /Only this group/ }),
    );
    const [to] = onNavigate.mock.calls[0] as [DashboardNavigation];
    expect(to.kind === 'unsaved' && to.config.kind).toBe('analysis');
    expect(JSON.stringify(to.kind === 'unsaved' && to.config.filter)).toContain(
      '"EU"',
    );
  });

  it('offers nothing to press without a route', async () => {
    setup(board());
    const row = await rowOf('By warehouse', 'CN');
    expect(row.dataset.pickable).toBeUndefined();
    expect(row.getAttribute('aria-haspopup')).toBeNull();
  });
});

describe('cross-filtering on screen (D22 I)', () => {
  const crossing = board({ click: { kind: 'filter', filter: 'region' } });

  it('says so on the panel, sets the bar from it and marks the group; a second press clears', async () => {
    const { user, runtime } = setup(crossing);
    const badge = await screen.findByText('Click filters “Region”');
    expect(badge.closest('[data-slot="panel-click-filter"]')).toBeTruthy();

    const row = await rowOf('By warehouse', 'CN');
    // A press filters rather than opening a menu.
    expect(row.getAttribute('aria-haspopup')).toBeNull();
    await user.click(row);
    expect(runtime().getSnapshot().filters.values.region).toEqual(['CN']);
    const bar = await screen.findByRole('region', { name: 'Filters' });
    expect(within(bar).getByText('from “By warehouse”').dataset.slot).toBe(
      'dashboard-filter-from',
    );
    await waitFor(() =>
      expect(
        screen.getByText('“Region” now filters by the group pressed'),
      ).toBeTruthy(),
    );
    const marked = await rowOf('By warehouse', 'CN');
    await waitFor(() => expect(marked.dataset.pressed).toBe(''));
    expect(marked.getAttribute('aria-current')).toBe('true');
    expect((await rowOf('By warehouse', 'EU')).dataset.pressed).toBeUndefined();

    await user.click(await rowOf('By warehouse', 'CN'));
    expect(runtime().getSnapshot().filters.values.region).toBeUndefined();
    expect(within(bar).queryByText('from “By warehouse”')).toBeNull();
  });

  it('marks the bar pressed on a chart, the others drawn faint', async () => {
    const charted: DashboardViewConfig = {
      ...crossing,
      panels: crossing.panels.map(entry =>
        entry.id === 'chart'
          ? { ...entry, presentation: { layout: 'chart' } }
          : entry,
      ),
    };
    const { runtime } = setup(charted);
    const frame = await waitFor(() => {
      const found = document.querySelector<HTMLElement>('[data-chart="bar"]');
      if (!found) throw new Error('no chart yet');
      return found;
    });
    expect(frame.dataset.highlighted).toBe('0');
    runtime().crossFilter('chart', { warehouse: 'CN' });
    await waitFor(() => expect(frame.dataset.highlighted).toBe('1'));
  });

  it('works on a page with no route, since it goes nowhere', async () => {
    setup(crossing);
    const row = await rowOf('By warehouse', 'CN');
    expect(row.dataset.pickable).toBe('');
  });
});

describe('a custom destination on screen (D22 I)', () => {
  it('hands the host the page, filled with the group pressed', async () => {
    const onNavigate = vi.fn();
    const { user } = setup(
      board({ click: { kind: 'url', url: '/warehouses/{{warehouse}}' } }),
      onNavigate,
    );
    await user.click(await rowOf('By warehouse', 'CN'));
    await waitFor(() =>
      expect(onNavigate).toHaveBeenCalledWith({
        kind: 'url',
        url: '/warehouses/CN',
      }),
    );
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('says why a view it goes to cannot be opened', async () => {
    const onNavigate = vi.fn();
    const { user } = setup(
      board({ click: { kind: 'view', instanceId: 'gone' } }),
      onNavigate,
    );
    await user.click(await rowOf('By warehouse', 'CN'));
    await screen.findByText(
      'The view a press goes to was deleted, or you may not open it.',
    );
    expect(onNavigate).not.toHaveBeenCalled();
  });
});

describe('「点击时…」 (D22 I)', () => {
  it('sets a press to update a filter wired through a field the panel groups by', async () => {
    const { user, runtime } = setup(board(), vi.fn());
    await rowOf('By warehouse', 'CN');
    await user.click(await screen.findByRole('button', { name: 'Edit' }));
    await user.click(
      await screen.findByRole('button', { name: 'Actions for “By warehouse”' }),
    );
    await user.click(
      await screen.findByRole('menuitem', { name: 'When clicked…' }),
    );
    const dialog = await screen.findByRole('dialog', {
      name: 'When “By warehouse” is clicked',
    });
    expect(
      within(dialog)
        .getByRole('radio', { name: 'Open the follow-up menu' })
        .getAttribute('aria-checked'),
    ).toBe('true');
    await user.click(
      within(dialog).getByRole('radio', { name: 'Update a dashboard filter' }),
    );
    await user.click(within(dialog).getByRole('button', { name: 'Done' }));
    const chart = runtime()
      .getSnapshot()
      .draft.panels.find(entry => entry.id === 'chart') as DashboardViewPanel;
    expect(chart.click).toEqual({ kind: 'filter', filter: 'region' });
  });

  it('asks for a view or a usable address before it takes a destination', async () => {
    const { user, runtime } = setup(board(), vi.fn());
    await rowOf('By warehouse', 'CN');
    await user.click(await screen.findByRole('button', { name: 'Edit' }));
    await user.click(
      await screen.findByRole('button', { name: 'Actions for “By warehouse”' }),
    );
    await user.click(
      await screen.findByRole('menuitem', { name: 'When clicked…' }),
    );
    const dialog = await screen.findByRole('dialog');
    await user.click(
      within(dialog).getByRole('radio', {
        name: 'Go to another view, dashboard or page',
      }),
    );
    await user.click(within(dialog).getByRole('button', { name: 'Done' }));
    await within(dialog).findByText('Choose the view a press opens.');
    await user.click(
      within(dialog).getByRole('button', { name: 'Web address' }),
    );
    const address = within(dialog).getByRole('textbox', { name: 'Address' });
    await user.type(address, 'javascript:x');
    await user.click(within(dialog).getByRole('button', { name: 'Done' }));
    expect(address.getAttribute('aria-invalid')).toBe('true');
    await user.clear(address);
    await user.type(address, '/w/{{{{warehouse}}');
    await user.click(within(dialog).getByRole('button', { name: 'Done' }));
    const chart = runtime()
      .getSnapshot()
      .draft.panels.find(entry => entry.id === 'chart') as DashboardViewPanel;
    expect(chart.click).toEqual({ kind: 'url', url: '/w/{{warehouse}}' });
  });
});

describe('在工作台中打开 an analysis the board owns', () => {
  it('opens it unsaved, the board’s filters its own conditions', async () => {
    const onNavigate = vi.fn();
    const owned = dashboardConfig({
      fields: [{ name: 'region', label: 'Region', kind: 'string' }],
      panels: [
        {
          id: 'mine',
          kind: 'view',
          title: 'Mine',
          owned: { definitionId: 'orders', config: analysisConfig() },
          bindings: [{ globalField: 'region', panelField: 'warehouse' }],
          layout: { x: 0, y: 0, w: 12, h: 4 },
        },
      ],
    });
    const { user, runtime } = setup(owned, onNavigate);
    await rowOf('Mine', 'CN');
    runtime().setFilterValue('region', ['EU']);
    await waitFor(() =>
      expect(
        JSON.stringify(runtime().panelRuntime('mine')?.scopeFilter),
      ).toContain('"EU"'),
    );
    await user.click(
      await screen.findByRole('button', { name: 'Actions for “Mine”' }),
    );
    await user.click(
      await screen.findByRole('menuitem', { name: 'Open in the workbench' }),
    );
    const [to] = onNavigate.mock.calls[0] as [DashboardNavigation];
    expect(to).toMatchObject({
      kind: 'unsaved',
      definitionId: 'orders',
      title: 'Mine',
    });
    expect(JSON.stringify(to.kind === 'unsaved' && to.config.filter)).toContain(
      '"EU"',
    );
  });
});

describe('a view nobody saved, opened in the workbench', () => {
  it('opens once per object handed to it', async () => {
    const store = new MemoryViewStore({ instances: views });
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store,
      resolveSource: () => testSource(),
    });
    const unsaved = {
      title: 'Orders · Warehouse is CN',
      config: recordConfig(),
    };
    const { rerender } = render(
      <DataWorkbench engine={engine} definitionId="orders" unsaved={unsaved} />,
    );
    await screen.findByRole('heading', {
      level: 2,
      name: /Orders · Warehouse is CN/,
    });
    // Opened from elsewhere, with its conditions in hand: the editor stays
    // folded, as it does for a view opened from another's group.
    expect(folded()).toBe(true);
    const held = engine.openRuntimes().length;
    rerender(
      <DataWorkbench engine={engine} definitionId="orders" unsaved={unsaved} />,
    );
    expect(engine.openRuntimes().length).toBe(held);
  });

  it('opens an analysis handed to it folded too, and a new view unfolded', async () => {
    const store = new MemoryViewStore({ instances: views });
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store,
      resolveSource: () => testSource(),
    });
    const { unmount } = render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        unsaved={{ title: 'By warehouse · CN', config: analysisConfig() }}
      />,
    );
    await screen.findByRole('heading', { level: 2, name: /By warehouse · CN/ });
    expect(folded()).toBe(true);
    unmount();

    // What it is set apart from: a view made from nothing opens unfolded.
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    render(<DataWorkbench engine={engine} definitionId="orders" />);
    await user.click(await screen.findByRole('button', { name: 'New view' }));
    await user.click(
      await screen.findByRole('menuitem', { name: /Record view/ }),
    );
    await waitFor(() => expect(folded()).toBe(false));
  });
});

/** Whether the workbench's editor band is folded away. */
function folded(): boolean {
  const toggle = document.querySelector('[data-slot="editor-toggle"]');
  if (!toggle) throw new Error('no editor toggle');
  return toggle.querySelector('[aria-expanded="true"]') === null;
}

describe('a press that opens another board (D23 Q17)', () => {
  /** The item a select shows, not its chevron. */
  const chosen = (select: HTMLElement) =>
    select.querySelector('[data-slot="select-value"]')?.textContent;
  /** 「点击时…」 on 「By warehouse」, while the board is built. */
  async function clickSettings(user: ReturnType<typeof userEvent.setup>) {
    await rowOf('By warehouse', 'CN');
    await user.click(await screen.findByRole('button', { name: 'Edit' }));
    await user.click(
      await screen.findByRole('button', { name: 'Actions for “By warehouse”' }),
    );
    await user.click(
      await screen.findByRole('menuitem', { name: 'When clicked…' }),
    );
    return screen.findByRole('dialog', {
      name: 'When “By warehouse” is clicked',
    });
  }

  it('lists the board’s filters for the author to map, one per row, none guessed', async () => {
    const { user, runtime } = setup(board(), vi.fn());
    const dialog = await clickSettings(user);
    await user.click(
      within(dialog).getByRole('radio', {
        name: 'Go to another view, dashboard or page',
      }),
    );
    await user.click(within(dialog).getByRole('button', { name: 'Dashboard' }));
    await user.click(within(dialog).getByRole('button', { name: 'Done' }));
    await within(dialog).findByText('Choose the dashboard a press opens.');

    await user.click(
      within(dialog).getByRole('button', { name: 'Choose a dashboard…' }),
    );
    const picker = await screen.findByRole('dialog', {
      name: 'Which dashboard a press on “By warehouse” opens',
    });
    // Boards only: no kind to narrow by, and no record or analysis view.
    expect(within(picker).queryByRole('group', { name: 'Kind' })).toBeNull();
    expect(within(picker).queryByText('Order list')).toBeNull();
    await user.click(await within(picker).findByText('Regional'));

    const rows = await within(dialog).findByRole('group', {
      name: 'Its filters',
    });
    const area = within(rows).getByRole('combobox', { name: 'Area' });
    const period = within(rows).getByRole('combobox', { name: 'Period' });
    // Nothing is carried until the author says so — not even Area, which
    // a name could have matched.
    expect(chosen(area)).toBe('Not carried');
    expect(chosen(period)).toBe('Not carried');
    // A date filter takes no text dimension: nothing to pick, and it says so.
    expect(period.hasAttribute('data-disabled')).toBe(true);
    within(rows).getByText(
      'Neither a dimension of this panel nor a filter of this board’s fits it.',
    );
    await user.click(area);
    await user.click(
      await screen.findByRole('option', { name: 'This group’s Warehouse' }),
    );
    await user.click(within(dialog).getByRole('button', { name: 'Done' }));
    const chart = runtime()
      .getSnapshot()
      .draft.panels.find(entry => entry.id === 'chart') as DashboardViewPanel;
    expect(chart.click).toEqual({
      kind: 'dashboard',
      instanceId: 'regional',
      values: { area: { dimension: 'warehouse' } },
    });
  });

  it('offers this board’s filters as a second group, after the group pressed', async () => {
    const onNavigate = vi.fn();
    const { user, runtime } = setup(
      board({
        click: {
          kind: 'dashboard',
          instanceId: 'regional',
          values: {},
        },
      }),
      onNavigate,
    );
    const dialog = await clickSettings(user);
    const rows = await within(dialog).findByRole('group', {
      name: 'Its filters',
    });
    await user.click(within(rows).getByRole('combobox', { name: 'Area' }));
    const listbox = await screen.findByRole('listbox');
    // 「不带」 first, then the group pressed, then this board's filters.
    expect(
      within(listbox)
        .getAllByRole('option')
        .map(option => option.textContent),
    ).toEqual(['Not carried', 'This group’s Warehouse', 'This board’s Region']);
    within(listbox).getByText('The group pressed');
    within(listbox).getByText('This board’s filters');
    await user.click(
      within(listbox).getByRole('option', { name: 'This board’s Region' }),
    );
    await user.click(within(dialog).getByRole('button', { name: 'Done' }));
    const chart = runtime()
      .getSnapshot()
      .draft.panels.find(entry => entry.id === 'chart') as DashboardViewPanel;
    expect(chart.click).toEqual({
      kind: 'dashboard',
      instanceId: 'regional',
      values: { area: { filter: 'region' } },
    });
  });

  it('reads a stored mapping back, and says what no longer holds', async () => {
    const { user } = setup(
      board({
        click: {
          kind: 'dashboard',
          instanceId: 'regional',
          values: {
            area: { dimension: 'warehouse' },
            zone: { dimension: 'warehouse' },
          },
        },
      }),
      vi.fn(),
    );
    const dialog = await clickSettings(user);
    const rows = await within(dialog).findByRole('group', {
      name: 'Its filters',
    });
    expect(chosen(within(rows).getByRole('combobox', { name: 'Area' }))).toBe(
      'This group’s Warehouse',
    );
    within(dialog).getByText(/What went to “zone” no longer applies/);
    expect(
      within(dialog).getByText('Regional').closest('[data-slot="click-board"]'),
    ).not.toBeNull();
  });

  it('hands the host the board with its filters set from the group pressed', async () => {
    const onNavigate = vi.fn();
    const { user } = setup(
      board({
        click: {
          kind: 'dashboard',
          instanceId: 'regional',
          values: { area: { dimension: 'warehouse' } },
        },
      }),
      onNavigate,
    );
    await user.click(await rowOf('By warehouse', 'CN'));
    await waitFor(() =>
      expect(onNavigate).toHaveBeenCalledWith({
        kind: 'dashboard',
        definitionId: 'overview',
        instanceId: 'regional',
        filters: { values: { area: ['CN'] } },
      }),
    );
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('opens the follow-up menu instead when the mapping went stale, and says why', async () => {
    const onNavigate = vi.fn();
    const { user } = setup(
      board({
        click: {
          kind: 'dashboard',
          instanceId: 'regional',
          values: { zone: { dimension: 'warehouse' } },
        },
      }),
      onNavigate,
    );
    await user.click(await rowOf('By warehouse', 'CN'));
    await screen.findByRole('menu');
    await screen.findByText(
      'The dashboard a press opens no longer has the filter “zone”; pressing it opens the follow-up menu.',
    );
    expect(onNavigate).not.toHaveBeenCalled();
  });
});
