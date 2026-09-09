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
import { filter, FilterOperator as Op } from '@ahoo-wang/fetcher-wow';
import { expect, it, vi } from 'vitest';
import { compileFilterConfiguration } from '../src/filter/filterCore';
import { compile, fields, node } from './fixtures/filterCore.js';

it('outputs real date strings and rejects calendar normalization', () => {
  expect(
    compile(node(Op.EQ, 'day', { value: '2024-02-29' })).expression,
  ).toEqual(filter.eq('day', '2024-02-29'));
  for (const value of [
    '2023-02-29',
    '2024-02-30',
    '2024-13-01',
    '2024-2-01',
    '',
  ])
    expect(compile(node(Op.EQ, 'day', { value })).errors).not.toEqual([]);
});

it('keeps epoch zero and converts precise datetime parts using the global timezone', () => {
  expect(compile(node(Op.EQ, 'created', { value: 0 })).expression).toEqual(
    filter.eq('created', 0),
  );
  expect(
    compile(
      node(Op.EQ, 'created', {
        value: { date: '1970-01-01', time: '08:00' },
      }),
    ).expression,
  ).toEqual(filter.eq('created', 0));
  expect(compile(node(Op.EQ, 'created', { value: {} })).expression).toEqual(
    filter.matchAll(),
  );
  expect(
    compile(node(Op.EQ, 'created', { value: { date: '', time: '' } }))
      .expression,
  ).toEqual(filter.matchAll());
  for (const value of [
    { date: '2024-01-01' },
    { time: '12:00' },
    { date: '2024-02-30', time: '12:00' },
    { date: '2024-01-01', time: '25:00' },
  ])
    expect(compile(node(Op.EQ, 'created', { value })).errors).not.toEqual([]);
});

it('rejects DST gaps and invalid zones while handling real zoned dates', () => {
  const zoned = [fields[4]];
  expect(
    compile(
      node(Op.EQ, 'created', {
        value: { date: '2024-03-10', time: '02:30' },
      }),
      zoned,
      'America/New_York',
    ).errors,
  ).not.toEqual([]);
  expect(
    compile(
      node(Op.EQ, 'created', {
        value: { date: '2024-03-10', time: '03:30' },
      }),
      zoned,
      'America/New_York',
    ).expression,
  ).toEqual(filter.eq('created', 1710055800000));
  expect(
    compile(
      node(Op.EQ, 'created', {
        value: { date: '2024-01-01', time: '12:00' },
      }),
      zoned,
      'Bad/Zone',
    ).errors,
  ).not.toEqual([]);
});

it.each([
  ['2026-11-01', '01:30', 240, 1793511000000],
  ['2026-11-01', '01:30', 300, 1793514600000],
  ['2026-11-01', '01:45:12.345', 240, 1793511912000],
  ['2026-11-01', '01:45:12.345', 300, 1793515512000],
  ['2026-11-01', '01:30', undefined, 1793511000000],
  ['2026-07-01', '01:30', 300, 1782883800000],
  ['2026-12-01', '01:30', 240, 1796106600000],
])(
  'uses an applicable offset hint for %s %s (offset %s)',
  (date, time, offsetMinutes, timestamp) => {
    expect(
      compile(
        node(Op.EQ, 'created', {
          value: { date, time, offsetMinutes },
        }),
        [fields[4]],
        'America/New_York',
      ),
    ).toEqual({
      expression: { op: Op.EQ, field: 'created', value: timestamp },
      errors: [],
    });
  },
);

it('rejects DST gaps and malformed datetime offset hints', () => {
  const zoned = [fields[4]];
  for (const value of [
    { date: '2026-03-08', time: '02:30', offsetMinutes: 240 },
    { date: '2026-03-08', time: '02:30', offsetMinutes: 300 },
    ...[NaN, Infinity, -Infinity, '300', null, {}, 300.5].map(
      offsetMinutes => ({
        date: '2026-11-01',
        time: '01:30',
        offsetMinutes,
      }),
    ),
    { offsetMinutes: NaN },
  ]) {
    const draft = node(Op.EQ, 'created', { value });
    const result = compile(draft, zoned, 'America/New_York');
    expect(result.expression).toBeUndefined();
    expect(result.errors).toEqual([
      { id: draft.id, message: expect.any(String) },
    ]);
  }
});

it.each([
  [
    node(Op.EQ, 'created', { value: 0 }, {}),
    { op: Op.EQ, field: 'created', value: 0 },
  ] as const,
  [
    node(Op.IN, 'created', { values: [0] }, {}),
    { op: Op.IN, field: 'created', values: [0] },
  ] as const,
  [
    node(Op.BETWEEN, 'created', { lowerBound: 0, upperBound: 1000 }, {}),
    { op: Op.BETWEEN, field: 'created', lowerBound: 0, upperBound: 1000 },
  ] as const,
])(
  'validates the timezone of loaded numeric datetime values for %j',
  (draft, expression) => {
    const invalid = compile(draft, [fields[4]], 'Bad/Zone');
    expect(invalid.errors).not.toEqual([]);
    expect(invalid.expression).toBeUndefined();
    for (const timeZone of ['Asia/Shanghai', '+08:00', undefined]) {
      expect(
        compileFilterConfiguration(
          { mode: 'advanced', root: draft },
          [fields[4]],
          undefined,
          undefined,
          timeZone,
        ),
      ).toEqual({ expression, errors: [] });
    }
  },
);

it('keeps local datetime behavior when the global timezone is unspecified', () => {
  const local = [fields[4]];
  const expected = new Date(2024, 0, 15, 12, 30, 59, 0).getTime();
  expect(
    compileFilterConfiguration(
      {
        mode: 'advanced',
        root: node(Op.EQ, 'created', {
          value: { date: '2024-01-15', time: '12:30:59.123' },
        }),
      },
      local,
      undefined,
      undefined,
      undefined,
    ).expression,
  ).toEqual(filter.eq('created', expected));
});

it.each(['UTC', 'Asia/Shanghai', 'America/Los_Angeles'])(
  'resolves overlaps independently of system timezone %s',
  systemZone => {
    vi.stubEnv('TZ', systemZone);
    try {
      expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe(systemZone);
      for (const [timeZone, date, time, offsetMinutes, timestamp] of [
        [
          'America/New_York',
          '2026-11-01',
          '01:30',
          undefined,
          Date.UTC(2026, 10, 1, 5, 30),
        ],
        [
          'America/New_York',
          '2026-11-01',
          '01:30',
          300,
          Date.UTC(2026, 10, 1, 6, 30),
        ],
        [
          'America/New_York',
          '2026-11-01',
          '01:30',
          0,
          Date.UTC(2026, 10, 1, 5, 30),
        ],
        [
          'Australia/Lord_Howe',
          '2026-04-05',
          '01:45',
          undefined,
          Date.UTC(2026, 3, 4, 14, 45),
        ],
        [
          'Australia/Lord_Howe',
          '2026-04-05',
          '01:45',
          -630,
          Date.UTC(2026, 3, 4, 15, 15),
        ],
      ] as const) {
        expect(
          compile(
            node(Op.EQ, 'created', { value: { date, time, offsetMinutes } }),
            [fields[4]],
            timeZone,
          ),
        ).toEqual({
          expression: { op: Op.EQ, field: 'created', value: timestamp },
          errors: [],
        });
      }
    } finally {
      vi.unstubAllEnvs();
    }
  },
);
