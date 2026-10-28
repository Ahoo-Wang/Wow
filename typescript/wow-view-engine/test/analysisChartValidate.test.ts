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
  validateChart,
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type ChartSpec,
} from '../src/index.js';
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
    // A config from a store may have no chart at all; that is a finding on
    // its own, not a TypeError from indexing the family table.
    expect(codes(undefined as unknown as ChartSpec)).toEqual([
      'analysis.config.malformed',
    ]);
  });

  /**
   * A saved colour reaches the drawing and the legend, so it may name a
   * colour and nothing else.
   */
  it('takes a colour and nothing else', () => {
    const coloured = (colors: Record<string, string>): ChartSpec => ({
      type: 'bar',
      cartesian: { x: 'wh', series: [{ metric: 'orders' }] },
      colors,
    });

    expect(
      codes(coloured({ orders: '#2a78d6', wh: 'var(--chart-2)' })),
    ).toEqual([]);

    expect(
      validateChart(config(coloured({ orders: 'red; } .x { color: red' }))),
    ).toEqual([
      {
        code: 'chart.colors.invalid',
        severity: 'error',
        path: ['chart', 'colors', 'orders'],
      },
    ]);
  });

  /**
   * Only `undefined` is "none pinned". Every other non-object read as one,
   * so a `colors` a migration had turned into a string, a number or a list
   * validated clean and then coloured nothing.
   */
  it('takes a map of colours, or nothing at all', () => {
    const withColors = (colors: unknown): AnalysisViewConfig =>
      config({
        type: 'bar',
        cartesian: { x: 'wh', series: [{ metric: 'orders' }] },
        colors: colors as Record<string, string>,
      });

    expect(validateChart(withColors(undefined))).toEqual([]);

    for (const malformed of ['red', 3, null, ['#2a78d6']])
      expect(validateChart(withColors(malformed))).toEqual([
        {
          code: 'chart.colors.malformed',
          severity: 'error',
          path: ['chart', 'colors'],
        },
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

  it('draws a pie only of a metric that adds up (D33 Q56)', () => {
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
    ).toEqual(['chart.pie.not-additive']);
    // A slice is a share of a whole whether or not the tail is merged.
    expect(
      codes(
        { type: 'pie', pie: { category: 'wh', value: 'average' } },
        [GROUPS.wh],
        [METRICS.average],
      ),
    ).toEqual(['chart.pie.not-additive']);
    expect(
      codes(
        { type: 'pie', pie: { category: 'wh', value: 'total', maxSlices: 3 } },
        [GROUPS.wh],
        [METRICS.total],
      ),
    ).toEqual([]);
  });

  it('needs a whole number of slices', () => {
    // NaN and a fraction both pass `< 2`, and then `slice` keeps nothing:
    // every category would collapse into "other".
    for (const maxSlices of [Number.NaN, 2.5])
      expect(
        codes({
          type: 'pie',
          pie: { category: 'wh', value: 'orders', maxSlices },
        }),
      ).toEqual(['chart.pie.maxSlices-too-small']);
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

    /**
     * A funnel is how many entered and how many remained: a conversion of
     * averages, distinct counts, percentiles or extremes means nothing, and
     * a cumulative funnel adds later stages into earlier ones. The stage
     * value of a funnel over a dimension, and every metric stage, adds up.
     */
    it('counts only a metric that adds up', () => {
      const metrics = [METRICS.orders, METRICS.total, METRICS.average];
      const over = (value: string) =>
        validateChart(
          config(
            {
              type: 'funnel',
              funnel: {
                stages: {
                  from: 'group',
                  category: 'wh',
                  value,
                  order: ['A', 'B'],
                },
              },
            },
            [GROUPS.wh],
            metrics,
          ),
        );
      expect(over('orders')).toEqual([]);
      expect(over('total')).toEqual([]);
      expect(over('average')).toEqual([
        {
          code: 'chart.funnel.not-additive',
          severity: 'error',
          path: ['chart', 'funnel', 'stages', 'value'],
          params: { metric: 'average' },
        },
      ]);
      // An unknown alias is said once, as unknown.
      expect(over('gone').map(issue => issue.code)).toEqual([
        'chart.metric.unknown',
      ]);

      const staged = validateChart(
        config(
          {
            type: 'funnel',
            funnel: {
              stages: {
                from: 'metrics',
                items: [
                  { metric: 'orders' },
                  { metric: 'average' },
                  { metric: 'total' },
                ],
              },
            },
          },
          [],
          metrics,
        ),
      );
      expect(staged.map(issue => [issue.code, issue.path])).toEqual([
        [
          'chart.funnel.not-additive',
          ['chart', 'funnel', 'stages', 'items', 1, 'metric'],
        ],
      ]);
    });

    /**
     * A stage is a step, named: a month is a bucket of a scale, and the
     * "conversion" from one month to the next is no conversion — nor could
     * its bucket keys be read back as stage names.
     */
    it('takes its stages from a category, never from buckets of a scale', () => {
      expect(
        codes(
          {
            type: 'funnel',
            funnel: {
              stages: {
                from: 'group',
                category: 'month',
                value: 'orders',
                order: ['2026-01', '2026-02'],
              },
            },
          },
          [GROUPS.month],
        ),
      ).toEqual(['chart.funnel.stages-need-category']);
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

    it('refuses a trend headline over a metric that does not add', () => {
      // Without a totals row the headline is the buckets added up, which is
      // meaningless for an average — the same rule as a pie's merged slice,
      // for the headline and for the value it is compared against.
      expect(
        codes(
          {
            type: 'metric',
            metric: { metric: 'average', trend: { x: 'month' } },
          },
          [GROUPS.month],
          [METRICS.average],
        ),
      ).toEqual(['chart.metric.trend-not-additive']);
      expect(
        validateChart(
          config(
            {
              type: 'metric',
              metric: {
                metric: 'orders',
                compare: { metric: 'average', mode: 'delta' },
                trend: { x: 'month' },
              },
            },
            [GROUPS.month],
            [METRICS.orders, METRICS.average],
          ),
        ),
      ).toEqual([
        {
          code: 'chart.metric.trend-not-additive',
          severity: 'error',
          path: ['chart', 'metric', 'compare', 'metric'],
          params: { metric: 'average' },
        },
      ]);
      // Without a trend the one row is the headline, so any metric will do.
      expect(
        codes(
          { type: 'metric', metric: { metric: 'average' } },
          [],
          [METRICS.average],
        ),
      ).toEqual([]);
    });
  });
});
