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
 * Board-date anchoring (D39): a board's date filter set to one day anchors
 * the trend cards wired to it — the window ends with that day, the card's
 * own 「近 N 天」 read as of it — while every other panel is narrowed to the
 * day as before. Refreshed after midnight, 「昨日」 is read again.
 */

import {
  AggregationDateUnit,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import { describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type AnalysisViewConfig,
  type DashboardFilters,
  type DashboardPanel,
  type DataViewDefinition,
  type FilterNode,
} from '../src/index.js';
import { AUTO_APPLY_DELAY_MS } from '../src/runtime/autoApply.js';
import { DashboardViewRuntime } from '../src/runtime/dashboardRuntime.js';
import {
  analysisConfig,
  dashboardConfig,
  nextTask,
  ordersDefinition,
  overviewDefinition,
  testEnvironment,
  testSource,
} from './fixtures.js';

const DAY = 24 * 60 * 60 * 1000;
const YESTERDAY = { type: 'preset', preset: 'yesterday' } as const;
const LAST_WEEK = { type: 'relative', amount: 7, unit: 'day' } as const;

function orders(): DataViewDefinition {
  const base = ordersDefinition();
  return {
    ...base,
    fields: [
      ...base.fields,
      { name: 'createdAt', label: 'Created', kind: 'datetime' },
    ],
    analysis: {
      count: true,
      expressions: true,
      fields: [
        ...(base.analysis?.fields ?? []),
        {
          field: 'createdAt',
          groups: [AggregationGroupType.DATE_HISTOGRAM],
          functions: [],
          dateUnits: [AggregationDateUnit.DAY, AggregationDateUnit.MONTH],
        },
      ],
    },
  };
}

const since = (value: unknown): FilterNode => ({
  field: 'createdAt',
  operator: 'BETWEEN',
  value: value as never,
});

/** Orders by the day as a trend card, over its own conditions. */
function card(own: FilterNode[] = [], headline?: 'whole'): AnalysisViewConfig {
  return analysisConfig({
    filter: { op: 'and', children: own },
    groups: [
      {
        alias: 'day',
        field: 'createdAt',
        type: 'DATE_HISTOGRAM',
        unit: 'DAY',
      },
    ],
    sort: [{ alias: 'day', direction: 'ASC' }],
    chart: {
      type: 'metric',
      metric: {
        metric: 'orders',
        trend: { x: 'day', ...(headline ? { headline } : {}) },
      },
    },
  });
}

function panel(id: string, config: AnalysisViewConfig, x: number) {
  return {
    id,
    kind: 'view',
    owned: { definitionId: 'orders', config },
    bindings: [{ globalField: 'date', panelField: 'createdAt' }],
    layout: { x, y: 0, w: 4, h: 3 },
  } as DashboardPanel;
}

async function harness(
  filters?: DashboardFilters,
  extra: readonly DashboardPanel[] = [],
) {
  const clock = testEnvironment();
  const source = testSource({ aggregate: vi.fn(() => Promise.resolve([])) });
  const store = new MemoryViewStore();
  const engine = new ViewEngine({
    definitions: [orders(), overviewDefinition()],
    store,
    resolveSource: () => source,
    environment: clock.environment,
  });
  const config = dashboardConfig({
    fields: [
      {
        name: 'date',
        label: 'Date',
        kind: 'datetime',
        required: true,
        default: YESTERDAY,
      },
    ],
    panels: [
      // 「近 7 天」 of its own: read as of the day the board picked.
      panel('week', card([since(LAST_WEEK)]), 0),
      // Nothing of its own: the day and the one before it.
      panel('bare', card(), 4),
      // Read as the whole: the day is its whole.
      panel('whole', card([], 'whole'), 8),
      // Not a card: narrowed to the day.
      panel(
        'line',
        {
          ...card(),
          chart: {
            type: 'line',
            cartesian: { x: 'day', series: [{ metric: 'orders' }] },
          },
        },
        12,
      ),
      ...extra,
    ],
  });
  const saved = await store.create(
    { definitionId: 'overview', title: 'Board', scope: 'shared', config },
    { requestId: 'board' },
  );
  const runtime = await engine.open(saved.id, filters ? { filters } : {});
  await nextTask();
  if (!(runtime instanceof DashboardViewRuntime))
    throw new Error('expected a dashboard');
  const child = (id: string) => {
    const found = runtime.getSnapshot().panels.find(entry => entry.id === id);
    if (!found?.runtime) throw new Error(`no panel ${id}`);
    return found.runtime;
  };
  /** The one condition on `createdAt` the board hands a panel. */
  const window = (id: string) =>
    JSON.parse(JSON.stringify(child(id).scopeFilter)) as unknown;
  return { runtime, clock, source, child, window };
}

/** A scope holding one `createdAt` condition, as the board merges it. */
const scoped = (value: unknown) => ({
  op: 'and',
  children: [{ op: 'and', children: [since(value)] }],
});

const between = (from: string, to: string) => ({
  type: 'absolute',
  from,
  to,
});

describe('a board date of one day anchors its trend cards (D39)', () => {
  it('ends a card’s own 「近 7 天」 with the day picked', async () => {
    const { child, window } = await harness();

    // NOW is 16 September 10:30 UTC: 「昨日」 is the 15th, and the seven
    // days ending with it start on the 9th.
    expect(window('week')).toEqual(
      scoped(between('2026-09-09T00:00:00.000Z', '2026-09-15T23:59:59.999Z')),
    );
    // The card's own window is read as of that day, not as of now.
    expect(child('week').getSnapshot().applied.filter).toEqual({
      op: 'and',
      children: [
        since(between('2026-09-09T00:00:00.000Z', '2026-09-15T23:59:59.999Z')),
      ],
    });
  });

  it('reaches back one period for a card with no window of its own', async () => {
    const { window } = await harness();

    expect(window('bare')).toEqual(
      scoped(between('2026-09-14T00:00:00.000Z', '2026-09-15T23:59:59.999Z')),
    );
  });

  it('narrows a card read as the whole, and any other panel, as before', async () => {
    const { window } = await harness();

    expect(window('whole')).toEqual(scoped(YESTERDAY));
    expect(window('line')).toEqual(scoped(YESTERDAY));
  });

  it('follows the day the reader picks, 「前天」 included', async () => {
    const { runtime, clock, window } = await harness();

    runtime.setFilterValue('date', {
      type: 'preset',
      preset: 'dayBeforeYesterday',
    });
    clock.advance(AUTO_APPLY_DELAY_MS);
    expect(window('week')).toEqual(
      scoped(between('2026-09-08T00:00:00.000Z', '2026-09-14T23:59:59.999Z')),
    );

    // A day off the calendar is one day too.
    runtime.setFilterValue('date', {
      type: 'absolute',
      from: '2026-08-31',
      to: '2026-08-31',
    });
    clock.advance(AUTO_APPLY_DELAY_MS);
    expect(window('bare')).toEqual(
      scoped(between('2026-08-30T00:00:00.000Z', '2026-08-31T23:59:59.999Z')),
    );
  });

  it('does not anchor to a span of several days', async () => {
    const { window } = await harness({ values: { date: LAST_WEEK } });

    expect(window('week')).toEqual(scoped(LAST_WEEK));
    expect(window('bare')).toEqual(scoped(LAST_WEEK));
  });

  it('asks the source for the anchored window', async () => {
    const { source } = await harness();

    const asked = vi
      .mocked(source.aggregate)
      .mock.calls.map(([query]) => JSON.stringify(query));
    // The 9th's first moment and the 15th's last, as the field keeps time.
    const from = Date.parse('2026-09-09T00:00:00.000Z');
    const to = Date.parse('2026-09-15T23:59:59.999Z');
    expect(
      asked.some(
        query => query.includes(String(from)) && query.includes(String(to)),
      ),
    ).toBe(true);
  });

  it('hands the window over with the panel, in the reader’s part', async () => {
    const { runtime } = await harness();

    expect(runtime.handOver('bare')).toMatchObject({
      scopeFilter: null,
      filter: {
        op: 'and',
        children: [
          since(
            between('2026-09-14T00:00:00.000Z', '2026-09-15T23:59:59.999Z'),
          ),
        ],
      },
    });
  });

  it('reads 「昨日」 again when the board is refreshed after midnight', async () => {
    const { runtime, clock, window } = await harness();

    clock.advance(DAY);
    runtime.refresh();
    expect(window('bare')).toEqual(
      scoped(between('2026-09-15T00:00:00.000Z', '2026-09-16T23:59:59.999Z')),
    );
  });
});

/**
 * A saved board owning an analysis that is nothing but its kind. The board's
 * admission cannot judge an owned analysis (the dashboard kernel may not
 * import the analysis one), so the board syncs with it, and the period
 * anchor is read of every panel before any child has judged its view. It
 * used to read `config.chart.type` there and throw a `TypeError` that took
 * the whole board down; a view with no chart has no trend axis.
 */
describe('a board owning an incomplete analysis', () => {
  it('syncs, and still anchors the cards that are whole', async () => {
    const bare = {
      id: 'bare-kind',
      kind: 'view',
      owned: { definitionId: 'orders', config: { kind: 'analysis' } },
      bindings: [{ globalField: 'date', panelField: 'createdAt' }],
      layout: { x: 16, y: 0, w: 4, h: 3 },
    } as unknown as DashboardPanel;

    const { runtime, window } = await harness(undefined, [bare]);

    expect(runtime.getSnapshot().panels.map(entry => entry.id)).toContain(
      'bare-kind',
    );
    expect(window('bare')).toEqual(
      scoped(between('2026-09-14T00:00:00.000Z', '2026-09-15T23:59:59.999Z')),
    );
  });
});

describe('a ratio of sums on a board card (D38 on a board)', () => {
  it('runs as a trend card anchored to the day', async () => {
    const clock = testEnvironment();
    const source = testSource({ aggregate: vi.fn(() => Promise.resolve([])) });
    const store = new MemoryViewStore();
    const engine = new ViewEngine({
      definitions: [orders(), overviewDefinition()],
      store,
      resolveSource: () => source,
      environment: clock.environment,
    });
    // 客单价 = GMV ÷ 订单数, read as money.
    const aov: AnalysisViewConfig = {
      ...card(),
      metrics: [
        {
          type: 'NUMERIC',
          alias: 'gmv',
          function: 'SUM',
          expression: { type: 'FIELD', field: 'amount' },
        },
        { type: 'COUNT', alias: 'orders' },
        {
          type: 'DERIVED',
          alias: 'aov',
          format: { style: 'currency', currency: 'CNY' },
          expression: {
            type: 'BINARY',
            operator: 'DIVIDE',
            left: { type: 'METRIC_REF', metric: 'gmv' },
            right: { type: 'METRIC_REF', metric: 'orders' },
          },
        },
      ],
      chart: {
        type: 'metric',
        metric: { metric: 'aov', trend: { x: 'day' } },
      },
    };
    const saved = await store.create(
      {
        definitionId: 'overview',
        title: 'Board',
        scope: 'shared',
        config: dashboardConfig({
          fields: [
            {
              name: 'date',
              label: 'Date',
              kind: 'datetime',
              default: YESTERDAY,
            },
          ],
          panels: [panel('aov', aov, 0)],
        }),
      },
      { requestId: 'board' },
    );
    const runtime = await engine.open(saved.id);
    await nextTask();
    if (!(runtime instanceof DashboardViewRuntime))
      throw new Error('expected a dashboard');
    const [state] = runtime.getSnapshot().panels;

    // Admitted — `chart.metric.trend-not-additive` is only for an average
    // or a distinct count — and anchored like any other trend card.
    expect(state.issues).toEqual([]);
    expect(JSON.parse(JSON.stringify(state.runtime?.scopeFilter))).toEqual(
      scoped(between('2026-09-14T00:00:00.000Z', '2026-09-15T23:59:59.999Z')),
    );
    expect(source.aggregate).toHaveBeenCalled();
  });
});
