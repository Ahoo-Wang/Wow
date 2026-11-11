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
 * `filter`'s time filters: calendar periods, days relative to today, and
 * instants relative to the server's now, with their zones and durations.
 * A filter carries no encoding: the server writes its bounds as the field
 * keeps time.
 */

import { filter, FilterOperator } from '../../../src/dsl.js';
import { describe, expect, it } from 'vitest';

describe('filter', () => {
  it.each([
    ['yesterday', filter.yesterday, FilterOperator.YESTERDAY],
    ['next month', filter.nextMonth, FilterOperator.NEXT_MONTH],
    ['last year', filter.lastYear, FilterOperator.LAST_YEAR],
    ['this year', filter.thisYear, FilterOperator.THIS_YEAR],
    ['next year', filter.nextYear, FilterOperator.NEXT_YEAR],
  ] as const)('builds %s filters', (_name, create, op) => {
    expect(create('createdAt')).toEqual({
      op,
      field: 'createdAt',
    });
  });

  it('sends a zone only, the encoding being the field’s', () => {
    const options = {
      zoneId: 'UTC',
      datePattern: 'yyyy-MM-dd',
      timeUnit: 'seconds',
    } as never;
    expect(filter.today('createdAt', options)).toEqual({
      op: FilterOperator.TODAY,
      field: 'createdAt',
      zoneId: 'UTC',
    });
  });

  it.each<{
    name: string;
    create: () => unknown;
    expected: unknown;
  }>([
    {
      name: 'today',
      create: () => filter.today('createdAt'),
      expected: {
        op: FilterOperator.TODAY,
        field: 'createdAt',
      },
    },
    {
      name: 'today at the JVM boundary offset',
      create: () => filter.today('createdAt', { zoneId: '+18:00' }),
      expected: {
        op: FilterOperator.TODAY,
        field: 'createdAt',
        zoneId: '+18:00',
      },
    },
    {
      name: 'before today',
      create: () =>
        filter.beforeToday('createdAt', '09:30', { zoneId: 'UTC+05:30' }),
      expected: {
        op: FilterOperator.BEFORE_TODAY,
        field: 'createdAt',
        time: '09:30',
        zoneId: 'UTC+05:30',
      },
    },
    {
      name: 'tomorrow',
      create: () => filter.tomorrow('createdAt'),
      expected: {
        op: FilterOperator.TOMORROW,
        field: 'createdAt',
      },
    },
    {
      name: 'this week',
      create: () => filter.thisWeek('createdAt'),
      expected: {
        op: FilterOperator.THIS_WEEK,
        field: 'createdAt',
      },
    },
    {
      name: 'next week',
      create: () => filter.nextWeek('createdAt'),
      expected: {
        op: FilterOperator.NEXT_WEEK,
        field: 'createdAt',
      },
    },
    {
      name: 'last week',
      create: () => filter.lastWeek('createdAt'),
      expected: {
        op: FilterOperator.LAST_WEEK,
        field: 'createdAt',
      },
    },
    {
      name: 'this month',
      create: () => filter.thisMonth('createdAt'),
      expected: {
        op: FilterOperator.THIS_MONTH,
        field: 'createdAt',
      },
    },
    {
      name: 'last month',
      create: () => filter.lastMonth('createdAt'),
      expected: {
        op: FilterOperator.LAST_MONTH,
        field: 'createdAt',
      },
    },
    {
      name: 'recent days',
      create: () => filter.recentDays('createdAt', 7),
      expected: {
        op: FilterOperator.RECENT_DAYS,
        field: 'createdAt',
        days: 7,
      },
    },
    {
      name: 'earlier days',
      create: () => filter.earlierDays('createdAt', 30),
      expected: {
        op: FilterOperator.EARLIER_DAYS,
        field: 'createdAt',
        days: 30,
      },
    },
    {
      name: 'before now, with the zero offset by default',
      create: () => filter.beforeNow('timeoutAt'),
      expected: {
        op: FilterOperator.BEFORE_NOW,
        field: 'timeoutAt',
        offset: 'PT0S',
      },
    },
    {
      name: 'after now, looking back',
      create: () => filter.afterNow('createdAt', '-PT30M', { zoneId: 'UTC' }),
      expected: {
        op: FilterOperator.AFTER_NOW,
        field: 'createdAt',
        offset: '-PT30M',
        zoneId: 'UTC',
      },
    },
  ])('builds the $name wire shape', ({ create, expected }) => {
    expect(create()).toEqual(expected);
  });

  // Each of these java.time.Duration.parse accepts, and each of the refused
  // offsets further down it refuses (checked on JDK 21).
  it.each([
    'PT0S',
    '-PT30M',
    '+PT1H',
    'pt1h',
    'P1D',
    'P-1D',
    'P1DT2H',
    'PT1H30M',
    'PT-30M',
    '-PT-30M',
    'PT1.S',
    'PT1,5S',
    'PT0.000000001S',
  ])('accepts the JVM duration %s as an offset', offset => {
    expect(filter.afterNow('createdAt', offset)).toEqual({
      op: FilterOperator.AFTER_NOW,
      field: 'createdAt',
      offset,
    });
  });

  it.each([
    'P',
    'P1DT',
    'PT1',
    'PT.5S',
    'PT30M1H',
    'P1Y',
    'PT1.1234567890S',
    ' PT1S',
  ])('refuses %s, which a JVM Duration does not parse', offset => {
    expect(() => filter.beforeNow('createdAt', offset)).toThrow(
      'BEFORE_NOW offset must be an ISO-8601 duration such as PT0S or -PT30M.',
    );
  });

  it.each(['Z', 'UT', '+5', '+0530', '+05:30:15'])(
    'accepts the JVM zone ID %s',
    zoneId => {
      expect(filter.today('createdAt', { zoneId })).toEqual({
        op: FilterOperator.TODAY,
        field: 'createdAt',
        zoneId,
      });
    },
  );

  it('keeps builder-owned fields authoritative over structural options', () => {
    const options = {
      zoneId: 'UTC',
      op: FilterOperator.MATCH_NONE,
      field: 'ignored',
      time: '00:00',
      days: 99,
      offset: 'P9D',
    };
    const calendarFilters = [
      [FilterOperator.TODAY, filter.today('createdAt', options)],
      [FilterOperator.TOMORROW, filter.tomorrow('createdAt', options)],
      [FilterOperator.THIS_WEEK, filter.thisWeek('createdAt', options)],
      [FilterOperator.NEXT_WEEK, filter.nextWeek('createdAt', options)],
      [FilterOperator.LAST_WEEK, filter.lastWeek('createdAt', options)],
      [FilterOperator.THIS_MONTH, filter.thisMonth('createdAt', options)],
      [FilterOperator.LAST_MONTH, filter.lastMonth('createdAt', options)],
    ] as const;

    calendarFilters.forEach(([op, expression]) => {
      expect(expression).toEqual({
        zoneId: 'UTC',
        op,
        field: 'createdAt',
      });
    });
    expect(filter.beforeToday('createdAt', '09:30', options)).toEqual({
      zoneId: 'UTC',
      op: FilterOperator.BEFORE_TODAY,
      field: 'createdAt',
      time: '09:30',
    });
    expect(filter.recentDays('createdAt', 7, options)).toEqual({
      zoneId: 'UTC',
      op: FilterOperator.RECENT_DAYS,
      field: 'createdAt',
      days: 7,
    });
    expect(filter.earlierDays('createdAt', 30, options)).toEqual({
      zoneId: 'UTC',
      op: FilterOperator.EARLIER_DAYS,
      field: 'createdAt',
      days: 30,
    });
    expect(filter.beforeNow('createdAt', 'PT1H', options)).toEqual({
      zoneId: 'UTC',
      op: FilterOperator.BEFORE_NOW,
      field: 'createdAt',
      offset: 'PT1H',
    });
  });

  it('rejects malformed zone offsets with a validation error', () => {
    expect(() => filter.today('createdAt', { zoneId: '+invalid' })).toThrow(
      'zoneId is invalid: [+invalid].',
    );
  });

  it.each([
    [
      'invalid before-today time',
      () => filter.beforeToday('createdAt', '25:00'),
    ],
    ['non-positive recent days', () => filter.recentDays('createdAt', 0)],
    [
      'offset that is not an ISO-8601 duration',
      () => filter.beforeNow('createdAt', '30 minutes'),
    ],
    [
      'offset in weeks, which a Duration does not take',
      () => filter.afterNow('createdAt', 'P1W'),
    ],
    [
      'offset with an empty time part',
      () => filter.afterNow('createdAt', 'PT'),
    ],
    [
      'non-string offset',
      () => Reflect.apply(filter.beforeNow, null, ['createdAt', 30]),
    ],
    ['fractional earlier days', () => filter.earlierDays('createdAt', 1.5)],
    [
      'days outside the JVM Int range',
      () => filter.recentDays('createdAt', 2_147_483_648),
    ],
    ['blank zone ID', () => filter.today('createdAt', { zoneId: ' ' })],
    [
      'zone offset outside the JVM range',
      () => filter.today('createdAt', { zoneId: '+23:59' }),
    ],
    [
      'zone offset beyond the JVM boundary minute',
      () => filter.today('createdAt', { zoneId: '+18:01' }),
    ],
  ])('rejects %s', (_name, create) => {
    expect(create).toThrow();
  });
});
