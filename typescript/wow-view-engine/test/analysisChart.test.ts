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

import { describe, expect, it } from 'vitest';
import { CATEGORY_LABEL_MAX, categoryTick } from '../src/ui/charts/axis.js';
import {
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type CartesianData,
  type ChartSpec,
  type FunnelData,
  type HeatmapData,
  type MetricCardData,
  type PieData,
  type RecordData,
  type ScatterData,
} from '../src/index.js';
import { CHART_COLOR_SLOTS } from '../src/model/chart.js';
import { isChartColor } from '../src/analysis/validateChart.js';
import { shapeChart } from '../src/analysis/chart.js';
import {
  AggregationDateUnit,
  AggregationExpressionType,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
} from '@ahoo-wang/wow-client/dsl';

const GROUPS: Record<string, AnalysisGroup> = {
  wh: { type: AggregationGroupType.TERMS, field: 'warehouse', alias: 'wh' },
  month: {
    type: AggregationGroupType.DATE_HISTOGRAM,
    field: 'createdAt',
    alias: 'month',
    unit: AggregationDateUnit.MONTH,
  },
};

const METRICS: Record<string, AnalysisMetric> = {
  orders: { type: AggregationMetricType.COUNT, alias: 'orders' },
  total: {
    type: AggregationMetricType.NUMERIC,
    alias: 'total',
    function: AggregationFunction.SUM,
    expression: { type: AggregationExpressionType.FIELD, field: 'amount' },
  },
  average: {
    type: AggregationMetricType.NUMERIC,
    alias: 'average',
    function: AggregationFunction.AVG,
    expression: { type: AggregationExpressionType.FIELD, field: 'amount' },
  },
};

function config(
  chart: ChartSpec,
  groups: AnalysisGroup[] = [GROUPS.wh],
  metrics: AnalysisMetric[] = [METRICS.orders],
): AnalysisViewConfig {
  return {
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    kind: 'analysis',
    groups,
    metrics: metrics as [AnalysisMetric, ...AnalysisMetric[]],
    sort: [],
    limit: 100,
    layout: 'chart',
    table: { columns: [] },
    chart,
  };
}

describe('isChartColor', () => {
  it('accepts the shapes a theme is written in', () => {
    for (const value of [
      '#fff',
      '#2a78d6',
      '#2a78d6ff',
      'rgb(42 120 214)',
      'rgba(42, 120, 214, 0.5)',
      'hsl(210 50% 40%)',
      'oklch(0.62 0.14 250deg)',
      'oklab(0.62 -0.02 -0.13)',
      'color(display-p3 0.1 0.45 0.84)',
      'color(display-p3 0.5 0.2 0.1)',
      'oklch(0.6 0.2 30)',
      'hsl(120 50% 50%)',
      'rebeccapurple',
      'var(--chart-1)',
      'var(--chart-2)',
      'transparent',
      // CSS reads a colour name case-insensitively and so does the parser.
      'Red',
    ])
      expect(isChartColor(value)).toBe(true);
  });

  it('refuses anything that could leave the declaration it sits in', () => {
    for (const value of [
      'red; } .x { color: red',
      'var(--chart-1); background: url(//evil/)',
      'url(//evil/)',
      'rgb(1,2,3) !important',
      '#12345',
      // A character class took these for colours — a word, a function name
      // with nonsense inside it, an unknown colour space — and the series
      // each one named then came out unpainted rather than from the palette.
      'banana',
      'rgb(foo)',
      'color(nope)',
      // A keyword that resolves against the element rather than naming a
      // colour: the parser does not know it, so neither does the chart.
      'currentcolor',
      '',
      42,
      null,
      undefined,
    ])
      expect(isChartColor(value)).toBe(false);
  });
});

describe('shapeChart', () => {
  const rows: RecordData[] = [
    { wh: 'SH', month: '2026-08', orders: 30, total: 300 },
    { wh: 'BJ', month: '2026-08', orders: 10, total: 100 },
    { wh: 'SH', month: '2026-09', orders: 20, total: 200 },
  ];

  it('returns nothing when the family sub-object is missing', () => {
    expect(shapeChart(config({ type: 'pie' }), rows)).toBeUndefined();
  });

  it('collects one point per x value', () => {
    const data = shapeChart(
      config(
        {
          type: 'bar',
          cartesian: { x: 'month', series: [{ metric: 'orders' }] },
        },
        [GROUPS.month],
      ),
      rows,
    ) as CartesianData;
    expect(data.type).toBe('cartesian');
    expect(data.points).toHaveLength(2);
    expect(data.series).toEqual([
      { key: 'orders', label: 'orders', metric: 'orders' },
    ]);
  });

  it('pivots a second dimension into one series per value', () => {
    const data = shapeChart(
      config(
        {
          type: 'line',
          cartesian: {
            x: 'month',
            splitBy: 'wh',
            series: [{ metric: 'orders' }],
          },
        },
        [GROUPS.month, GROUPS.wh],
      ),
      rows,
    ) as CartesianData;
    expect(data.series.map(series => series.key)).toEqual(['SH', 'BJ']);
    expect(data.points[0]).toEqual({
      x: '2026-08',
      values: { SH: 30, BJ: 10 },
    });
  });

  // The label prints the value; the UI needs the value itself to show it as
  // its field does, an enum by its name and a date bucket as its day.
  it('keeps the raw value each pivot series was split by', () => {
    const data = shapeChart(
      config(
        {
          type: 'line',
          cartesian: {
            x: 'month',
            splitBy: 'wh',
            series: [{ metric: 'orders' }],
          },
        },
        [GROUPS.month, GROUPS.wh],
      ),
      [{ wh: 7, month: '2026-08', orders: 1 }],
    ) as CartesianData;

    expect(data.series[0]).toMatchObject({ label: '7', value: 7 });
  });

  it('keeps a plain string split value as its own series key', () => {
    const data = shapeChart(
      config(
        {
          type: 'line',
          cartesian: {
            x: 'month',
            splitBy: 'wh',
            series: [{ metric: 'orders' }],
          },
        },
        [GROUPS.month, GROUPS.wh],
      ),
      [{ wh: 'SH', month: '2026-08', orders: 1 }],
    ) as CartesianData;
    // The key doubles as the legend label, so a group value stays readable.
    expect(data.series.map(series => series.key)).toEqual(['SH']);
  });

  it('names a pivot series apart for split values that print alike', () => {
    const odd: RecordData[] = [
      { wh: null, month: '2026-08', orders: 4 },
      { wh: '', month: '2026-08', orders: 3 },
      { wh: 7, month: '2026-08', orders: 2 },
      { wh: '7', month: '2026-08', orders: 1 },
    ];
    const data = shapeChart(
      config(
        {
          type: 'line',
          cartesian: {
            x: 'month',
            splitBy: 'wh',
            series: [{ metric: 'orders' }],
          },
        },
        [GROUPS.month, GROUPS.wh],
      ),
      odd,
    ) as CartesianData;
    // Four values, four series: none of them overwrites another.
    expect(data.series.map(series => series.key)).toEqual([
      '\u0001n',
      '',
      '\u0001d7',
      '7',
    ]);
    expect(data.points[0].values).toEqual({
      '\u0001n': 4,
      '': 3,
      '\u0001d7': 2,
      '7': 1,
    });
  });

  it('tells apart split values of every other shape', () => {
    const odd: RecordData[] = [
      { wh: true, month: '2026-08', orders: 5 },
      { wh: { id: 1 }, month: '2026-08', orders: 4 },
      // A string that holds the tag character doubles it, so it can never be
      // read as the tagged key of some other type.
      { wh: '\u0001b', month: '2026-08', orders: 3 },
    ];
    const data = shapeChart(
      config(
        {
          type: 'line',
          cartesian: {
            x: 'month',
            splitBy: 'wh',
            series: [{ metric: 'orders' }],
          },
        },
        [GROUPS.month, GROUPS.wh],
      ),
      odd,
    ) as CartesianData;
    expect(data.series.map(series => series.key)).toEqual([
      '\u0001btrue',
      '\u0001j{"id":1}',
      '\u0001\u0001b',
    ]);
    // The key is for the points; a legend shows the value as it prints.
    expect(data.series.map(series => series.label)).toEqual([
      'true',
      '{"id":1}',
      '\u0001b',
    ]);
  });

  it('keeps a heatmap cell of null apart from one of the empty string', () => {
    const data = shapeChart(
      config(
        { type: 'heatmap', heatmap: { x: 'month', y: 'wh', value: 'orders' } },
        [GROUPS.month, GROUPS.wh],
      ),
      [
        { wh: null, month: '2026-08', orders: 1 },
        { wh: '', month: '2026-08', orders: 2 },
      ],
    ) as HeatmapData;
    expect(data.cells).toEqual([[1], [2]]);
  });

  it('merges the pie remainder into one slice', () => {
    const plain = shapeChart(
      config({ type: 'pie', pie: { category: 'wh', value: 'orders' } }),
      rows,
    ) as PieData;
    expect(plain.slices).toHaveLength(3);

    const merged = shapeChart(
      config({
        type: 'pie',
        pie: { category: 'wh', value: 'orders', maxSlices: 2 },
      }),
      rows,
    ) as PieData;
    expect(merged.slices).toEqual([
      { category: 'SH', value: 30 },
      { category: null, value: 30, other: true },
    ]);
  });

  /**
   * The palette holds `CHART_COLOR_SLOTS` colours, and a slice past them
   * would wear the first again, so an additive pie folds there even when no
   * `maxSlices` was written, and never later than there when one was.
   */
  describe('a pie past the palette', () => {
    const categories = (count: number): RecordData[] =>
      Array.from({ length: count }, (_, index) => ({
        wh: `W${index}`,
        orders: count - index,
        average: count - index,
      }));
    const pieOf = (pie: Partial<ChartSpec['pie']> = {}, metric = 'orders') =>
      config(
        { type: 'pie', pie: { category: 'wh', value: metric, ...pie } },
        [GROUPS.wh],
        [METRICS.orders, METRICS.average],
      );

    it('draws as many slices as there are colours, and no fold', () => {
      const data = shapeChart(
        pieOf(),
        categories(CHART_COLOR_SLOTS),
      ) as PieData;
      expect(data.slices).toHaveLength(CHART_COLOR_SLOTS);
      expect(data.slices.some(slice => slice.other)).toBe(false);
    });

    it('folds the tail into "other" at the palette size by default', () => {
      const data = shapeChart(pieOf(), categories(10)) as PieData;
      expect(data.slices).toHaveLength(CHART_COLOR_SLOTS);
      // The seven largest keep a slice each; the three smallest are 3 + 2 + 1.
      expect(data.slices.slice(0, -1).map(slice => slice.category)).toEqual([
        'W0',
        'W1',
        'W2',
        'W3',
        'W4',
        'W5',
        'W6',
      ]);
      expect(data.slices[data.slices.length - 1]).toEqual({
        category: null,
        value: 6,
        other: true,
      });
    });

    it('reads a larger maxSlices as the palette size', () => {
      const data = shapeChart(
        pieOf({ maxSlices: 12 }),
        categories(12),
      ) as PieData;
      expect(data.slices).toHaveLength(CHART_COLOR_SLOTS);
      expect(data.slices[data.slices.length - 1]?.other).toBe(true);
    });
  });

  /**
   * A view of the last days sorted newest first — so its table leads with
   * today — drew its chart right to left, today at the origin, and its
   * sparkline backwards (analysis audit, 2026-09-23).
   */
  describe('a time axis runs forward', () => {
    const day = (n: number) => Date.UTC(2026, 8, n);
    const daily: AnalysisGroup = {
      type: AggregationGroupType.DATE_HISTOGRAM,
      field: 'createdAt',
      alias: 'day',
      unit: AggregationDateUnit.DAY,
    };
    // Newest first, as a view sorted by the day descending answers.
    const newestFirst: RecordData[] = [
      { day: day(3), wh: 'SH', orders: 3, total: 30 },
      { day: day(2), wh: 'BJ', orders: 2, total: 20 },
      { day: day(1), wh: 'SH', orders: 1, total: 10 },
    ];

    it('puts a time x axis in time order, whatever the sort', () => {
      const data = shapeChart(
        config(
          {
            type: 'line',
            cartesian: { x: 'day', series: [{ metric: 'orders' }] },
          },
          [daily],
        ),
        newestFirst,
      ) as CartesianData;
      expect(data.points.map(point => point.x)).toEqual([
        day(1),
        day(2),
        day(3),
      ]);
      expect(data.points.map(point => point.values.orders)).toEqual([1, 2, 3]);
    });

    it('reads a bucket written as digits or as a day the same way', () => {
      const data = shapeChart(
        config(
          {
            type: 'bar',
            cartesian: { x: 'day', series: [{ metric: 'orders' }] },
          },
          [daily],
        ),
        [
          { day: '2026-09-03', orders: 3 },
          { day: String(day(1)), orders: 1 },
          { day: day(2), orders: 2 },
        ],
      ) as CartesianData;
      expect(data.points.map(point => point.values.orders)).toEqual([1, 2, 3]);
    });

    it('puts a bucket that names no moment after the last one', () => {
      const data = shapeChart(
        config(
          {
            type: 'bar',
            cartesian: { x: 'day', series: [{ metric: 'orders' }] },
          },
          [daily],
        ),
        [
          { day: null, orders: 0 },
          { day: day(2), orders: 2 },
          { day: day(1), orders: 1 },
        ],
      ) as CartesianData;
      expect(data.points.map(point => point.x)).toEqual([day(1), day(2), null]);
    });

    it('keeps a category axis in the order the rows came in', () => {
      const data = shapeChart(
        config(
          {
            type: 'bar',
            cartesian: { x: 'wh', series: [{ metric: 'orders' }] },
          },
          [GROUPS.wh],
        ),
        [
          { wh: 'SH', orders: 3 },
          { wh: 'BJ', orders: 2 },
          { wh: 'CQ', orders: 1 },
        ],
      ) as CartesianData;
      expect(data.points.map(point => point.x)).toEqual(['SH', 'BJ', 'CQ']);
    });

    it('orders series split by time, and leaves the category axis alone', () => {
      const data = shapeChart(
        config(
          {
            type: 'bar',
            cartesian: {
              x: 'wh',
              splitBy: 'day',
              series: [{ metric: 'orders' }],
            },
          },
          [GROUPS.wh, daily],
        ),
        newestFirst,
      ) as CartesianData;
      expect(data.series.map(series => series.value)).toEqual([
        day(1),
        day(2),
        day(3),
      ]);
      expect(data.points.map(point => point.x)).toEqual(['SH', 'BJ']);
    });

    it('runs a heatmap over time forward and keeps its cells with them', () => {
      const data = shapeChart(
        config(
          { type: 'heatmap', heatmap: { x: 'day', y: 'wh', value: 'orders' } },
          [daily, GROUPS.wh],
        ),
        newestFirst,
      ) as HeatmapData;
      expect(data.xs).toEqual([day(1), day(2), day(3)]);
      expect(data.ys).toEqual(['SH', 'BJ']);
      expect(data.cells).toEqual([
        [1, null, 3],
        [null, 2, null],
      ]);
    });

    it('runs a card’s sparkline forward', () => {
      const data = shapeChart(
        config(
          { type: 'metric', metric: { metric: 'orders', trend: { x: 'day' } } },
          [daily],
        ),
        newestFirst,
      ) as MetricCardData;
      expect(data.trend).toEqual([
        { x: day(1), value: 1 },
        { x: day(2), value: 2 },
        { x: day(3), value: 3 },
      ]);
      // The headline is the last period — the latest day, not the first row.
      expect(data.value).toBe(3);
      expect(data.period?.at).toBe(day(3));
    });

    it('leaves a pie over time in the rows’ order: it has no axis', () => {
      const data = shapeChart(
        config({ type: 'pie', pie: { category: 'day', value: 'orders' } }, [
          daily,
        ]),
        newestFirst,
      ) as PieData;
      expect(data.slices.map(slice => slice.category)).toEqual([
        day(3),
        day(2),
        day(1),
      ]);
    });
  });

  it('lays a heatmap out as a matrix with holes', () => {
    const data = shapeChart(
      config(
        { type: 'heatmap', heatmap: { x: 'month', y: 'wh', value: 'orders' } },
        [GROUPS.month, GROUPS.wh],
      ),
      rows,
    ) as HeatmapData;
    expect(data.xs).toEqual(['2026-08', '2026-09']);
    expect(data.ys).toEqual(['SH', 'BJ']);
    expect(data.cells).toEqual([
      [30, 20],
      [10, null],
    ]);
  });

  it('places one scatter point per category', () => {
    const data = shapeChart(
      config({
        type: 'scatter',
        scatter: { category: 'wh', x: 'orders', y: 'total', size: 'orders' },
      }),
      rows,
    ) as ScatterData;
    expect(data.points[0]).toEqual({
      category: 'SH',
      x: 30,
      y: 300,
      size: 30,
    });
  });

  describe('funnel', () => {
    const stageRows: RecordData[] = [
      { wh: 'created', orders: 100 },
      { wh: 'paid', orders: 60 },
      { wh: 'shipped', orders: 30 },
    ];

    /**
     * 「事件类型分布」 as a funnel said 「首次失败 1,831,229」 — all seven
     * types added up — where the table said 65.9万 (chart audit P0-1). A
     * stage is its own rows' number unless the spec asks for more.
     */
    it('draws each group stage as its own number by default', () => {
      const funnelOf = (cumulative?: boolean) =>
        shapeChart(
          config({
            type: 'funnel',
            funnel: {
              stages: {
                from: 'group',
                category: 'wh',
                value: 'orders',
                order: ['created', 'paid', 'shipped'],
                ...(cumulative === undefined ? {} : { cumulative }),
              },
            },
          }),
          stageRows,
        ) as FunnelData;
      const own = funnelOf();
      expect(own.stages.map(stage => stage.value)).toEqual([100, 60, 30]);
      expect(own.stages[1].conversion).toBeCloseTo(0.6);
      expect(own.cumulative).toBeUndefined();

      // Asked for "reached at least here", it adds up — and says it did.
      const reached = funnelOf(true);
      expect(reached.stages.map(stage => stage.value)).toEqual([190, 90, 30]);
      expect(reached.stages[1].conversion).toBeCloseTo(90 / 190);
      expect(reached.cumulative).toBe(true);
      // Accumulated, the drop between two stages is the earlier stage's own
      // rows: the ones that stopped there.
      expect(reached.stages.map(stage => stage.drop)).toEqual([
        undefined,
        100,
        60,
      ]);
    });

    it('leaves the values alone when accumulation is switched off', () => {
      const data = shapeChart(
        config({
          type: 'funnel',
          funnel: {
            stages: {
              from: 'group',
              category: 'wh',
              value: 'orders',
              order: ['created', 'paid', 'shipped'],
              cumulative: false,
            },
          },
        }),
        stageRows,
      ) as FunnelData;
      expect(data.stages.map(stage => stage.value)).toEqual([100, 60, 30]);
      expect(data.stages[2].share).toBeCloseTo(0.3);
      expect(data.stages[2].conversion).toBeCloseTo(0.5);
    });

    it('reads a metric per stage from the single row', () => {
      const data = shapeChart(
        config(
          {
            type: 'funnel',
            funnel: {
              stages: {
                from: 'metrics',
                items: [
                  { metric: 'orders', label: 'Created' },
                  { metric: 'total' },
                ],
              },
            },
          },
          [],
          [METRICS.orders, METRICS.total],
        ),
        [{ orders: 100, total: 40 }],
      ) as FunnelData;
      expect(data).toEqual({
        type: 'funnel',
        stages: [
          { label: 'Created', value: 100, conversion: 1, share: 1 },
          {
            label: 'total',
            value: 40,
            conversion: 0.4,
            share: 0.4,
            drop: 60,
          },
        ],
        largestDrop: 1,
      });
    });

    it('reports no conversion when the base stage is empty', () => {
      const data = shapeChart(
        config(
          {
            type: 'funnel',
            funnel: {
              stages: {
                from: 'metrics',
                items: [{ metric: 'orders' }, { metric: 'total' }],
              },
            },
          },
          [],
          [METRICS.orders, METRICS.total],
        ),
        [{ orders: 0, total: 0 }],
      ) as FunnelData;
      expect(data.stages[1].conversion).toBeUndefined();
    });
  });

  describe('metric card', () => {
    it('reads one value with an optional comparison', () => {
      const delta = shapeChart(
        config(
          {
            type: 'metric',
            metric: {
              metric: 'orders',
              compare: { metric: 'total', mode: 'delta' },
              target: 50,
            },
          },
          [],
          [METRICS.orders, METRICS.total],
        ),
        [{ orders: 30, total: 20 }],
      ) as MetricCardData;
      expect(delta).toEqual({
        type: 'metric',
        value: 30,
        compare: { value: 20, delta: 10 },
        target: 50,
      });

      const percent = shapeChart(
        config(
          {
            type: 'metric',
            metric: {
              metric: 'orders',
              compare: { metric: 'total', mode: 'percent' },
            },
          },
          [],
          [METRICS.orders, METRICS.total],
        ),
        [{ orders: 30, total: 20 }],
      ) as MetricCardData;
      expect(percent.compare?.delta).toBeCloseTo(0.5);
    });

    it('reports a missing or unusable comparison as null', () => {
      const empty = shapeChart(
        config({ type: 'metric', metric: { metric: 'orders' } }, []),
        [],
      ) as MetricCardData;
      expect(empty.value).toBeNull();

      const zero = shapeChart(
        config(
          {
            type: 'metric',
            metric: {
              metric: 'orders',
              compare: { metric: 'total', mode: 'percent' },
            },
          },
          [],
          [METRICS.orders, METRICS.total],
        ),
        [{ orders: 30, total: 0 }],
      ) as MetricCardData;
      expect(zero.compare).toEqual({ value: 0, delta: null });
    });

    describe('over a trend, read as the whole', () => {
      const buckets: RecordData[] = [
        { month: '2026-08', orders: 40, total: 400 },
        { month: '2026-09', orders: 20, total: 100 },
        { month: '2026-10', orders: null, total: null },
      ];
      const points = [
        { x: '2026-08', value: 40 },
        { x: '2026-09', value: 20 },
        { x: '2026-10', value: null },
      ];
      const trend = config(
        {
          type: 'metric',
          metric: {
            metric: 'orders',
            trend: { x: 'month', headline: 'whole' },
          },
        },
        [GROUPS.month],
      );

      it('takes the headline from the totals row when there is one', () => {
        // The totals query is the ungrouped aggregation, which is the
        // headline for any metric; the buckets only draw the sparkline.
        const data = shapeChart(trend, buckets, {
          orders: 55,
        }) as MetricCardData;
        expect(data.value).toBe(55);
        expect(data.trend).toEqual(points);
      });

      it('adds the buckets up when no totals row came back', () => {
        const data = shapeChart(trend, buckets) as MetricCardData;
        expect(data.value).toBe(60);
        expect(data.trend).toEqual(points);
      });

      it('compares and targets the headline as a single value is', () => {
        const compared = config(
          {
            type: 'metric',
            metric: {
              metric: 'orders',
              compare: { metric: 'total', mode: 'delta' },
              target: 100,
              trend: { x: 'month', headline: 'whole' },
            },
          },
          [GROUPS.month],
          [METRICS.orders, METRICS.total],
        );
        expect(shapeChart(compared, buckets)).toEqual({
          type: 'metric',
          value: 60,
          compare: { value: 500, delta: -440 },
          target: 100,
          trend: points,
          whole: true,
        });

        // With a totals row both sides come from it, and the card is the
        // one a trend-less config draws from that same row, plus the points.
        const fromTotals = shapeChart(compared, buckets, {
          orders: 55,
          total: 500,
        });
        const single = shapeChart(
          config(
            {
              type: 'metric',
              metric: {
                metric: 'orders',
                compare: { metric: 'total', mode: 'delta' },
                target: 100,
              },
            },
            [],
            [METRICS.orders, METRICS.total],
          ),
          [{ orders: 55, total: 500 }],
        );
        expect(fromTotals).toEqual({ ...single, trend: points, whole: true });
      });
    });
  });
});

describe('categoryTick', () => {
  /**
   * A category axis sizes itself to its names, so a name is cut to a length
   * before it can take the chart: the whole of it is in the tooltip.
   */
  it('cuts a long category name with an ellipsis and leaves a short one', () => {
    expect(categoryTick('OrderItemReservedTrackEventProcessor')).toBe(
      'OrderItemReservedTrackE…',
    );
    expect(categoryTick('OrderItemReservedTrackEventProcessor')).toHaveLength(
      CATEGORY_LABEL_MAX,
    );
    expect(categoryTick('QuotationSaga')).toBe('QuotationSaga');
    expect(categoryTick(42)).toBe('42');
  });
});
