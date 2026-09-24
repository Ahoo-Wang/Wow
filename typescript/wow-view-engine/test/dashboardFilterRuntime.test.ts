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
 * A board's filters in the runtime (D22 F/G, batch C1): what they hold is
 * the reader's — it starts at the defaults or a host's address, never
 * makes the board dirty, and runs on its own a moment after it changes
 * (「改了就跑」), a required filter never empty; a filter reaches only the
 * panels wired to it; the time grouping sets every wired time dimension it
 * can; setting the filters up goes through the draft like every edit of a
 * board, a panel added coming wired; a text filter offers the values of the
 * fields it is wired to.
 */

import {
  AggregationDateUnit,
  AggregationGroupType,
} from '@ahoo-wang/fetcher-wow';
import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import {
  AUTO_APPLY_DELAY_MS,
  DashboardViewRuntime,
  MemoryViewStore,
  ViewEngine,
  type AnalysisViewConfig,
  type DashboardFilters,
  type DashboardPanel,
  type DashboardViewConfig,
  type DataViewDefinition,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { useDashboard } from '../src/react/index.js';
import {
  analysisConfig,
  dashboardConfig,
  nextTask,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';

const LAST_WEEK = { type: 'relative', amount: 7, unit: 'day' };
const LAST_MONTH = { type: 'relative', amount: 1, unit: 'month' };

/** Orders, bucketed by the day, the week or the month. */
function orders(): DataViewDefinition {
  const base = ordersDefinition();
  return {
    ...base,
    fields: [
      // A closed set: a category filter wired to it picks from its labels.
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

/** Shipments, another dataset: a date bucketed by the month alone. */
function shipments(): DataViewDefinition {
  return {
    id: 'shipments',
    title: 'Shipments',
    kind: 'data',
    source: 'shipments',
    fields: [
      { name: 'createdAt', label: 'Shipped', kind: 'date' },
      { name: 'warehouse', label: 'Warehouse', kind: 'string' },
    ],
    analysis: {
      count: true,
      fields: [
        {
          field: 'warehouse',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
        {
          field: 'createdAt',
          groups: [AggregationGroupType.DATE_HISTOGRAM],
          functions: [],
          dateUnits: [AggregationDateUnit.MONTH],
        },
      ],
    },
  };
}

function trend(unit: 'DAY' | 'MONTH'): AnalysisViewConfig {
  return analysisConfig({
    groups: [
      { alias: 'createdAt', field: 'createdAt', type: 'DATE_HISTOGRAM', unit },
    ],
    chart: {
      type: 'line',
      cartesian: { x: 'createdAt', series: [{ metric: 'orders' }] },
    },
  });
}

const views: ViewInstance[] = [
  {
    id: 'orders-trend',
    definitionId: 'orders',
    title: 'Orders by day',
    scope: 'shared',
    revision: 'r1',
    config: trend('DAY'),
  },
  {
    id: 'ship-trend',
    definitionId: 'shipments',
    title: 'Shipments by month',
    scope: 'shared',
    revision: 'r1',
    config: trend('MONTH'),
  },
  {
    id: 'orders-list',
    definitionId: 'orders',
    title: 'Orders',
    scope: 'shared',
    revision: 'r1',
    config: recordConfig(),
  },
];

function view(
  id: string,
  instanceId: string,
  bindings: { globalField: string; panelField: string; auto?: true }[] = [],
  x = 0,
): DashboardPanel {
  return {
    id,
    kind: 'view',
    instanceId,
    bindings,
    layout: { x, y: 0, w: 8, h: 4 },
  } as DashboardPanel;
}

/**
 * A required time filter and a text filter that takes several; the trend of
 * orders wired to both, of shipments to the time alone, the order list to
 * the text alone; and a time grouping by the day, the week or the month.
 */
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
      { name: 'region', label: 'Region', kind: 'string', multiple: true },
    ],
    timeGrouping: { units: ['DAY', 'WEEK', 'MONTH'], default: 'WEEK' },
    panels: [
      view('a', 'orders-trend', [
        { globalField: 'created', panelField: 'createdAt' },
        { globalField: 'region', panelField: 'warehouse' },
      ]),
      view(
        'b',
        'ship-trend',
        [{ globalField: 'created', panelField: 'createdAt', auto: true }],
        8,
      ),
      view(
        'c',
        'orders-list',
        [{ globalField: 'region', panelField: 'warehouse' }],
        16,
      ),
    ],
  });
}

/** Candidate rows for a values question, and none for anything else. */
function sourceAnswering(values: Record<string, number>): ViewSource {
  return testSource({
    aggregate: vi.fn(query =>
      Promise.resolve(
        query.groupBy?.[0]?.alias === 'value'
          ? Object.entries(values).map(([value, count]) => ({ value, count }))
          : [],
      ),
    ),
  });
}

async function harness(
  config: DashboardViewConfig = board(),
  filters?: DashboardFilters,
) {
  const clock = testEnvironment();
  const sources: Record<string, ViewSource> = {
    orders: sourceAnswering({ CN: 3, EU: 1 }),
    shipments: sourceAnswering({ CN: 2, US: 5 }),
  };
  const store = new MemoryViewStore({ instances: views });
  const engine = new ViewEngine({
    definitions: [orders(), shipments(), overviewDefinition()],
    store,
    resolveSource: key => sources[key],
    environment: clock.environment,
  });
  const saved = await store.create(
    { definitionId: 'overview', title: 'Board', scope: 'shared', config },
    { requestId: 'board' },
  );
  const runtime = await engine.open(saved.id, filters ? { filters } : {});
  await nextTask();
  if (!(runtime instanceof DashboardViewRuntime))
    throw new Error('expected a dashboard');
  const panel = (id: string) => {
    const found = runtime.getSnapshot().panels.find(entry => entry.id === id);
    if (!found) throw new Error(`no panel ${id}`);
    return found;
  };
  return {
    runtime,
    clock,
    sources,
    panel,
    scope: (id: string) => panel(id).runtime?.scopeFilter ?? null,
    config: (id: string) => panel(id).runtime?.getSnapshot().applied,
  };
}

describe('what the filters hold', () => {
  it('starts at the defaults, each panel under the filters wired to it', async () => {
    const { runtime, scope } = await harness();

    expect(runtime.getSnapshot().filters).toEqual({
      values: { created: LAST_WEEK },
      unit: 'WEEK',
    });
    expect(scope('a')).toEqual({
      op: 'and',
      children: [
        {
          op: 'and',
          children: [
            { field: 'createdAt', operator: 'BETWEEN', value: LAST_WEEK },
          ],
        },
      ],
    });
    // The list is wired to the region alone, which holds nothing.
    expect(JSON.stringify(scope('c'))).not.toContain('createdAt');
    expect(runtime.getSnapshot().dirty).toBe(false);
  });

  it('runs a change a moment later on its own, only where the filter is wired, and never makes the board dirty', async () => {
    const { runtime, clock, scope } = await harness();

    expect(runtime.setFilterValue('region', ['CN'])).toEqual([]);
    // Shown at once …
    expect(runtime.getSnapshot().filters.values.region).toEqual(['CN']);
    expect(JSON.stringify(scope('a'))).not.toContain('"CN"');
    // … and run a moment after the last change.
    clock.advance(AUTO_APPLY_DELAY_MS);
    expect(JSON.stringify(scope('a'))).toContain('"CN"');
    expect(JSON.stringify(scope('c'))).toContain('"CN"');
    // The shipments trend is not wired to the region.
    expect(JSON.stringify(scope('b'))).not.toContain('"CN"');
    expect(runtime.getSnapshot().dirty).toBe(false);
    expect(runtime.getSnapshot().draft.fields[1].default).toBeUndefined();
  });

  it('puts a required filter back at its default when cleared', async () => {
    const { runtime } = await harness();

    runtime.setFilterValue('created', LAST_MONTH);
    expect(runtime.getSnapshot().filters.values.created).toEqual(LAST_MONTH);
    runtime.setFilterValue('created', null);
    expect(runtime.getSnapshot().filters.values.created).toEqual(LAST_WEEK);
    runtime.setFilterValue('region', ['CN']);
    runtime.clearFilters();
    expect(runtime.getSnapshot().filters).toEqual({
      values: { created: LAST_WEEK },
      unit: 'WEEK',
    });
  });

  it('refuses a value its filter cannot take, and changes nothing', async () => {
    const { runtime } = await harness();
    const before = runtime.getSnapshot().filters;

    expect(
      runtime.setFilterValue('created', 'yesterday').map(found => found.code),
    ).toEqual(['filter.value.expected-date']);
    expect(runtime.setFilterValue('gone', ['x'])[0].code).toBe(
      'dashboard.filter.unknown',
    );
    expect(runtime.setFilterValue('region', [])).toEqual([]);
    expect(runtime.getSnapshot().filters).toBe(before);
  });

  it('opens under what a host keeps in its address', async () => {
    const { runtime, scope } = await harness(board(), {
      values: { region: ['EU'], created: LAST_MONTH },
      unit: 'MONTH',
    });

    expect(runtime.getSnapshot().filters).toEqual({
      values: { region: ['EU'], created: LAST_MONTH },
      unit: 'MONTH',
    });
    // The very first query carries them: no run under the defaults first.
    expect(JSON.stringify(scope('c'))).toContain('"EU"');
    expect(runtime.refusedFilters).toEqual([]);
    expect(runtime.setFilters({ values: {}, unit: 'DAY' })).toEqual([]);
    expect(runtime.getSnapshot().filters).toEqual({
      values: { created: LAST_WEEK },
      unit: 'DAY',
    });
  });

  it('opens on what it takes of an address gone partly stale, and says what it left out', async () => {
    const { runtime, scope } = await harness(board(), {
      values: {
        region: ['EU'],
        // A filter renamed since the address was written, and a value the
        // required date filter cannot read.
        gone: ['x'],
        created: 'yesterday',
      },
      unit: 'MONTH',
    });

    expect(runtime.getSnapshot().filters).toEqual({
      values: { region: ['EU'], created: LAST_WEEK },
      unit: 'MONTH',
    });
    // The very first query carries what was taken.
    expect(JSON.stringify(scope('c'))).toContain('"EU"');
    expect(runtime.refusedFilters.map(found => found.code)).toEqual([
      'dashboard.filter.unknown',
      'filter.value.expected-date',
    ]);
    // Said as the board opened, and not again.
    runtime.setFilterValue('region', ['CN']);
    expect(runtime.refusedFilters).toHaveLength(2);
  });

  it('takes what it can of every filter put at once, answers the rest, and runs on it', async () => {
    const { runtime, clock, scope } = await harness();

    const refused = runtime.setFilters({
      values: { region: ['CN'], gone: ['y'], created: 'yesterday' },
      unit: 'DAY',
    });
    expect(refused.map(found => found.code)).toEqual([
      'dashboard.filter.unknown',
      'filter.value.expected-date',
    ]);
    expect(refused[0].path).toEqual(['filters', 'gone']);
    // A required filter refused holds its default rather than nothing.
    expect(runtime.getSnapshot().filters).toEqual({
      values: { region: ['CN'], created: LAST_WEEK },
      unit: 'DAY',
    });
    clock.advance(AUTO_APPLY_DELAY_MS);
    expect(JSON.stringify(scope('c'))).toContain('"CN"');
    // Said again, it answers the same, and nothing moves.
    const before = runtime.getSnapshot().filters;
    expect(
      runtime
        .setFilters({ values: { region: ['CN'], gone: ['y'] }, unit: 'DAY' })
        .map(found => found.code),
    ).toEqual(['dashboard.filter.unknown']);
    expect(runtime.getSnapshot().filters).toBe(before);
  });

  it('does nothing once disposed', async () => {
    const { runtime } = await harness();
    runtime.dispose();

    expect(runtime.setFilterValue('region', ['CN'])).toEqual([]);
    expect(runtime.setFilters({ values: {} })).toEqual([]);
    runtime.setGroupingUnit('DAY');
    runtime.clearFilters();
    expect(runtime.getSnapshot().filters.values.region).toBeUndefined();
  });
});

describe('the time grouping', () => {
  it("sets every panel's time dimension it can, and a panel that cannot keeps its own and says so", async () => {
    const { runtime, clock, panel, config } = await harness();

    const group = (id: string) => {
      const applied = config(id);
      return applied?.kind === 'analysis' ? applied.groups[0] : undefined;
    };
    expect(group('a')).toMatchObject({ unit: 'WEEK' });
    expect(panel('a').grouping).toBe('taken');
    // Shipments are bucketed by the month alone.
    expect(group('b')).toMatchObject({ unit: 'MONTH' });
    expect(panel('b').grouping).toBe('kept');
    expect(panel('b').issues).toContainEqual(
      expect.objectContaining({
        code: 'dashboard.grouping.kept',
        severity: 'note',
        params: { unit: 'MONTH' },
      }),
    );
    // A list has no time dimension.
    expect(panel('c').grouping).toBeNull();

    runtime.setGroupingUnit('DAY');
    clock.advance(AUTO_APPLY_DELAY_MS);
    expect(group('a')).toMatchObject({ unit: 'DAY' });
    runtime.setGroupingUnit('MONTH');
    clock.advance(AUTO_APPLY_DELAY_MS);
    expect(group('b')).toMatchObject({ unit: 'MONTH' });
    expect(panel('b').grouping).toBe('taken');
    // A unit the board does not offer is not taken.
    runtime.setGroupingUnit('YEAR');
    expect(runtime.getSnapshot().filters.unit).toBe('MONTH');
  });

  it('touches nothing on a board without one', async () => {
    const plain = { ...board() };
    delete plain.timeGrouping;
    const { panel, config } = await harness(plain);
    expect(panel('a').grouping).toBeNull();
    const applied = config('a');
    expect(applied?.kind === 'analysis' && applied.groups[0]).toMatchObject({
      unit: 'DAY',
    });
  });
});

describe('what reaches a panel', () => {
  it('is on each panel, wired or not and why', async () => {
    const { panel } = await harness();

    expect(panel('a').reach).toEqual({
      created: { wired: true, field: 'createdAt', auto: false },
      region: { wired: true, field: 'warehouse', auto: false },
    });
    expect(panel('b').reach).toEqual({
      created: { wired: true, field: 'createdAt', auto: true },
      region: { wired: false, why: 'unwired' },
    });
    expect(panel('c').reach.created).toEqual({ wired: false, why: 'unwired' });
  });
});

describe('setting the filters up', () => {
  it('wires by hand and auto-connects through the draft, and undoes it', async () => {
    const { runtime, panel } = await harness(
      dashboardConfig({
        fields: [{ name: 'when', label: 'When', kind: 'date' }],
        panels: [
          view('a', 'orders-trend'),
          view('b', 'ship-trend', [], 8),
          view('c', 'orders-list', [], 16),
        ],
      }),
    );
    runtime.setBuilding(true);

    expect(runtime.bindPanel('when', 'b', 'createdAt')).toEqual(['a', 'c']);
    expect(panel('a').reach.when).toEqual({
      wired: true,
      field: 'createdAt',
      auto: true,
    });
    expect(runtime.getSnapshot().dirty).toBe(true);
    runtime.unbindPanels('when', ['a', 'c']);
    expect(panel('a').reach.when).toEqual({ wired: false, why: 'unwired' });
    expect(panel('b').reach.when).toMatchObject({ wired: true, auto: false });
  });

  it('wires a panel added to every filter it has a field for', async () => {
    const { runtime, panel } = await harness();
    runtime.setBuilding(true);

    await runtime.preload('orders-trend');
    const id = runtime.addPanel({ kind: 'view', instanceId: 'orders-trend' });
    expect(panel(id ?? '').reach).toEqual({
      created: { wired: true, field: 'createdAt', auto: true },
      region: { wired: true, field: 'warehouse', auto: true },
    });
    const note = runtime.addPanel({ kind: 'markdown', content: 'hi' });
    expect(panel(note ?? '').reach).toEqual({});
  });

  it('adds, names, retypes, moves and removes a filter, what it holds following', async () => {
    const { runtime } = await harness();
    runtime.setBuilding(true);

    const name = runtime.addFilter({ type: 'text', label: 'Status' });
    expect(name).toBe('filter-1');
    runtime.renameFilter('filter-1', 'State');
    runtime.moveFilter('filter-1', 0);
    expect(runtime.getSnapshot().draft.fields.map(f => f.label)).toEqual([
      'State',
      'Created',
      'Region',
    ]);
    // What a filter starts at is what it holds from then on.
    runtime.setFilterDefault('region', ['CN']);
    expect(runtime.getSnapshot().draft.fields[2].default).toEqual(['CN']);
    expect(runtime.getSnapshot().filters.values.region).toEqual(['CN']);
    runtime.setFilterMultiple('region', false);
    runtime.setFilterRequired('region', true);
    runtime.setFilterOptions('region', [{ value: 'CN', label: 'China' }]);
    expect(runtime.getSnapshot().applied.fields[2]).toEqual({
      name: 'region',
      label: 'Region',
      kind: 'string',
      default: ['CN'],
      required: true,
      options: [{ value: 'CN', label: 'China' }],
    });
    // Retyped, what it held is of the old type and goes.
    runtime.retypeFilter('region', 'number');
    expect(runtime.getSnapshot().filters.values.region).toBeUndefined();
    runtime.removeFilter('filter-1');
    runtime.setTimeGrouping(null);
    expect(runtime.getSnapshot().filters).toEqual({
      values: { created: LAST_WEEK },
    });
    expect(runtime.getSnapshot().draft.fields).toHaveLength(2);
    expect(runtime.getSnapshot().dirty).toBe(true);
  });
});

describe('what a text filter offers', () => {
  it('is the values of every field it is wired to, one list across the board', async () => {
    const { runtime } = await harness(
      dashboardConfig({
        fields: [{ name: 'region', label: 'Region', kind: 'string' }],
        panels: [
          view('a', 'orders-trend', [
            { globalField: 'region', panelField: 'warehouse' },
          ]),
          // The same field of the same data is asked once.
          view('c', 'orders-list', [
            { globalField: 'region', panelField: 'warehouse' },
          ]),
          view('b', 'ship-trend', [
            { globalField: 'region', panelField: 'warehouse' },
          ]),
        ],
      }),
    );

    const source = runtime.valueCandidates('region');
    expect(source).not.toBeNull();
    // Handed out once while it asks the same fields.
    expect(runtime.valueCandidates('region')).toBe(source);
    const answer = await source?.search('');
    expect(answer).toEqual({
      values: [
        { value: 'CN', count: 5 },
        { value: 'US', count: 5 },
        { value: 'EU', count: 1 },
      ],
      complete: true,
    });
  });

  it('is nothing for a filter of another type, with a list, or wired nowhere', async () => {
    const { runtime } = await harness(
      dashboardConfig({
        fields: [
          { name: 'when', label: 'When', kind: 'date' },
          {
            name: 'listed',
            label: 'Listed',
            kind: 'string',
            options: [{ value: 'CN', label: 'China' }],
          },
          { name: 'loose', label: 'Loose', kind: 'string' },
          { name: 'amount', label: 'Amount', kind: 'string' },
        ],
        panels: [
          view('a', 'orders-trend', [
            { globalField: 'when', panelField: 'createdAt' },
            { globalField: 'listed', panelField: 'warehouse' },
            // A field whose values are not offered: no TERMS on it.
            { globalField: 'amount', panelField: 'status' },
          ]),
        ],
      }),
    );

    expect(runtime.valueCandidates('when')).toBeNull();
    expect(runtime.valueCandidates('listed')).toBeNull();
    expect(runtime.valueCandidates('loose')).toBeNull();
    expect(runtime.valueCandidates('amount')).toBeNull();
    expect(runtime.valueCandidates('gone')).toBeNull();
  });

  it('is the list the wired fields declare where they declare one, never counted', async () => {
    const { runtime, sources } = await harness(
      dashboardConfig({
        fields: [
          { name: 'phase', label: 'Phase', kind: 'string' },
          {
            name: 'own',
            label: 'Own',
            kind: 'string',
            options: [{ value: 'X', label: 'X' }],
          },
        ],
        panels: [
          view('c', 'orders-list', [
            { globalField: 'phase', panelField: 'status' },
            { globalField: 'own', panelField: 'status' },
          ]),
        ],
      }),
    );

    expect(runtime.wiredOptions('phase')).toEqual([
      { value: 'PENDING', label: 'Pending' },
      { value: 'SHIPPED', label: 'Shipped' },
    ]);
    // Its own list is its own; a filter the board lacks has none.
    expect(runtime.wiredOptions('own')).toBeNull();
    expect(runtime.wiredOptions('gone')).toBeNull();
    expect(runtime.valueCandidates('phase')).toBeNull();
    expect(vi.mocked(sources.orders.aggregate)).not.toHaveBeenCalledWith(
      expect.objectContaining({
        groupBy: [expect.objectContaining({ alias: 'value' })],
      }),
      expect.anything(),
      expect.anything(),
    );
  });

  it('counts under the condition the panel runs under: the board’s fixed scope, never the reader’s values', async () => {
    const { runtime, sources, scope } = await harness(
      dashboardConfig({
        fields: [
          { name: 'region', label: 'Region', kind: 'string' },
          // Its own default: the board's condition on it stays the board's
          // fixed scope rather than moving into it (`migrateDashboardConfig`).
          {
            name: 'phase',
            label: 'Phase',
            kind: 'string',
            default: ['SHIPPED'],
          },
        ],
        fixed: {
          op: 'and',
          children: [{ field: 'phase', operator: 'IN', value: ['PENDING'] }],
        },
        panels: [
          view('a', 'orders-trend', [
            { globalField: 'region', panelField: 'warehouse' },
            { globalField: 'phase', panelField: 'status' },
          ]),
        ],
      }),
    );
    const asked = () =>
      vi
        .mocked(sources.orders.aggregate)
        .mock.calls.filter(([query]) => query.groupBy?.[0]?.alias === 'value');

    // The panel runs under the fixed scope and the reader's values …
    expect(JSON.stringify(scope('a'))).toContain('"PENDING"');
    expect(JSON.stringify(scope('a'))).toContain('"SHIPPED"');
    runtime.setFilterValue('region', ['CN']);
    await runtime.valueCandidates('region')?.search('');
    // … and a filter's values are counted under the fixed scope alone: the
    // reader's picks aside, or a list narrowed to what is picked would offer
    // nothing else.
    const query = JSON.stringify(asked()[0][0]);
    expect(query).toContain('"PENDING"');
    expect(query).not.toContain('"SHIPPED"');
    expect(query).not.toContain('"CN"');
  });

  it('counts again once what the panel runs under moves, a held value included', async () => {
    const { runtime, sources } = await harness(
      dashboardConfig({
        fields: [
          { name: 'region', label: 'Region', kind: 'string' },
          { name: 'phase', label: 'Phase', kind: 'string' },
        ],
        panels: [
          view('a', 'orders-trend', [
            { globalField: 'region', panelField: 'warehouse' },
            { globalField: 'phase', panelField: 'status' },
          ]),
        ],
      }),
    );
    const asked = () =>
      vi
        .mocked(sources.orders.aggregate)
        .mock.calls.filter(([query]) => query.groupBy?.[0]?.alias === 'value')
        .map(([query]) => JSON.stringify(query));

    runtime.holdFilters({ values: { phase: ['PENDING'] } });
    await runtime.valueCandidates('region')?.search('');
    await runtime.valueCandidates('region')?.search('');
    expect(asked()).toHaveLength(1);
    expect(asked()[0]).toContain('"PENDING"');
    // The page holds the same filter at another value: nobody said the
    // scope moved, and the values are counted again all the same.
    runtime.holdFilters({ values: { phase: ['SHIPPED'] } });
    await runtime.valueCandidates('region')?.search('');
    expect(asked()).toHaveLength(2);
    expect(asked()[1]).toContain('"SHIPPED"');
  });
});

describe('useDashboard', () => {
  it('hands a host the filters, what they hold and the commands that set them', async () => {
    const { runtime, clock } = await harness();
    const { result } = renderHook(() => useDashboard(runtime));

    expect(result.current.filterFields.map(field => field.name)).toEqual([
      'created',
      'region',
    ]);
    expect(result.current.timeGrouping?.default).toBe('WEEK');
    expect(result.current.panels[0].reach.created).toMatchObject({
      wired: true,
    });
    expect(result.current.panels[1].grouping).toBe('kept');

    act(() => {
      expect(result.current.setFilterValue('region', ['CN'])).toEqual([]);
      result.current.setGroupingUnit('DAY');
    });
    expect(result.current.filters).toEqual({
      values: { created: LAST_WEEK, region: ['CN'] },
      unit: 'DAY',
    });
    act(() => clock.advance(AUTO_APPLY_DELAY_MS));
    act(() => result.current.clearFilters());
    expect(result.current.filters.values).toEqual({ created: LAST_WEEK });
    expect(result.current.filterCandidates('region')).not.toBeNull();
  });

  it('holds nothing without a board', () => {
    const { result } = renderHook(() => useDashboard(null));
    expect(result.current.filters).toEqual({ values: {} });
    expect(result.current.filterFields).toEqual([]);
    expect(result.current.timeGrouping).toBeNull();
    expect(result.current.setFilterValue('x', null)).toEqual([]);
    expect(result.current.filterCandidates('x')).toBeNull();
    expect(result.current.filterChoices('x')).toBeNull();
    result.current.setGroupingUnit('DAY');
    result.current.clearFilters();
  });
});
