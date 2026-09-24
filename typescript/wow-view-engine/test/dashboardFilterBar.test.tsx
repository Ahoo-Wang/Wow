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
 * A board's filters on screen (D22 F, G, batch C2), through the default
 * workbench: the filter bar — a chip a filter with the condition editor's
 * value controls, required ones starred and never empty, the time grouping
 * as one choice, 「清空」, a filter reaching nothing on the tab drawn quieter
 * and saying why — the badge on a panel a filter does not reach, and while
 * the board is built 「添加筛选」, a filter's settings and wiring, with the
 * toast that says how many panels auto-connect wired and unwires them again.
 */

import {
  AggregationDateUnit,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type DashboardField,
  type DashboardPanel,
  type DashboardViewConfig,
  type DataViewDefinition,
  type ViewInstance,
} from '../src/index.js';
import { DashboardViewRuntime } from '../src/runtime/dashboardRuntime.js';
import { useDashboard, useFilterEditor } from '../src/react/index.js';
import { DashboardWorkbench } from '../src/ui/index.js';
import { FilterBar } from '../src/ui/dashboard/FilterBar.js';
import { barMove } from '../src/ui/dashboard/FilterOrder.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

const LAST_WEEK = { type: 'relative', amount: 7, unit: 'day' };

/** Orders with a creation time, bucketed by the day, the week or the month. */
function orders(): DataViewDefinition {
  const base = ordersDefinition();
  return {
    ...base,
    fields: [
      ...base.fields.map(field =>
        field.name === 'status'
          ? {
              ...field,
              kind: 'enum',
              options: [
                { value: 'PENDING', label: 'Pending' },
                { value: 'SHIPPED', label: 'Shipped' },
              ],
            }
          : field,
      ),
      { name: 'createdAt', label: 'Created', kind: 'datetime' },
    ],
    analysis: {
      count: true,
      fields: [
        ...(base.analysis?.fields ?? []),
        {
          field: 'createdAt',
          groups: [AggregationGroupType.DATE_HISTOGRAM],
          functions: [],
          dateUnits: [
            AggregationDateUnit.DAY,
            AggregationDateUnit.WEEK,
            AggregationDateUnit.MONTH,
          ],
        },
      ],
    },
  };
}

const views: ViewInstance[] = [
  {
    id: 'trend',
    definitionId: 'orders',
    title: 'Orders by day',
    scope: 'shared',
    revision: 'r1',
    config: analysisConfig({
      groups: [
        {
          alias: 'createdAt',
          field: 'createdAt',
          type: 'DATE_HISTOGRAM',
          unit: 'DAY',
        },
      ],
      chart: {
        type: 'line',
        cartesian: { x: 'createdAt', series: [{ metric: 'orders' }] },
      },
    }),
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

function panel(
  id: string,
  instanceId: string,
  x: number,
  bindings: DashboardPanel extends infer P
    ? P extends { bindings: infer B }
      ? B
      : never
    : never = [],
  title?: string,
): DashboardPanel {
  return {
    id,
    kind: 'view',
    instanceId,
    bindings,
    layout: { x, y: 0, w: 12, h: 4 },
    ...(title ? { title } : {}),
  } as DashboardPanel;
}

/** A required time filter on the trend, a region filter on the list. */
function board(): DashboardViewConfig {
  return dashboardConfig({
    fields: [
      {
        name: 'created',
        label: 'Created',
        kind: 'datetime',
        required: true,
        default: LAST_WEEK,
      },
      { name: 'region', label: 'Region', kind: 'string' },
    ],
    timeGrouping: { units: ['DAY', 'WEEK', 'MONTH'], default: 'DAY' },
    panels: [
      panel(
        'a',
        'trend',
        0,
        [{ globalField: 'created', panelField: 'createdAt' }],
        'Trend',
      ),
      panel(
        'b',
        'list',
        12,
        [{ globalField: 'region', panelField: 'warehouse' }],
        'List',
      ),
    ],
  });
}

function setup(
  config: DashboardViewConfig = board(),
  { workbench = true }: { workbench?: boolean } = {},
) {
  const source = testSource();
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
    definitions: [orders(), overviewDefinition()],
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
  if (workbench)
    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="board"
      />,
    );
  return { engine, source, runtime, store };
}

async function bar() {
  return screen.findByRole('region', { name: 'Filters' });
}

/** The 「不受…影响」 badge on the panel titled so, if it wears one. */
function badgeOn(title: string): string | undefined {
  const heading = screen.getByText(title, {
    selector: '[data-slot="panel-title"]',
  });
  return (
    heading
      .closest('[data-slot="dashboard-panel"]')
      ?.querySelector('[data-slot="panel-not-reached"]')?.textContent ??
    undefined
  );
}

function chip(name: string) {
  return screen.getByRole('group', { name });
}

describe('the filter bar (D22 F)', () => {
  it('draws a chip a filter, a required one starred, and the time grouping', async () => {
    setup();
    const filters = await bar();

    const created = within(filters).getByRole('group', {
      name: 'Created (required)',
    });
    expect(created.dataset.required).toBe('true');
    expect(within(filters).getByRole('group', { name: 'Region' })).toBeTruthy();
    const grouping = within(filters).getByRole('group', {
      name: 'Time grouping',
    });
    expect(
      within(grouping)
        .getAllByRole('button')
        .map(button => button.textContent),
    ).toEqual(['By day', 'By week', 'By month']);
    // At the defaults, there is nothing to clear.
    expect(
      (
        within(filters).getByRole('button', {
          name: 'Clear',
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true);
  });

  it('runs a value on its own, only on the panels it reaches, and clears it', async () => {
    const user = userEvent.setup();
    const { runtime } = setup();
    await bar();
    await waitFor(() => expect(runtime().getSnapshot().panels).toHaveLength(2));

    const region = within(chip('Region')).getByRole('combobox');
    await user.type(region, 'CN');
    await waitFor(() =>
      expect(runtime().getSnapshot().filters.values.region).toEqual(['CN']),
    );
    const scopeOf = (id: string) =>
      JSON.stringify(
        runtime()
          .getSnapshot()
          .panels.find(entry => entry.id === id)?.runtime?.scopeFilter,
      );
    await waitFor(() => expect(scopeOf('b')).toContain('"CN"'));
    expect(scopeOf('a')).not.toContain('"CN"');

    // The trend is not reached by it, and says so.
    await waitFor(() =>
      expect(badgeOn('Trend')).toBe('Not filtered by “Region”'),
    );

    await user.click(
      within(chip('Region')).getByRole('button', { name: 'Clear “Region”' }),
    );
    await waitFor(() =>
      expect(runtime().getSnapshot().filters.values.region).toBeUndefined(),
    );
    await waitFor(() => expect(badgeOn('Trend')).toBeUndefined());
    // The list is not reached by the time, which always holds a value.
    expect(badgeOn('List')).toBe('Not filtered by “Created”');
  });

  it('never leaves a required filter empty: its ✕ and 「清空」 go back to the default', async () => {
    const user = userEvent.setup();
    const { runtime } = setup();
    const filters = await bar();
    const created = within(filters).getByRole('group', {
      name: 'Created (required)',
    });
    // At its default, it has nothing to go back to.
    expect(
      within(created).queryByRole('button', { name: /default/ }),
    ).toBeNull();

    runtime().setFilterValue('created', {
      type: 'relative',
      amount: 1,
      unit: 'month',
    });
    await user.click(
      await within(created).findByRole('button', {
        name: 'Put “Created” back to its default',
      }),
    );
    expect(runtime().getSnapshot().filters.values.created).toEqual(LAST_WEEK);

    runtime().setFilterValue('region', ['CN']);
    runtime().setGroupingUnit('MONTH');
    const clear = within(filters).getByRole('button', { name: 'Clear' });
    await waitFor(() =>
      expect((clear as HTMLButtonElement).disabled).toBe(false),
    );
    await user.click(clear);
    expect(runtime().getSnapshot().filters).toEqual({
      values: { created: LAST_WEEK },
      unit: 'DAY',
    });
  });

  it('switches the time grouping of every panel that can take it', async () => {
    const user = userEvent.setup();
    const { runtime } = setup();
    const filters = await bar();
    await waitFor(() => expect(runtime().getSnapshot().panels).toHaveLength(2));

    await user.click(within(filters).getByRole('button', { name: 'By week' }));
    await waitFor(() => {
      const applied = runtime()
        .getSnapshot()
        .panels.find(entry => entry.id === 'a')
        ?.runtime?.getSnapshot().applied;
      expect(applied?.kind === 'analysis' && applied.groups[0]).toMatchObject({
        unit: 'WEEK',
      });
    });
    expect(
      within(filters)
        .getByRole('button', { name: 'By week' })
        .getAttribute('aria-pressed'),
    ).toBe('true');
  });

  it('draws a filter that reaches nothing on the tab quieter, and says why', async () => {
    setup(
      dashboardConfig({
        ...board(),
        panels: [panel('b', 'list', 0, [], 'List')],
      }),
    );
    const filters = await bar();
    await waitFor(() => expect(chip('Region').dataset.idle).toBe('true'));
    expect(
      within(chip('Region')).getByRole('button', {
        name: 'Nothing on this tab is filtered by “Region”, so it changes nothing here.',
      }),
    ).toBeTruthy();
    // No panel groups by time either.
    expect(
      filters
        .querySelector('[data-slot="dashboard-grouping"]')
        ?.getAttribute('data-idle'),
    ).toBe('true');
  });

  it('picks a category from the labels its wired fields declare, and holds their codes', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const { runtime } = setup(
      dashboardConfig({
        fields: [{ name: 'phase', label: 'Phase', kind: 'string' }],
        panels: [
          panel(
            'b',
            'list',
            0,
            [{ globalField: 'phase', panelField: 'status' }],
            'List',
          ),
        ],
      }),
    );
    await bar();
    const select = await within(chip('Phase')).findByRole('combobox', {
      name: 'Phase',
    });
    // A select over the list, not a box to type protocol codes into.
    expect(within(chip('Phase')).queryByRole('textbox')).toBeNull();
    await user.click(select);
    await user.click(await screen.findByRole('option', { name: 'Pending' }));
    await waitFor(() =>
      expect(runtime().getSnapshot().filters.values.phase).toEqual(['PENDING']),
    );
    expect(select.textContent).toContain('Pending');
  });

  it('draws no bar on a board without filters', async () => {
    setup(dashboardConfig({ panels: [panel('b', 'list', 0, [], 'List')] }));
    await screen.findByText('List');
    expect(screen.queryByRole('region', { name: 'Filters' })).toBeNull();
  });

  /**
   * D27: the board's fixed scope is read on the bar's row, at its head, as
   * the board's — 「Fixed scope」 — with a note on why and nothing to take
   * it out by; there is no 「正在显示」 band to say it instead.
   */
  it('reads the board’s fixed scope at the head of the bar, with nothing to remove it by', async () => {
    setup({ ...board(), fixed: NOT_NORTH });
    const filters = await bar();

    const fixed = await within(filters).findByRole('group', {
      name: 'Fixed scope',
    });
    expect(filters.firstElementChild).toBe(fixed);
    expect(fixed.textContent).toContain('Region is not north');
    expect(
      within(fixed)
        .getAllByRole('button')
        .map(button => button.getAttribute('aria-label')),
    ).toEqual([
      'The dashboard itself holds every panel to this; it cannot be changed here.',
    ]);
    expect(screen.queryByRole('region', { name: 'Showing' })).toBeNull();
  });

  /**
   * D23 Q16: while the board is built its author takes the fixed scope out
   * whole — a ✕ where the lock was, one step of the history, saved as the
   * empty tree; the keyboard lands on 「撤销」, which brings it back.
   */
  it('lets its author take the fixed scope out whole while building, undone like any edit', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const { runtime, store } = setup({ ...board(), fixed: NOT_NORTH });
    const scope = () =>
      within(document.body).queryByRole('group', { name: 'Fixed scope' });
    await bar();
    await startBuilding(user);

    const fixed = scope()!;
    expect(
      within(fixed)
        .getAllByRole('button')
        .map(button => button.getAttribute('aria-label')),
    ).toEqual(['Remove the fixed scope']);
    await user.click(
      within(fixed).getByRole('button', { name: 'Remove the fixed scope' }),
    );

    expect(scope()).toBeNull();
    expect(runtime().getSnapshot().draft.fixed).toEqual({
      op: 'and',
      children: [],
    });
    expect(
      document.querySelector('[data-slot="dashboard-announcement"]')
        ?.textContent,
    ).toBe('Removed the fixed scope');
    const undo = screen.getByRole('button', {
      name: 'Undo removing the fixed scope',
    });
    await waitFor(() => expect(document.activeElement).toBe(undo));

    await user.click(undo);
    await within(await bar()).findByText(/Region is not north/);
    expect(runtime().getSnapshot().draft.fixed).toEqual(NOT_NORTH);
    await user.click(
      screen.getByRole('button', { name: 'Redo removing the fixed scope' }),
    );
    expect(scope()).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(async () => {
      const stored = (await store.get('board')).config as DashboardViewConfig;
      expect(stored.fixed).toEqual({ op: 'and', children: [] });
    });
    // Read again, the board has no fixed scope for a reader to see.
    await screen.findByRole('button', { name: 'Edit' });
    expect(scope()).toBeNull();
  });
});

/** 「仓库 不是 north」, a board's fixed scope. */
const NOT_NORTH = {
  op: 'and',
  children: [{ field: 'region', operator: 'NE', value: 'north' }],
} as const satisfies DashboardViewConfig['fixed'];

/** The bar as a board draws it below `md`, over its own runtime. */
function NarrowBar({ runtime }: { runtime: DashboardViewRuntime }) {
  const dashboard = useDashboard(runtime);
  const { fixed } = useFilterEditor(runtime);
  return (
    <FilterBar
      dashboard={dashboard}
      fixed={fixed}
      modes={{ filters: { region: 'locked' } }}
      narrow
    />
  );
}

describe('the filter bar below md (D26 Q38)', () => {
  it('folds into one button and a sheet below md, the held ones read beside it (D26 Q38)', async () => {
    const { engine } = setup(
      { ...board(), fixed: NOT_NORTH },
      { workbench: false },
    );
    const runtime = (await engine.open('board')) as DashboardViewRuntime;
    const user = userEvent.setup();
    render(<NarrowBar runtime={runtime} />);
    const filters = await bar();

    expect(filters.hasAttribute('data-narrow')).toBe(true);
    // Read beside the button: the fixed scope and the locked filter.
    expect(
      within(filters).getByRole('group', { name: 'Fixed scope' }),
    ).toBeTruthy();
    const held = filters.querySelector<HTMLElement>(
      '[data-slot="dashboard-filter-held"]',
    );
    expect(held?.dataset.filter).toBe('region');
    // What the reader changes is not on the bar itself.
    expect(
      within(filters).queryByRole('group', { name: 'Created (required)' }),
    ).toBeNull();

    // One of the reader's filters holds a value: Created, at its default.
    await user.click(
      within(filters).getByRole('button', { name: 'Filters (1 set)' }),
    );
    const sheet = await screen.findByRole('dialog', { name: 'Filters' });
    expect(
      within(sheet).getByRole('group', { name: 'Created (required)' }),
    ).toBeTruthy();
    expect(
      within(sheet).getByRole('group', { name: 'Time grouping' }),
    ).toBeTruthy();
    expect(
      [
        ...sheet.querySelectorAll<HTMLElement>(
          '[data-slot="dashboard-filter"]',
        ),
      ].map(chip => chip.dataset.filter),
    ).toEqual(['created', 'region']);
  });

  it('offers no button when there is nothing the reader could change', async () => {
    const { engine } = setup(
      dashboardConfig({
        fields: [{ name: 'region', label: 'Region', kind: 'string' }],
        fixed: NOT_NORTH,
        panels: [
          panel(
            'b',
            'list',
            0,
            [{ globalField: 'region', panelField: 'warehouse' }],
            'List',
          ),
        ],
      }),
      { workbench: false },
    );
    const runtime = (await engine.open('board')) as DashboardViewRuntime;
    render(<NarrowBar runtime={runtime} />);
    const filters = await bar();

    expect(
      within(filters).queryByRole('button', { name: /Filters/ }),
    ).toBeNull();
    expect(filters.textContent).toContain('Region is not north');
  });
});

async function startBuilding(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: 'Edit' }));
  await screen.findByRole('region', { name: 'Editing' });
}

describe('adding and wiring a filter (D22 G)', () => {
  it('adds a date filter, sets it up, and wires it — auto-connecting the rest, with an undo', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const plain = dashboardConfig({
      panels: [
        panel('a', 'trend', 0, [], 'Trend'),
        panel('c', 'trend', 12, [], 'Trend again'),
        panel('b', 'list', 0, [], 'List'),
      ],
    });
    plain.panels[2] = {
      ...plain.panels[2],
      layout: { x: 0, y: 4, w: 12, h: 4 },
    };
    const { runtime } = setup(plain);
    await screen.findByText('Trend');
    await startBuilding(user);

    await user.click(screen.getByRole('button', { name: 'Add filter' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Date' }));

    // Its settings open on it at once.
    const settings = await screen.findByRole('dialog', {
      name: 'Settings of “Date”',
    });
    const name = within(settings).getByRole('textbox', { name: 'Name' });
    await user.clear(name);
    await user.type(name, 'When');
    await waitFor(() =>
      expect(runtime().getSnapshot().draft.fields[0].label).toBe('When'),
    );
    // A date takes one window: no 「可多选」, no list of its own.
    expect(within(settings).queryByText('Several values')).toBeNull();
    expect(within(settings).queryByText('Values from')).toBeNull();
    // Required without a default is said at the field.
    await user.click(
      within(settings).getByRole('checkbox', { name: 'Required' }),
    );
    expect(
      await within(settings).findByText(
        'A required filter needs a default to start at.',
      ),
    ).toBeTruthy();
    await user.click(
      within(settings).getByRole('checkbox', { name: 'Required' }),
    );

    await user.click(
      within(settings).getByRole('button', { name: 'Wire to panels' }),
    );
    const wiring = await screen.findByRole('region', { name: 'Wiring “When”' });
    const strips = () => [
      ...document.querySelectorAll('[data-slot="panel-wiring"]'),
    ];
    await waitFor(() => expect(strips()).toHaveLength(3));

    await user.click(
      screen.getByRole('combobox', {
        name: 'Field of “Trend” filtered by “When”',
      }),
    );
    await user.click(await screen.findByRole('option', { name: 'Created' }));

    // The other trend and the list — orders too — were wired on their own,
    // and the toast says so.
    expect(
      await screen.findByText(
        'Wired 2 more panels with a “Created” field automatically',
      ),
    ).toBeTruthy();
    const reach = (id: string) =>
      runtime()
        .getSnapshot()
        .panels.find(entry => entry.id === id)?.reach['filter-1'];
    await waitFor(() => expect(reach('c')).toMatchObject({ wired: true }));
    expect(reach('a')).toEqual({
      wired: true,
      field: 'createdAt',
      auto: false,
    });
    // Chosen by hand: 「手动」, and why on focus as on hover (U-11).
    await waitFor(() =>
      expect(
        document.querySelectorAll('[data-slot="panel-wiring-manual"]'),
      ).toHaveLength(1),
    );
    const manual = document.querySelector<HTMLElement>(
      '[data-slot="panel-wiring-manual"]',
    )!;
    expect(manual.tagName).toBe('BUTTON');
    expect(manual.getAttribute('title')).toBeNull();
    expect(
      document.getElementById(manual.getAttribute('aria-describedby')!)
        ?.textContent,
    ).toBe('Wired by hand rather than by name');

    await user.click(
      screen.getByRole('button', { name: 'Only the panel picked' }),
    );
    await waitFor(() =>
      expect(reach('c')).toEqual({ wired: false, why: 'unwired' }),
    );
    expect(reach('a')).toMatchObject({ wired: true });

    // Taken off by hand.
    await user.click(
      screen.getByRole('combobox', {
        name: 'Field of “Trend” filtered by “When”',
      }),
    );
    await user.click(await screen.findByRole('option', { name: 'Not wired' }));
    await waitFor(() => expect(reach('a')).toMatchObject({ wired: false }));

    await user.click(
      within(wiring).getByRole('button', { name: 'Done wiring' }),
    );
    expect(screen.queryByRole('region', { name: 'Wiring “When”' })).toBeNull();
    expect(strips()).toHaveLength(0);

    // A yes-or-no filter has nothing to wire to on these panels, and says so.
    await user.click(screen.getByRole('button', { name: 'Add filter' }));
    await user.click(
      await screen.findByRole('menuitem', { name: 'Yes or no' }),
    );
    const yesNo = await screen.findByRole('dialog', {
      name: 'Settings of “Yes or no”',
    });
    await user.click(
      within(yesNo).getByRole('button', { name: 'Wire to panels' }),
    );
    await waitFor(() =>
      expect(
        document.querySelectorAll('[data-slot="panel-wiring-none"]'),
      ).toHaveLength(3),
    );
  });

  it('sets a text filter up: several values, a list of its own, then removes it', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const { runtime } = setup();
    await bar();
    await startBuilding(user);

    await user.click(
      within(chip('Region')).getByRole('button', {
        name: 'Settings of “Region”',
      }),
    );
    const settings = await screen.findByRole('dialog', {
      name: 'Settings of “Region”',
    });
    await user.click(
      within(settings).getByRole('checkbox', { name: 'Several values' }),
    );
    await waitFor(() =>
      expect(runtime().getSnapshot().draft.fields[1].multiple).toBe(true),
    );
    await user.click(
      within(settings).getByRole('button', { name: 'A list of its own' }),
    );
    const list = await within(settings).findByRole('combobox', {
      name: 'New The values',
    });
    await user.type(list, 'CN{Enter}');
    await waitFor(() =>
      expect(runtime().getSnapshot().draft.fields[1].options).toEqual([
        { value: 'CN', label: 'CN' },
      ]),
    );
    await user.click(
      within(settings).getByRole('button', { name: 'The wired fields' }),
    );
    await waitFor(() =>
      expect(runtime().getSnapshot().draft.fields[1].options).toBeUndefined(),
    );
    await user.click(
      within(settings).getByRole('button', { name: 'Remove the filter' }),
    );
    await waitFor(() =>
      expect(
        runtime()
          .getSnapshot()
          .draft.fields.map(field => field.name),
      ).toEqual(['created']),
    );
  });

  it('adds the time grouping from 「添加筛选」 and takes it off from the bar', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const plain = board();
    delete plain.timeGrouping;
    const { runtime } = setup(plain);
    await bar();
    await startBuilding(user);

    await user.click(screen.getByRole('button', { name: 'Add filter' }));
    await user.click(
      await screen.findByRole('menuitem', { name: 'Time grouping' }),
    );
    await waitFor(() =>
      expect(runtime().getSnapshot().draft.timeGrouping).toEqual({
        units: ['DAY', 'WEEK', 'MONTH'],
        default: 'DAY',
      }),
    );
    await user.click(
      screen.getByRole('button', { name: 'Remove the time grouping' }),
    );
    await waitFor(() =>
      expect(runtime().getSnapshot().draft.timeGrouping).toBeUndefined(),
    );
  });
});

describe('putting the filters in order (D22 G)', () => {
  /** The filters on the bar, by name, in the order drawn. */
  const onBar = () =>
    [
      ...document.querySelectorAll<HTMLElement>(
        '[data-slot="dashboard-filter-bar"] [data-filter]',
      ),
    ].map(entry => entry.dataset.filter);

  it('moves a filter by the arrows on its handle, and saves the order with the board', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const { runtime, store } = setup();
    await bar();
    // Read, the order is read: no handle to carry a filter by.
    expect(
      screen.queryByRole('button', { name: 'Reorder “Region”' }),
    ).toBeNull();
    await startBuilding(user);

    const handle = screen.getByRole('button', { name: 'Reorder “Region”' });
    handle.focus();
    await user.keyboard('{ArrowLeft}');
    expect(
      runtime()
        .getSnapshot()
        .draft.fields.map(f => f.name),
    ).toEqual(['region', 'created']);
    expect(onBar()).toEqual(['region', 'created']);
    expect(
      document.querySelector('[data-slot="dashboard-announcement"]')
        ?.textContent,
    ).toBe('“Region” is now filter 1 of 2');
    // The keyboard stays on the handle it moved, and the time grouping
    // keeps its place after the filters.
    await waitFor(() =>
      expect(document.activeElement).toBe(
        screen.getByRole('button', { name: 'Reorder “Region”' }),
      ),
    );
    const grouping = screen.getByRole('group', { name: 'Time grouping' });
    expect(
      chip('Created (required)').compareDocumentPosition(grouping) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    // No place past either end, and no arrow up or down on a row.
    await user.keyboard('{ArrowLeft}{ArrowUp}');
    expect(onBar()).toEqual(['region', 'created']);

    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(async () => {
      const stored = (await store.get('board')).config as DashboardViewConfig;
      expect(stored.fields?.map(field => field.name)).toEqual([
        'region',
        'created',
      ]);
    });
  });
});

/**
 * The places a move names are the bar's: a filter the embedding page hid
 * is not on it, and a move goes past the neighbour drawn.
 */
describe('where a move on the bar lands among all the filters', () => {
  const field = (name: string): DashboardField => ({
    name,
    label: name,
    kind: 'string',
  });
  const all = ['a', 'hidden', 'b', 'c'].map(field);
  const shown = ['a', 'b', 'c'].map(field);

  it('lands just past the neighbour it moves over, a hidden one between kept in order', () => {
    // a → after b: [hidden, b, a, c].
    expect(barMove(shown, all, 'a', 1)).toBe(2);
    // b → before a: [b, a, hidden, c].
    expect(barMove(shown, all, 'b', 0)).toBe(0);
    expect(barMove(shown, all, 'c', 0)).toBe(0);
  });

  it('is no move off the bar, past its ends, or onto the place it holds', () => {
    expect(barMove(shown, all, 'hidden', 0)).toBeNull();
    expect(barMove(shown, all, 'a', -1)).toBeNull();
    expect(barMove(shown, all, 'c', 3)).toBeNull();
    expect(barMove(shown, all, 'b', 1)).toBeNull();
    expect(barMove(shown, shown.slice(1), 'b', 0)).toBeNull();
  });
});

/**
 * A press on the bar that takes its own control away used to leave the
 * keyboard on `<body>` (U-02); it lands on the next sensible control now,
 * as it does on the panels.
 */
describe('where the keyboard goes after a press on the bar (U-02)', () => {
  /** Presses Enter on a control the way a keyboard does: focused first. */
  async function enter(
    user: ReturnType<typeof userEvent.setup>,
    control: HTMLElement,
  ) {
    control.focus();
    await user.keyboard('{Enter}');
  }
  const valueOf = (name: string) =>
    chip(name).querySelector('[data-slot="filter-value"]')!;

  it('lands on the value a ✕ cleared, and on the first value after 「清空」', async () => {
    const user = userEvent.setup();
    const { runtime } = setup();
    const filters = await bar();

    runtime().setFilterValue('region', ['CN']);
    await enter(
      user,
      await within(chip('Region')).findByRole('button', {
        name: 'Clear “Region”',
      }),
    );
    await waitFor(() =>
      expect(valueOf('Region').contains(document.activeElement)).toBe(true),
    );

    runtime().setFilterValue('region', ['CN']);
    const clear = within(filters).getByRole('button', { name: 'Clear' });
    await waitFor(() =>
      expect((clear as HTMLButtonElement).disabled).toBe(false),
    );
    await enter(user, clear);
    // Disabled by its own press: on to the first chip's value.
    await waitFor(() =>
      expect(
        valueOf('Created (required)').contains(document.activeElement),
      ).toBe(true),
    );
  });

  it('lands on 「添加筛选」 when the time grouping goes, and on the grouping when it comes back', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    setup();
    await bar();
    await startBuilding(user);

    await enter(
      user,
      screen.getByRole('button', { name: 'Remove the time grouping' }),
    );
    await waitFor(() =>
      expect(document.activeElement).toBe(
        screen.getByRole('button', { name: 'Add filter' }),
      ),
    );

    await user.keyboard('{Enter}');
    await enter(
      user,
      await screen.findByRole('menuitem', { name: 'Time grouping' }),
    );
    await waitFor(() =>
      expect(
        screen
          .getByRole('group', { name: 'Time grouping' })
          .contains(document.activeElement),
      ).toBe(true),
    );
  });

  it('goes back to the settings wiring started from, and on past a removed filter', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    setup();
    await bar();
    await startBuilding(user);
    const gear = () =>
      within(chip('Created (required)')).getByRole('button', {
        name: 'Settings of “Created”',
      });

    await user.click(gear());
    const settings = await screen.findByRole('dialog', {
      name: 'Settings of “Created”',
    });
    await user.click(
      within(settings).getByRole('button', { name: 'Wire to panels' }),
    );
    await enter(
      user,
      await screen.findByRole('button', { name: 'Done wiring' }),
    );
    await waitFor(() => expect(document.activeElement).toBe(gear()));

    await user.click(gear());
    await enter(
      user,
      within(
        await screen.findByRole('dialog', { name: 'Settings of “Created”' }),
      ).getByRole('button', { name: 'Remove the filter' }),
    );
    // The chip that took its place.
    await waitFor(() =>
      expect(valueOf('Region').contains(document.activeElement)).toBe(true),
    );
  });

  /**
   * One primary on the board while a filter is wired (U-09): 「保存」, which
   * saves. 「完成接线」 under it and 「接线」 in the settings are outline.
   */
  it('carries one primary while a filter is wired, and it is 保存', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    setup();
    await bar();
    await startBuilding(user);
    await user.click(
      within(chip('Region')).getByRole('button', {
        name: 'Settings of “Region”',
      }),
    );
    const settings = await screen.findByRole('dialog', {
      name: 'Settings of “Region”',
    });
    const wire = within(settings).getByRole('button', {
      name: 'Wire to panels',
    });
    // **Surviving class assertions**: emphasis is a fill and nothing else —
    // a button carries no state saying which variant it is (the same
    // exception as `test/saveActions.test.tsx`), and the board's buttons
    // name their own `data-slot`, so every `<button>` is probed. What the
    // fills come to is measured in the browser stories.
    const primary = () =>
      [...document.querySelectorAll<HTMLElement>('button')]
        .filter(button => button.classList.contains('bg-primary'))
        .map(button => button.textContent?.trim());
    expect(wire.classList.contains('bg-primary')).toBe(false);
    await user.click(wire);
    await screen.findByRole('button', { name: 'Done wiring' });
    expect(primary()).toEqual(['Save']);
  });

  /**
   * A setting's words are its control's name (U-16): the type's label is a
   * `<label>` for its select, and the list's a legend over its box.
   */
  it('names the settings’ controls by the words over them', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    setup();
    await bar();
    await startBuilding(user);
    await user.click(
      within(chip('Region')).getByRole('button', {
        name: 'Settings of “Region”',
      }),
    );
    const settings = await screen.findByRole('dialog', {
      name: 'Settings of “Region”',
    });
    const type = within(settings).getByLabelText('Type');
    expect(type.getAttribute('aria-label')).toBeNull();
    expect(type.dataset.slot).toBe('select-trigger');
    await user.click(
      within(settings).getByRole('button', { name: 'A list of its own' }),
    );
    expect(
      await within(settings).findByRole('group', { name: 'The values' }),
    ).toBeTruthy();
  });
});

/**
 * One voice per board (Q-03, U-08): the grid, the tabs, the filters and the
 * building all speak through the board's one polite region; the toast
 * viewport — a landmark and a region of its own — is there only while the
 * board is built, the one time a toast can come, and named in the board's
 * language.
 */
describe('one live region on a board', () => {
  // The board's own, inside its surface. `@dnd-kit`'s `Accessibility`
  // plugin appends a region of its own to `<body>` once a drag handle is
  // drawn (the filters' and the tabs' order while building): it says the
  // pick-up and the drop the plugin drives, which never reach the board's
  // voice (`Announcer.tsx`).
  const regions = () =>
    [
      ...document.querySelectorAll<HTMLElement>(
        '[data-slot="view-surface"] [aria-live]',
      ),
    ].map(region => region.dataset.slot);

  it('says everything in one region, and has no toasts to show while read', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    setup();
    await bar();
    expect(regions()).toEqual(['dashboard-announcement']);
    expect(screen.queryByRole('region', { name: 'Notifications' })).toBeNull();

    await startBuilding(user);
    // Which of the two comes first in the DOM is not the rule; that the
    // toasts are the only other one, and only now, is.
    expect(regions().sort()).toEqual([
      'dashboard-announcement',
      'dashboard-toasts',
    ]);
    expect(
      screen.getByRole('region', { name: 'Wiring notices' }).dataset.slot,
    ).toBe('dashboard-toasts');
  });
});
