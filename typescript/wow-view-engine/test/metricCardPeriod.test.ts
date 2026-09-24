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
  validateChart,
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type MetricCardData,
  type MetricCardSpec,
  type RecordData,
} from '../src/index.js';

/**
 * A metric card over a trend reads as its last period (user ruling
 * 2026-09-23, audit P1-6): the big number is the last bucket that had
 * ended when the question was asked, beside its change from the one before
 * — Metabase's Trend card. The whole range is the other reading, chosen in
 * the card's options.
 */

const DAY: AnalysisGroup = {
  type: 'DATE_HISTOGRAM',
  field: 'createdAt',
  alias: 'day',
  unit: 'DAY',
};

const ORDERS: AnalysisMetric = { type: 'COUNT', alias: 'orders' };
const TOTAL: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'total',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'amount' },
};

const day = (n: number) => Date.UTC(2026, 8, n);

function card(
  spec: Partial<MetricCardSpec> = {},
  group: AnalysisGroup = DAY,
): AnalysisViewConfig {
  return {
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    kind: 'analysis',
    groups: [group],
    metrics: [ORDERS, TOTAL],
    sort: [],
    limit: 100,
    layout: 'chart',
    table: { columns: [] },
    chart: {
      type: 'metric',
      metric: { metric: 'orders', trend: { x: group.alias }, ...spec },
    },
  };
}

/** Newest first, as a view of the last days is usually sorted. */
const ROWS: RecordData[] = [
  { day: day(22), orders: 12, total: 1200 },
  { day: day(21), orders: 10, total: 900 },
  { day: day(20), orders: 8, total: 700 },
];

const shape = (
  config: AnalysisViewConfig,
  rows: readonly RecordData[] = ROWS,
  context: { now?: Date; timeZone?: string } = { timeZone: 'UTC' },
) => shapeChart(config, rows, undefined, context) as MetricCardData;

describe('a metric card over a trend, read as its last period', () => {
  it('headlines the last day and its change from the day before', () => {
    const data = shape(card());
    expect(data.value).toBe(12);
    expect(data.period).toEqual({
      at: day(22),
      unit: 'DAY',
      previous: { at: day(21), value: 10 },
      change: { delta: 2, ratio: 0.2 },
    });
    expect(data.whole).toBeUndefined();
    // The sparkline still draws every day, earliest first.
    expect(data.trend?.map(point => point.value)).toEqual([8, 10, 12]);
  });

  it('measures a fall against the size of the period before', () => {
    const data = shape(card(), [
      { day: day(22), orders: 5 },
      { day: day(21), orders: 10 },
    ]);
    expect(data.period?.change).toEqual({ delta: -5, ratio: -0.5 });
  });

  it('leaves out a period still under way, and says which', () => {
    // Asked at noon on the 22nd: the 22nd is half a day, and against a
    // whole 21st it would read as a fall every morning.
    const data = shape(card(), ROWS, {
      timeZone: 'UTC',
      now: new Date(Date.UTC(2026, 8, 22, 12)),
    });
    expect(data.value).toBe(10);
    expect(data.period).toMatchObject({
      at: day(21),
      skipped: day(22),
      previous: { at: day(20), value: 8 },
      change: { delta: 2, ratio: 0.25 },
    });
    // It still draws on the sparkline.
    expect(data.trend).toHaveLength(3);

    // A moment after midnight the 22nd is over, and it is the headline.
    const later = shape(card(), ROWS, {
      timeZone: 'UTC',
      now: new Date(Date.UTC(2026, 8, 23)),
    });
    expect(later.period?.at).toBe(day(22));
    expect(later.period?.skipped).toBeUndefined();
  });

  it('reads the period so far when no period has ended', () => {
    const data = shape(card(), [{ day: day(22), orders: 3 }], {
      timeZone: 'UTC',
      now: new Date(Date.UTC(2026, 8, 22, 9)),
    });
    expect(data.value).toBe(3);
    expect(data.period).toEqual({ at: day(22), unit: 'DAY', partial: true });
  });

  it('decides whether a period is over on the engine’s clock', () => {
    // A day written as a wall-clock date names no zone: the 22nd is over
    // once a clock in the engine's zone has passed midnight. At 20:00 UTC
    // that is 04:00 on the 23rd in Shanghai, and still the 22nd in UTC.
    const rows = [
      { day: '2026-09-22', orders: 12 },
      { day: '2026-09-21', orders: 10 },
    ];
    const now = new Date(Date.UTC(2026, 8, 22, 20));
    expect(
      shape(card(), rows, { timeZone: 'Asia/Shanghai', now }).period?.at,
    ).toBe('2026-09-22');
    expect(shape(card(), rows, { timeZone: 'UTC', now }).period?.at).toBe(
      '2026-09-21',
    );
  });

  it('says when there is no period before, or no number in it', () => {
    // The range starts at the headline: nothing to compare.
    const first = shape(card(), [{ day: day(22), orders: 12 }]);
    expect(first.period).toEqual({ at: day(22), unit: 'DAY' });

    // A day before with no number: the change is unknown, not zero.
    const blank = shape(card(), [
      { day: day(22), orders: 12 },
      { day: day(21), orders: null },
    ]);
    expect(blank.period?.change).toBeNull();

    // A day before of zero: the difference is known, the share is not.
    const zero = shape(card(), [
      { day: day(22), orders: 12 },
      { day: day(21), orders: 0 },
    ]);
    expect(zero.period?.change).toEqual({ delta: 12, ratio: null });
  });

  it('reads a quiet day before as 0 where it is known to be empty', () => {
    const data = shape(card(), [
      { day: day(22), orders: 12, total: 1200 },
      { day: day(20), orders: 8, total: 700 },
    ]);
    expect(data.period?.previous).toEqual({ at: day(21), value: 0 });
    expect(data.period?.change).toEqual({ delta: 12, ratio: null });
  });

  it('compares with the period right before, never an earlier one', () => {
    // A dense histogram is the source's to fill, so the kernel does not;
    // the row before the 22nd is the 19th, which is no "previous period".
    const data = shape(card({}, { ...DAY, dense: true }), [
      { day: day(22), orders: 12 },
      { day: day(19), orders: 8 },
    ]);
    expect(data.period).toEqual({ at: day(22), unit: 'DAY' });
  });

  it('compares with another metric and aims at a target over the same period', () => {
    const data = shape(
      card({ compare: { metric: 'total', mode: 'delta' }, target: 20 }),
    );
    expect(data.value).toBe(12);
    // This period's orders against this period's total, not the whole's.
    expect(data.compare).toEqual({ value: 1200, delta: -1188 });
    expect(data.target).toBe(20);
  });

  it('skips a bucket that names no day', () => {
    const data = shape(card(), [{ day: '__missing__', orders: 4 }, ...ROWS]);
    expect(data.period?.at).toBe(day(22));
    const none = shape(card(), [{ day: '__missing__', orders: 4 }]);
    expect(none.value).toBeNull();
    expect(none.period).toBeUndefined();
  });

  it('names a week and a month by the bucket they start', () => {
    const month: AnalysisGroup = { ...DAY, alias: 'month', unit: 'MONTH' };
    const data = shape(card({}, month), [
      { month: Date.UTC(2026, 7, 1), orders: 40 },
      { month: Date.UTC(2026, 8, 1), orders: 30 },
    ]);
    expect(data.period).toMatchObject({
      at: Date.UTC(2026, 8, 1),
      unit: 'MONTH',
      previous: { at: Date.UTC(2026, 7, 1), value: 40 },
    });
  });
});

describe('a metric card over a trend, read as the whole', () => {
  const whole = card({ trend: { x: 'day', headline: 'whole' } });

  it('headlines the ungrouped answer and says it is the whole', () => {
    const data = shapeChart(whole, ROWS, { orders: 99 }) as MetricCardData;
    expect(data.value).toBe(99);
    expect(data.whole).toBe(true);
    expect(data.period).toBeUndefined();
    expect(data.trend).toHaveLength(3);
  });

  it('is admitted as the last-period reading is: over metrics that add', () => {
    expect(validateChart(whole)).toEqual([]);
    expect(validateChart(card())).toEqual([]);
  });

  it('leaves a headline it cannot add up as null', () => {
    // Validation refuses this; without a totals row the shaping still must
    // not invent a number from buckets of an average.
    const average: AnalysisMetric = {
      ...TOTAL,
      alias: 'average',
      function: 'AVG',
    };
    const data = shapeChart(
      {
        ...whole,
        metrics: [average],
        chart: {
          type: 'metric',
          metric: { metric: 'average', trend: { x: 'day', headline: 'whole' } },
        },
      },
      [
        { day: day(21), average: 4 },
        { day: day(22), average: 6 },
      ],
    ) as MetricCardData;
    expect(data.value).toBeNull();
    expect(data.trend).toEqual([
      { x: day(21), value: 4 },
      { x: day(22), value: 6 },
    ]);
  });
});
