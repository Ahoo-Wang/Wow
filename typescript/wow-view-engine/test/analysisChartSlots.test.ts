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
  CHART_TYPES,
  fitChartSlots,
  validateChart,
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type ChartSpec,
  type ChartType,
} from '../src/index.js';
import { analysisKernelConfig } from './fixtures/analysis.js';

const WAREHOUSE: AnalysisGroup = {
  type: 'TERMS',
  field: 'warehouse',
  alias: 'wh',
};
const MONTH: AnalysisGroup = {
  type: 'DATE_HISTOGRAM',
  field: 'createdAt',
  alias: 'month',
  unit: 'MONTH',
};
const COUNT: AnalysisMetric = { type: 'COUNT', alias: 'orders' };
const TOTAL: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'total',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'amount' },
};
const AVERAGE: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'average',
  function: 'AVG',
  expression: { type: 'FIELD', field: 'amount' },
};

/** The chart's own issues, which is what a switch must not produce. */
function issuesOf(
  chart: ChartSpec,
  groups: AnalysisGroup[],
  metrics: AnalysisMetric[],
): string[] {
  const config = analysisKernelConfig({
    groups,
    metrics: metrics as AnalysisViewConfig['metrics'],
    chart,
    sort: [],
    table: { columns: [] },
  });
  return validateChart(config).map(issue => issue.code);
}

describe('fitChartSlots', () => {
  /**
   * The whole spec, not just its `type`: switching used to change the one
   * word and leave the family sub-object absent, so `validateChart` reported
   * `chart.family.missing` and the chart disappeared.
   */
  it('builds the family the new type needs, from the shape in force', () => {
    const fitted = fitChartSlots({ type: 'pie' }, [WAREHOUSE], [COUNT, TOTAL]);

    expect(fitted).toEqual({
      type: 'pie',
      pie: { category: 'wh', value: 'orders' },
    });
    expect(issuesOf(fitted, [WAREHOUSE], [COUNT, TOTAL])).toEqual([]);
  });

  /**
   * One dimension and two metrics is the shape the editor starts most views
   * in, so every type it offers has to come out of a switch drawable — or,
   * where the shape cannot express one, say which slot is missing rather
   * than that the family is.
   */
  it('leaves no chart type reporting a missing family', () => {
    const groups = [WAREHOUSE, MONTH];
    const metrics = [COUNT, TOTAL];
    const missing = CHART_TYPES.filter((type: ChartType) =>
      issuesOf(fitChartSlots({ type }, groups, metrics), groups, metrics).some(
        code => code === 'chart.family.missing',
      ),
    );

    expect(missing).toEqual([]);
  });

  it('switches a cartesian chart back and forth without an issue', () => {
    const groups = [WAREHOUSE];
    const metrics = [COUNT, TOTAL];
    for (const type of ['bar', 'line', 'area', 'combo'] as const) {
      const fitted = fitChartSlots({ type }, groups, metrics);
      expect(issuesOf(fitted, groups, metrics)).toEqual([]);
      // A combo draws one mark per series and each names its own.
      expect(fitted.cartesian?.series.every(s => s.metric !== '')).toBe(true);
      if (type === 'combo')
        expect(fitted.cartesian?.series.map(s => s.type)).toEqual([
          'bar',
          'bar',
        ]);
    }
  });

  /**
   * A cartesian chart consumes every dimension, so the second one becomes the
   * split and the series narrow to the one metric a pivot can draw.
   */
  it('pivots on a second dimension and opens back up when it goes', () => {
    const one = fitChartSlots({ type: 'bar' }, [WAREHOUSE], [COUNT, TOTAL]);
    const two = fitChartSlots(one, [WAREHOUSE, MONTH], [COUNT, TOTAL]);

    expect(two.cartesian).toEqual({
      x: 'wh',
      splitBy: 'month',
      series: [{ metric: 'orders' }],
    });
    expect(issuesOf(two, [WAREHOUSE, MONTH], [COUNT, TOTAL])).toEqual([]);

    const back = fitChartSlots(two, [WAREHOUSE], [COUNT, TOTAL]);
    expect(back.cartesian).toEqual({
      x: 'wh',
      series: [{ metric: 'orders' }, { metric: 'total' }],
    });
    expect(issuesOf(back, [WAREHOUSE], [COUNT, TOTAL])).toEqual([]);
  });

  /**
   * The series list is a slot the analyst fills too, and this function runs
   * again on every redraw: a list re-derived here is a series the options
   * panel cannot remove — pressed, it came straight back (D20 屏 J).
   */
  it('keeps the series the chart names, and fills the list only when it is empty', () => {
    const one: ChartSpec = {
      type: 'bar',
      cartesian: { x: 'wh', series: [{ metric: 'total', axis: 'right' }] },
    };
    expect(
      fitChartSlots(one, [WAREHOUSE], [COUNT, TOTAL]).cartesian?.series,
    ).toEqual([{ metric: 'total', axis: 'right' }]);

    // A metric that left takes its series with it, and a list with nothing
    // left in it fills again rather than drawing nothing at all.
    expect(
      fitChartSlots(one, [WAREHOUSE], [COUNT, AVERAGE]).cartesian?.series,
    ).toEqual([{ metric: 'orders' }, { metric: 'average' }]);
    expect(
      fitChartSlots(
        { type: 'bar', cartesian: { x: 'wh', series: [] } },
        [WAREHOUSE],
        [COUNT, TOTAL],
      ).cartesian?.series,
    ).toEqual([{ metric: 'orders' }, { metric: 'total' }]);
  });

  it('keeps a slot the user chose while it still names something', () => {
    const chosen: ChartSpec = {
      type: 'bar',
      cartesian: {
        x: 'month',
        series: [{ metric: 'total', axis: 'right' }],
        orientation: 'horizontal',
      },
    };

    const fitted = fitChartSlots(chosen, [MONTH, WAREHOUSE], [COUNT, TOTAL]);
    expect(fitted.cartesian).toEqual({
      x: 'month',
      splitBy: 'wh',
      series: [{ metric: 'total', axis: 'right' }],
      orientation: 'horizontal',
    });
  });

  it('drops a slot whose alias is gone and fills it again', () => {
    const chosen: ChartSpec = {
      type: 'heatmap',
      heatmap: { x: 'gone', y: 'month', value: 'nowhere', scale: 'log' },
    };

    expect(fitChartSlots(chosen, [WAREHOUSE, MONTH], [COUNT]).heatmap).toEqual({
      x: 'wh',
      y: 'month',
      value: 'orders',
      scale: 'log',
    });
  });

  /**
   * The merged tail of a pie is the sum of the slices it swallowed, so it
   * only means anything for a metric that adds. Switching the value to an
   * average takes the merge with it rather than leaving an issue behind.
   */
  it('lets the merged tail go when its metric stops adding up', () => {
    const chosen: ChartSpec = {
      type: 'pie',
      pie: { category: 'wh', value: 'average', maxSlices: 3, donut: true },
    };

    expect(fitChartSlots(chosen, [WAREHOUSE], [AVERAGE]).pie).toEqual({
      category: 'wh',
      value: 'average',
      donut: true,
    });
    expect(fitChartSlots(chosen, [WAREHOUSE], [AVERAGE, TOTAL]).pie).toEqual({
      category: 'wh',
      value: 'average',
      donut: true,
    });
  });

  /** A scatter measures two different metrics, one per axis. */
  it('gives a scatter two different metrics', () => {
    expect(
      fitChartSlots({ type: 'scatter' }, [WAREHOUSE], [COUNT, TOTAL]).scatter,
    ).toEqual({ category: 'wh', x: 'orders', y: 'total' });
  });

  /**
   * A sparkline needs exactly one time dimension and a headline that adds up,
   * because over a trend the headline is the buckets summed.
   */
  it('gives a metric card a trend only where one can be drawn', () => {
    expect(fitChartSlots({ type: 'metric' }, [MONTH], [COUNT]).metric).toEqual({
      metric: 'orders',
      trend: { x: 'month' },
    });
    // An average does not add up, so the buckets cannot make a headline.
    expect(fitChartSlots({ type: 'metric' }, [MONTH], [AVERAGE]).type).toBe(
      'bar',
    );
    // A card is one number, so a dimension it cannot draw as a sparkline
    // moves it to a family that can address one.
    expect(fitChartSlots({ type: 'metric' }, [WAREHOUSE], [COUNT])).toEqual({
      type: 'bar',
      cartesian: { x: 'wh', series: [{ metric: 'orders' }] },
    });
  });

  /**
   * Removing the last dimension is a change to the question, not to the
   * chart, so the chart follows rather than turning the config red: there is
   * one family that draws an ungrouped aggregation.
   */
  it('becomes a card when the last dimension goes', () => {
    const bar = fitChartSlots({ type: 'bar' }, [WAREHOUSE], [COUNT, TOTAL]);

    const none = fitChartSlots(bar, [], [COUNT, TOTAL]);
    expect(none.type).toBe('metric');
    expect(none.metric).toEqual({ metric: 'orders' });
    expect(issuesOf(none, [], [COUNT, TOTAL])).toEqual([]);
  });

  /**
   * Stages come from metrics when there is nothing to group by, and from the
   * values of the one dimension otherwise — whose business order is data the
   * kernel has never seen, so one already written down is kept.
   */
  it('stages a funnel from whichever the shape offers', () => {
    expect(
      fitChartSlots({ type: 'funnel' }, [], [COUNT, TOTAL]).funnel,
    ).toEqual({
      stages: {
        from: 'metrics',
        items: [{ metric: 'orders' }, { metric: 'total' }],
      },
    });

    const staged: ChartSpec = {
      type: 'funnel',
      funnel: {
        stages: {
          from: 'group',
          category: 'wh',
          value: 'orders',
          order: ['CN', 'JP'],
        },
        conversion: 'first',
      },
    };
    expect(fitChartSlots(staged, [WAREHOUSE], [COUNT]).funnel).toEqual(
      staged.funnel,
    );

    // Metric stages are a hand-made order too, so the list the spec holds
    // stands and a metric it has never named joins at the end.
    const ordered: ChartSpec = {
      type: 'funnel',
      funnel: {
        stages: {
          from: 'metrics',
          items: [{ metric: 'total' }, { metric: 'orders' }],
        },
      },
    };
    expect(
      fitChartSlots(ordered, [], [COUNT, TOTAL, AVERAGE]).funnel?.stages,
    ).toEqual({
      from: 'metrics',
      items: [{ metric: 'total' }, { metric: 'orders' }, { metric: 'average' }],
    });
  });

  it('carries every other family over untouched', () => {
    const chosen: ChartSpec = {
      type: 'pie',
      pie: { category: 'wh', value: 'orders' },
      heatmap: { x: 'stale', y: 'stale', value: 'stale' },
      legend: 'right',
    };

    const fitted = fitChartSlots(chosen, [WAREHOUSE], [COUNT]);
    expect(fitted.heatmap).toEqual(chosen.heatmap);
    expect(fitted.legend).toBe('right');
  });

  it('leaves a type it does not know alone', () => {
    const unknown = { type: 'sankey' as ChartType };

    expect(fitChartSlots(unknown, [WAREHOUSE], [COUNT])).toBe(unknown);
  });
});
