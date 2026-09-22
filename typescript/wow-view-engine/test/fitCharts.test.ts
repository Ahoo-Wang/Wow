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
import { fitCharts } from '../src/analysis/index.js';
import type {
  AnalysisGroup,
  AnalysisMetric,
  ChartType,
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

describe('fitCharts', () => {
  it('reads a bare number as a card, and greys everything that needs an axis', () => {
    const fits = fitCharts({ groups: [], metrics: [count] });
    expect(available(fits)).toEqual(['metric']);
    expect(recommended(fits)).toBe('metric');
    expect(fits.bar.reason).toBe('chart.fit.needs-dimension');
    expect(fits.heatmap.reason).toBe('chart.fit.needs-two-dimensions');
    // Two metrics with no dimension are a funnel of stages.
    expect(
      fitCharts({ groups: [], metrics: [count, average] }).funnel.available,
    ).toBe(true);
  });

  it('recommends bars for one dimension, a line for a date, and says what the rest lack', () => {
    const byWarehouse = fitCharts({ groups: [warehouse], metrics: [count] });
    expect(recommended(byWarehouse)).toBe('bar');
    expect(available(byWarehouse)).toEqual(
      ['area', 'bar', 'combo', 'funnel', 'line', 'pie'].sort(),
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
