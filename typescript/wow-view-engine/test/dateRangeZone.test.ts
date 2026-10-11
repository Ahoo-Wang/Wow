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
import { zonedDay, zonedParts, zonedTime } from '../src/analysis/index.js';
import { readBound } from '../src/ui/filter/inputs/daterange.js';

describe('a stored instant in the date editor (R2-103)', () => {
  it('is read on the surface’s clock, the one its trigger reads it on', () => {
    // 16:00 in London, midnight the next day in Shanghai.
    expect(readBound('2026-01-31T16:00:00Z', true, 'Asia/Shanghai')).toEqual({
      day: '2026-02-01',
      time: '00:00',
    });
    expect(readBound('2026-01-31T16:00:00Z', true, 'Europe/London')).toEqual({
      day: '2026-01-31',
      time: '16:00',
    });
    // A field with no time of day keeps the day alone.
    expect(readBound('2026-01-31T16:00:00Z', false, 'Asia/Shanghai')).toEqual({
      day: '2026-02-01',
      time: '',
    });
  });

  it('leaves a wall clock as written, whatever the zone', () => {
    expect(readBound('2026-01-31T15:30', true, 'Asia/Shanghai')).toEqual({
      day: '2026-01-31',
      time: '15:30',
    });
    expect(readBound('2026-01-31', true, 'Asia/Shanghai')).toEqual({
      day: '2026-01-31',
      time: '',
    });
  });

  it('reads nothing from nothing, or from what is no instant', () => {
    expect(readBound(undefined, true, 'Asia/Shanghai')).toEqual({
      day: '',
      time: '',
    });
    expect(readBound('soon', true, 'Asia/Shanghai')).toEqual({
      day: '',
      time: '',
    });
  });
});

describe('the one zone reader (R2-103)', () => {
  const at = Date.UTC(2026, 0, 31, 16, 0, 0);

  it('reads a day and a time on the zone’s clock', () => {
    expect(zonedDay(at, 'Asia/Shanghai')).toBe('2026-02-01');
    expect(zonedTime(at, 'Asia/Shanghai')).toBe('00:00');
    expect(zonedDay(at, 'America/Los_Angeles')).toBe('2026-01-31');
    expect(zonedTime(at, 'America/Los_Angeles')).toBe('08:00');
  });

  it('keeps the hour of a clock that moves (a DST change in New York)', () => {
    // 2026-03-08 07:00 UTC is 03:00 EDT, the hour after the jump.
    expect(zonedTime(Date.UTC(2026, 2, 8, 7, 0), 'America/New_York')).toBe(
      '03:00',
    );
  });

  it('writes the year in four digits', () => {
    expect(zonedDay(Date.UTC(999, 0, 1), 'UTC')).toBe('0999-01-01');
  });

  it('answers every field, the second included', () => {
    expect(zonedParts(at + 7_000, 'UTC')).toEqual({
      year: 2026,
      month: 1,
      day: 31,
      hour: 16,
      minute: 0,
      second: 7,
    });
  });
});
