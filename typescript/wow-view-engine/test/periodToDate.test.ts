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

/**
 * Period-to-date windows (D38): 「本月至今」 runs from the start of this month
 * to now, and 「上月同期（至今）」 from the start of last month to the same
 * moment of it — so "this month against the same days of last month" (the
 * retail A-01, A-03) is a saved view that stays true, not two absolute dates
 * that go stale. Both are read on the engine's clock and zone.
 */

import { describe, expect, it } from 'vitest';
import {
  DATE_TIME_PRESETS,
  resolveDateTimeBound,
  resolveDateTimeRange,
  validateFilter,
  builtinFieldKinds,
  type DateTimePreset,
} from '../src/index.js';
import {
  andTree as tree,
  errorCodes as errors,
  filterFields as fields,
} from './fixtures/filter.js';

/** 2026-09-22 10:00 in Shanghai. */
const NOW = new Date('2026-09-22T02:00:00.000Z');
const ZONE = 'Asia/Shanghai';
const resolve = (preset: DateTimePreset, now = NOW, zone = ZONE) =>
  resolveDateTimeRange({ type: 'preset', preset }, now, zone);

describe('a period so far', () => {
  it.each([
    ['weekToDate', '2026-09-20T16:00:00.000Z'],
    ['monthToDate', '2026-08-31T16:00:00.000Z'],
    ['quarterToDate', '2026-06-30T16:00:00.000Z'],
    ['yearToDate', '2025-12-31T16:00:00.000Z'],
  ] as const)(
    'runs %s from the period’s first moment to now',
    (preset, from) => {
      expect(resolve(preset)).toEqual({ from, to: NOW.toISOString() });
    },
  );

  it.each([
    // Monday 2026-09-14 00:00 to Tuesday 09-15 10:00, Shanghai.
    ['lastWeekToDate', '2026-09-13T16:00:00.000Z', '2026-09-15T02:00:00.000Z'],
    // 08-01 00:00 to 08-22 10:00.
    ['lastMonthToDate', '2026-07-31T16:00:00.000Z', '2026-08-22T02:00:00.000Z'],
    // 04-01 00:00 to 06-22 10:00.
    [
      'lastQuarterToDate',
      '2026-03-31T16:00:00.000Z',
      '2026-06-22T02:00:00.000Z',
    ],
    // 2025-01-01 00:00 to 2025-09-22 10:00.
    ['lastYearToDate', '2024-12-31T16:00:00.000Z', '2025-09-22T02:00:00.000Z'],
  ] as const)(
    'runs %s over the same stretch of the period before',
    (preset, from, to) => {
      expect(resolve(preset)).toEqual({ from, to });
    },
  );

  it('ends last month’s stretch at its last day when it is shorter', () => {
    // 03-31 10:00: February has no 31st, so the stretch runs to its end.
    const now = new Date('2026-03-31T02:00:00.000Z');
    expect(resolve('lastMonthToDate', now)).toEqual({
      from: '2026-01-31T16:00:00.000Z',
      to: '2026-02-28T02:00:00.000Z',
    });
  });

  it('reads the period in the engine’s zone', () => {
    // The same instant is still 09-21 in Los Angeles, and the month's first
    // moment there is 09-01 00:00 PDT.
    expect(resolve('monthToDate', NOW, 'America/Los_Angeles')).toEqual({
      from: '2026-09-01T07:00:00.000Z',
      to: NOW.toISOString(),
    });
  });

  it('stands on its own edges as a single bound', () => {
    const value = { type: 'preset', preset: 'lastMonthToDate' } as const;
    expect(resolveDateTimeBound(value, NOW, ZONE, 'start')).toBe(
      '2026-07-31T16:00:00.000Z',
    );
    expect(resolveDateTimeBound(value, NOW, ZONE, 'end')).toBe(
      '2026-08-22T02:00:00.000Z',
    );
  });

  it('is a period every date condition takes', () => {
    for (const preset of [
      'weekToDate',
      'lastWeekToDate',
      'monthToDate',
      'lastMonthToDate',
      'quarterToDate',
      'lastQuarterToDate',
      'yearToDate',
      'lastYearToDate',
    ] as const) {
      expect(DATE_TIME_PRESETS).toContain(preset);
      expect(
        errors(
          validateFilter(
            fields,
            tree({
              field: 'createdAt',
              operator: 'BETWEEN',
              value: { type: 'preset', preset },
            }),
            builtinFieldKinds,
          ),
        ),
      ).toEqual([]);
    }
  });
});
