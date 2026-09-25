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
 * `EmbeddedDashboard` (D22, the embedding half): a board on a business page,
 * split from `EmbeddedView` by resource; its tier — static or interactive,
 * neither of which writes anything (D36); each filter adjustable, locked or
 * hidden, its values the host's address and followed; and the switches —
 * title, panel titles, export, filling the screen.
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
  MemoryViewStore,
  ViewEngine,
  type DashboardFilters,
  type DashboardPanel,
  type DashboardViewConfig,
  type ViewInstance,
} from '../src/index.js';
import { DashboardViewRuntime } from '../src/runtime/dashboardRuntime.js';
import {
  EmbeddedDashboard,
  zhCN,
  type EmbeddedDashboardProps,
} from '../src/ui/index.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';
import { mine } from './fixtures/ui.js';

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
  mine,
];

function panel(
  id: string,
  instanceId: string,
  title: string,
  extra: Partial<DashboardPanel> = {},
  x = 0,
): DashboardPanel {
  return {
    id,
    kind: 'view',
    title,
    instanceId,
    bindings: [
      { globalField: 'region', panelField: 'warehouse' },
      { globalField: 'status', panelField: 'status' },
    ],
    layout: { x, y: 0, w: 12, h: 4 },
    ...extra,
  } as DashboardPanel;
}

/** Two text filters, a chart that cross-filters the region, and the list. */
function board(): DashboardViewConfig {
  return dashboardConfig({
    fields: [
      { name: 'region', label: 'Region', kind: 'string' },
      { name: 'status', label: 'State', kind: 'string' },
    ],
    panels: [
      panel('chart', 'by-warehouse', 'By warehouse', {
        click: { kind: 'filter', filter: 'region' },
      }),
      panel('list', 'list', 'Order list', {}, 12),
    ],
  });
}

function engineOf(config: DashboardViewConfig = board()) {
  const store = new MemoryViewStore({
    instances: [
      ...views,
      {
        id: 'board',
        definitionId: 'overview',
        title: 'Operations',
        scope: 'shared',
        revision: 'r1',
        config,
      },
    ],
  });
  const engine = new ViewEngine({
    definitions: [ordersDefinition(), overviewDefinition()],
    store,
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
  return engine;
}

function embed(props: Partial<EmbeddedDashboardProps> = {}) {
  const engine = props.engine ?? engineOf();
  const view = render(
    <EmbeddedDashboard engine={engine} instanceId="board" {...props} />,
  );
  const runtime = () => {
    const found = engine
      .openRuntimes()
      .find(entry => entry instanceof DashboardViewRuntime);
    if (!(found instanceof DashboardViewRuntime))
      throw new Error('no board open');
    return found;
  };
  const rerender = (next: Partial<EmbeddedDashboardProps>) =>
    view.rerender(
      <EmbeddedDashboard
        engine={engine}
        instanceId="board"
        {...props}
        {...next}
      />,
    );
  return { engine, runtime, rerender };
}

/** A row of the chart panel's table, by the warehouse it is. */
async function chartRow(name: string): Promise<HTMLElement> {
  const frame = await screen.findByRole('group', { name: 'By warehouse' });
  return within(frame).findByRole('row', { name: new RegExp(name) });
}

function note(content: string, title = 'Note'): DashboardPanel {
  return {
    id: 'note',
    kind: 'markdown',
    title,
    content,
    layout: { x: 0, y: 0, w: 6, h: 2 },
  } as DashboardPanel;
}

describe('EmbeddedDashboard', () => {
  it('shows a board as its grid of panels', async () => {
    embed({
      engine: engineOf(dashboardConfig({ panels: [note('# Weekly review')] })),
    });

    expect(
      await screen.findByRole('heading', { name: 'Weekly review' }),
    ).toBeDefined();
  });

  /**
   * One panel's error stops that panel and nothing else — the runtime runs
   * the rest — but the embed read it as the board's own and drew an error
   * strip where the whole grid should have been (R3).
   */
  it('draws the grid around a panel that is out', async () => {
    embed({
      engine: engineOf(
        dashboardConfig({
          panels: [
            {
              id: 'private',
              kind: 'view',
              title: 'Mine only',
              instanceId: 'orders-1',
              bindings: [{ globalField: 'ghost', panelField: 'status' }],
              layout: { x: 0, y: 0, w: 6, h: 4 },
            },
            { ...note('# Weekly review'), layout: { x: 6, y: 0, w: 6, h: 2 } },
          ],
        }),
      ),
    });

    expect(
      await screen.findByRole('heading', { name: 'Weekly review' }),
    ).toBeDefined();
    const out = document.querySelector('[data-slot="panel-unavailable"]');
    expect(out?.textContent).toContain(
      "The dashboard's filters do not fit this panel",
    );
    expect(screen.queryByRole('alert')).toBeNull();
    // No title of its own, so its panels sit one under the host's `h1`.
    expect(
      screen.getByRole('heading', { level: 2, name: 'Note' }),
    ).toBeDefined();
  });

  it('puts the titles where the host outline wants them: the board’s, and its panels one under', async () => {
    const engine = engineOf(dashboardConfig({ panels: [note('# Hello')] }));
    const { rerender } = embed({ engine, headingLevel: 4 });
    expect(
      await screen.findByRole('heading', { level: 4, name: 'Note' }),
    ).toBeDefined();
    // And the note's own `#` one under its panel (U-12).
    expect(
      screen.getByRole('heading', { level: 5, name: 'Hello' }),
    ).toBeDefined();

    rerender({ headingLevel: 2, withTitle: true });
    expect(
      await screen.findByRole('heading', { level: 2, name: 'Operations' }),
    ).toBeDefined();
    expect(
      screen.getByRole('heading', { level: 3, name: 'Note' }),
    ).toBeDefined();
    expect(
      screen.getByRole('heading', { level: 4, name: 'Hello' }),
    ).toBeDefined();
  });

  it('keeps a panel’s title for a screen reader alone when the host turns titles off', async () => {
    embed({
      engine: engineOf(dashboardConfig({ panels: [note('hello')] })),
      withPanelTitles: false,
    });

    const title = await screen.findByRole('heading', { name: 'Note' });
    expect(title.className).toContain('sr-only');
    const head = title.closest('[data-slot="card-header"]');
    expect(head?.getAttribute('data-untitled')).toBe('true');
    // Nothing else stood in the header, so the row goes with the title
    // (surviving class assertion: `sr-only` is the whole of the contract).
    expect(head?.className).toContain('sr-only');
  });

  it('names a record view as one it cannot show: that is EmbeddedView', async () => {
    render(<EmbeddedDashboard engine={engineOf()} instanceId="orders-1" />);

    // What this page opens is a dashboard (Q34): what it was handed is not.
    expect(
      await screen.findByText(
        'This is not a dashboard (record), so this page cannot show it.',
      ),
    ).toBeDefined();
    expect(
      screen.getByText('This dashboard could not be opened'),
    ).toBeDefined();
  });

  it('says a board it cannot open is a dashboard, in the host’s language (Q34)', async () => {
    render(
      <EmbeddedDashboard
        engine={engineOf()}
        instanceId="missing"
        messages={zhCN}
      />,
    );

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('无法打开这个仪表盘');
    expect(alert.textContent).toContain('这个仪表盘已不存在。');
    expect(alert.textContent).not.toContain('视图');
  });

  it('answers no press and offers no way off the board in the static tier', async () => {
    const onNavigate = vi.fn();
    const { runtime } = embed({ onNavigate });

    const row = await chartRow('CN');
    expect(row.getAttribute('aria-haspopup')).toBeNull();
    expect(row.hasAttribute('data-pickable')).toBe(false);
    await userEvent.click(row);
    expect(runtime().getSnapshot().filters.values.region).toBeUndefined();
    expect(
      document.querySelector('[data-slot="panel-click-filter"]'),
    ).toBeNull();
    // No 「⋯」 either: nothing on a board being read answers.
    expect(document.querySelector('[data-slot="panel-menu"]')).toBeNull();
    expect(screen.queryByRole('button', { name: /^Edit$/ })).toBeNull();
    expect(onNavigate).not.toHaveBeenCalled();
  });

  /**
   * The tier is the ceiling and the export opts in within it (D36, amending
   * D24 Q24): switched on in the interactive tier, a record panel's 「⋯」
   * holds 导出数据… and so does an analysis panel's (D25 Q28); in the static
   * tier the switch has no effect and no panel has a 「⋯」 at all; switched
   * off (the default), there is no such item in any tier.
   */
  it('offers a panel’s export in the interactive tier where the host switched it on, and never in the static one', async () => {
    const user = userEvent.setup();
    embed({ withExport: true });
    await chartRow('CN');
    expect(document.querySelector('[data-slot="panel-menu"]')).toBeNull();
    cleanup();

    embed({ interaction: 'interactive', withExport: true });
    const menu = await screen.findByRole('button', {
      name: 'Actions for “Order list”',
    });
    await user.click(menu);
    await user.click(
      await screen.findByRole('menuitem', { name: 'Export data…' }),
    );
    const dialog = await screen.findByRole('dialog', { name: 'Export' });
    expect(dialog.textContent).toMatch(
      /File: Order list-\d{4}-\d{2}-\d{2}\.csv/,
    );
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await user.click(
      screen.getByRole('button', { name: 'Actions for “By warehouse”' }),
    );
    expect(
      within(await screen.findByRole('menu')).getByRole('menuitem', {
        name: 'Export data…',
      }),
    ).toBeDefined();
    cleanup();

    embed({ interaction: 'interactive' });
    await user.click(
      await screen.findByRole('button', { name: 'Actions for “Order list”' }),
    );
    expect(
      within(await screen.findByRole('menu')).queryByRole('menuitem', {
        name: 'Export data…',
      }),
    ).toBeNull();
  });

  it('cross-filters on a press in the interactive tier', async () => {
    const { runtime } = embed({ interaction: 'interactive' });

    await userEvent.click(await chartRow('CN'));
    await waitFor(() =>
      expect(runtime().getSnapshot().filters.values.region).toEqual(['CN']),
    );
    expect(
      document.querySelector('[data-slot="dashboard-filter-from"]'),
    ).not.toBeNull();
  });

  it('draws a locked filter as what it holds, keeps a hidden one off the bar, and lets the reader change neither', async () => {
    const { runtime } = embed({
      interaction: 'interactive',
      filterModes: { region: 'locked', status: 'hidden' },
      pageValues: { values: { region: ['CN'], status: ['PENDING'] } },
    });

    const locked = await screen.findByRole('group', {
      name: 'Region (set by this page)',
    });
    expect(locked.getAttribute('data-locked')).toBe('');
    expect(locked.textContent).toContain('CN');
    expect(within(locked).queryByRole('textbox')).toBeNull();
    expect(within(locked).queryByRole('combobox')).toBeNull();
    // The hidden one is not on the bar, and no panel names it either.
    expect(
      document.querySelector(
        '[data-slot="dashboard-filter"][data-filter="status"]',
      ),
    ).toBeNull();
    expect(screen.queryByText(/State/)).toBeNull();
    // Nothing the reader holds, so nothing to clear.
    expect(screen.queryByRole('button', { name: 'Clear' })).toBeNull();

    // A press on the chart would set the locked region: it is set aside,
    // and the press does what it does on a panel with no click — here,
    // without a route, nothing.
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="panel-click-filter"]'),
      ).toBeNull(),
    );
    await userEvent.click(await chartRow('EU'));
    expect(runtime().getSnapshot().filters.values).toEqual({
      region: ['CN'],
      status: ['PENDING'],
    });
    expect(runtime().setFilterValue('status', ['SHIPPED'])[0]?.code).toBe(
      'dashboard.filter.held',
    );
  });

  it('reads a locked time grouping as its unit, and draws a hidden one not at all', async () => {
    const grouped = board();
    grouped.timeGrouping = { units: ['DAY', 'WEEK'], default: 'DAY' };
    const engine = engineOf(grouped);
    const { runtime, rerender } = embed({
      engine,
      interaction: 'interactive',
      groupingMode: 'locked',
      pageValues: { values: {}, unit: 'WEEK' },
    });

    const locked = await screen.findByRole('group', {
      name: 'Time grouping (set by this page)',
    });
    expect(locked.textContent).toContain('By week');
    expect(screen.queryByRole('group', { name: 'Time grouping' })).toBeNull();
    runtime().setGroupingUnit('DAY');
    expect(runtime().getSnapshot().filters.unit).toBe('WEEK');
    // The reader's filters are still theirs to clear.
    expect(screen.getByRole('button', { name: 'Clear' })).toBeDefined();

    rerender({ groupingMode: 'hidden' });
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="dashboard-grouping"]'),
      ).toBeNull(),
    );
  });

  it('reads a locked filter that holds nothing as any value', async () => {
    embed({ filterModes: { region: 'locked' } });

    const locked = await screen.findByRole('group', {
      name: 'Region (set by this page)',
    });
    expect(locked.textContent).toContain('Any');
  });

  it('follows what the page holds as it changes, and never tells the address a held value', async () => {
    const onFiltersChange = vi.fn();
    const { runtime, rerender } = embed({
      filterModes: { region: 'locked' },
      pageValues: { values: { region: ['CN'] } },
      initialFilters: { values: { status: ['SHIPPED'] } },
      onFiltersChange,
    });

    // The reader's filter goes out to the address; the locked one does not.
    await waitFor(() =>
      expect(onFiltersChange).toHaveBeenLastCalledWith({
        values: { status: ['SHIPPED'] },
      }),
    );
    expect(runtime().getSnapshot().filters.values.region).toEqual(['CN']);
    // The same thing said again in a new object changes nothing.
    rerender({ pageValues: { values: { region: ['CN'] } } });
    // Another customer: the locked filter follows the page …
    rerender({ pageValues: { values: { region: ['EU'] } } });
    await waitFor(() =>
      expect(runtime().getSnapshot().filters.values.region).toEqual(['EU']),
    );
    expect(
      (await screen.findByRole('group', { name: 'Region (set by this page)' }))
        .textContent,
    ).toContain('EU');
    // … and the address still hears nothing of it.
    for (const [told] of onFiltersChange.mock.calls as [DashboardFilters][])
      expect(told.values).not.toHaveProperty('region');
  });

  it('ignores an address that names a held filter: the page’s value wins', async () => {
    const onFiltersChange = vi.fn();
    const grouped = board();
    grouped.timeGrouping = { units: ['DAY', 'WEEK'], default: 'DAY' };
    const { runtime } = embed({
      engine: engineOf(grouped),
      filterModes: { region: 'locked', status: 'hidden' },
      groupingMode: 'locked',
      pageValues: { values: { region: ['CN'] }, unit: 'WEEK' },
      // A reader who edited the address to another customer, a hidden value
      // and another unit.
      initialFilters: {
        values: { region: ['EU'], status: ['SHIPPED'] },
        unit: 'DAY',
      },
      onFiltersChange,
    });

    await waitFor(() =>
      expect(runtime().getSnapshot().filters).toEqual({
        values: { region: ['CN'] },
        unit: 'WEEK',
      }),
    );
    await waitFor(() =>
      expect(onFiltersChange).toHaveBeenLastCalledWith({ values: {} }),
    );
  });

  it('opens the reader’s filters at their defaults when the address names none of them', async () => {
    const withDefault = board();
    withDefault.fields = withDefault.fields.map(field =>
      field.name === 'status' ? { ...field, default: ['PENDING'] } : field,
    );
    const { runtime } = embed({
      engine: engineOf(withDefault),
      filterModes: { region: 'locked' },
      pageValues: { values: { region: ['CN'] } },
      initialFilters: { values: { region: ['EU'] } },
    });

    await waitFor(() =>
      expect(runtime().getSnapshot().filters.values).toEqual({
        region: ['CN'],
        status: ['PENDING'],
      }),
    );
  });

  it('says what the board refuses of the page’s values, as a refused narrowing', async () => {
    embed({
      filterModes: { ghost: 'locked', region: 'locked' },
      pageValues: { values: { ghost: ['x'], region: ['CN'] } },
    });

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain(
      'This page could not narrow this dashboard',
    );
    expect(alert.textContent).toContain('This dashboard has no filter ghost.');
    // What it could take, it took.
    expect(
      (await screen.findByRole('group', { name: 'Region (set by this page)' }))
        .textContent,
    ).toContain('CN');
    // Said once, as the page's: not again over the bar as the link's.
    expect(
      document.querySelector('[data-slot="dashboard-filters-refused"]'),
    ).toBeNull();
  });

  it('opens on what it takes of an address gone partly stale: one stale name or bad value costs only itself', async () => {
    const onFiltersChange = vi.fn();
    const { runtime } = embed({
      // A filter renamed since the address was written, and two values on a
      // filter that takes one — beside a region the board still has.
      initialFilters: {
        values: { region: ['EU'], gone: ['x'], status: ['A', 'B'] },
      },
      onFiltersChange,
    });

    await waitFor(() =>
      expect(onFiltersChange).toHaveBeenLastCalledWith({
        values: { region: ['EU'] },
      }),
    );
    expect(runtime().getSnapshot().filters.values).toEqual({ region: ['EU'] });
    expect(runtime().refusedFilters.map(found => found.code)).toEqual([
      'dashboard.filter.unknown',
      'dashboard.field.not-multiple',
    ]);
    // And said over the bar, the reader's link being the reader's to fix.
    const notice = await waitFor(() => {
      const found = document.querySelector<HTMLElement>(
        '[data-slot="dashboard-filters-refused"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    expect(notice.textContent).toContain(
      'Some of the filters in the link could not be used',
    );
    expect(notice.textContent).toContain('This dashboard has no filter gone.');
    await userEvent.click(
      within(notice).getByRole('button', { name: 'Dismiss' }),
    );
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="dashboard-filters-refused"]'),
      ).toBeNull(),
    );
  });

  /**
   * The static tier is view only (D36): the reader changes none of the
   * filters — each one the page left editable reads as what it holds, as a
   * locked one does — and there is nothing to clear, group or fill the
   * screen with, whatever the host switched on.
   */
  it('reads every filter as what it holds in the static tier, with no control on the bar', async () => {
    const grouped = board();
    grouped.timeGrouping = { units: ['DAY', 'WEEK'], default: 'DAY' };
    const { runtime } = embed({
      engine: engineOf(grouped),
      initialFilters: { values: { status: ['SHIPPED'] } },
      expandable: true,
      openInWorkbench: true,
      onNavigate: vi.fn(),
    });

    const status = await screen.findByRole('group', {
      name: 'State (set by this page)',
    });
    expect(status.textContent).toContain('SHIPPED');
    expect(
      screen.getByRole('group', { name: 'Region (set by this page)' })
        .textContent,
    ).toContain('Any');
    expect(
      screen.getByRole('group', { name: 'Time grouping (set by this page)' })
        .textContent,
    ).toContain('By day');
    const bar = document.querySelector<HTMLElement>(
      '[data-slot="dashboard-filter-bar"]',
    )!;
    // Nothing but each reading's lock, whose note a press opens.
    expect(
      within(bar)
        .queryAllByRole('button')
        .map(button => button.dataset.slot),
    ).toEqual([
      'dashboard-filter-locked',
      'dashboard-filter-locked',
      'dashboard-filter-locked',
    ]);
    expect(within(bar).queryAllByRole('textbox')).toEqual([]);
    expect(within(bar).queryAllByRole('combobox')).toEqual([]);
    expect(document.querySelector('[data-slot="view-expand"]')).toBeNull();
    // Nor does a record panel's header sort or resize: nothing on the
    // board changes how it is looked at.
    const list = screen.getByRole('group', { name: 'Order list' });
    await waitFor(() =>
      expect(within(list).getAllByRole('columnheader').length).toBeGreaterThan(
        0,
      ),
    );
    for (const head of within(list).getAllByRole('columnheader'))
      expect(within(head).queryByRole('button')).toBeNull();
    expect(list.querySelector('[data-slot="column-resizer"]')).toBeNull();
    // What the page holds is still its own: the reader's value came in
    // from the address and is in force, never held by the page.
    expect(runtime().getSnapshot().filters.values).toEqual({
      status: ['SHIPPED'],
    });
  });

  /**
   * 「铺满屏幕」 on an interactive embed, where the host asked for it (D36):
   * the embed's own surface fills the screen in place, and Escape puts it
   * back — the workbench's `useViewExpansion`, not the browser's
   * fullscreen.
   */
  it('fills the screen from its first row in the interactive tier, where the host asked', async () => {
    const { rerender } = embed({ interaction: 'interactive' });
    await chartRow('CN');
    expect(document.querySelector('[data-slot="view-expand"]')).toBeNull();

    rerender({ interaction: 'interactive', expandable: true });
    const expand = await screen.findByRole('button', {
      name: 'Fill the screen',
    });
    const surface = expand.closest<HTMLElement>('.fve-root')!;
    await userEvent.click(expand);
    expect(surface.getAttribute('data-view-expanded')).toBe('true');
    expect(expand.getAttribute('aria-expanded')).toBe('true');
    await userEvent.keyboard('{Escape}');
    expect(surface.hasAttribute('data-view-expanded')).toBe(false);
  });

  /**
   * A board-level search (D36, item 4 of the embed batch): a filter of the
   * `search` kind, wired to each detail panel's search field, runs the
   * reader's words as Wow's `SEARCH` over the fields the definition names —
   * and reaches only the panels wired to it.
   */
  it('searches the detail panel through a board filter of the search kind', async () => {
    const orders = ordersDefinition();
    const source = testSource();
    const engine = new ViewEngine({
      definitions: [
        {
          ...orders,
          fields: [
            ...orders.fields,
            {
              name: 'q',
              label: 'Search',
              kind: 'search',
              searchFields: ['id', 'status'],
            },
          ],
        },
        overviewDefinition(),
      ],
      store: new MemoryViewStore({
        instances: [
          ...views,
          {
            id: 'board',
            definitionId: 'overview',
            title: 'Operations',
            scope: 'shared',
            revision: 'r1',
            config: dashboardConfig({
              fields: [{ name: 'q', label: 'Find', kind: 'search' }],
              panels: [
                panel('list', 'list', 'Order list', {
                  bindings: [{ globalField: 'q', panelField: 'q' }],
                }),
              ],
            }),
          },
        ],
      }),
      resolveSource: () => source,
    });
    embed({ engine, interaction: 'interactive' });

    const find = await screen.findByRole('group', { name: 'Find' });
    // Empty, it invites a search rather than saying 「未设置」.
    expect(within(find).getByRole('textbox').getAttribute('placeholder')).toBe(
      'Search…',
    );
    await userEvent.type(within(find).getByRole('textbox'), 'SO-1');
    await waitFor(() =>
      expect(vi.mocked(source.paged).mock.lastCall?.[0].filter).toEqual({
        op: 'SEARCH',
        query: 'SO-1',
        mode: 'TERMS',
        fields: ['id', 'status'],
      }),
    );
  });

  /**
   * Embeds never write (D36): no 「编辑」, no save, no 另存为 in either tier,
   * even for a reader who may save the board — building a board is
   * `DashboardWorkbench`'s.
   */
  it('offers no building, save or save-as in either tier, whoever reads it', async () => {
    const { rerender } = embed({ withTitle: true, withExport: true });
    await chartRow('CN');
    const none = () => {
      for (const name of [/^Edit$/, /^Save$/, /^Save as/])
        expect(screen.queryByRole('button', { name })).toBeNull();
      expect(document.querySelector('[data-slot="dashboard-edit"]')).toBeNull();
      expect(document.querySelector('[data-slot="edit-bar"]')).toBeNull();
    };
    none();

    rerender({ withTitle: true, withExport: true, interaction: 'interactive' });
    await chartRow('CN');
    none();
    // The panel menu holds what reads, never what builds.
    await userEvent.click(
      screen.getByRole('button', { name: 'Actions for “Order list”' }),
    );
    expect(
      within(await screen.findByRole('menu'))
        .getAllByRole('menuitem')
        .map(item => item.textContent),
    ).toEqual(['Refresh this panel', 'Export data…']);
  });
});
