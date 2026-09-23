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

import { renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { shortDateTicks, useDateTicks } from '../src/ui/charts/dateTicks.js';

/**
 * A time axis's ticks, written short (audit P1-1): the year on each of
 * thirty ticks was thirty times the same word, and it slanted them 45°.
 */
const at = (iso: string) => Date.parse(iso);
const shanghai = { locale: 'zh-CN', timeZone: 'Asia/Shanghai' };

describe('shortDateTicks', () => {
  it('writes a day as its month and day, the year only first and where it turns', () => {
    const days = [
      at('2025-12-30T16:00:00Z'), // 12月31日 in Shanghai
      at('2025-12-31T16:00:00Z'), // 1月1日, 2026
      at('2026-01-01T16:00:00Z'),
    ];
    expect(shortDateTicks(days, 'DAY', shanghai)).toEqual([
      '2025年12月31日',
      '2026年1月1日',
      '1月2日',
    ]);
    expect(
      shortDateTicks(days, 'DAY', { locale: 'en-US', timeZone: 'UTC' }),
    ).toEqual(['Dec 30, 2025', 'Dec 31', 'Jan 1, 2026']);
  });

  it('reads the bucket in the zone it was cut in, and a wall-clock key as written', () => {
    // Midnight in Shanghai is the evening before at UTC.
    expect(
      shortDateTicks([at('2026-08-31T16:00:00Z')], 'DAY', shanghai),
    ).toEqual(['2026年9月1日']);
    expect(
      shortDateTicks(['2026-09-01', '2026-09-02'], 'DAY', {
        locale: 'zh-CN',
        timeZone: 'America/Los_Angeles',
      }),
    ).toEqual(['2026年9月1日', '9月2日']);
  });

  it('writes a month by its name, a quarter by its number', () => {
    expect(
      shortDateTicks(['2026-11-01', '2026-12-01', '2027-01-01'], 'MONTH', {
        locale: 'en-US',
      }),
    ).toEqual(['Nov 2026', 'Dec', 'Jan 2027']);
    expect(
      shortDateTicks(['2026-08-01', '2026-09-01'], 'MONTH', shanghai),
    ).toEqual(['2026年8月', '9月']);
    expect(
      shortDateTicks(['2026-07-01', '2026-10-01', '2027-01-01'], 'QUARTER', {
        locale: 'en-US',
      }),
    ).toEqual(['2026 Q3', 'Q4', '2027 Q1']);
  });

  it('writes an hour as its time, the day where a new one begins', () => {
    expect(
      shortDateTicks(
        ['2026-09-01T22:00', '2026-09-01T23:00', '2026-09-02T00:00'],
        'HOUR',
        { locale: 'zh-CN' },
      ),
    ).toEqual(['2026年9月1日 22:00', '23:00', '9月2日 00:00']);
    expect(
      shortDateTicks(['2026-09-01T22:00:05'], 'SECOND', { locale: 'en-US' }),
    ).toEqual(['Sep 1, 2026, 22:00:05']);
  });

  it('leaves a year axis, and a key that is no time, as their column reads them', () => {
    expect(shortDateTicks(['2026-01-01'], 'YEAR', shanghai)).toBeUndefined();
    expect(
      shortDateTicks([null, 'soon', '2026-09-01'], 'DAY', shanghai),
    ).toEqual([undefined, undefined, '2026年9月1日']);
  });

  it('gives way to the runtime where the language or the zone is refused', () => {
    expect(
      shortDateTicks(['2026-09-01'], 'DAY', {
        locale: 'zh_CN',
        timeZone: 'Mars/Olympus',
      })?.[0],
    ).toMatch(/2026/);
  });
});

describe('useDateTicks', () => {
  it('writes short ticks only for a column that is a date bucket', () => {
    const { result } = renderHook(() =>
      useDateTicks([
        { alias: 'day', label: 'createdAt', role: 'group', dateUnit: 'DAY' },
        { alias: 'wh', label: 'warehouse', role: 'group' },
      ]),
    );
    expect(result.current('day', ['2026-09-01'])?.[0]).toMatch(/2026/);
    expect(result.current('wh', ['CN'])).toBeUndefined();
    expect(result.current(undefined, ['CN'])).toBeUndefined();
  });
});
