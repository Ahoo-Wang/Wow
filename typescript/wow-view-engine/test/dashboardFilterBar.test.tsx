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
 * the board is built 「筛选 ＋」, a filter's settings and wiring, with the
 * toast that says how many panels auto-connect wired and undoes it.
 */

import {
  AggregationDateUnit,
  AggregationGroupType,
} from '@ahoo-wang/fetcher-wow';
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
  DashboardViewRuntime,
  MemoryViewStore,
  ViewEngine,
  type DashboardField,
  type DashboardPanel,
  type DashboardViewConfig,
  type DataViewDefinition,
  type ViewInstance,
} from '../src/index.js';
import { DashboardWorkbench } from '../src/ui/index.js';
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

function setup(config: DashboardViewConfig = board()) {
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

    await user.click(screen.getByRole('button', { name: 'Add a filter' }));
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
    // Chosen by hand: 「手动」.
    await waitFor(() =>
      expect(
        document.querySelectorAll('[data-slot="panel-wiring-manual"]'),
      ).toHaveLength(1),
    );

    await user.click(screen.getByRole('button', { name: 'Undo' }));
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
    await user.click(screen.getByRole('button', { name: 'Add a filter' }));
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

  it('adds the time grouping from 「筛选 ＋」 and takes it off from the bar', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const plain = board();
    delete plain.timeGrouping;
    const { runtime } = setup(plain);
    await bar();
    await startBuilding(user);

    await user.click(screen.getByRole('button', { name: 'Add a filter' }));
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

    await user.click(screen.getByRole('button', { name: 'Done' }));
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
