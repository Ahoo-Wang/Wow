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
  comboMark,
  fitChartSlots,
  leadMetric,
  switchChartType,
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
          'line',
        ]);
    }
  });

  /**
   * A combo is bars with a line (audit P1-8): picked, it used to draw every
   * series as bars — a bar chart under another name.
   */
  it('draws a combo’s first metric as bars and the others as lines, unless told', () => {
    const metrics = [COUNT, TOTAL, AVERAGE];
    const fitted = fitChartSlots({ type: 'combo' }, [WAREHOUSE], metrics);
    expect(fitted.cartesian?.series).toEqual([
      { metric: 'orders', type: 'bar' },
      { metric: 'total', type: 'line' },
      { metric: 'average', type: 'line' },
    ]);
    // The axes are left alone: the scales are the rows' fact, which the
    // slots never see.
    expect(fitted.cartesian?.series.every(s => s.axis === undefined)).toBe(
      true,
    );

    // A mark the analyst chose stands, whatever its place.
    const chosen = fitChartSlots(
      {
        type: 'combo',
        cartesian: {
          x: 'wh',
          series: [
            { metric: 'total', type: 'area' },
            { metric: 'orders' },
            { metric: 'average', type: 'bar' },
          ],
        },
      },
      [WAREHOUSE],
      metrics,
    );
    expect(chosen.cartesian?.series.map(s => s.type)).toEqual([
      'area',
      'line',
      'bar',
    ]);
    expect(comboMark(0)).toBe('bar');
    expect(comboMark(2)).toBe('line');
  });

  it('brings the other metrics in as lines when a one-series chart becomes a combo', () => {
    const metrics = [COUNT, TOTAL];
    // A bar chart narrowed to the amount: one mark is no combo.
    const bar = fitChartSlots(
      {
        type: 'bar',
        cartesian: { x: 'wh', series: [{ metric: 'total', axis: 'right' }] },
      },
      [WAREHOUSE],
      metrics,
    );
    const combo = fitChartSlots(
      switchChartType(bar, 'combo'),
      [WAREHOUSE],
      metrics,
    );
    // Fitted with no measures, every metric is one measure: the line joins
    // the bars' axis, wherever the analyst had put them.
    expect(combo.cartesian?.series).toEqual([
      { metric: 'total', axis: 'right', type: 'bar' },
      { metric: 'orders', type: 'line', axis: 'right' },
    ]);
    expect(issuesOf(combo, [WAREHOUSE], metrics)).toEqual([]);

    // A combo the analyst narrowed to one series on its options page names
    // its mark, and stays one series on every redraw.
    const narrowed = fitChartSlots(
      {
        type: 'combo',
        cartesian: { x: 'wh', series: [{ metric: 'total', type: 'bar' }] },
      },
      [WAREHOUSE],
      metrics,
    );
    expect(narrowed.cartesian?.series).toEqual([
      { metric: 'total', type: 'bar' },
    ]);
    // A split draws one metric: nothing to bring in.
    const split = fitChartSlots(
      {
        type: 'combo',
        cartesian: { x: 'wh', splitBy: 'month', series: [{ metric: 'total' }] },
      },
      [WAREHOUSE, MONTH],
      metrics,
    );
    expect(split.cartesian?.series).toEqual([{ metric: 'total', type: 'bar' }]);
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

  it('lets a reference line go with the axis its series was on', () => {
    // A line hangs on an axis, and an axis is an axis because a series
    // measures on it. When the metric that opened the right-hand one
    // leaves the shape, the line that named it has nothing to hang from —
    // and a line drawn against an axis nobody can see is a number floating
    // on the plot.
    const both: ChartSpec = {
      type: 'line',
      cartesian: {
        x: 'wh',
        series: [{ metric: 'orders' }, { metric: 'total', axis: 'right' }],
        referenceLines: [
          { axis: 'left', value: 100, label: 'target' },
          { axis: 'right', value: 20 },
        ],
      },
    };

    // Both series still drawn: both lines stay, said as they were said.
    expect(fitChartSlots(both, [WAREHOUSE], [COUNT, TOTAL]).cartesian).toEqual(
      both.cartesian,
    );

    // The total leaves the shape; so does the axis it held, so does its
    // line. The left one is untouched.
    expect(fitChartSlots(both, [WAREHOUSE], [COUNT]).cartesian).toEqual({
      x: 'wh',
      series: [{ metric: 'orders' }],
      referenceLines: [{ axis: 'left', value: 100, label: 'target' }],
    });

    // And when the last line goes, the member goes with it rather than
    // staying behind as an empty array in the saved config.
    expect(
      fitChartSlots(
        {
          type: 'line',
          cartesian: {
            x: 'wh',
            series: [{ metric: 'total', axis: 'right' }],
            referenceLines: [{ axis: 'right', value: 20 }],
          },
        },
        [WAREHOUSE],
        [COUNT],
      ).cartesian,
    ).toEqual({ x: 'wh', series: [{ metric: 'orders' }] });
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
  it('gives a pie a metric that adds up, its merged tail kept (D33 Q56)', () => {
    const chosen: ChartSpec = {
      type: 'pie',
      pie: { category: 'wh', value: 'average', maxSlices: 3, donut: true },
    };

    // A slice is a share of a whole: the average gives way to the sum.
    expect(fitChartSlots(chosen, [WAREHOUSE], [AVERAGE, TOTAL]).pie).toEqual({
      category: 'wh',
      value: 'total',
      donut: true,
      maxSlices: 3,
    });
  });

  it('keeps how a scatter draws its axes, whichever metrics they hold', () => {
    expect(
      fitChartSlots(
        {
          type: 'scatter',
          scatter: {
            category: 'wh',
            x: 'orders',
            y: 'gone',
            xAxis: { scale: 'log' },
            yAxis: { label: 'Amount' },
          },
        },
        [WAREHOUSE],
        [COUNT, TOTAL],
      ).scatter,
    ).toEqual({
      category: 'wh',
      x: 'orders',
      y: 'total',
      xAxis: { scale: 'log' },
      yAxis: { label: 'Amount' },
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
        orientation: 'horizontal',
      },
    };
    expect(fitChartSlots(staged, [WAREHOUSE], [COUNT]).funnel).toEqual(
      staged.funnel,
    );

    // Metric stages are a hand-made order too, so the list the spec holds
    // stands and a metric it has never named joins at the end — one that
    // adds up: an average is no stage of a funnel.
    const ordered: ChartSpec = {
      type: 'funnel',
      funnel: {
        stages: {
          from: 'metrics',
          items: [{ metric: 'total' }],
        },
      },
    };
    expect(
      fitChartSlots(ordered, [], [COUNT, AVERAGE, TOTAL]).funnel?.stages,
    ).toEqual({
      from: 'metrics',
      items: [{ metric: 'total' }, { metric: 'orders' }],
    });
  });

  /**
   * A funnel is how many entered and how many remained: its stages add up
   * (`chart.funnel.not-additive`). A lead that does not — an average, a
   * distinct count — gives way to the first metric that does, rather than
   * the funnel refusing; a stage that does not leaves the list.
   */
  it('measures a funnel with what adds up, the first of it when the lead does not', () => {
    const averaged: ChartSpec = {
      type: 'funnel',
      funnel: {
        stages: {
          from: 'group',
          category: 'wh',
          value: 'average',
          order: ['CN', 'JP'],
        },
      },
    };
    expect(
      fitChartSlots(averaged, [WAREHOUSE], [AVERAGE, COUNT, TOTAL]).funnel
        ?.stages,
    ).toMatchObject({ value: 'orders', order: ['CN', 'JP'] });
    // Nothing adds up: the slot is left empty for validation to name.
    expect(
      fitChartSlots(averaged, [WAREHOUSE], [AVERAGE]).funnel?.stages,
    ).toMatchObject({ value: '' });
    // A bar chart of the average picked as a funnel measures the count.
    const bars = fitChartSlots({ type: 'bar' }, [WAREHOUSE], [AVERAGE, COUNT]);
    expect(
      fitChartSlots(
        switchChartType(bars, 'funnel'),
        [WAREHOUSE],
        [AVERAGE, COUNT],
      ).funnel?.stages,
    ).toMatchObject({ value: 'orders' });
    // Metric stages drop the average they named.
    const staged: ChartSpec = {
      type: 'funnel',
      funnel: {
        stages: {
          from: 'metrics',
          items: [{ metric: 'average' }, { metric: 'orders' }],
        },
      },
    };
    expect(fitChartSlots(staged, [], [AVERAGE, COUNT]).funnel?.stages).toEqual({
      from: 'metrics',
      items: [{ metric: 'orders' }],
    });
  });

  it('keeps a stage listed twice once', () => {
    const twice: ChartSpec = {
      type: 'funnel',
      funnel: {
        stages: {
          from: 'group',
          category: 'wh',
          value: 'orders',
          order: ['CN', 'CN', 'JP'],
        },
      },
    };
    expect(
      fitChartSlots(twice, [WAREHOUSE], [COUNT]).funnel?.stages,
    ).toMatchObject({ order: ['CN', 'JP'] });
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
    const unknown = { type: 'chord' as ChartType };

    expect(fitChartSlots(unknown, [WAREHOUSE], [COUNT])).toBe(unknown);
  });
});

/**
 * Picking another type changes how the numbers are drawn, not which numbers
 * (the user's 2026-09-23 decision, audit P0-10): bars of the amount turned
 * into a pie used to become a pie of the record count.
 */
describe('a type switch keeps the metric', () => {
  const groups = [WAREHOUSE];
  const metrics = [COUNT, TOTAL];
  const bars: ChartSpec = {
    type: 'bar',
    cartesian: { x: 'wh', series: [{ metric: 'total' }] },
  };
  const switched = (chart: ChartSpec, type: ChartType) =>
    fitChartSlots(switchChartType(chart, type), groups, metrics);

  it('reads the metric a chart is about off its first mark', () => {
    expect(leadMetric(bars)).toBe('total');
    expect(
      leadMetric({ type: 'pie', pie: { category: 'wh', value: 'orders' } }),
    ).toBe('orders');
    expect(leadMetric({ type: 'pie' })).toBeUndefined();
    expect(leadMetric({ type: 'metric', metric: { metric: 'total' } })).toBe(
      'total',
    );
    expect(
      leadMetric({
        type: 'heatmap',
        heatmap: { x: 'wh', y: 'month', value: 'total' },
      }),
    ).toBe('total');
    expect(
      leadMetric({
        type: 'scatter',
        scatter: { category: 'wh', x: 'total', y: 'orders' },
      }),
    ).toBe('total');
    expect(
      leadMetric({
        type: 'funnel',
        funnel: {
          stages: { from: 'metrics', items: [{ metric: 'orders' }] },
        },
      }),
    ).toBe('orders');
  });

  it('carries it into a heatmap and onto a scatter’s horizontal measure', () => {
    const two = [WAREHOUSE, MONTH];
    const heat = fitChartSlots(
      switchChartType(bars, 'heatmap'),
      two,
      metrics,
    ).heatmap;
    expect(heat?.value).toBe('total');
    const visited: ChartSpec = {
      ...bars,
      heatmap: { x: 'wh', y: 'month', value: 'orders' },
    };
    expect(switchChartType(visited, 'heatmap').heatmap).toEqual({
      x: 'wh',
      y: 'month',
      value: 'total',
    });

    // A scatter reads two measures; the lead takes the horizontal one,
    // unless it already is the vertical one — a point plotted against
    // itself is a diagonal line.
    const scattered: ChartSpec = {
      ...bars,
      scatter: { category: 'wh', x: 'orders', y: 'average' },
    };
    expect(switchChartType(scattered, 'scatter').scatter?.x).toBe('total');
    expect(
      switchChartType(
        { ...bars, scatter: { category: 'wh', x: 'orders', y: 'total' } },
        'scatter',
      ).scatter,
    ).toEqual({ category: 'wh', x: 'orders', y: 'total' });
  });

  it('makes it the one series of a pivot', () => {
    const pie: ChartSpec = {
      type: 'pie',
      pie: { category: 'wh', value: 'total' },
      cartesian: {
        x: 'wh',
        splitBy: 'month',
        series: [{ metric: 'orders', axis: 'right' }],
      },
    };
    expect(switchChartType(pie, 'bar').cartesian?.series).toEqual([
      { metric: 'total', axis: 'right' },
    ]);
  });

  it('carries it into a family never visited', () => {
    expect(switched(bars, 'pie').pie?.value).toBe('total');
    expect(switched(bars, 'funnel').funnel?.stages).toMatchObject({
      from: 'group',
    });
  });

  it('carries it into a family visited before, keeping the rest', () => {
    const visited: ChartSpec = {
      ...bars,
      pie: { category: 'wh', value: 'orders', donut: true },
    };
    const pie = switched(visited, 'pie').pie;
    expect(pie?.value).toBe('total');
    expect(pie?.donut).toBe(true);
  });

  it('brings it back to the bars, at the front of the list', () => {
    const pie: ChartSpec = {
      type: 'pie',
      pie: { category: 'wh', value: 'total' },
      cartesian: { x: 'wh', series: [{ metric: 'orders' }] },
    };
    expect(
      switched(pie, 'bar').cartesian?.series.map(series => series.metric),
    ).toEqual(['total', 'orders']);
  });

  it('leaves the fit to fall back where the new family cannot measure it', () => {
    // A card over a trend headlines the buckets' whole: an average does not
    // add up, so the card takes the first metric that does.
    const averages: ChartSpec = {
      type: 'bar',
      cartesian: { x: 'month', series: [{ metric: 'average' }] },
    };
    const card = fitChartSlots(
      switchChartType(averages, 'metric'),
      [MONTH],
      [AVERAGE, COUNT],
    );
    expect(card.metric?.metric).toBe('orders');
  });
});
