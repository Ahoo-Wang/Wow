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
 * split from `EmbeddedView` by resource; its tier — read-only, interactive,
 * editable; each filter editable, locked or hidden, its values the host's
 * address and followed; and the switches — title, panel titles, export.
 */

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
import { FilterOperator } from '@ahoo-wang/wow-client';
import {
  builtinFieldKinds,
  MemoryViewStore,
  ViewEngine,
  withFieldKinds,
  type FieldKind,
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

  it('answers no press and offers no way off the board in the read-only tier', async () => {
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
   * The export is a switch, not a tier (D24 Q24): switched on, a record
   * panel's 「⋯」 holds 导出数据… — alone on a board that is only read — and
   * so does an analysis panel's (D25 Q28); switched off (the default), there
   * is no such item in any tier.
   */
  it('offers a panel’s export where the host switched it on, in any tier', async () => {
    const user = userEvent.setup();
    embed({ withExport: true });

    const menu = await screen.findByRole('button', {
      name: 'Actions for “Order list”',
    });
    await user.click(menu);
    expect(
      within(await screen.findByRole('menu'))
        .getAllByRole('menuitem')
        .map(item => item.textContent),
    ).toEqual(['Export data…']);
    await user.click(screen.getByRole('menuitem', { name: 'Export data…' }));
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
      within(await screen.findByRole('menu'))
        .getAllByRole('menuitem')
        .map(item => item.textContent),
    ).toEqual(['Export data…']);
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

  it('builds in place in the editable tier, for whoever may save the board', async () => {
    const { rerender } = embed({ interaction: 'editable', withTitle: true });

    await userEvent.click(
      await screen.findByRole('button', { name: /^Edit$/ }),
    );
    const bar = await screen.findByRole('region', { name: /Editing/ });
    // It stays in view while the board is built (R3b); the pixels are the
    // browser story's to measure.
    expect(bar.hasAttribute('data-sticky')).toBe(true);
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    // The keyboard is handed back to 「编辑」, which is back as the bar goes.
    await waitFor(() =>
      expect(document.activeElement?.textContent).toBe('Edit'),
    );

    // The interactive tier offers no building.
    rerender({ interaction: 'interactive' });
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /^Edit$/ })).toBeNull(),
    );
  });

  /**
   * Q-01: the embed says what the board says above its panels, the one
   * reading the workbench shares — a panel's finding the draft raised and
   * no panel wears yet among it, named after its panel, since 「这个面板」
   * points at nothing up there. It used to leave every finding under
   * `['panels', n]` to the panels, and this one no panel held.
   */
  it('says a draft panel warning no panel wears yet while building, after its panel', async () => {
    // Warns on the panel's field only, so the finding is the panel's and
    // nowhere else: the board's own reading of its scope says nothing.
    const rounded: FieldKind = {
      id: 'rounded',
      operators: ['EQ'],
      defaultOperator: 'EQ',
      emptyValue: () => null,
      validate: ({ value, field, path }) =>
        field.name === 'mass' &&
        typeof value === 'number' &&
        !Number.isInteger(value)
          ? [{ code: 'filter.value.rounded', severity: 'warning', path }]
          : [],
      compile: ({ leaf, field }) => ({
        op: FilterOperator.EQ,
        field: field.name,
        value: Math.round(leaf.value as number),
      }),
      editor: () => ({ input: 'number' }),
      describe: ({ leaf, field }) => ({
        text: `${field.label} = ${String(leaf.value)}`,
        value: { kind: 'text', value: String(leaf.value) },
      }),
    };
    const orders = ordersDefinition();
    const engine = new ViewEngine({
      definitions: [
        {
          ...orders,
          fields: [
            ...orders.fields,
            { name: 'mass', label: 'Mass', kind: 'rounded' },
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
              fields: [{ name: 'weight', label: 'Weight', kind: 'rounded' }],
              panels: [
                panel('list', 'list', 'Order list', {
                  bindings: [{ globalField: 'weight', panelField: 'mass' }],
                }),
              ],
            }),
          },
        ],
      }),
      resolveSource: () => testSource(),
      kinds: withFieldKinds(builtinFieldKinds, [rounded]),
    });
    const { runtime } = embed({ engine, interaction: 'editable' });
    await userEvent.click(
      await screen.findByRole('button', { name: /^Edit$/ }),
    );
    const notice = () =>
      document.querySelector('[data-slot="status-strip"][data-tone="warning"]');
    expect(notice()).toBeNull();

    // The board's fixed scope, changed in the draft and not yet applied:
    // mapped onto the panel's field it warns, and no panel wears that yet.
    act(() =>
      runtime().edit({
        fixed: {
          op: 'and',
          children: [{ field: 'weight', operator: 'EQ', value: 2.5 }],
        },
      }),
    );

    await waitFor(() =>
      expect(notice()?.textContent).toContain(
        'Order list: filter.value.rounded',
      ),
    );
    expect(
      runtime()
        .getSnapshot()
        .panels.flatMap(entry => entry.issues),
    ).toEqual([]);
    // A warning stops nothing: the board is still there to build on.
    expect(screen.getByRole('region', { name: /Editing/ })).toBeDefined();
  });

  it('reorders the filters on the bar while building, the locked one with them and the hidden one kept held', async () => {
    const config = board();
    config.fields = [
      ...(config.fields ?? []),
      { name: 'code', label: 'Code', kind: 'string' },
    ];
    const { runtime } = embed({
      engine: engineOf(config),
      interaction: 'editable',
      filterModes: { region: 'locked', status: 'hidden' },
      pageValues: { values: { region: ['CN'], status: ['PENDING'] } },
    });

    await userEvent.click(
      await screen.findByRole('button', { name: /^Edit$/ }),
    );
    // A locked filter is carried like the rest; a hidden one is not there.
    expect(
      screen.getByRole('button', { name: 'Reorder “Region”' }),
    ).toBeDefined();
    expect(
      screen.queryByRole('button', { name: 'Reorder “State”' }),
    ).toBeNull();
    screen.getByRole('button', { name: 'Reorder “Code”' }).focus();
    await userEvent.keyboard('{ArrowLeft}');

    // Past the locked neighbour on the bar; the hidden one keeps its order
    // among the rest, and what the page holds stays held.
    expect(
      runtime()
        .getSnapshot()
        .draft.fields.map(f => f.name),
    ).toEqual(['code', 'region', 'status']);
    expect(
      document.querySelector('[data-slot="dashboard-announcement"]')
        ?.textContent,
    ).toBe('“Code” is now filter 1 of 2');
    expect(
      (await screen.findByRole('group', { name: 'Region (set by this page)' }))
        .textContent,
    ).toContain('CN');
    expect(runtime().getSnapshot().filters.values).toEqual({
      region: ['CN'],
      status: ['PENDING'],
    });
  });

  it('offers no building on a board nobody may save here', async () => {
    const engine = new ViewEngine({
      definitions: [
        ordersDefinition(),
        overviewDefinition({
          views: [{ id: 'ops', title: 'Ops', config: board() }],
        }),
      ],
      store: new MemoryViewStore({ instances: views }),
      resolveSource: () => testSource(),
    });
    render(
      <EmbeddedDashboard
        engine={engine}
        instanceId="system:overview:ops"
        interaction="editable"
      />,
    );

    await chartRow('CN');
    expect(screen.queryByRole('button', { name: /^Edit$/ })).toBeNull();
  });
});
