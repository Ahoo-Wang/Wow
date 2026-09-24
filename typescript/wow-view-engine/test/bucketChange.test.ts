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
  bucketChange,
  shapeChart,
  type AnalysisGroup,
  type AnalysisViewConfig,
  type CartesianData,
  type RecordData,
} from '../src/index.js';
import { consecutive } from '../src/analysis/timeAxis.js';

/**
 * 「较上一期」 in a time axis's tooltip (D33 batch A, Q59): the kernel says
 * whether two points side by side are a bucket and the one before it
 * (`CartesianData.timeline`), and how the number moved between them — from
 * the rows already there, never a second query.
 */

const day = (n: number) => Date.UTC(2026, 8, n);

const daily: AnalysisGroup = {
  type: 'DATE_HISTOGRAM',
  field: 'createdAt',
  alias: 'day',
  unit: 'DAY',
  timeZone: 'UTC',
};

function config(
  groups: AnalysisGroup[],
  missing?: 'gap',
  metric: 'orders' | 'average' = 'orders',
): AnalysisViewConfig {
  return {
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    kind: 'analysis',
    groups,
    metrics: [
      metric === 'orders'
        ? { type: 'COUNT', alias: 'orders' }
        : {
            type: 'NUMERIC',
            alias: 'average',
            function: 'AVG',
            expression: { type: 'FIELD', field: 'amount' },
          },
    ],
    sort: [],
    limit: 100,
    layout: 'chart',
    table: { columns: [] },
    chart: {
      type: 'line',
      cartesian: {
        x: groups[0].alias,
        series: [{ metric }],
        ...(missing ? { missing } : {}),
      },
    },
  };
}

const shape = (
  groups: AnalysisGroup[],
  rows: RecordData[],
  missing?: 'gap',
  metric?: 'orders' | 'average',
) =>
  shapeChart(config(groups, missing, metric), rows, undefined, {
    timeZone: 'UTC',
  }) as CartesianData;

describe('CartesianData.timeline', () => {
  it('marks a time axis whose buckets run one after another', () => {
    const data = shape(
      [daily],
      [
        { day: day(3), orders: 3 },
        { day: day(1), orders: 1 },
      ],
    );
    // The hole on the 2nd is filled, so the axis runs 1, 2, 3.
    expect(data.points.map(point => point.x)).toEqual([day(1), day(2), day(3)]);
    expect(data.timeline).toBe(true);
  });

  it('is absent on a category axis', () => {
    const data = shape(
      [{ type: 'TERMS', field: 'warehouse', alias: 'wh' }],
      [
        { wh: 'SH', orders: 1 },
        { wh: 'BJ', orders: 2 },
      ],
    );
    expect(data.timeline).toBeUndefined();
  });

  it('is absent where a hole could not be placed', () => {
    // Half past noon is no day of this histogram: nothing is filled, and
    // the two points are not a day and the one before it.
    const data = shape(
      [daily],
      [
        { day: day(1), orders: 1 },
        { day: day(3) + 12 * 3_600_000, orders: 3 },
      ],
    );
    expect(data.points).toHaveLength(2);
    expect(data.timeline).toBeUndefined();
  });
});

describe('consecutive', () => {
  it('passes over the missing-value bucket and reads the rest in order', () => {
    const at = (key: unknown) => key;
    expect(consecutive([day(1), day(2), null], at, daily, 'UTC')).toBe(true);
    expect(consecutive([day(1), day(3)], at, daily, 'UTC')).toBe(false);
    expect(consecutive([], at, daily, 'UTC')).toBe(true);
  });
});

describe('bucketChange', () => {
  const data = shape(
    [daily],
    [
      { day: day(1), orders: 4 },
      { day: day(2), orders: 5 },
      { day: day(4), orders: 5 },
      { day: null, orders: 7 },
    ],
  );

  it('says how a bucket moved from the one before it', () => {
    expect(bucketChange(data, 'orders', 1)).toEqual({
      delta: 1,
      ratio: 0.25,
    });
  });

  it('compares with a 0 the kernel filled in, a share of which is none', () => {
    // The 3rd had no orders — known, since a count adds and nothing cut the
    // rows — so the 4th went from 0 to 5.
    expect(data.points[2].filled).toEqual(['orders']);
    expect(bucketChange(data, 'orders', 2)).toEqual({ delta: -5, ratio: -1 });
    expect(bucketChange(data, 'orders', 3)).toEqual({ delta: 5, ratio: null });
  });

  it('says nothing for the first bucket, or around the missing one', () => {
    expect(bucketChange(data, 'orders', 0)).toBeUndefined();
    // The missing-value bucket is last, and no period.
    expect(data.points[4].x).toBeNull();
    expect(bucketChange(data, 'orders', 4)).toBeUndefined();
    expect(bucketChange(data, 'orders', 9)).toBeUndefined();
    expect(bucketChange(data, 'nothing', 1)).toBeUndefined();
  });

  it('says nothing across a hole no number fills', () => {
    const gapped = shape(
      [daily],
      [
        { day: day(1), orders: 4 },
        { day: day(3), orders: 5 },
      ],
      'gap',
    );
    expect(gapped.timeline).toBe(true);
    expect(gapped.points[1].values.orders).toBeNull();
    expect(bucketChange(gapped, 'orders', 1)).toBeUndefined();
    expect(bucketChange(gapped, 'orders', 2)).toBeUndefined();
  });

  it('says nothing on a category axis', () => {
    const terms = shape(
      [{ type: 'TERMS', field: 'warehouse', alias: 'wh' }],
      [
        { wh: 'SH', orders: 1 },
        { wh: 'BJ', orders: 2 },
      ],
    );
    expect(bucketChange(terms, 'orders', 1)).toBeUndefined();
  });

  it('takes the share of a negative number by its size', () => {
    const falling = shape(
      [daily],
      [
        { day: day(1), average: -4 },
        { day: day(2), average: -2 },
      ],
      undefined,
      'average',
    );
    expect(bucketChange(falling, 'average', 1)).toEqual({
      delta: 2,
      ratio: 0.5,
    });
  });
});
