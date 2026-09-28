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
  projectAnalysis,
  shapeChart,
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type CartesianData,
  type RecordData,
} from '../src/index.js';
import { OTHER_SERIES_KEY } from '../src/analysis/chartRows.js';
import { analysisConfig, ordersDefinition } from './fixtures.js';

/**
 * The order a split's series read in — the legend, the stack — is the
 * engine's, not the order a source answered the rows in: a Wow service
 * answers groups by key, so a split read in the rows' order was an
 * alphabet, not a ranking. A category split reads the largest first; a
 * split along a scale keeps the scale's order.
 */

const SUM: AnalysisMetric = {
  alias: 'amount',
  type: 'NUMERIC',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'amount' },
};
const AVG: AnalysisMetric = { ...SUM, function: 'AVG' } as AnalysisMetric;

function split(
  splitGroup: AnalysisGroup = {
    alias: 'status',
    field: 'status',
    type: 'TERMS',
  },
  metric: AnalysisMetric = SUM,
  overrides: Partial<AnalysisViewConfig> = {},
): AnalysisViewConfig {
  return analysisConfig({
    groups: [
      { alias: 'warehouse', field: 'warehouse', type: 'TERMS' },
      splitGroup,
    ],
    metrics: [metric],
    layout: 'chart',
    chart: {
      type: 'bar',
      cartesian: {
        x: 'warehouse',
        splitBy: splitGroup.alias,
        series: [{ metric: 'amount' }],
      },
    },
    ...overrides,
  });
}

const labels = (config: AnalysisViewConfig, rows: readonly RecordData[]) =>
  (shapeChart(config, rows) as CartesianData).series.map(entry => entry.label);

describe('a category split reads the largest first', () => {
  // By key, as a Wow service answers them: A, B, C.
  const rows: RecordData[] = [
    { warehouse: 'CN', status: 'A', amount: 1 },
    { warehouse: 'CN', status: 'B', amount: 5 },
    { warehouse: 'CN', status: 'C', amount: 3 },
    { warehouse: 'US', status: 'A', amount: 2 },
    { warehouse: 'US', status: 'C', amount: 4 },
  ];

  it('orders the series by their total, whatever order the rows came in', () => {
    // C 7, B 5, A 3.
    expect(labels(split(), rows)).toEqual(['C', 'B', 'A']);
    expect(labels(split(), [...rows].reverse())).toEqual(['C', 'B', 'A']);
  });

  it('leaves the axis and the table in the rows’ order', () => {
    const view = projectAnalysis(ordersDefinition(), split(), rows);
    const chart = view.chart as CartesianData;
    expect(chart.points.map(point => point.x)).toEqual(['CN', 'US']);
    expect(view.rows.map(row => `${row.warehouse}/${row.status}`)).toEqual(
      rows.map(row => `${row.warehouse}/${row.status}`),
    );
  });

  it('measures a number below the axis by how far it reaches', () => {
    const net: RecordData[] = [
      { warehouse: 'CN', status: 'gain', amount: 3 },
      { warehouse: 'CN', status: 'loss', amount: -8 },
      { warehouse: 'US', status: 'gain', amount: 2 },
      { warehouse: 'US', status: 'loss', amount: -1 },
      { warehouse: 'US', status: 'flat', amount: 0 },
    ];
    expect(labels(split(), net)).toEqual(['loss', 'gain', 'flat']);
  });

  it('ranks a metric that does not add up by its mean, not by how often it turns up', () => {
    const averages: RecordData[] = [
      { warehouse: 'CN', status: 'often', amount: 2 },
      { warehouse: 'US', status: 'often', amount: 2 },
      { warehouse: 'JP', status: 'often', amount: 2 },
      { warehouse: 'JP', status: 'rare', amount: 5 },
    ];
    expect(labels(split(undefined, AVG), averages)).toEqual(['rare', 'often']);
    // Added up, the same numbers rank the other way.
    expect(labels(split(), averages)).toEqual(['often', 'rare']);
  });

  it('folds a metric that does not add up by the same mean it orders by', () => {
    const warehouses = ['CN', 'US', 'JP', 'DE'];
    const rows: RecordData[] = [
      // Seven high averages, each in one warehouse: a mean of 10 … 16, and
      // a sum no larger.
      ...Array.from({ length: 7 }, (_, index) => ({
        warehouse: warehouses[index % warehouses.length],
        status: `high${index}`,
        amount: 10 + index,
      })),
      // Three low averages in every warehouse: a mean of 5, a sum of 20 —
      // the largest totals, which a fold by sum would have kept.
      ...['low0', 'low1', 'low2'].flatMap(status =>
        warehouses.map(warehouse => ({ warehouse, status, amount: 5 })),
      ),
    ];
    const data = shapeChart(split(undefined, AVG), rows, undefined, {
      splitWhole: warehouses.map(warehouse => ({ warehouse, amount: 5 })),
    }) as CartesianData;
    expect(data.series.map(entry => entry.label)).toEqual([
      'high6',
      'high5',
      'high4',
      'high3',
      'high2',
      'high1',
      'high0',
      '',
    ]);
    expect(data.series[data.series.length - 1]).toMatchObject({
      key: OTHER_SERIES_KEY,
      other: true,
    });
  });

  it('keeps series of one size in the order they came', () => {
    const even: RecordData[] = [
      { warehouse: 'CN', status: 'B', amount: 2 },
      { warehouse: 'CN', status: 'A', amount: 2 },
    ];
    expect(labels(split(), even)).toEqual(['B', 'A']);
  });

  it('folds the smallest past the palette, 「其他」 last', () => {
    // s0 … s9 by key, s<i> totalling i + 1.
    const many: RecordData[] = Array.from({ length: 10 }, (_, index) => ({
      warehouse: 'CN',
      status: `s${index}`,
      amount: index + 1,
    }));
    const data = shapeChart(split(), many, undefined, {
      splitWhole: [{ warehouse: 'CN', amount: 55 }],
    }) as CartesianData;
    expect(data.series.map(entry => entry.label)).toEqual([
      's9',
      's8',
      's7',
      's6',
      's5',
      's4',
      's3',
      '',
    ]);
    expect(data.series[data.series.length - 1]?.key).toBe(OTHER_SERIES_KEY);
    // 1 + 2 + 3 folded.
    expect(data.points[0].values[OTHER_SERIES_KEY]).toBe(6);
  });

  it('leaves an unsplit chart’s series in the order the analyst set', () => {
    const config = analysisConfig({
      metrics: [{ alias: 'orders', type: 'COUNT' } as AnalysisMetric, SUM],
      chart: {
        type: 'bar',
        cartesian: {
          x: 'warehouse',
          series: [{ metric: 'orders' }, { metric: 'amount' }],
        },
      },
    });
    expect(
      labels(config, [{ warehouse: 'CN', orders: 1, amount: 100 }]),
    ).toEqual(['orders', 'amount']);
  });
});

describe('a split along a scale keeps the scale’s order', () => {
  it('runs a time split forward, not by size', () => {
    const day = (date: number) => Date.UTC(2026, 8, date);
    const config = split({
      alias: 'day',
      field: 'createdAt',
      type: 'DATE_HISTOGRAM',
      unit: 'DAY',
      timeZone: 'UTC',
    });
    const data = shapeChart(config, [
      { warehouse: 'CN', day: day(3), amount: 9 },
      { warehouse: 'CN', day: day(1), amount: 1 },
      { warehouse: 'CN', day: day(2), amount: 5 },
    ]) as CartesianData;
    expect(data.series.map(entry => entry.value)).toEqual([
      day(1),
      day(2),
      day(3),
    ]);
  });

  it('runs a calendar part along its cycle, not by size', () => {
    const config = split({
      alias: 'weekday',
      field: 'createdAt',
      type: 'DATE_PART',
      part: 'DAY_OF_WEEK',
    } as AnalysisGroup);
    const data = shapeChart(config, [
      { warehouse: 'CN', weekday: 3, amount: 9 },
      { warehouse: 'CN', weekday: 1, amount: 1 },
      { warehouse: 'CN', weekday: 2, amount: 5 },
    ]) as CartesianData;
    expect(data.series.map(entry => entry.value)).toEqual([1, 2, 3]);
  });

  it('runs bands low to high, not by size', () => {
    const config = split({
      alias: 'band',
      field: 'amount',
      type: 'HISTOGRAM',
      interval: 100,
    });
    const data = shapeChart(config, [
      { warehouse: 'CN', band: 200, amount: 1 },
      { warehouse: 'CN', band: 0, amount: 9 },
      { warehouse: 'CN', band: 100, amount: 5 },
      { warehouse: 'CN', band: null, amount: 7 },
    ]) as CartesianData;
    // The records with no value follow the bands.
    expect(data.series.map(entry => entry.value)).toEqual([0, 100, 200, null]);
  });
});
