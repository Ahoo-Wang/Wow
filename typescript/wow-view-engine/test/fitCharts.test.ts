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
import { chartPickerGroups, fitCharts } from '../src/analysis/index.js';
import type {
  AnalysisGroup,
  AnalysisMetric,
  ChartType,
  RecordData,
} from '../src/model/index.js';

const warehouse: AnalysisGroup = {
  type: 'TERMS',
  field: 'warehouse',
  alias: 'warehouse',
};
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

const available = (fits: ReturnType<typeof fitCharts>) =>
  Object.entries(fits)
    .filter(([, fit]) => fit.available)
    .map(([type]) => type)
    .sort();
const recommended = (fits: ReturnType<typeof fitCharts>) =>
  Object.entries(fits).find(([, fit]) => fit.recommended)?.[0];

describe('a funnel’s fit', () => {
  const funnelOf = (groups: AnalysisGroup[], rows?: RecordData[]) =>
    fitCharts({ groups, metrics: [count], ...(rows ? { rows } : {}) }).funnel;

  /**
   * Picking a funnel fills its stages from the rows (`withStagesFrom`), so
   * the rows decide whether it has the two a funnel needs. Judged on the
   * shape alone, a result of one group was offered a funnel, and picking it
   * drew nothing but 「漏斗至少要有两个阶段」 (the 2026-09-23 audit).
   */
  it('counts the stages the rows give it', () => {
    expect(funnelOf([warehouse], [{ warehouse: 'CN' }])).toEqual({
      available: false,
      reason: 'chart.fit.needs-two-stages',
    });
    // A value twice over is still one stage.
    expect(
      funnelOf([warehouse], [{ warehouse: 'CN' }, { warehouse: 'CN' }]).reason,
    ).toBe('chart.fit.needs-two-stages');
    expect(
      funnelOf([warehouse], [{ warehouse: 'CN' }, { warehouse: 'JP' }])
        .available,
    ).toBe(true);
    // No rows yet is no answer yet: the shape alone is judged.
    expect(funnelOf([warehouse]).available).toBe(true);
    expect(funnelOf([warehouse], []).available).toBe(true);
  });

  it('takes stages from a category only', () => {
    // A date bucket is a scale, not steps: greyed with or without rows.
    expect(funnelOf([month]).reason).toBe('chart.fit.needs-category');
    expect(funnelOf([month], [{ month: 1 }, { month: 2 }]).reason).toBe(
      'chart.fit.needs-category',
    );
    // A category whose values are none of them text cannot be ordered by
    // name, which is how a stage is read back.
    expect(
      funnelOf([warehouse], [{ warehouse: 1 }, { warehouse: 2 }]).reason,
    ).toBe('chart.fit.needs-category');
  });

  it('keeps the funnel of metrics, which reads one row', () => {
    expect(
      fitCharts({ groups: [], metrics: [count, sum], rows: [{}] }).funnel
        .available,
    ).toBe(true);
  });

  /**
   * A funnel is how many entered and how many remained, and its conversion
   * one stage over another: of averages, distinct counts, percentiles or
   * extremes it means nothing. Offered only with something that adds up —
   * one for a funnel over a dimension, two for one of metric stages.
   */
  it('counts only what adds up', () => {
    const rows = [{ warehouse: 'CN' }, { warehouse: 'JP' }];
    const over = (metrics: AnalysisMetric[]) =>
      fitCharts({ groups: [warehouse], metrics, rows }).funnel;
    expect(over([average])).toEqual({
      available: false,
      reason: 'chart.fit.needs-additive',
    });
    // The lead is not additive but another metric is: the funnel takes it.
    expect(over([average, count]).available).toBe(true);
    expect(over([average, sum]).available).toBe(true);

    const staged = (metrics: AnalysisMetric[]) =>
      fitCharts({ groups: [], metrics }).funnel;
    expect(staged([count, average]).reason).toBe('chart.fit.needs-additive');
    expect(staged([count, average, sum]).available).toBe(true);
  });

  /**
   * Before anything ran — a saved funnel refused, so it never ran — there
   * are no rows to fill stages from, and a funnel over the dimension has
   * the stages its chart already names, and no others.
   */
  it('reads the stages a chart names while no rows are known', () => {
    const named = (order: string[]) =>
      fitCharts({
        groups: [warehouse],
        metrics: [count],
        chart: {
          type: 'bar',
          funnel: {
            stages: {
              from: 'group',
              category: 'warehouse',
              value: 'orders',
              order,
            },
          },
        },
      }).funnel;
    expect(named(['CN', 'JP']).available).toBe(true);
    expect(named(['CN']).reason).toBe('chart.fit.needs-two-stages');
    expect(named(['CN', 'CN']).reason).toBe('chart.fit.needs-two-stages');
    // A chart that names no funnel names no stages.
    expect(
      fitCharts({
        groups: [warehouse],
        metrics: [count],
        chart: { type: 'bar' },
      }).funnel.reason,
    ).toBe('chart.fit.needs-two-stages');
    // Rows, when there are any, are what the stages are filled from.
    expect(
      fitCharts({
        groups: [warehouse],
        metrics: [count],
        rows: [{ warehouse: 'CN' }, { warehouse: 'JP' }],
        chart: { type: 'bar' },
      }).funnel.available,
    ).toBe(true);
  });
});

describe('fitCharts', () => {
  it('reads a bare number as a card, and greys everything that needs an axis', () => {
    const fits = fitCharts({ groups: [], metrics: [count] });
    // The card and the gauge: one number, and one number on a scale.
    expect(available(fits)).toEqual(['gauge', 'metric']);
    expect(recommended(fits)).toBe('metric');
    expect(fits.bar.reason).toBe('chart.fit.needs-dimension');
    expect(fits.heatmap.reason).toBe('chart.fit.needs-two-dimensions');
    // Two metrics with no dimension are a funnel of stages.
    expect(
      fitCharts({ groups: [], metrics: [count, sum] }).funnel.available,
    ).toBe(true);
  });

  it('recommends bars for one dimension, a line for a date, and says what the rest lack', () => {
    const byWarehouse = fitCharts({ groups: [warehouse], metrics: [count] });
    expect(recommended(byWarehouse)).toBe('bar');
    expect(available(byWarehouse)).toEqual(
      [
        'area',
        'bar',
        'combo',
        'funnel',
        'line',
        'pie',
        'treemap',
        'waterfall',
      ].sort(),
    );
    expect(byWarehouse.scatter.reason).toBe('chart.fit.needs-two-metrics');
    expect(byWarehouse.heatmap.reason).toBe('chart.fit.needs-two-dimensions');
    expect(byWarehouse.metric.reason).toBe('chart.fit.needs-no-dimension');

    const byMonth = fitCharts({ groups: [month], metrics: [count] });
    expect(recommended(byMonth)).toBe('line');
    // A count over months can be a card with a sparkline.
    expect(byMonth.metric.available).toBe(true);
    // An average cannot: the card would have to add the months up.
    expect(
      fitCharts({ groups: [month], metrics: [average] }).metric.available,
    ).toBe(false);
  });

  it('opens the heatmap for two dimensions and closes the pie', () => {
    const fits = fitCharts({ groups: [warehouse, month], metrics: [count] });
    expect(fits.heatmap.available).toBe(true);
    expect(fits.pie.reason).toBe('chart.fit.needs-one-dimension');
    expect(fits.funnel.reason).toBe('chart.fit.needs-one-dimension');
    expect(recommended(fits)).toBe('bar');
  });

  it('opens the scatter once there are two metrics to plot against each other', () => {
    expect(
      fitCharts({ groups: [warehouse], metrics: [count, average] }).scatter
        .available,
    ).toBe(true);
  });

  it('never recommends a type the same shape greys out', () => {
    // `fitCharts` used to ask whether its own recommendation was available
    // before marking it, a branch no shape could take: `recommend` answers
    // `metric` only with no dimension, which is the card's own condition,
    // and `line` or `bar` only with at least one, which is the cartesian
    // family's. The branch is gone; the rule it stood for is here, where
    // the day the two stop agreeing it fails out loud instead of silently
    // leaving every tile unmarked.
    const shapes: { groups: AnalysisGroup[]; metrics: AnalysisMetric[] }[] = [
      { groups: [], metrics: [count] },
      { groups: [], metrics: [average] },
      { groups: [], metrics: [count, average] },
      { groups: [warehouse], metrics: [count] },
      { groups: [warehouse], metrics: [average] },
      { groups: [month], metrics: [count] },
      { groups: [month], metrics: [average] },
      { groups: [warehouse, month], metrics: [count, average] },
    ];
    for (const shape of shapes) {
      const fits = fitCharts(shape);
      const best = recommended(fits);
      expect(best).toBeDefined();
      expect(fits[best as ChartType].available).toBe(true);
      // And exactly one tile wears it, so the picker has one thing to mark.
      expect(Object.values(fits).filter(fit => fit.recommended)).toHaveLength(
        1,
      );
    }
  });
});

describe('the two charts that add their numbers up', () => {
  /**
   * A waterfall steps along one dimension and adds its steps into a
   * running total; a treemap tiles one dimension, or nests a second in it,
   * and its tiles are parts of a whole. Both refuse what does not add up,
   * saying what they lack, as a funnel does (D33 Q55).
   */
  it('opens a waterfall on one dimension and a count or a sum', () => {
    expect(fitCharts({ groups: [month], metrics: [sum] }).waterfall).toEqual({
      available: true,
    });
    expect(fitCharts({ groups: [], metrics: [count] }).waterfall.reason).toBe(
      'chart.fit.needs-one-dimension',
    );
    expect(
      fitCharts({ groups: [warehouse, month], metrics: [count] }).waterfall
        .reason,
    ).toBe('chart.fit.needs-one-dimension');
    expect(
      fitCharts({ groups: [warehouse], metrics: [average] }).waterfall.reason,
    ).toBe('chart.fit.needs-additive');
    // An average beside a count: the count is the one it steps by.
    expect(
      fitCharts({ groups: [warehouse], metrics: [average, count] }).waterfall
        .available,
    ).toBe(true);
  });

  it('opens a treemap on one or two dimensions and a count or a sum', () => {
    expect(
      fitCharts({ groups: [warehouse], metrics: [count] }).treemap,
    ).toEqual({ available: true });
    expect(
      fitCharts({ groups: [warehouse, month], metrics: [sum] }).treemap,
    ).toEqual({ available: true });
    expect(fitCharts({ groups: [], metrics: [count] }).treemap.reason).toBe(
      'chart.fit.needs-dimension',
    );
    expect(
      fitCharts({ groups: [warehouse, month, warehouse], metrics: [count] })
        .treemap.reason,
    ).toBe('chart.fit.too-many-dimensions');
    expect(
      fitCharts({ groups: [warehouse], metrics: [average] }).treemap.reason,
    ).toBe('chart.fit.needs-additive');
  });
});

describe('chartPickerGroups', () => {
  /**
   * The picker's two groups (D33 Q54) are the fit and nothing else: what
   * draws the result first, the table last among them, and every greyed
   * type under 「其他图型」 — each group in the picker's order.
   */
  it('puts what draws the result first, the table last among it', () => {
    const groups = chartPickerGroups(
      fitCharts({ groups: [warehouse], metrics: [count] }),
    );
    expect(groups.suits).toEqual([
      'bar',
      'line',
      'area',
      'combo',
      'waterfall',
      'pie',
      'treemap',
      'funnel',
      'table',
    ]);
    expect(groups.others).toEqual([
      'themeRiver',
      'sunburst',
      'tree',
      'sankey',
      'heatmap',
      'calendar',
      'scatter',
      'boxplot',
      'radar',
      'parallel',
      'metric',
      'gauge',
    ]);
  });

  it('holds the table alone where no chart draws the result', () => {
    const none = chartPickerGroups(
      fitCharts({
        groups: [warehouse, month, { ...warehouse, alias: 'again' }],
        metrics: [average],
      }),
    );
    expect(none.suits).toEqual(['table']);
    expect(none.others).toHaveLength(20);
  });
});
