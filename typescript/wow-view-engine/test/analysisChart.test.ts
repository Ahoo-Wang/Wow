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
import {
  shapeChart,
  validateChart,
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

const GROUPS: Record<string, AnalysisGroup> = {
  wh: { type: 'TERMS', field: 'warehouse', alias: 'wh' },
  month: {
    type: 'DATE_HISTOGRAM',
    field: 'createdAt',
    alias: 'month',
    unit: 'MONTH',
  },
};

const METRICS: Record<string, AnalysisMetric> = {
  orders: { type: 'COUNT', alias: 'orders' },
  total: {
    type: 'NUMERIC',
    alias: 'total',
    function: 'SUM',
    expression: { type: 'FIELD', field: 'amount' },
  },
  average: {
    type: 'NUMERIC',
    alias: 'average',
    function: 'AVG',
    expression: { type: 'FIELD', field: 'amount' },
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

const codes = (
  chart: ChartSpec,
  groups?: AnalysisGroup[],
  metrics?: AnalysisMetric[],
) => validateChart(config(chart, groups, metrics)).map(issue => issue.code);

describe('validateChart', () => {
  it('requires the sub-object of its own family', () => {
    expect(codes({ type: 'pie' })).toEqual(['chart.family.missing']);
    expect(codes({ type: 'nope' } as unknown as ChartSpec)).toEqual([
      'chart.type.unknown',
    ]);
  });

  describe('cartesian', () => {
    it('resolves x and every series metric', () => {
      expect(
        codes({
          type: 'bar',
          cartesian: { x: 'gone', series: [{ metric: 'orders' }] },
        }),
      ).toEqual(['chart.group.unknown', 'chart.group.unconsumed']);
      expect(
        codes({
          type: 'bar',
          cartesian: { x: 'wh', series: [{ metric: 'gone' }] },
        }),
      ).toEqual(['chart.metric.unknown']);
    });

    it('accepts a pivot of exactly one metric', () => {
      const groups = [GROUPS.wh, GROUPS.month];
      expect(
        codes(
          {
            type: 'line',
            cartesian: {
              x: 'month',
              splitBy: 'wh',
              series: [{ metric: 'orders' }],
            },
          },
          groups,
        ),
      ).toEqual([]);
      expect(
        codes(
          {
            type: 'line',
            cartesian: {
              x: 'month',
              splitBy: 'wh',
              series: [{ metric: 'orders' }, { metric: 'total' }],
            },
          },
          groups,
          [METRICS.orders, METRICS.total],
        ),
      ).toEqual(['chart.splitBy.needs-one-series']);
      expect(
        codes({
          type: 'line',
          cartesian: { x: 'wh', splitBy: 'wh', series: [{ metric: 'orders' }] },
        }),
      ).toEqual(['chart.splitBy.same-as-x']);
    });

    it('requires a type per series in a combo and a series per reference axis', () => {
      expect(
        codes({
          type: 'combo',
          cartesian: { x: 'wh', series: [{ metric: 'orders' }] },
        }),
      ).toEqual(['chart.combo.series-type-missing']);
      expect(
        codes({
          type: 'bar',
          cartesian: {
            x: 'wh',
            series: [{ metric: 'orders' }],
            referenceLines: [{ axis: 'right', value: 10 }],
          },
        }),
      ).toEqual(['chart.referenceLine.empty-axis']);
      expect(
        codes({
          type: 'bar',
          cartesian: {
            x: 'wh',
            series: [{ metric: 'orders', axis: 'right' }],
            referenceLines: [{ axis: 'right', value: 10 }],
          },
        }),
      ).toEqual([]);
    });

    it('refuses to leave a group dimension undrawn', () => {
      expect(
        codes(
          {
            type: 'bar',
            cartesian: { x: 'wh', series: [{ metric: 'orders' }] },
          },
          [GROUPS.wh, GROUPS.month],
        ),
      ).toEqual(['chart.group.unconsumed']);
    });
  });

  it('merges a pie remainder only for an additive metric', () => {
    expect(
      codes({ type: 'pie', pie: { category: 'wh', value: 'orders' } }),
    ).toEqual([]);
    expect(
      codes({
        type: 'pie',
        pie: { category: 'wh', value: 'orders', maxSlices: 1 },
      }),
    ).toEqual(['chart.pie.maxSlices-too-small']);
    expect(
      codes(
        {
          type: 'pie',
          pie: { category: 'wh', value: 'average', maxSlices: 3 },
        },
        [GROUPS.wh],
        [METRICS.average],
      ),
    ).toEqual(['chart.pie.maxSlices-not-additive']);
    expect(
      codes(
        { type: 'pie', pie: { category: 'wh', value: 'total', maxSlices: 3 } },
        [GROUPS.wh],
        [METRICS.total],
      ),
    ).toEqual([]);
  });

  it('keeps the two heatmap axes and the two scatter metrics apart', () => {
    expect(
      codes(
        { type: 'heatmap', heatmap: { x: 'wh', y: 'wh', value: 'orders' } },
        [GROUPS.wh, GROUPS.month],
      ),
    ).toEqual(['chart.heatmap.same-axes', 'chart.group.unconsumed']);
    expect(
      codes({
        type: 'scatter',
        scatter: { category: 'wh', x: 'orders', y: 'orders' },
      }),
    ).toEqual(['chart.scatter.same-metrics']);
    expect(
      codes(
        {
          type: 'scatter',
          scatter: { category: 'wh', x: 'orders', y: 'total', size: 'gone' },
        },
        [GROUPS.wh],
        [METRICS.orders, METRICS.total],
      ),
    ).toEqual(['chart.metric.unknown']);
  });

  describe('funnel', () => {
    it('reads stages from metrics only when nothing is grouped', () => {
      expect(
        codes(
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
      ).toEqual([]);
      expect(
        codes(
          {
            type: 'funnel',
            funnel: {
              stages: { from: 'metrics', items: [{ metric: 'orders' }] },
            },
          },
          [GROUPS.wh],
        ),
      ).toEqual([
        'chart.funnel.too-few-stages',
        'chart.funnel.metrics-need-no-group',
      ]);
    });

    it('reads stages from one group with an explicit order', () => {
      const spec = (order: string[]): ChartSpec => ({
        type: 'funnel',
        funnel: {
          stages: { from: 'group', category: 'wh', value: 'orders', order },
        },
      });
      expect(codes(spec(['A', 'B']))).toEqual([]);
      expect(codes(spec(['A']))).toEqual(['chart.funnel.too-few-stages']);
      expect(codes(spec(['A', 'A']))).toEqual(['chart.funnel.duplicate-stage']);
    });
  });

  describe('metric card', () => {
    it('reads one row unless it draws a trend', () => {
      expect(
        codes({ type: 'metric', metric: { metric: 'orders' } }, []),
      ).toEqual([]);
      expect(codes({ type: 'metric', metric: { metric: 'orders' } })).toEqual([
        'chart.metric.needs-no-group',
      ]);
      expect(codes({ type: 'metric', metric: { metric: 'gone' } }, [])).toEqual(
        ['chart.metric.unknown'],
      );
    });

    it('checks the compare metric and the trend dimension', () => {
      expect(
        codes(
          {
            type: 'metric',
            metric: {
              metric: 'orders',
              compare: { metric: 'gone', mode: 'delta' },
            },
          },
          [],
        ),
      ).toEqual(['chart.metric.unknown']);

      expect(
        codes(
          {
            type: 'metric',
            metric: { metric: 'orders', trend: { x: 'month' } },
          },
          [GROUPS.month],
        ),
      ).toEqual([]);
      expect(
        codes(
          { type: 'metric', metric: { metric: 'orders', trend: { x: 'wh' } } },
          [GROUPS.wh],
        ),
      ).toEqual(['chart.metric.trend-needs-one-date-group']);
      expect(
        codes(
          {
            type: 'metric',
            metric: { metric: 'orders', trend: { x: 'other' } },
          },
          [GROUPS.month],
        ),
      ).toEqual(['chart.metric.trend-alias-mismatch']);
    });
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
    expect(data.series).toEqual([{ key: 'orders', metric: 'orders' }]);
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

  it('names a pivot series even for an odd split value', () => {
    const odd: RecordData[] = [
      { wh: null, month: '2026-08', orders: 1 },
      { wh: 7, month: '2026-08', orders: 2 },
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
    expect(data.series.map(series => series.key)).toEqual(['', '7']);
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

    it('accumulates group stages into "reached at least here"', () => {
      const data = shapeChart(
        config({
          type: 'funnel',
          funnel: {
            stages: {
              from: 'group',
              category: 'wh',
              value: 'orders',
              order: ['created', 'paid', 'shipped'],
            },
          },
        }),
        stageRows,
      ) as FunnelData;
      expect(data.stages.map(stage => stage.value)).toEqual([190, 90, 30]);
      expect(data.stages[1].conversion).toBeCloseTo(90 / 190);
    });

    it('leaves the values alone when accumulation is switched off', () => {
      const data = shapeChart(
        config({
          type: 'funnel',
          funnel: {
            conversion: 'first',
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
      expect(data.stages[2].conversion).toBeCloseTo(0.3);
    });

    it('reads a metric per stage from the single row', () => {
      const data = shapeChart(
        config(
          {
            type: 'funnel',
            funnel: {
              conversion: 'none',
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
      expect(data.stages).toEqual([
        { label: 'Created', value: 100 },
        { label: 'total', value: 40 },
      ]);
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

    it('sums a trend and keeps its points', () => {
      const data = shapeChart(
        config(
          {
            type: 'metric',
            metric: { metric: 'orders', trend: { x: 'month' } },
          },
          [GROUPS.month],
        ),
        [
          { month: '2026-08', orders: 40 },
          { month: '2026-09', orders: 20 },
          { month: '2026-10', orders: null },
        ],
      ) as MetricCardData;
      expect(data.value).toBe(60);
      expect(data.trend).toEqual([
        { x: '2026-08', value: 40 },
        { x: '2026-09', value: 20 },
        { x: '2026-10', value: null },
      ]);
    });
  });
});
