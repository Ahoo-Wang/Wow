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
 * A metric card's last period against the same period a year earlier
 * (D90): the source computes it as a hidden `PREVIOUS` by a year along the
 * trend, over the range shifted back a year; the card reads it off the
 * headline's row and says 「较去年同期」. A chart edit asking for it runs
 * the question again, since a chart edit alone never asks the source.
 */

import {
  DerivedExpressionType,
  AggregationGroupType,
  AggregationDateUnit,
  AggregationMetricType,
  AggregationExpressionOperator,
  WindowFunction,
} from '@ahoo-wang/wow-client/dsl';
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
import { cardLineMetrics, yearAgoGap } from '../src/analysis/serverLines.js';
import {
  MemoryViewStore,
  ViewEngine,
  builtinFieldKinds,
  compileAnalysis,
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type MetricCardData,
  type MetricTrend,
  type RecordData,
} from '../src/index.js';
import { shapeChart } from '../src/analysis/chart.js';
import {
  AnalysisChart,
  DataWorkbench,
  ViewSurface,
  zhCN,
} from '../src/ui/index.js';
import { dailyOrdersDefinition, resourcesOf, testSource } from './fixtures.js';
import { analysisContext as context } from './fixtures/analysis.js';

afterEach(cleanup);

const DAY: AnalysisGroup = {
  type: AggregationGroupType.DATE_HISTOGRAM,
  field: 'createdAt',
  alias: 'day',
  unit: AggregationDateUnit.DAY,
};
const ORDERS: AnalysisMetric = {
  type: AggregationMetricType.COUNT,
  alias: 'orders',
};
const PER_ORDER: AnalysisMetric = {
  type: AggregationMetricType.DERIVED,
  alias: 'perOrder',
  expression: {
    type: DerivedExpressionType.BINARY,
    operator: AggregationExpressionOperator.DIVIDE,
    left: { type: DerivedExpressionType.METRIC_REF, metric: 'orders' },
    right: { type: DerivedExpressionType.METRIC_REF, metric: 'orders' },
  },
};

const day = (n: number) => Date.UTC(2026, 8, n);

function card(
  trend: Partial<MetricTrend> = { against: 'previous-year' },
  overrides: Partial<AnalysisViewConfig> = {},
  metric = 'orders',
): AnalysisViewConfig {
  return {
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    kind: 'analysis',
    groups: [DAY],
    metrics: [ORDERS, PER_ORDER],
    sort: [],
    limit: 100,
    layout: 'chart',
    table: { columns: [] },
    chart: {
      type: 'metric',
      metric: { metric, trend: { x: 'day', ...trend } },
    },
    ...overrides,
  };
}

function windowed(
  windows: (`${WindowFunction.PREVIOUS}` | `${WindowFunction.TOTAL}`)[],
): DataViewDefinition {
  const base = dailyOrdersDefinition();
  return { ...base, analysis: { ...base.analysis!, windows } };
}

describe('asking for the same period a year earlier', () => {
  it('adds a hidden PREVIOUS by a year along the trend', () => {
    const query = compileAnalysis(
      windowed([WindowFunction.PREVIOUS]),
      card(),
      builtinFieldKinds,
      context,
    );
    expect(query.metrics[query.metrics.length - 1]).toEqual({
      type: AggregationMetricType.DERIVED,
      alias: 'orders__previousYear',
      expression: {
        type: DerivedExpressionType.PREVIOUS,
        metric: 'orders',
        along: 'day',
        period: AggregationDateUnit.YEAR,
      },
    });
  });

  it('asks for nothing measured against the period before or read whole', () => {
    expect(cardLineMetrics([WindowFunction.PREVIOUS], card({}))).toEqual([]);
    expect(
      cardLineMetrics(
        [WindowFunction.PREVIOUS],
        card({ against: 'previous-year', headline: 'whole' }),
      ),
    ).toEqual([]);
  });

  it('asks for nothing the source cannot compute, and says why', () => {
    expect(yearAgoGap([WindowFunction.TOTAL], card(), 'orders')).toBe(
      'unsupported',
    );
    expect(cardLineMetrics([WindowFunction.TOTAL], card())).toEqual([]);
    const weekly = card(undefined, {
      groups: [{ ...DAY, unit: AggregationDateUnit.WEEK } as AnalysisGroup],
    });
    expect(yearAgoGap([WindowFunction.PREVIOUS], weekly, 'orders')).toBe(
      'week',
    );
    expect(cardLineMetrics([WindowFunction.PREVIOUS], weekly)).toEqual([]);
    expect(yearAgoGap([WindowFunction.PREVIOUS], card(), 'perOrder')).toBe(
      'not-measured',
    );
    expect(
      cardLineMetrics(
        [WindowFunction.PREVIOUS],
        card(undefined, {}, 'perOrder'),
      ),
    ).toEqual([]);
    expect(
      yearAgoGap(
        [WindowFunction.PREVIOUS],
        card(),
        'orders',
        new Set(['orders']),
      ),
    ).toBe('not-measured');
    expect(
      yearAgoGap([WindowFunction.PREVIOUS], card(), 'orders'),
    ).toBeUndefined();
  });

  it('asks for nothing when the alias is taken', () => {
    const taken = card(undefined, {
      metrics: [
        ORDERS,
        { type: AggregationMetricType.COUNT, alias: 'orders__previousYear' },
      ],
    });
    expect(cardLineMetrics([WindowFunction.PREVIOUS], taken)).toEqual([]);
  });
});

const shape = (config: AnalysisViewConfig, rows: readonly RecordData[]) =>
  shapeChart(config, rows, undefined, { timeZone: 'UTC' }) as MetricCardData;

describe('reading the same period a year earlier', () => {
  it('measures the last period against the year-ago value on its row', () => {
    const data = shape(card(), [
      { day: day(21), orders: 10, orders__previousYear: 4 },
      { day: day(22), orders: 12, orders__previousYear: 8 },
    ]);
    expect(data.period).toEqual({
      at: day(22),
      unit: AggregationDateUnit.DAY,
      against: 'previous-year',
      change: { delta: 4, ratio: 0.5 },
    });
  });

  it('has no number to measure against where last year had none', () => {
    const data = shape(card(), [
      { day: day(22), orders: 12, orders__previousYear: null },
    ]);
    expect(data.period?.change).toBeNull();
  });

  it('has nothing to measure against where the rows lack it', () => {
    const data = shape(card(), [
      { day: day(21), orders: 10 },
      { day: day(22), orders: 12 },
    ]);
    expect(data.period).toEqual({
      at: day(22),
      unit: AggregationDateUnit.DAY,
      against: 'previous-year',
    });
  });

  it('is not unmatched for the period before cut otherwise', () => {
    // Measured against the year before, the period right before does not
    // matter, however the dates cut it.
    const data = shape(card(), [
      { day: day(20), orders: 3, orders__previousYear: 1 },
      { day: day(22), orders: 12, orders__previousYear: 6 },
    ]);
    expect(data.period?.previous).toBeUndefined();
    expect(data.period?.change).toEqual({ delta: 6, ratio: 1 });
  });
});

describe('the card says what it is measured against', () => {
  function draw(rows: readonly RecordData[], zh = false) {
    const config = card();
    const data = shape(config, rows);
    render(
      <ViewSurface
        locale={zh ? 'zh-CN' : 'en'}
        timeZone="UTC"
        {...(zh ? { messages: zhCN } : {})}
      >
        <AnalysisChart data={data} spec={config.chart} columns={[]} />
      </ViewSurface>,
    );
    return document.querySelector('[data-slot="metric-change"]')?.textContent;
  }

  it('says the same period last year', () => {
    expect(
      draw([{ day: day(22), orders: 12, orders__previousYear: 8 }], true),
    ).toContain('较去年同期');
  });

  it('says there is no number last year to compare with', () => {
    expect(
      draw([{ day: day(22), orders: 12, orders__previousYear: null }]),
    ).toBe('No number in the same period last year to compare with');
  });

  it('says there is no same period last year to compare with', () => {
    expect(draw([{ day: day(22), orders: 12 }], true)).toBe('没有去年同期可比');
  });
});

describe('a chart edit asking for the year before', () => {
  it('runs the question again with the hidden metric', async () => {
    const source = testSource({
      aggregate: vi.fn(() =>
        Promise.resolve([{ day: day(22), orders: 12 }] as RecordData[]),
      ),
    });
    const config = card({}, { metrics: [ORDERS] });
    const engine = new ViewEngine({
      resources: resourcesOf(
        [windowed([WindowFunction.PREVIOUS])],
        () => source,
      ),
      store: new MemoryViewStore({
        instances: [
          {
            id: 'orders-1',
            definitionId: 'orders',
            title: 'Orders per day',
            scope: 'personal',
            revision: '1',
            config,
          },
        ],
      }),
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        viewKinds={['analysis']}
      />,
    );
    await waitFor(() => expect(source.aggregate).toHaveBeenCalled());
    const asked = () =>
      vi
        .mocked(source.aggregate)
        .mock.calls.map(call =>
          call[0].metrics.map((metric: { alias: string }) => metric.alias),
        );
    expect(asked().flat()).not.toContain('orders__previousYear');

    act(() => engine.openRuntimes()[0].edit({ chart: card().chart }));
    await waitFor(() =>
      expect(asked()[asked().length - 1]).toContain('orders__previousYear'),
    );
  });
});

describe('a line chart edit asking for a line Wow computes', () => {
  it('runs the question again with the hidden metric (D88)', async () => {
    const source = testSource({
      aggregate: vi.fn(() =>
        Promise.resolve([
          { day: day(21), orders: 10 },
          { day: day(22), orders: 12 },
        ] as RecordData[]),
      ),
    });
    const line = (derived: { kind: 'previous'; metric: string }[]) => ({
      type: 'line' as const,
      cartesian: { x: 'day', series: [{ metric: 'orders' }], derived },
    });
    const engine = new ViewEngine({
      resources: resourcesOf(
        [windowed([WindowFunction.PREVIOUS])],
        () => source,
      ),
      store: new MemoryViewStore({
        instances: [
          {
            id: 'orders-1',
            definitionId: 'orders',
            title: 'Orders per day',
            scope: 'personal',
            revision: '1',
            config: card({}, { metrics: [ORDERS], chart: line([]) }),
          },
        ],
      }),
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        viewKinds={['analysis']}
      />,
    );
    await waitFor(() => expect(source.aggregate).toHaveBeenCalled());
    const calls = () => vi.mocked(source.aggregate).mock.calls.length;
    const before = calls();
    act(() =>
      engine
        .openRuntimes()[0]
        .edit({ chart: line([{ kind: 'previous', metric: 'orders' }]) }),
    );
    await waitFor(() => expect(calls()).toBe(before + 1));
    const all = vi.mocked(source.aggregate).mock.calls;
    const last = all[all.length - 1][0];
    expect(
      last.metrics.map((metric: { alias: string }) => metric.alias),
    ).toContain('orders__previous');
  });
});

describe('choosing what the last period is measured against', () => {
  async function open(definition: DataViewDefinition, trend = {}) {
    const source = testSource({
      aggregate: vi.fn(() =>
        Promise.resolve([{ day: day(22), orders: 12 }] as RecordData[]),
      ),
    });
    const engine = new ViewEngine({
      resources: resourcesOf([definition], () => source),
      store: new MemoryViewStore({
        instances: [
          {
            id: 'orders-1',
            definitionId: 'orders',
            title: 'Orders per day',
            scope: 'personal',
            revision: '1',
            config: card(trend, { metrics: [ORDERS] }),
          },
        ],
      }),
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        viewKinds={['analysis']}
        messages={zhCN}
      />,
    );
    const user = userEvent.setup();
    await user.click(
      await screen.findByRole('button', {
        name: zhCN['label.analysis.visualize'],
      }),
    );
    await user.click(
      screen.getByRole('button', {
        name: `${zhCN['label.chart.type.metric']}选项`,
      }),
    );
    const against = () =>
      document.querySelector<HTMLElement>('[data-slot="metric-against"]');
    const draft = () =>
      engine.openRuntimes()[0].getSnapshot().draft as AnalysisViewConfig;
    return { user, against, draft };
  }

  it('measures against the same period last year and back', async () => {
    const { user, against, draft } = await open(
      windowed([WindowFunction.PREVIOUS]),
    );
    const group = within(against()!);
    expect(against()?.textContent).toContain(
      zhCN['label.chart.against.previous.hint'],
    );
    await user.click(
      group.getByRole('button', {
        name: zhCN['label.chart.against.previous-year'],
      }),
    );
    expect(draft().chart.metric?.trend).toEqual({
      x: 'day',
      against: 'previous-year',
    });
    await user.click(
      group.getByRole('button', {
        name: zhCN['label.chart.against.previous'],
      }),
    );
    expect(draft().chart.metric?.trend).toEqual({ x: 'day' });
  });

  it('is not offered where the source reads no year back', async () => {
    const { against } = await open(dailyOrdersDefinition());
    expect(against()).toBeNull();
  });

  it('keeps a stored choice the source cannot read, greyed with the reason', async () => {
    const { against } = await open(dailyOrdersDefinition(), {
      against: 'previous-year',
    });
    expect(against()?.textContent).toContain(
      zhCN['label.chart.against.gap.unsupported'],
    );
  });
});
