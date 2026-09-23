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
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type CartesianData,
  type ChartData,
  type ChartSpec,
  type HeatmapData,
  type MetricCardData,
  type RecordData,
} from '../src/index.js';

/**
 * What the chart projection puts where the rows have nothing: a group of no
 * records is 0 for a metric that adds and nothing for one that does not,
 * and a time axis runs through every bucket between its first and its last
 * (chart audit 2026-09-23, P0-3 and P0-4).
 */

const GROUPS: Record<string, AnalysisGroup> = {
  wh: { type: 'TERMS', field: 'warehouse', alias: 'wh' },
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
  groups: AnalysisGroup[],
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

describe('shapeChart', () => {
  /**
   * A warehouse with no cancelled orders drew as "no data": a stacked area
   * of lone dots floating at the stack's height, where the answer was 0
   * (chart audit P0-3).
   */
  describe('a split fills the combinations it lacks', () => {
    const split = (
      metric: AnalysisMetric,
      overrides: Partial<AnalysisViewConfig> = {},
    ): AnalysisViewConfig => ({
      ...config(
        {
          type: 'area',
          cartesian: {
            x: 'wh',
            splitBy: 'status',
            series: [{ metric: metric.alias }],
          },
        },
        [GROUPS.wh, { type: 'TERMS', field: 'status', alias: 'status' }],
        [metric],
      ),
      ...overrides,
    });
    const statusRows: RecordData[] = [
      { wh: 'SH', status: 'open', orders: 3, total: 30, average: 30 },
      { wh: 'SH', status: 'cancelled', orders: 1, total: 10, average: 10 },
      { wh: 'BJ', status: 'open', orders: 2, total: 20, average: 20 },
    ];
    const bj = (data: ChartData | undefined) =>
      (data as CartesianData).points.find(point => point.x === 'BJ')!.values;

    it('with 0 for a metric that adds', () => {
      expect(bj(shapeChart(split(METRICS.orders), statusRows))).toEqual({
        open: 2,
        cancelled: 0,
      });
      expect(bj(shapeChart(split(METRICS.total), statusRows))).toEqual({
        open: 20,
        cancelled: 0,
      });
    });

    // A split value is any text, one an object already answers to included.
    it('by the combinations the rows hold, whatever the split value is called', () => {
      const data = shapeChart(split(METRICS.orders), [
        { wh: 'SH', status: 'toString', orders: 3 },
        { wh: 'BJ', status: 'open', orders: 2 },
      ]) as CartesianData;
      expect(data.points.map(point => point.values)).toEqual([
        { toString: 3, open: 0 },
        { toString: 0, open: 2 },
      ]);
    });

    it('with nothing for a metric that does not: an average of no rows is no number', () => {
      expect(bj(shapeChart(split(METRICS.average), statusRows))).toEqual({
        open: 20,
        cancelled: null,
      });
    });

    it('with nothing where 「只保留」 may have dropped it', () => {
      const kept = split(METRICS.orders, {
        having: {
          type: 'CONDITION',
          metric: 'orders',
          operator: 'GT',
          value: 1,
        },
      });
      expect(bj(shapeChart(kept, statusRows)).cancelled).toBeNull();
    });

    it('with nothing where the limit may have cut it, unless the sort puts the cut elsewhere', () => {
      // Three rows at a limit of three: more groups may exist.
      const byCount = split(METRICS.orders, {
        limit: 3,
        sort: [{ alias: 'orders', direction: 'DESC' }],
      });
      expect(bj(shapeChart(byCount, statusRows)).cancelled).toBeNull();

      // Sorted by warehouse first, the limit cuts inside the last warehouse
      // the rows reach at most; every one before it is whole.
      const byWarehouse = split(METRICS.orders, {
        limit: 2,
        sort: [{ alias: 'wh', direction: 'ASC' }],
      });
      const cut = shapeChart(byWarehouse, [
        { wh: 'BJ', status: 'open', orders: 2 },
        { wh: 'SH', status: 'cancelled', orders: 1 },
      ]) as CartesianData;
      expect(cut.points.map(point => point.values)).toEqual([
        { open: 2, cancelled: 0 },
        { open: null, cancelled: 1 },
      ]);
    });
  });

  /**
   * 「每日重试成功」 ran 8/20 straight into 8/23: a category axis of the days
   * that had rows, so the bars read as consecutive days and the line
   * smoothed three days of zero into a slope (chart audit P0-4).
   */
  describe('a date axis runs without holes', () => {
    const day = (n: number) => Date.UTC(2026, 8, n);
    const daily = (
      overrides: Partial<
        Extract<AnalysisGroup, { type: 'DATE_HISTOGRAM' }>
      > = {},
    ): AnalysisGroup => ({
      type: 'DATE_HISTOGRAM',
      field: 'createdAt',
      alias: 'day',
      unit: 'DAY',
      timeZone: 'UTC',
      ...overrides,
    });
    const line = (
      group: AnalysisGroup = daily(),
      metric: AnalysisMetric = METRICS.orders,
      overrides: Partial<AnalysisViewConfig> = {},
    ): AnalysisViewConfig => ({
      ...config(
        {
          type: 'line',
          cartesian: { x: 'day', series: [{ metric: metric.alias }] },
        },
        [group],
        [metric],
      ),
      ...overrides,
    });
    const xs = (data: ChartData | undefined) =>
      (data as CartesianData).points.map(point => point.x);
    const values = (data: ChartData | undefined, key = 'orders') =>
      (data as CartesianData).points.map(point => point.values[key]);
    const sparse: RecordData[] = [
      { day: day(5), orders: 5, average: 50 },
      { day: day(1), orders: 1, average: 10 },
      { day: day(2), orders: 2, average: 20 },
    ];

    it('fills every missing bucket between the first and the last with 0', () => {
      const data = shapeChart(line(), sparse);
      expect(xs(data)).toEqual([day(1), day(2), day(3), day(4), day(5)]);
      expect(values(data)).toEqual([1, 2, 0, 0, 5]);
    });

    it('leaves a gap for a metric that does not add', () => {
      const data = shapeChart(line(daily(), METRICS.average), sparse);
      expect(values(data, 'average')).toEqual([10, 20, null, null, 50]);
    });

    it('fills every series of a split at a missing bucket', () => {
      const data = shapeChart(
        config(
          {
            type: 'bar',
            cartesian: {
              x: 'day',
              splitBy: 'wh',
              series: [{ metric: 'orders' }],
            },
          },
          [daily(), GROUPS.wh],
        ),
        [
          { day: day(1), wh: 'SH', orders: 1 },
          { day: day(3), wh: 'BJ', orders: 3 },
        ],
      ) as CartesianData;
      expect(data.points).toEqual([
        { x: day(1), values: { SH: 1, BJ: 0 } },
        { x: day(2), values: { SH: 0, BJ: 0 } },
        { x: day(3), values: { SH: 0, BJ: 3 } },
      ]);
    });

    it('keeps the bucket that names no moment after the last one', () => {
      const data = shapeChart(line(), [
        { day: null, orders: 7 },
        { day: day(3), orders: 3 },
        { day: day(1), orders: 1 },
      ]);
      expect(xs(data)).toEqual([day(1), day(2), day(3), null]);
      expect(values(data)).toEqual([1, 0, 3, 7]);
    });

    it('writes a missing bucket as the buckets are written', () => {
      expect(
        xs(
          shapeChart(line(), [
            { day: '2026-09-04', orders: 4 },
            { day: '2026-09-01', orders: 1 },
          ]),
        ),
      ).toEqual(['2026-09-01', '2026-09-02', '2026-09-03', '2026-09-04']);
      expect(
        xs(
          shapeChart(line(daily({ unit: 'HOUR' })), [
            { day: '2026-09-01 10:00', orders: 1 },
            { day: '2026-09-01 12:00', orders: 1 },
          ]),
        ),
      ).toEqual(['2026-09-01 10:00', '2026-09-01 11:00', '2026-09-01 12:00']);
      expect(
        xs(
          shapeChart(line(), [
            { day: String(day(1)), orders: 1 },
            { day: String(day(3)), orders: 3 },
          ]),
        ),
      ).toEqual([String(day(1)), String(day(2)), String(day(3))]);
      const iso = [day(1), day(2), day(3)].map(ms =>
        new Date(ms).toISOString(),
      );
      expect(
        xs(
          shapeChart(line(), [
            { day: iso[0], orders: 1 },
            { day: iso[2], orders: 3 },
          ]),
        ),
      ).toEqual(iso);
      expect(
        xs(
          shapeChart(line(), [
            { day: new Date(day(1)), orders: 1 },
            { day: new Date(day(3)), orders: 3 },
          ]),
        ),
      ).toEqual([day(1), day(2), day(3)].map(ms => new Date(ms)));
    });

    it('steps a calendar unit in the zone the histogram was cut in', () => {
      // Shanghai's month starts, as epoch milliseconds: 16:00 UTC the day before.
      const month = (m: number) => Date.UTC(2026, m - 1, 1) - 8 * 3_600_000;
      const monthly = daily({ unit: 'MONTH', timeZone: undefined });
      const rows = [
        { day: month(1), orders: 1 },
        { day: month(4), orders: 4 },
      ];
      const expected = [month(1), month(2), month(3), month(4)];
      // The engine's zone, handed in…
      expect(
        xs(
          shapeChart(line(monthly), rows, undefined, {
            timeZone: 'Asia/Shanghai',
          }),
        ),
      ).toEqual(expected);
      // …or the group's own, which wins over it.
      expect(
        xs(
          shapeChart(
            line(daily({ unit: 'MONTH', timeZone: 'Asia/Shanghai' })),
            rows,
            undefined,
            { timeZone: 'America/New_York' },
          ),
        ),
      ).toEqual(expected);
    });

    it('steps a day across a daylight-saving change as the calendar does', () => {
      // New York's midnights around 8 March 2026, when the clocks go forward.
      const est = (n: number) => Date.UTC(2026, 2, n, 5);
      const edt = (n: number) => Date.UTC(2026, 2, n, 4);
      const data = shapeChart(line(daily({ timeZone: 'America/New_York' })), [
        { day: est(7), orders: 7 },
        { day: edt(10), orders: 10 },
      ]);
      expect(xs(data)).toEqual([est(7), est(8), edt(9), edt(10)]);
    });

    it('fills nothing it cannot place', () => {
      // A bucket off the zone's grid: cut somewhere else, or not a day.
      const off = [
        { day: day(1), orders: 1 },
        { day: day(3) + 3_600_000, orders: 3 },
      ];
      expect(xs(shapeChart(line(), off))).toEqual(off.map(row => row.day));
      // A histogram the source filled itself.
      expect(xs(shapeChart(line(daily({ dense: true })), sparse))).toEqual([
        day(1),
        day(2),
        day(5),
      ]);
      // More buckets than any dense answer could hold.
      const seconds = shapeChart(line(daily({ unit: 'SECOND' })), [
        { day: 0, orders: 1 },
        { day: 20_000_000, orders: 1 },
      ]);
      expect(xs(seconds)).toEqual([0, 20_000_000]);
      // One bucket is no run.
      expect(xs(shapeChart(line(), [{ day: day(1), orders: 1 }]))).toEqual([
        day(1),
      ]);
    });

    it('fills with nothing where the rows are a pick rather than a run', () => {
      // The busiest three days of a longer history: a missing day may be a
      // busy one the limit left out.
      const busiest = line(daily(), METRICS.orders, {
        limit: 3,
        sort: [{ alias: 'orders', direction: 'DESC' }],
      });
      expect(values(shapeChart(busiest, sparse))).toEqual([
        1,
        2,
        null,
        null,
        5,
      ]);
      // The last three days of a longer history, newest first: whole days.
      const latest = line(daily(), METRICS.orders, {
        limit: 3,
        sort: [{ alias: 'day', direction: 'DESC' }],
      });
      const newestFirst = [sparse[0], sparse[2], sparse[1]];
      expect(values(shapeChart(latest, newestFirst))).toEqual([1, 2, 0, 0, 5]);
    });

    it('runs a card’s sparkline and a heatmap’s time axis the same way', () => {
      const card = shapeChart(
        config(
          { type: 'metric', metric: { metric: 'orders', trend: { x: 'day' } } },
          [daily()],
        ),
        sparse,
      ) as MetricCardData;
      expect(card.trend).toEqual([
        { x: day(1), value: 1 },
        { x: day(2), value: 2 },
        { x: day(3), value: 0 },
        { x: day(4), value: 0 },
        { x: day(5), value: 5 },
      ]);
      expect(card.value).toBe(8);

      const matrix = shapeChart(
        config(
          { type: 'heatmap', heatmap: { x: 'wh', y: 'day', value: 'orders' } },
          [GROUPS.wh, daily()],
        ),
        [
          { wh: 'SH', day: day(3), orders: 3 },
          { wh: 'SH', day: day(1), orders: 1 },
        ],
      ) as HeatmapData;
      expect(matrix.ys).toEqual([day(1), day(2), day(3)]);
      // A heatmap draws no group as no cell, a filled bucket included.
      expect(matrix.cells).toEqual([[1], [null], [3]]);
    });
  });
});
