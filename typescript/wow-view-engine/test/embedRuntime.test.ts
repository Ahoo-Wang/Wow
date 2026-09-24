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
 * What an embedding page asks of the runtime (D22, the embedding half): a
 * view or a board that does not refresh itself (`setAutoRefresh`), and the
 * filters a page holds — locked or hidden — kept off every command of the
 * reader's (`holdFilters`).
 */

import { describe, expect, it, vi } from 'vitest';
import {
  AUTO_APPLY_DELAY_MS,
  DashboardViewRuntime,
  MemoryViewStore,
  ViewEngine,
  type DashboardFilters,
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
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';

const views: ViewInstance[] = [
  {
    id: 'list',
    definitionId: 'orders',
    title: 'Orders',
    scope: 'shared',
    revision: 'r1',
    config: recordConfig({ refresh: { interval: 30 } }),
  },
  {
    id: 'by-warehouse',
    definitionId: 'orders',
    title: 'By warehouse',
    scope: 'shared',
    revision: 'r1',
    config: analysisConfig(),
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
    bindings: [
      { globalField: 'region', panelField: 'warehouse' },
      { globalField: 'status', panelField: 'status' },
    ],
    layout: { x, y: 0, w: 8, h: 4 },
    ...extra,
  } as DashboardPanel;
}

/**
 * A region the page may lock, a status the reader holds, and a required
 * text filter with a default; a chart whose press sets the region, and the
 * order list; a time grouping; a board refreshing every thirty seconds.
 */
function board(): DashboardViewConfig {
  return dashboardConfig({
    fields: [
      { name: 'region', label: 'Region', kind: 'string' },
      {
        name: 'status',
        label: 'Status',
        kind: 'string',
        required: true,
        default: ['PENDING'],
      },
    ],
    timeGrouping: { units: ['DAY', 'WEEK'], default: 'DAY' },
    refresh: { interval: 30 },
    panels: [
      view('chart', 'by-warehouse', {
        click: { kind: 'filter', filter: 'region' },
      }),
      view('list', 'list', {}, 8),
    ],
  });
}

async function engineWith() {
  const clock = testEnvironment();
  const source = testSource({
    aggregate: vi.fn(query =>
      Promise.resolve(
        query.groupBy?.[0]?.alias === 'value'
          ? [{ value: 'PENDING', count: 3 }]
          : [
              { warehouse: 'CN', orders: 2 },
              { warehouse: 'EU', orders: 1 },
            ],
      ),
    ),
  });
  const store = new MemoryViewStore({ instances: views });
  const engine = new ViewEngine({
    definitions: [ordersDefinition(), overviewDefinition()],
    store,
    resolveSource: () => source,
    environment: clock.environment,
  });
  return { clock, source, store, engine };
}

async function harness(filters?: DashboardFilters) {
  const { clock, source, store, engine } = await engineWith();
  const saved = await store.create(
    {
      definitionId: 'overview',
      title: 'Board',
      scope: 'shared',
      config: board(),
    },
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
    source,
    panel,
    scope: (id: string) =>
      JSON.stringify(panel(id).runtime?.scopeFilter ?? null),
  };
}

describe('setAutoRefresh', () => {
  it('holds a view’s timer for as long as the host says so, the interval kept', async () => {
    const { engine, clock, source } = await engineWith();
    const runtime = await engine.open('list');
    await nextTask();
    expect(clock.timers).toBe(1);

    runtime.setAutoRefresh(false);
    expect(clock.timers).toBe(0);
    expect(runtime.getSnapshot().nextRefreshAt).toBeNull();
    clock.advance(60_000);
    await nextTask();
    expect(source.paged).toHaveBeenCalledTimes(1);
    // The interval is the view's still: nothing was edited.
    expect(runtime.getSnapshot().draft.refresh).toEqual({ interval: 30 });
    expect(runtime.getSnapshot().dirty).toBe(false);
    // A refresh asked for still runs.
    runtime.refresh();
    await nextTask();
    expect(source.paged).toHaveBeenCalledTimes(2);

    runtime.setAutoRefresh(true);
    clock.advance(30_000);
    await nextTask();
    expect(source.paged).toHaveBeenCalledTimes(3);
  });

  it('holds a board’s one timer too', async () => {
    const { runtime, clock } = await harness();
    expect(runtime.getSnapshot().nextRefreshAt).not.toBeNull();

    runtime.setAutoRefresh(false);
    expect(runtime.getSnapshot().nextRefreshAt).toBeNull();
    runtime.setAutoRefresh(false);
    expect(clock.timers).toBe(0);

    runtime.setAutoRefresh(true);
    expect(runtime.getSnapshot().nextRefreshAt).not.toBeNull();
  });
});

describe('holdFilters', () => {
  it('keeps the reader off what the page holds: a value, 「清空」 and the grouping', async () => {
    const { runtime } = await harness();
    expect(
      runtime.holdFilters({ values: { region: ['CN'] }, unit: 'WEEK' }),
    ).toEqual([]);
    expect(runtime.getSnapshot().filters).toEqual({
      values: { region: ['CN'], status: ['PENDING'] },
      unit: 'WEEK',
    });

    expect(runtime.setFilterValue('region', ['EU'])).toEqual([
      expect.objectContaining({
        code: 'dashboard.filter.held',
        path: ['filters', 'region'],
      }),
    ]);
    expect(runtime.getSnapshot().filters.values.region).toEqual(['CN']);
    runtime.setGroupingUnit('DAY');
    expect(runtime.getSnapshot().filters.unit).toBe('WEEK');

    // 「清空」 clears what the reader holds — a required one to its default
    // — and leaves what the page holds as it stands.
    runtime.setFilterValue('status', ['SHIPPED']);
    runtime.clearFilters();
    expect(runtime.getSnapshot().filters).toEqual({
      values: { region: ['CN'], status: ['PENDING'] },
      unit: 'WEEK',
    });
  });

  it('is in force from the first query when the board opens under it', async () => {
    const { engine, store, source } = await engineWith();
    const saved = await store.create(
      {
        definitionId: 'overview',
        title: 'Board',
        scope: 'shared',
        config: board(),
      },
      { requestId: 'board' },
    );
    await engine.open(saved.id, { held: { values: { region: ['EU'] } } });
    await nextTask();

    const asked = vi.mocked(source.paged).mock.calls;
    expect(asked.length).toBeGreaterThan(0);
    for (const [query] of asked)
      expect(JSON.stringify(query.filter)).toContain('"EU"');
  });

  it('follows the page, puts a default back for null, and lets go', async () => {
    const { runtime, clock, scope } = await harness();
    runtime.holdFilters({ values: { region: ['CN'] } });
    runtime.holdFilters({ values: { region: ['EU'] } });
    clock.advance(AUTO_APPLY_DELAY_MS);
    expect(scope('list')).toContain('"EU"');

    // `null` is the filter's default: a required one's, or none at all.
    runtime.holdFilters({ values: { region: null, status: null } });
    expect(runtime.getSnapshot().filters.values).toEqual({
      status: ['PENDING'],
    });

    // Let go, it is the reader's again, value and all.
    runtime.holdFilters({ values: { region: ['EU'] } });
    runtime.holdFilters(null);
    expect(runtime.getSnapshot().filters.values.region).toEqual(['EU']);
    expect(runtime.setFilterValue('region', ['CN'])).toEqual([]);
    expect(runtime.getSnapshot().filters.values.region).toEqual(['CN']);
    // The grouping was never held: the reader's.
    runtime.clearFilters();
    expect(runtime.getSnapshot().filters.unit).toBe('DAY');
  });

  it('answers what the board refuses of the page, every time it is asked, and takes the rest', async () => {
    const { runtime } = await harness();
    const held = { values: { ghost: ['x'], region: ['CN'] } };

    const refused = runtime.holdFilters(held);
    expect(refused.map(found => found.code)).toEqual([
      'dashboard.filter.unknown',
    ]);
    expect(runtime.getSnapshot().filters.values.region).toEqual(['CN']);
    expect(runtime.holdFilters(held)).toEqual(refused);
  });

  it('sets aside a click that sets a filter the page holds: a press does what a panel without one does', async () => {
    const { runtime, panel } = await harness();
    expect(panel('chart').click).toEqual({ kind: 'filter', filter: 'region' });

    runtime.holdFilters({ values: { region: null } });
    expect(panel('chart').click).toBeNull();
    expect(runtime.crossFilter('chart', { warehouse: 'CN' })).toEqual({
      kind: 'none',
    });
    expect(runtime.getSnapshot().filters.values.region).toBeUndefined();
    // Holding the same again changes nothing.
    const panels = runtime.getSnapshot().panels;
    runtime.holdFilters({ values: { region: null } });
    expect(runtime.getSnapshot().panels).toBe(panels);

    runtime.holdFilters(null);
    expect(panel('chart').click).toEqual({ kind: 'filter', filter: 'region' });
    expect(runtime.crossFilter('chart', { warehouse: 'CN' }).kind).toBe('set');
  });

  it('counts what a text filter offers under what the page holds', async () => {
    const { runtime, source } = await harness();
    runtime.holdFilters({ values: { status: ['SHIPPED'] } });

    const offered = runtime.valueCandidates('region');
    expect(offered).not.toBeNull();
    await offered?.search('', new AbortController().signal);
    const asked = vi
      .mocked(source.aggregate)
      .mock.calls.map(([query]) => JSON.stringify(query))
      .find(query => query.includes('"value"'));
    expect(asked).toContain('"SHIPPED"');
  });
});
