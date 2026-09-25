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
  fitChartSlots,
  shapeChart,
  validateChart,
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type MetricCardData,
  type MetricCardSpec,
  type RecordData,
} from '../src/index.js';

/**
 * A metric card's trend over a ratio of sums (D38): 客单价 = GMV ÷ 订单数.
 * Each bucket's ratio is its own sums divided — the source computes a
 * derived metric per row — and the whole is the whole's sums divided, never
 * the ratios added up or averaged. So a derived metric over metrics that
 * add draws a trend as a sum does; one over an average still does not.
 */

const DAY: AnalysisGroup = {
  type: 'DATE_HISTOGRAM',
  field: 'createdAt',
  alias: 'day',
  unit: 'DAY',
};
const ORDERS: AnalysisMetric = { type: 'COUNT', alias: 'orders' };
const GMV: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'gmv',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'amount' },
};
const AVERAGE: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'average',
  function: 'AVG',
  expression: { type: 'FIELD', field: 'amount' },
};
const quotient = (alias: string, over: string, under: string) =>
  ({
    type: 'DERIVED',
    alias,
    expression: {
      type: 'BINARY',
      operator: 'DIVIDE',
      left: { type: 'METRIC_REF', metric: over },
      right: { type: 'METRIC_REF', metric: under },
    },
  }) as const satisfies AnalysisMetric;
const AOV = quotient('aov', 'gmv', 'orders');
/** A ratio of a ratio still reads off the sums underneath it. */
const PER_HUNDRED: AnalysisMetric = {
  type: 'DERIVED',
  alias: 'perHundred',
  expression: {
    type: 'BINARY',
    operator: 'MULTIPLY',
    left: { type: 'METRIC_REF', metric: 'aov' },
    right: { type: 'CONSTANT', value: 100 },
  },
};

const day = (n: number) => Date.UTC(2026, 8, n);

function card(
  spec: Partial<MetricCardSpec> = {},
  metrics: [AnalysisMetric, ...AnalysisMetric[]] = [GMV, ORDERS, AOV],
): AnalysisViewConfig {
  return {
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    kind: 'analysis',
    groups: [DAY],
    metrics,
    sort: [],
    limit: 100,
    layout: 'chart',
    table: { columns: [] },
    chart: {
      type: 'metric',
      metric: { metric: 'aov', trend: { x: 'day' }, ...spec },
    },
  };
}

/** Each day's ratio as the source computes it: its own sums divided. */
const ROWS: RecordData[] = [
  { day: day(20), gmv: 1000, orders: 10, aov: 100 },
  { day: day(21), gmv: 3000, orders: 20, aov: 150 },
  { day: day(22), gmv: 1200, orders: 4, aov: 300 },
];

const shape = (config: AnalysisViewConfig, totals?: RecordData) =>
  shapeChart(config, ROWS, totals, { timeZone: 'UTC' }) as MetricCardData;

describe('a metric card’s trend over a ratio of sums', () => {
  it('is admitted, headline and comparison alike', () => {
    expect(validateChart(card())).toEqual([]);
    expect(
      validateChart(
        card({ metric: 'gmv', compare: { metric: 'aov', mode: 'delta' } }),
      ),
    ).toEqual([]);
    // Over a ratio of a ratio of sums too.
    expect(
      validateChart(
        card({ metric: 'perHundred' }, [GMV, ORDERS, AOV, PER_HUNDRED]),
      ),
    ).toEqual([]);
  });

  it('is still refused over a ratio with an average under it', () => {
    const skewed = quotient('skewed', 'average', 'orders');
    expect(
      validateChart(card({ metric: 'skewed' }, [AVERAGE, ORDERS, skewed])).map(
        found => found.code,
      ),
    ).toEqual(['chart.metric.trend-not-additive']);
  });

  it('headlines the last day’s own ratio, and its change from the day before', () => {
    const data = shape(card());
    expect(data.value).toBe(300);
    expect(data.period?.change).toEqual({ delta: 150, ratio: 1 });
    expect(data.trend?.map(point => point.value)).toEqual([100, 150, 300]);
  });

  it('reads the whole as the sums divided, never the ratios added or averaged', () => {
    const whole = card({ trend: { x: 'day', headline: 'whole' } });
    // With the totals row, the source's own whole.
    expect(shape(whole, { gmv: 5200, orders: 34, aov: 5200 / 34 }).value).toBe(
      5200 / 34,
    );
    // Without it, the sums the buckets add up to, divided: 5200 ÷ 34, not
    // 550 (the ratios added) nor 183.33 (their mean).
    expect(shape(whole).value).toBeCloseTo(5200 / 34, 10);
    // A ratio of a ratio, the same way down.
    expect(
      shape(
        card({ metric: 'perHundred', trend: { x: 'day', headline: 'whole' } }, [
          GMV,
          ORDERS,
          AOV,
          PER_HUNDRED,
        ]),
      ).value,
    ).toBeCloseTo((5200 / 34) * 100, 8);
  });

  it('keeps the trend when the card is fitted to its shape', () => {
    const fitted = fitChartSlots(card().chart, [DAY], [GMV, ORDERS, AOV]);
    expect(fitted.type).toBe('metric');
    expect(fitted.metric).toMatchObject({ metric: 'aov', trend: { x: 'day' } });
  });
});
