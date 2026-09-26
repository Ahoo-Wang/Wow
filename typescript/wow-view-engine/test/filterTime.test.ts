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
 * When a time condition means, resolved at compile time against the injected
 * clock: relative amounts and presets, the windows and named periods they
 * open, a relative value used as one bound, an absolute value in the zone it
 * was written in, and a calendar day standing for the whole day.
 */

import { FilterOperator } from '@ahoo-wang/wow-client';
import { describe, expect, it } from 'vitest';
import type { FilterOperatorName } from '../src/model/index.js';
import {
  builtinFieldKinds,
  compileFilter,
  DATE_TIME_PRESETS,
  describeFilter,
  MAX_RELATIVE_DATE_AMOUNT,
  periodOf,
  RELATIVE_DATE_UNITS,
  resolveDateTimeBound,
  resolveDateTimeRange,
  validateFilter,
  type FieldDefinition,
} from '../src/index.js';
import {
  andTree as tree,
  errorCodes as errors,
  filterContext as context,
  filterFields as fields,
} from './fixtures/filter.js';

describe('relative and preset dates', () => {
  const now = new Date('2026-09-16T10:30:00.000Z');

  it('evaluates a relative window against the injected moment, in whole days (D39)', () => {
    // Today and the six days before it, as Wow's RECENT_DAYS reads them:
    // a window cut at 10:30 put half a day at its start.
    const range = resolveDateTimeRange(
      { type: 'relative', amount: 7, unit: 'day' },
      now,
      'UTC',
    );
    expect(range).toEqual({
      from: '2026-09-10T00:00:00.000Z',
      to: '2026-09-16T23:59:59.999Z',
    });
    // The next seven days are today and the six after it.
    expect(
      resolveDateTimeRange(
        { type: 'relative', amount: 7, unit: 'day', direction: 'future' },
        now,
        'UTC',
      ),
    ).toEqual({
      from: '2026-09-16T00:00:00.000Z',
      to: '2026-09-22T23:59:59.999Z',
    });
    // On the zone's calendar, not UTC's.
    expect(
      resolveDateTimeRange(
        { type: 'relative', amount: 30, unit: 'day' },
        now,
        'Asia/Shanghai',
      ),
    ).toEqual({
      from: '2026-08-17T16:00:00.000Z',
      to: '2026-09-16T15:59:59.999Z',
    });
  });

  it('keeps a window of hours a distance from now', () => {
    expect(
      resolveDateTimeRange(
        { type: 'relative', amount: 6, unit: 'hour' },
        now,
        'UTC',
      ),
    ).toEqual({
      from: '2026-09-16T04:30:00.000Z',
      to: '2026-09-16T10:30:00.000Z',
    });
  });

  it('keeps a quarter equal to three months', () => {
    const quarter = resolveDateTimeRange(
      { type: 'relative', amount: 1, unit: 'quarter' },
      now,
      'UTC',
    );
    const months = resolveDateTimeRange(
      { type: 'relative', amount: 3, unit: 'month' },
      now,
      'UTC',
    );
    expect(quarter).toEqual(months);
  });

  it('resolves presets on calendar boundaries of the given zone', () => {
    expect(
      resolveDateTimeRange({ type: 'preset', preset: 'today' }, now, 'UTC')
        .from,
    ).toBe('2026-09-16T00:00:00.000Z');

    // Shanghai is UTC+8, so its day started the previous UTC evening.
    expect(
      resolveDateTimeRange(
        { type: 'preset', preset: 'today' },
        now,
        'Asia/Shanghai',
      ).from,
    ).toBe('2026-09-15T16:00:00.000Z');

    // 16 September 2026 is a Wednesday; the ISO week starts on the Monday.
    expect(
      resolveDateTimeRange({ type: 'preset', preset: 'thisWeek' }, now, 'UTC')
        .from,
    ).toBe('2026-09-14T00:00:00.000Z');

    expect(
      resolveDateTimeRange(
        { type: 'preset', preset: 'thisQuarter' },
        now,
        'UTC',
      ),
    ).toEqual({
      from: '2026-07-01T00:00:00.000Z',
      to: '2026-09-30T23:59:59.999Z',
    });

    expect(
      resolveDateTimeRange({ type: 'preset', preset: 'yesterday' }, now, 'UTC'),
    ).toEqual({
      from: '2026-09-15T00:00:00.000Z',
      to: '2026-09-15T23:59:59.999Z',
    });

    // 「前天」 (D39).
    expect(
      resolveDateTimeRange(
        { type: 'preset', preset: 'dayBeforeYesterday' },
        now,
        'UTC',
      ),
    ).toEqual({
      from: '2026-09-14T00:00:00.000Z',
      to: '2026-09-14T23:59:59.999Z',
    });
  });

  it('supports every relative unit', () => {
    const from = (unit: 'hour' | 'day' | 'week' | 'month' | 'year') =>
      resolveDateTimeRange({ type: 'relative', amount: 2, unit }, now, 'UTC')
        .from;
    expect(from('hour')).toBe('2026-09-16T08:30:00.000Z');
    expect(from('day')).toBe('2026-09-15T00:00:00.000Z');
    expect(from('week')).toBe('2026-09-03T00:00:00.000Z');
    expect(from('month')).toBe('2026-07-17T00:00:00.000Z');
    expect(from('year')).toBe('2024-09-17T00:00:00.000Z');
  });

  it('supports every calendar preset', () => {
    const range = (preset: 'thisMonth' | 'thisYear') =>
      resolveDateTimeRange({ type: 'preset', preset }, now, 'UTC');
    expect(range('thisMonth')).toEqual({
      from: '2026-09-01T00:00:00.000Z',
      to: '2026-09-30T23:59:59.999Z',
    });
    expect(range('thisYear')).toEqual({
      from: '2026-01-01T00:00:00.000Z',
      to: '2026-12-31T23:59:59.999Z',
    });
  });

  it('leaves an absolute value alone and allows an open upper bound', () => {
    expect(
      resolveDateTimeRange(
        { type: 'absolute', from: '2026-01-01T00:00:00.000Z' },
        now,
        'UTC',
      ),
    ).toEqual({ from: '2026-01-01T00:00:00.000Z' });
  });

  it('compiles an open-ended range to GTE and a closed one to BETWEEN', () => {
    const open = compileFilter(
      fields,
      tree({
        field: 'createdAt',
        operator: 'BETWEEN',
        value: { type: 'absolute', from: '2026-01-01T00:00:00.000Z' },
      }),
      builtinFieldKinds,
      context,
    );
    expect(open).toMatchObject({ op: FilterOperator.GTE, field: 'createdAt' });

    const closed = compileFilter(
      fields,
      tree({
        field: 'createdAt',
        operator: 'BETWEEN',
        value: { type: 'preset', preset: 'today' },
      }),
      builtinFieldKinds,
      context,
    );
    expect(closed).toMatchObject({
      op: FilterOperator.BETWEEN,
      field: 'createdAt',
      lowerBound: '2026-09-16T00:00:00.000Z',
    });
  });
});

/**
 * A window measured from now, in either direction. "The last 7 days" asks
 * what happened; "the next 7 days" asks what is due, and only the second was
 * inexpressible — `amount` had to be positive and the window always ran
 * backwards.
 */
describe('relative windows and named periods', () => {
  const resolve = (value: unknown) =>
    compileFilter(
      fields,
      tree({
        field: 'createdAt',
        operator: `${FilterOperator.BETWEEN}`,
        value: value as never,
      }),
      builtinFieldKinds,
      context,
    ) as { lowerBound: string; upperBound: string };

  it('runs a relative window backwards by default', () => {
    const past = resolve({ type: 'relative', amount: 7, unit: 'day' });

    expect(Date.parse(past.upperBound)).toBeGreaterThan(context.now.getTime());
    expect(Date.parse(past.lowerBound)).toBeLessThan(context.now.getTime());
  });

  it('runs it forwards when the condition says so', () => {
    const future = resolve({
      type: 'relative',
      amount: 7,
      unit: 'day',
      direction: 'future',
    });

    expect(Date.parse(future.lowerBound)).toBeLessThan(context.now.getTime());
    expect(Date.parse(future.upperBound)).toBeGreaterThan(
      context.now.getTime(),
    );
  });

  it('refuses a direction it does not know', () => {
    expect(
      errors(
        validateFilter(
          fields,
          tree({
            field: 'createdAt',
            operator: `${FilterOperator.BETWEEN}`,
            value: {
              type: 'relative',
              amount: 7,
              unit: 'day',
              direction: 'sideways',
            } as never,
          }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['filter.value.expected-date']);
  });

  it.each(DATE_TIME_PRESETS)('resolves the %s period', preset => {
    const window = resolve({ type: 'preset', preset });

    // Every named period is a real window, and none of them is inverted.
    expect(Date.parse(window.lowerBound)).toBeLessThan(
      Date.parse(window.upperBound),
    );
  });

  it('places last, this and next in order', () => {
    const last = resolve({ type: 'preset', preset: 'lastMonth' });
    const current = resolve({ type: 'preset', preset: 'thisMonth' });
    const next = resolve({ type: 'preset', preset: 'nextMonth' });

    expect(Date.parse(last.upperBound)).toBeLessThan(
      Date.parse(current.lowerBound),
    );
    expect(Date.parse(current.upperBound)).toBeLessThan(
      Date.parse(next.lowerBound),
    );
  });

  it.each([
    [undefined, 'last 7 days'],
    ['past', 'last 7 days'],
    ['future', 'next 7 days'],
  ])('summarises a %s window as %s', (direction, want) => {
    // A forward window described as "last" would contradict the query that
    // actually ran, in the one place a user checks what is in force.
    expect(
      describeFilter(
        fields,
        tree({
          field: 'createdAt',
          operator: `${FilterOperator.BETWEEN}`,
          value: {
            type: 'relative',
            amount: 7,
            unit: 'day',
            ...(direction === undefined ? {} : { direction }),
          } as never,
        }),
        builtinFieldKinds,
      )[0].text,
    ).toContain(want);
  });

  it('summarises a period as words rather than as its key', () => {
    expect(
      describeFilter(
        fields,
        tree({
          field: 'createdAt',
          operator: `${FilterOperator.BETWEEN}`,
          value: { type: 'preset', preset: 'nextQuarter' } as never,
        }),
        builtinFieldKinds,
      )[0].text,
    ).toContain('next quarter');
  });

  it('keeps a quarter three months wide either side of this one', () => {
    const last = resolve({ type: 'preset', preset: 'lastQuarter' });
    const next = resolve({ type: 'preset', preset: 'nextQuarter' });

    // September 2026 sits in Q3, so its neighbours are Q2 and Q4.
    expect(last.lowerBound.slice(0, 7)).toBe('2026-04');
    expect(next.lowerBound.slice(0, 7)).toBe('2026-10');
  });
});

/**
 * "7 days" is a distance from now. A window has one edge at now and one at
 * that distance, and a single bound at now is not what anyone typed, so a
 * relative value stands on its far edge whichever operator asks. A preset
 * is a calendar period and keeps the edge the operator asks for.
 */
describe('a relative value as a single bound', () => {
  const last = { type: 'relative', amount: 7, unit: 'day' };
  const next = { ...last, direction: 'future' };
  const today = { type: 'preset', preset: 'today' };
  // The far edge of the window in whole days (D39).
  const weekAgo = '2026-09-10T00:00:00.000Z';
  const weekAhead = '2026-09-22T23:59:59.999Z';
  const bound = (operator: 'GTE' | 'LTE', value: unknown) =>
    (
      compileFilter(
        fields,
        tree({ field: 'createdAt', operator, value: value as never }),
        builtinFieldKinds,
        context,
      ) as { value: string }
    ).value;
  const text = (operator: string, value: unknown) =>
    describeFilter(
      fields,
      tree({ field: 'createdAt', operator, value } as never),
      builtinFieldKinds,
    )[0].text;

  it('stands on the edge that is not now', () => {
    expect(bound('GTE', last)).toBe(weekAgo);
    expect(bound('LTE', last)).toBe(weekAgo);
    expect(bound('GTE', next)).toBe(weekAhead);
    expect(bound('LTE', next)).toBe(weekAhead);
    expect(resolveDateTimeBound(last as never, context.now, 'UTC', 'end')).toBe(
      weekAgo,
    );
  });

  it('keeps a preset on the edge asked for', () => {
    expect(bound('GTE', today)).toBe('2026-09-16T00:00:00.000Z');
    expect(bound('LTE', today)).toBe('2026-09-16T23:59:59.999Z');
  });

  it('says which instant it compares against', () => {
    expect(text('GTE', last)).toBe('Created on or after 7 days ago');
    expect(text('LTE', last)).toBe('Created on or before 7 days ago');
    expect(text('GTE', next)).toBe('Created on or after 7 days ahead');
    expect(text('LTE', next)).toBe('Created on or before 7 days ahead');
    expect(text('GTE', today)).toBe('Created on or after today');
    expect(text('LTE', today)).toBe('Created on or before today');
    expect(text('BETWEEN', last)).toBe('Created last 7 days');
  });

  it('names the bound an absolute value stands on', () => {
    const day = { type: 'absolute', from: '2026-01-01' };
    const range = { ...day, to: '2026-01-31' };
    expect(text('GTE', day)).toBe('Created on or after 2026-01-01');
    expect(text('LTE', day)).toBe('Created on or before 2026-01-01');
    expect(text('GTE', range)).toBe('Created on or after 2026-01-01');
    expect(text('LTE', range)).toBe('Created on or before 2026-01-31');
  });
});

/**
 * A relative amount past what a `Date` can hold made dayjs produce an
 * invalid instant, and `compileFilter` threw `RangeError` on a tree the
 * validator had admitted. The validator is the gate; the compiler is total.
 */
describe('an unbounded relative amount', () => {
  const huge = { type: 'relative', amount: 1e15, unit: 'day' } as const;
  const ahead = { ...huge, direction: 'future' } as const;
  const at = (operator: string, value: unknown) =>
    tree({ field: 'createdAt', operator, value } as never);

  it('is refused by validation, with the bound it crossed', () => {
    expect(
      validateFilter(fields, at('BETWEEN', huge), builtinFieldKinds),
    ).toEqual([
      {
        code: 'filter.value.relative-too-large',
        severity: 'error',
        path: ['children', 0],
        params: { max: MAX_RELATIVE_DATE_AMOUNT },
      },
    ]);
  });

  it('admits the bound itself in every unit and direction', () => {
    for (const unit of RELATIVE_DATE_UNITS)
      for (const direction of ['past', 'future']) {
        const value = {
          type: 'relative',
          amount: MAX_RELATIVE_DATE_AMOUNT,
          unit,
          direction,
        };
        expect(
          validateFilter(fields, at('BETWEEN', value), builtinFieldKinds),
        ).toEqual([]);
        const range = resolveDateTimeRange(value as never, context.now, 'UTC');
        expect(Number.isNaN(Date.parse(range.from))).toBe(false);
        expect(Number.isNaN(Date.parse(range.to as string))).toBe(false);
      }
  });

  it('does not make compilation throw', () => {
    const earliest = new Date(-8.64e15).toISOString();
    const latest = new Date(8.64e15).toISOString();

    expect(() =>
      compileFilter(fields, at('BETWEEN', huge), builtinFieldKinds, context),
    ).not.toThrow();
    expect(() =>
      compileFilter(fields, at('LTE', ahead), builtinFieldKinds, context),
    ).not.toThrow();
    // Whole days (D39): today's first and last moments on the near edge.
    expect(resolveDateTimeRange(huge, context.now, 'UTC')).toEqual({
      from: earliest,
      to: '2026-09-16T23:59:59.999Z',
    });
    expect(resolveDateTimeRange(ahead, context.now, 'UTC')).toEqual({
      from: '2026-09-16T00:00:00.000Z',
      to: latest,
    });
  });
});

/**
 * An absolute value is the one place a stored string still needs reading, and
 * for a while its `timeZone` was stored, validated and then ignored: two
 * conditions differing only by zone compiled to the same query.
 */

describe('absolute dates and their zone', () => {
  const between = (from: string, to: string, timeZone?: string) =>
    compileFilter(
      fields,
      tree({
        field: 'createdAt',
        operator: `${FilterOperator.BETWEEN}`,
        value: {
          type: 'absolute',
          from,
          to,
          ...(timeZone ? { timeZone } : {}),
        },
      }),
      builtinFieldKinds,
      context,
    );

  it('reads a wall-clock string in the zone the condition names', () => {
    const tokyo = between('2026-01-01', '2026-01-02', 'Asia/Tokyo');

    // Midnight in Tokyo is 15:00 the previous day in UTC, and a day named
    // as the upper bound runs to its last millisecond.
    expect(tokyo).toMatchObject({
      lowerBound: '2025-12-31T15:00:00.000Z',
      upperBound: '2026-01-02T14:59:59.999Z',
    });
    expect(tokyo).not.toEqual(between('2026-01-01', '2026-01-02'));
  });

  it('falls back to the runtime zone when the condition names none', () => {
    expect(between('2026-01-01', '2026-01-02')).toMatchObject({
      lowerBound: '2026-01-01T00:00:00.000Z',
    });
  });

  it('leaves an instant that carries its own offset alone', () => {
    // `...Z` already names one moment; resolving it again would move it.
    expect(
      between('2026-01-01T00:00:00Z', '2026-01-02T00:00:00Z', 'Asia/Tokyo'),
    ).toMatchObject({ lowerBound: '2026-01-01T00:00:00Z' });
  });

  it('refuses a zone no runtime can resolve', () => {
    const found = validateFilter(
      fields,
      tree({
        field: 'createdAt',
        operator: `${FilterOperator.GTE}`,
        value: { type: 'absolute', from: '2026-01-01', timeZone: 'Not/AZone' },
      }),
      builtinFieldKinds,
    );

    // Without this the compiler throws a RangeError where a query was due.
    expect(errors(found)).toEqual(['filter.value.unknown-time-zone']);
  });
});

/**
 * A bound written as a calendar day means the whole day. Resolving `to:
 * 2026-01-31` to that day's first instant made `BETWEEN 01-01..01-31` stop
 * before the 31st began, and `LTE 01-31` exclude the very day it named.
 */
describe('a calendar day as a bound', () => {
  const dayFields: FieldDefinition[] = [
    // ISO text, so each bound reads as the instant it is.
    {
      name: 'orderedOn',
      label: 'Ordered',
      kind: 'date',
      temporal: { type: 'date' },
    },
  ];
  const shanghai = { ...context, timeZone: 'Asia/Shanghai' };
  const compileDay = (
    operator: FilterOperatorName,
    value: Record<string, unknown>,
  ) =>
    compileFilter(
      dayFields,
      tree({
        field: 'orderedOn',
        operator,
        value: { type: 'absolute', ...value },
      }),
      builtinFieldKinds,
      shanghai,
    );

  it('runs a range through the last millisecond of its final day', () => {
    expect(
      compileDay('BETWEEN', { from: '2026-01-01', to: '2026-01-31' }),
    ).toEqual({
      op: FilterOperator.BETWEEN,
      field: 'orderedOn',
      lowerBound: '2025-12-31T16:00:00.000Z',
      upperBound: '2026-01-31T15:59:59.999Z',
    });
  });

  it('takes "on or before a day" to the end of it, and "on or after" to its start', () => {
    expect(compileDay('LTE', { from: '2026-01-31' })).toMatchObject({
      op: FilterOperator.LTE,
      value: '2026-01-31T15:59:59.999Z',
    });
    expect(compileDay('GTE', { from: '2026-01-31' })).toMatchObject({
      op: FilterOperator.GTE,
      value: '2026-01-30T16:00:00.000Z',
    });
  });

  it('keeps a bound with a time of day at that time', () => {
    expect(
      compileDay('BETWEEN', { from: '2026-01-01', to: '2026-01-31T09:00' }),
    ).toMatchObject({ upperBound: '2026-01-31T01:00:00.000Z' });
    expect(compileDay('LTE', { from: '2026-01-31T09:00' })).toMatchObject({
      value: '2026-01-31T01:00:00.000Z',
    });
  });

  it('leaves a bound that names its own offset untouched', () => {
    expect(
      compileDay('BETWEEN', {
        from: '2026-01-01',
        to: '2026-01-31T00:00:00+08:00',
      }),
    ).toMatchObject({ upperBound: '2026-01-31T00:00:00+08:00' });
    expect(
      compileDay('LTE', { from: '2026-01-31T00:00:00-05:00' }),
    ).toMatchObject({ value: '2026-01-31T00:00:00-05:00' });
  });

  it('does not mistake the dashes of a plain date for an offset', () => {
    // Had `-01-31` matched as an offset, the day would have passed through
    // as-is instead of being read in the zone and widened to its end.
    const value = { type: 'absolute' as const, from: '2026-01-31' };
    expect(resolveDateTimeRange(value, context.now, 'Asia/Shanghai')).toEqual({
      from: '2026-01-30T16:00:00.000Z',
    });
    expect(
      resolveDateTimeBound(value, context.now, 'Asia/Shanghai', 'end'),
    ).toBe('2026-01-31T15:59:59.999Z');
  });

  it("still lets the condition's own zone win over the runtime's", () => {
    expect(
      compileDay('BETWEEN', {
        from: '2026-01-01',
        to: '2026-01-31',
        timeZone: 'Asia/Tokyo',
      }),
    ).toMatchObject({
      lowerBound: '2025-12-31T15:00:00.000Z',
      upperBound: '2026-01-31T14:59:59.999Z',
    });
  });

  it('gives a relative or preset value the edge asked for', () => {
    const today = { type: 'preset' as const, preset: 'today' as const };
    expect(resolveDateTimeBound(today, context.now, 'UTC', 'start')).toBe(
      '2026-09-16T00:00:00.000Z',
    );
    expect(resolveDateTimeBound(today, context.now, 'UTC', 'end')).toBe(
      '2026-09-16T23:59:59.999Z',
    );
  });
});

/**
 * D17-1: a `withTime` field's bound may carry a time of day, and an empty one
 * is read with **interval** semantics — as a start it is the day's first
 * millisecond, as an end its last. Both edges are in the surface's zone, like
 * every other date value, and an old config that stored only a day is read the
 * same way rather than kept as the instant its string would parse to.
 *
 * The boundary is the whole point: a report "through the 31st" that stopped at
 * the 31st's first instant dropped every record of that day, and one read as
 * the next midnight swept in the first record of February.
 */
describe('a bound with a time of day', () => {
  // +08:00, so every expectation below also proves the zone was applied:
  // none of these instants is the string read as UTC.
  const shanghai = { ...context, timeZone: 'Asia/Shanghai' };
  const at = (operator: FilterOperatorName, value: Record<string, unknown>) =>
    compileFilter(
      fields,
      tree({
        field: 'createdAt',
        operator,
        value: { type: 'absolute', ...value },
      }),
      builtinFieldKinds,
      shanghai,
    ) as unknown as { lowerBound: string; upperBound: string; value: string };

  /** The two records on either side of the end of 2026-01-31 in Shanghai. */
  const lastSecond = Date.parse('2026-01-31T23:59:59+08:00');
  const nextMidnight = Date.parse('2026-02-01T00:00:00+08:00');
  /** And the first instant of 2026-01-01 there. */
  const firstInstant = Date.parse('2026-01-01T00:00:00+08:00');

  it('runs an empty end time through the last millisecond of its day', () => {
    const { upperBound } = at('BETWEEN', {
      from: '2026-01-01',
      to: '2026-01-31',
    });

    expect(upperBound).toBe('2026-01-31T15:59:59.999Z');
    // A record at 23:59:59 on the end date is in.
    expect(Date.parse(upperBound)).toBeGreaterThan(lastSecond);
    // One at 00:00:00 the next day is out.
    expect(Date.parse(upperBound)).toBeLessThan(nextMidnight);
  });

  it('starts an empty start time at the first millisecond of its day', () => {
    const { lowerBound } = at('BETWEEN', {
      from: '2026-01-01',
      to: '2026-01-31',
    });

    expect(lowerBound).toBe('2025-12-31T16:00:00.000Z');
    // A record at 00:00:00 on the start date is in; one a millisecond
    // earlier — the last of the previous day — is out.
    expect(Date.parse(lowerBound)).toBe(firstInstant);
    expect(Date.parse(lowerBound)).toBeGreaterThan(firstInstant - 1);
  });

  it('gives a single bound the same two edges', () => {
    expect(at('GTE', { from: '2026-01-01' }).value).toBe(
      '2025-12-31T16:00:00.000Z',
    );
    expect(at('LTE', { from: '2026-01-31' }).value).toBe(
      '2026-01-31T15:59:59.999Z',
    );
  });

  /**
   * What the control stores when a time is typed: a wall-clock string with no
   * offset, so the zone still applies, and one instant on either edge — the
   * interval reading is for an empty box, not for a time the user gave.
   */
  it('stands an explicit time of day on that instant, either edge', () => {
    expect(
      at('BETWEEN', { from: '2026-01-01T09:00:00', to: '2026-01-31T15:30:00' }),
    ).toMatchObject({
      lowerBound: '2026-01-01T01:00:00.000Z',
      upperBound: '2026-01-31T07:30:00.000Z',
    });
    expect(at('LTE', { from: '2026-01-31T15:30:00' }).value).toBe(
      '2026-01-31T07:30:00.000Z',
    );
  });

  /**
   * An old config holding only a day on a `withTime` field is what an emptied
   * time box writes, and it is read identically: the day, not the midnight its
   * string parses to. Only the end edge can tell the two apart, which is
   * exactly where a report loses its last day.
   */
  it('reads an old date-only value as an emptied time box, not as midnight', () => {
    const stored = { from: '2026-01-01', to: '2026-01-31' };
    const midnight = { from: '2026-01-01T00:00:00', to: '2026-01-31T00:00:00' };

    expect(at('BETWEEN', stored).lowerBound).toBe(
      at('BETWEEN', midnight).lowerBound,
    );
    expect(at('BETWEEN', stored).upperBound).not.toBe(
      at('BETWEEN', midnight).upperBound,
    );
    expect(Date.parse(at('BETWEEN', midnight).upperBound)).toBeLessThan(
      lastSecond,
    );
  });

  /** The summary reads the time back where one was given, and only there. */
  it('says the time of day when the bound carries one', () => {
    const said = (value: Record<string, unknown>) =>
      describeFilter(
        fields,
        tree({
          field: 'createdAt',
          operator: 'BETWEEN',
          value: { type: 'absolute', ...value },
        }),
        builtinFieldKinds,
      )[0].text;

    expect(said({ from: '2026-01-01', to: '2026-01-31' })).toBe(
      'Created 2026-01-01 ~ 2026-01-31',
    );
    expect(
      said({ from: '2026-01-01T09:00:00', to: '2026-01-31T15:30:00' }),
    ).toBe('Created 2026-01-01T09:00:00 ~ 2026-01-31T15:30:00');
  });
});

/**
 * A range that is one period of its zone's calendar reads as the period —
 * what a date bucket pressed opens its records under (2026-09-23 review
 * P2). Only an exact match counts: the two edges to the millisecond.
 */
describe('periodOf', () => {
  const SHANGHAI = 'Asia/Shanghai';
  /** The range a bucket of `from` up to (not including) `next` is. */
  const range = (from: string, next: string) =>
    [
      new Date(from).toISOString(),
      new Date(Date.parse(next) - 1).toISOString(),
    ] as const;

  it('names each calendar unit a bucket can be, in the zone given', () => {
    const cases = [
      ['2026-09-22T00:00:00+08:00', '2026-09-23T00:00:00+08:00', 'DAY'],
      ['2026-09-01T00:00:00+08:00', '2026-10-01T00:00:00+08:00', 'MONTH'],
      ['2026-07-01T00:00:00+08:00', '2026-10-01T00:00:00+08:00', 'QUARTER'],
      ['2026-01-01T00:00:00+08:00', '2027-01-01T00:00:00+08:00', 'YEAR'],
      ['2026-09-22T14:00:00+08:00', '2026-09-22T15:00:00+08:00', 'HOUR'],
      ['2026-09-22T14:05:00+08:00', '2026-09-22T14:06:00+08:00', 'MINUTE'],
      ['2026-09-22T14:05:09+08:00', '2026-09-22T14:05:10+08:00', 'SECOND'],
    ] as const;
    for (const [from, next, unit] of cases)
      expect(periodOf(...range(from, next), SHANGHAI)).toBe(unit);
  });

  it('reads a week as the seven days from a midnight, whichever day it is', () => {
    // A Monday, and a Sunday: a source may start its weeks on either.
    expect(
      periodOf(
        ...range('2026-09-21T00:00:00+08:00', '2026-09-28T00:00:00+08:00'),
        SHANGHAI,
      ),
    ).toBe('WEEK');
    expect(
      periodOf(
        ...range('2026-09-20T00:00:00+08:00', '2026-09-27T00:00:00+08:00'),
        SHANGHAI,
      ),
    ).toBe('WEEK');
  });

  it('follows the zone’s calendar across a clock change', () => {
    // New York's day of the autumn change is 25 hours long.
    expect(
      periodOf(
        ...range('2026-11-01T00:00:00-04:00', '2026-11-02T00:00:00-05:00'),
        'America/New_York',
      ),
    ).toBe('DAY');
  });

  it('is no period a millisecond out, on another clock, or unreadable', () => {
    const [from, to] = range(
      '2026-09-22T00:00:00+08:00',
      '2026-09-23T00:00:00+08:00',
    );
    expect(
      periodOf(from, new Date(Date.parse(to) - 1).toISOString(), SHANGHAI),
    ).toBeNull();
    // The same instants are 16:00 to 16:00 in UTC: a range, not a day.
    expect(periodOf(from, to, 'UTC')).toBeNull();
    expect(periodOf(from, to, 'Not/AZone')).toBeNull();
    expect(periodOf('yesterday', to, SHANGHAI)).toBeNull();
    expect(periodOf(to, from, SHANGHAI)).toBeNull();
    // Two days is a range; a date alone names its whole day, in the zone.
    expect(
      periodOf(
        ...range('2026-09-22T00:00:00+08:00', '2026-09-24T00:00:00+08:00'),
        SHANGHAI,
      ),
    ).toBeNull();
    expect(periodOf('2026-09-22', '2026-09-22', SHANGHAI)).toBe('DAY');
  });
});
