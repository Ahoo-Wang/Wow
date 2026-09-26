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
  CHART_FAMILIES,
  familyOf,
  fitChartSlots,
  fitCharts,
  optionTabs,
  switchChartType,
  validateChart,
  valueLabelsOn,
  seriesMark,
  chartMarks,
  withStagesFrom,
} from '../src/analysis/index.js';
import {
  CHART_FAMILY,
  CHART_TYPES,
  type AnalysisGroup,
  type AnalysisMetric,
  type ChartSpec,
  type ChartType,
} from '../src/model/index.js';
import { analysisConfig } from './fixtures.js';

const terms = (alias: string): AnalysisGroup => ({
  type: 'TERMS',
  field: alias,
  alias,
});
const month: AnalysisGroup = {
  type: 'DATE_HISTOGRAM',
  field: 'createdAt',
  alias: 'month',
  unit: 'MONTH',
};
const count: AnalysisMetric = { type: 'COUNT', alias: 'orders' };
const sum: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'sum',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'amount' },
};
const average: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'avg',
  function: 'AVG',
  expression: { type: 'FIELD', field: 'amount' },
};
/** The latest of a date: a moment, which no mark measures. */
const latest: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'latest',
  function: 'MAX',
  expression: { type: 'FIELD', field: 'createdAt' },
};
const MOMENTS: ReadonlySet<string> = new Set([latest.alias]);
/** A field's five numbers: what a boxplot draws (D41). */
const amount = { type: 'FIELD', field: 'amount' } as const;
const five: AnalysisMetric[] = [
  { type: 'NUMERIC', alias: 'low', function: 'MIN', expression: amount },
  { type: 'PERCENTILE', alias: 'p25', expression: amount, percentile: 25 },
  { type: 'PERCENTILE', alias: 'p50', expression: amount, percentile: 50 },
  { type: 'PERCENTILE', alias: 'p75', expression: amount, percentile: 75 },
  { type: 'NUMERIC', alias: 'high', function: 'MAX', expression: amount },
];

/** A field's four numbers: what a candlestick draws (N1). */
const four: AnalysisMetric[] = [
  { type: 'FIRST', alias: 'open', field: 'amount' },
  { type: 'NUMERIC', alias: 'top', function: 'MAX', expression: amount },
  { type: 'NUMERIC', alias: 'bottom', function: 'MIN', expression: amount },
  { type: 'LAST', alias: 'close', field: 'amount' },
];

/** A day bucket: what a calendar lays out (D41). */
const day: AnalysisGroup = {
  type: 'DATE_HISTOGRAM',
  field: 'createdAt',
  alias: 'day',
  unit: 'DAY',
};

const GROUPS: AnalysisGroup[][] = [
  [],
  [day],
  [day, terms('warehouse')],
  [terms('warehouse')],
  [month],
  [terms('warehouse'), terms('status')],
  [terms('warehouse'), month],
  [terms('warehouse'), terms('status'), terms('region')],
];
const METRICS: AnalysisMetric[][] = [
  [count],
  [average],
  [count, sum],
  [count, average],
  [count, sum, average],
  [latest],
  [count, latest],
  [latest, sum, average],
  // A lead that does not add up beside one that does: a funnel takes the
  // second (`chart.funnel.not-additive`).
  [average, count],
  [average, sum, latest],
  // A box's five numbers, alone and beside a count; four of them are no box.
  five,
  [count, ...five],
  four,
  [count, ...four],
  five.slice(0, 4),
];

/**
 * The rows a result of these groups could answer with: one per value, each
 * group keyed by it — text for a category, a bucket's start for a date, as
 * a source hands them back.
 */
function rowsOf(groups: AnalysisGroup[], values: readonly string[]) {
  return values.map((value, index) =>
    Object.fromEntries(
      groups.map(group => [
        group.alias,
        group.type === 'TERMS' ? value : Date.UTC(2026, index, 1),
      ]),
    ),
  );
}

/** Two groups, one group, and two whose values are none of them text. */
const ROWS: readonly (readonly string[])[] = [['a', 'b'], ['a']];

/** The stages a saved funnel names: two, one, one listed twice, none. */
const ORDERS: readonly (readonly string[])[] = [
  ['a', 'b'],
  ['a'],
  ['a', 'a'],
  [],
];

/**
 * The chart the panel would draw on picking `type` for this shape: its
 * slots filled the way `fitChartSlots` fills them, and — a funnel whose
 * stages are group values names them from the rows on the pick — the
 * stages those rows give, as the result block supplies them.
 */
function drawn(
  type: ChartType,
  groups: AnalysisGroup[],
  metrics: AnalysisMetric[],
  rows: readonly Record<string, unknown>[],
): ChartSpec {
  const chart = fitChartSlots({ type }, groups, metrics, MOMENTS);
  return withStagesFrom(chart, rows);
}

/** Whether a valid chart has a mark to draw: bars with no series have none. */
function measures(chart: ChartSpec): boolean {
  return chart.cartesian === undefined ||
    CHART_FAMILY[chart.type] !== 'cartesian'
    ? true
    : chart.cartesian.series.length > 0;
}

describe('chartFamilies', () => {
  /**
   * The picker offers a type, the panel fills its slots, admission checks
   * them: three readings of one rule. Before the family table the first
   * and the last were written apart and had drifted — bars were offered
   * for three dimensions and a scatter for two, and either, once picked,
   * refused to draw (`chart.group.unconsumed`).
   */
  it('one rule, read forward and after the fact', () => {
    const drift: string[] = [];
    for (const groups of GROUPS)
      for (const metrics of METRICS)
        for (const values of ROWS) {
          const rows = rowsOf(groups, values);
          const fits = fitCharts({ groups, metrics, moments: MOMENTS, rows });
          for (const type of CHART_TYPES) {
            const chart = drawn(type, groups, metrics, rows);
            const errors = validateChart(
              analysisConfig({
                groups,
                metrics: metrics as [AnalysisMetric, ...AnalysisMetric[]],
                chart,
              }),
              MOMENTS,
            ).filter(issue => issue.severity === 'error');
            // Valid is not drawn: a shape with nothing to measure keeps a
            // valid chart that measures nothing (`fitChartSlots`), which the
            // result block shows as its table.
            const draws =
              chart.type === type && errors.length === 0 && measures(chart);
            if (draws !== fits[type].available)
              drift.push(
                `${groups.map(group => group.alias).join('+') || '∅'} (${values.length} rows) × ${metrics
                  .map(metric => metric.alias)
                  .join(
                    '+',
                  )} → ${type}: offered ${fits[type].available}, draws ${draws}`,
              );
          }
        }
    expect(drift).toEqual([]);
  });

  /**
   * Before anything ran there are no rows: a saved chart that is refused
   * runs nothing, and picking a type is how it is repaired. The picker then
   * reads the draft's shape and the stages its chart already names, and a
   * pick is fitted to that shape — offered exactly when that draws.
   */
  it('one rule, before anything ran', () => {
    const drift: string[] = [];
    for (const groups of GROUPS)
      for (const metrics of METRICS)
        for (const order of ORDERS) {
          const saved: ChartSpec = {
            type: 'funnel',
            funnel: {
              stages: {
                from: 'group',
                category: groups[0]?.alias ?? '',
                value: metrics[0].alias,
                order: [...order],
              },
            },
          };
          const fits = fitCharts({
            groups,
            metrics,
            moments: MOMENTS,
            chart: saved,
          });
          for (const type of CHART_TYPES) {
            const chart = fitChartSlots(
              switchChartType(saved, type),
              groups,
              metrics,
              MOMENTS,
            );
            const errors = validateChart(
              analysisConfig({
                groups,
                metrics: metrics as [AnalysisMetric, ...AnalysisMetric[]],
                chart,
              }),
              MOMENTS,
            ).filter(issue => issue.severity === 'error');
            const draws =
              chart.type === type && errors.length === 0 && measures(chart);
            if (draws !== fits[type].available)
              drift.push(
                `${groups.map(group => group.alias).join('+') || '∅'} [${order.join(',')}] × ${metrics
                  .map(metric => metric.alias)
                  .join(
                    '+',
                  )} → ${type}: offered ${fits[type].available}, draws ${draws}`,
              );
          }
        }
    expect(drift).toEqual([]);
  });

  it('greys every chart but the levelled ones from three dimensions up, and recommends none (D20, D41)', () => {
    const fits = fitCharts({
      groups: [terms('warehouse'), terms('status'), terms('region')],
      metrics: [count],
    });
    for (const type of ['bar', 'line', 'area', 'combo'] as const)
      expect(fits[type].reason).toBe('chart.fit.too-many-dimensions');
    // A sunburst, a tree and a sankey read each dimension as a level.
    expect(
      Object.entries(fits)
        .filter(([, fit]) => fit.available)
        .map(([type]) => type),
    ).toEqual(['sunburst', 'tree', 'sankey']);
    expect(Object.values(fits).some(fit => fit.recommended)).toBe(false);
  });

  it('plots a scatter per value of exactly one dimension', () => {
    const two = fitCharts({
      groups: [terms('warehouse'), month],
      metrics: [count, sum],
    });
    expect(two.scatter.reason).toBe('chart.fit.needs-one-dimension');
    expect(
      fitCharts({ groups: [terms('warehouse')], metrics: [count, sum] }).scatter
        .available,
    ).toBe(true);
  });

  it('lays out each family’s options once, for every type in it', () => {
    for (const type of CHART_TYPES)
      expect(optionTabs(type)).toBe(CHART_FAMILIES[CHART_FAMILY[type]].tabs);
    expect(familyOf('combo')).toBe(CHART_FAMILIES.cartesian);
    // A legend is worth placing where series or slices are told apart by
    // colour; values on the marks fit wherever a mark has room for one.
    expect(
      Object.entries(CHART_FAMILIES)
        .filter(([, traits]) => traits.legend)
        .map(([family]) => family),
    ).toEqual(['cartesian', 'pie', 'radar', 'parallel', 'themeRiver']);
    expect(
      Object.entries(CHART_FAMILIES)
        .filter(([, traits]) => traits.labels)
        .map(([family]) => family),
    ).toEqual(['cartesian', 'pie', 'heatmap', 'waterfall']);
    expect(optionTabs('table')).toEqual(['display']);
  });
});

describe('valueLabelsOn', () => {
  it('writes a bar’s values unasked, and no line’s, area’s or other family’s', () => {
    expect(valueLabelsOn({ type: 'bar' })).toBe(true);
    // A number on every point drowned the line (audit P1-3).
    expect(valueLabelsOn({ type: 'line' })).toBe(false);
    expect(valueLabelsOn({ type: 'area' })).toBe(false);
    expect(valueLabelsOn({ type: 'pie' })).toBe(false);
    expect(valueLabelsOn({ type: 'heatmap' })).toBe(false);
    // A waterfall's steps are bars, and write their changes as bars do; a
    // treemap's tiles carry their names and numbers whatever it says.
    expect(valueLabelsOn({ type: 'waterfall' })).toBe(true);
    expect(valueLabelsOn({ type: 'waterfall', labels: false })).toBe(false);
    expect(valueLabelsOn({ type: 'treemap', labels: true })).toBe(false);
    expect(valueLabelsOn(undefined)).toBe(false);
  });

  it('asks a combo mark by mark, and the chart by whether any writes', () => {
    const combo: ChartSpec = {
      type: 'combo',
      cartesian: {
        x: 'wh',
        series: [
          { metric: 'orders', type: 'bar' },
          { metric: 'total', type: 'line' },
        ],
      },
    };
    expect(valueLabelsOn(combo, 'bar')).toBe(true);
    expect(valueLabelsOn(combo, 'line')).toBe(false);
    expect(valueLabelsOn(combo)).toBe(true);
    // A combo of lines alone writes nothing unasked.
    expect(
      valueLabelsOn({
        ...combo,
        cartesian: {
          x: 'wh',
          series: [{ metric: 'total', type: 'line' }],
        },
      }),
    ).toBe(false);
    // Asked for, every mark writes; turned off, none does.
    expect(valueLabelsOn({ ...combo, labels: true }, 'line')).toBe(true);
    expect(valueLabelsOn({ ...combo, labels: false }, 'bar')).toBe(false);
  });

  it('takes the analyst’s word, but never for a family that cannot write them', () => {
    expect(valueLabelsOn({ type: 'bar', labels: false })).toBe(false);
    expect(valueLabelsOn({ type: 'line', labels: true })).toBe(true);
    expect(valueLabelsOn({ type: 'pie', labels: true })).toBe(true);
    expect(valueLabelsOn({ type: 'scatter', labels: true })).toBe(false);
  });
});

describe('seriesMark and chartMarks', () => {
  it('draws a combo’s series as each names, and every other type’s alike', () => {
    expect(seriesMark('combo', { type: 'line' })).toBe('line');
    expect(seriesMark('combo', {})).toBe('bar');
    expect(seriesMark('area', { type: 'line' })).toBe('area');
    expect(seriesMark('bar')).toBe('bar');
    expect(chartMarks({ type: 'line' })).toEqual(['line']);
    expect(chartMarks({ type: 'pie' })).toEqual([]);
    expect(
      chartMarks({
        type: 'combo',
        cartesian: {
          x: 'wh',
          series: [{ metric: 'a', type: 'area' }, { metric: 'b' }],
        },
      }),
    ).toEqual(['area', 'bar']);
    expect(chartMarks({ type: 'combo' })).toEqual(['bar']);
  });
});
