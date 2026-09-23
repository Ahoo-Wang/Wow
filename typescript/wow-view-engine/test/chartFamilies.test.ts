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
  validateChart,
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

const GROUPS: AnalysisGroup[][] = [
  [],
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

  it('greys every chart from three dimensions up, and recommends none (D20)', () => {
    const fits = fitCharts({
      groups: [terms('warehouse'), terms('status'), terms('region')],
      metrics: [count],
    });
    for (const type of ['bar', 'line', 'area', 'combo'] as const)
      expect(fits[type].reason).toBe('chart.fit.too-many-dimensions');
    expect(Object.values(fits).some(fit => fit.available)).toBe(false);
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
    ).toEqual(['cartesian', 'pie']);
    expect(
      Object.entries(CHART_FAMILIES)
        .filter(([, traits]) => traits.labels)
        .map(([family]) => family),
    ).toEqual(['cartesian', 'pie', 'heatmap']);
    expect(optionTabs('table')).toEqual(['display']);
  });
});
