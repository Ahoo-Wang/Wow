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
  rangeSpan,
  recommendDateUnit,
  resultSpan,
} from '../src/analysis/index.js';
import type { AnalysisDateUnit, FilterTree } from '../src/model/index.js';

const DAY = 86_400_000;
const ALL: AnalysisDateUnit[] = [
  'SECOND',
  'MINUTE',
  'HOUR',
  'DAY',
  'WEEK',
  'MONTH',
  'QUARTER',
  'YEAR',
];
const now = new Date('2026-09-22T10:00:00Z');
const zone = 'Asia/Shanghai';

describe('recommendDateUnit', () => {
  it('picks the coarsest offered unit that still gives a handful of buckets', () => {
    // A year: months (12), not quarters (4).
    expect(recommendDateUnit(365 * DAY, ALL)).toBe('MONTH');
    // Two months: weeks (8), not months (2).
    expect(recommendDateUnit(60 * DAY, ALL)).toBe('WEEK');
    // A week: days.
    expect(recommendDateUnit(7 * DAY, ALL)).toBe('DAY');
    // A morning: minutes would be hundreds; hours are three, too few, so
    // it falls to minutes — the coarsest that still clears the bar.
    expect(recommendDateUnit(3 * 3_600_000, ALL)).toBe('MINUTE');
    // Ten years: years.
    expect(recommendDateUnit(3650 * DAY, ALL)).toBe('YEAR');
  });

  it('stays within what the field offers', () => {
    expect(recommendDateUnit(365 * DAY, ['DAY', 'MONTH'])).toBe('MONTH');
    // Four quarters are too few buckets, so a year offered days or quarters
    // is cut by the day.
    expect(recommendDateUnit(365 * DAY, ['DAY', 'QUARTER'])).toBe('DAY');
    expect(recommendDateUnit(365 * DAY, ['HOUR', 'DAY'])).toBe('DAY');
    // Nothing offered cuts the span into enough buckets: the finest one.
    expect(recommendDateUnit(2 * DAY, ['MONTH', 'YEAR'])).toBe('MONTH');
  });

  it('starts at the first offered unit when there is no span to read', () => {
    expect(recommendDateUnit(null, ['MONTH', 'DAY'])).toBe('MONTH');
    expect(recommendDateUnit(0, ['DAY'])).toBe('DAY');
    expect(recommendDateUnit(365 * DAY, [])).toBe('DAY');
  });
});

describe('rangeSpan', () => {
  const tree = (children: FilterTree['children']): FilterTree => ({
    op: 'and',
    children,
  });

  it('reads a between as its two ends, on the zone’s clock', () => {
    const span = rangeSpan(
      tree([
        {
          field: 'createdAt',
          operator: 'BETWEEN',
          value: { type: 'absolute', from: '2026-01-01', to: '2026-01-31' },
        },
      ]),
      'createdAt',
      now,
      zone,
    );
    // The last day is read at its end, so the window is 31 whole days.
    expect(span).toBeCloseTo(31 * DAY, -3);
  });

  it('runs a lower bound to now, and reads no span from an upper bound alone', () => {
    const since = rangeSpan(
      tree([
        {
          field: 'createdAt',
          operator: 'GTE',
          value: { type: 'absolute', from: '2026-09-15' },
        },
      ]),
      'createdAt',
      now,
      zone,
    );
    expect(since).toBeGreaterThan(6 * DAY);
    expect(since).toBeLessThan(8 * DAY);
    expect(
      rangeSpan(
        tree([
          {
            field: 'createdAt',
            operator: 'LTE',
            value: { type: 'absolute', from: '2026-09-15' },
          },
        ]),
        'createdAt',
        now,
        zone,
      ),
    ).toBeNull();
  });

  it('narrows by every anded condition on the field and ignores the rest', () => {
    const span = rangeSpan(
      tree([
        {
          field: 'createdAt',
          operator: 'GTE',
          value: { type: 'absolute', from: '2026-01-01' },
        },
        {
          field: 'createdAt',
          operator: 'LT',
          value: { type: 'absolute', from: '2026-03-01' },
        },
        // Another field, and a relative window on this one under an OR:
        // neither is a bound.
        {
          field: 'shippedAt',
          operator: 'GTE',
          value: { type: 'absolute', from: '2025-01-01' },
        },
        {
          op: 'or',
          children: [
            {
              field: 'createdAt',
              operator: 'GTE',
              value: { type: 'relative', amount: 1, unit: 'day' },
            },
          ],
        },
      ]),
      'createdAt',
      now,
      zone,
    );
    expect(span).toBeCloseTo(59 * DAY, -3);
  });

  it('resolves a relative window against now', () => {
    const span = rangeSpan(
      tree([
        {
          field: 'createdAt',
          operator: 'BETWEEN',
          value: { type: 'relative', amount: 30, unit: 'day' },
        },
      ]),
      'createdAt',
      now,
      zone,
    );
    expect(span).toBeCloseTo(30 * DAY, -3);
    expect(recommendDateUnit(span, ALL)).toBe('DAY');
  });

  it('reads nothing from a range with no condition on the field', () => {
    expect(rangeSpan(tree([]), 'createdAt', now, zone)).toBeNull();
    expect(
      rangeSpan(
        tree([{ field: 'createdAt', operator: 'IS_NULL', value: null }]),
        'createdAt',
        now,
        zone,
      ),
    ).toBeNull();
  });
});

describe('resultSpan', () => {
  const groups = [
    { type: 'DATE_HISTOGRAM', field: 'createdAt', alias: 'm', unit: 'MONTH' },
  ] as const;

  it('spans from the first bucket’s start to the last bucket’s end', () => {
    const span = resultSpan(
      [
        { m: '2026-03-01T00:00:00+08:00', n: 1 },
        { m: '2026-01-01T00:00:00+08:00', n: 2 },
        { m: '2026-05-01T00:00:00+08:00', n: 3 },
      ],
      groups,
      'createdAt',
      zone,
    );
    // January through May: five months.
    expect(span).toBeCloseTo(151 * DAY, -3);
    expect(recommendDateUnit(span, ALL)).toBe('WEEK');
  });

  it('reads nothing without a time dimension on the field, or without rows', () => {
    expect(resultSpan([], groups, 'createdAt', zone)).toBeNull();
    expect(
      resultSpan([{ m: '2026-01-01', n: 1 }], groups, 'shippedAt', zone),
    ).toBeNull();
    expect(
      resultSpan(
        [{ wh: 'CN', n: 1 }],
        [{ type: 'TERMS', field: 'createdAt', alias: 'wh' }],
        'createdAt',
        zone,
      ),
    ).toBeNull();
  });
});
