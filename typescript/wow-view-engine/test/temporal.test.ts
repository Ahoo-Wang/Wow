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

import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import { describe, expect, it } from 'vitest';
import { drillConditions } from '../src/analysis/index.js';
import { builtinFieldKinds, compileFilter } from '../src/filter/index.js';
import {
  DEFAULT_TEMPORAL,
  temporalOf,
  type FieldDefinition,
  type FieldTemporal,
  type FilterNode,
  type FilterOperatorName,
} from '../src/model/index.js';
import { validateDefinition } from '../src/runtime/index.js';
import { analysisConfig } from './fixtures.js';

/**
 * How a date condition is written for the store (`FieldDefinition.temporal`).
 *
 * Measured against a real Wow compensation service: its snapshots keep
 * `eventTime` as epoch milliseconds, the query schema says so
 * (`TEMPORAL_EPOCH`, `MILLISECONDS`), and a bound sent as ISO text was
 * refused with `Filter value does not match [eventTime]` — every date
 * condition, every time.
 */

const SHANGHAI = 'Asia/Shanghai';
/** Midday of the 20th in Shanghai. */
const context = {
  now: new Date('2026-09-20T04:00:00.000Z'),
  timeZone: SHANGHAI,
};
/** Shanghai's 20th, first and last millisecond. */
const DAY_FROM = '2026-09-19T16:00:00.000Z';
const DAY_TO = '2026-09-20T15:59:59.999Z';

function fieldOf(temporal?: FieldTemporal): FieldDefinition {
  return {
    name: 'eventTime',
    label: 'Event time',
    kind: 'datetime',
    ...(temporal ? { temporal } : {}),
  };
}

function compileOne(
  field: FieldDefinition,
  operator: FilterOperatorName,
  value: unknown,
) {
  return compileFilter(
    [field],
    {
      op: 'and',
      children: [{ field: field.name, operator, value: value as never }],
    },
    builtinFieldKinds,
    context,
  );
}

const today = { type: 'preset', preset: 'today' };
const absoluteDay = { type: 'absolute', from: '2026-09-20', to: '2026-09-20' };
const lastDay = { type: 'relative', amount: 1, unit: 'day' };

describe('the stored representation of a time', () => {
  it('is epoch milliseconds when the field declares none', () => {
    // A definition converted from a Wow schema is right without the member.
    expect(temporalOf(fieldOf())).toEqual({
      type: 'epoch',
      timeUnit: 'MILLISECONDS',
    });
    expect(DEFAULT_TEMPORAL).toEqual(temporalOf(fieldOf()));
  });

  it('sends the relative "today" over eventTime as two integers (regression)', () => {
    const compiled = compileOne(fieldOf(), 'BETWEEN', today);
    expect(compiled).toEqual({
      op: FilterOperator.BETWEEN,
      field: 'eventTime',
      lowerBound: Date.parse(DAY_FROM),
      upperBound: Date.parse(DAY_TO),
    });
    const { lowerBound, upperBound } = compiled as {
      lowerBound: unknown;
      upperBound: unknown;
    };
    expect(Number.isInteger(lowerBound)).toBe(true);
    expect(Number.isInteger(upperBound)).toBe(true);
  });

  const representations: {
    name: string;
    temporal?: FieldTemporal;
    from: unknown;
    to: unknown;
  }[] = [
    {
      name: 'epoch milliseconds, by default',
      from: Date.parse(DAY_FROM),
      to: Date.parse(DAY_TO),
    },
    {
      name: 'epoch milliseconds, declared',
      temporal: { type: 'epoch', timeUnit: 'MILLISECONDS' },
      from: Date.parse(DAY_FROM),
      to: Date.parse(DAY_TO),
    },
    {
      name: 'epoch milliseconds, unit unsaid',
      temporal: { type: 'epoch' },
      from: Date.parse(DAY_FROM),
      to: Date.parse(DAY_TO),
    },
    {
      // The last second of the day is still inside it.
      name: 'epoch seconds',
      temporal: { type: 'epoch', timeUnit: 'SECONDS' },
      from: Date.parse(DAY_FROM) / 1000,
      to: Math.floor(Date.parse(DAY_TO) / 1000),
    },
    {
      name: 'a native date, as ISO 8601',
      temporal: { type: 'date' },
      from: DAY_FROM,
      to: DAY_TO,
    },
  ];

  describe.each(representations)('as $name', ({ temporal, from, to }) => {
    const field = fieldOf(temporal);

    it.each([
      ['a preset', today],
      ['an absolute day', absoluteDay],
    ])('writes both bounds of BETWEEN over %s', (_, value) => {
      expect(compileOne(field, 'BETWEEN', value)).toEqual({
        op: FilterOperator.BETWEEN,
        field: 'eventTime',
        lowerBound: from,
        upperBound: to,
      });
    });

    it('writes GTE on the start edge and LTE on the end edge', () => {
      expect(compileOne(field, 'GTE', today)).toEqual({
        op: FilterOperator.GTE,
        field: 'eventTime',
        value: from,
      });
      expect(compileOne(field, 'LTE', today)).toEqual({
        op: FilterOperator.LTE,
        field: 'eventTime',
        value: to,
      });
    });

    it('writes an open-ended range as GTE', () => {
      expect(
        compileOne(field, 'BETWEEN', { type: 'absolute', from: '2026-09-20' }),
      ).toEqual({ op: FilterOperator.GTE, field: 'eventTime', value: from });
    });

    it('writes a relative window in the same representation', () => {
      const window = compileOne(field, 'BETWEEN', lastDay) as {
        lowerBound: unknown;
        upperBound: unknown;
      };
      const expected = (iso: string) =>
        typeof from === 'string'
          ? iso
          : temporal?.type === 'epoch' && temporal.timeUnit === 'SECONDS'
            ? Math.floor(Date.parse(iso) / 1000)
            : Date.parse(iso);
      expect(window.lowerBound).toEqual(expected('2026-09-19T04:00:00.000Z'));
      expect(window.upperBound).toEqual(expected('2026-09-20T04:00:00.000Z'));
    });

    it('leaves the presence operators without a value', () => {
      expect(compileOne(field, 'IS_NULL', null)).toEqual({
        op: FilterOperator.IS_NULL,
        field: 'eventTime',
      });
    });
  });

  it('holds for a date field too', () => {
    const day: FieldDefinition = {
      name: 'dueOn',
      label: 'Due',
      kind: 'date',
    };
    expect(compileOne(day, 'BETWEEN', absoluteDay)).toMatchObject({
      lowerBound: Date.parse(DAY_FROM),
      upperBound: Date.parse(DAY_TO),
    });
  });

  it('follows the element field inside an element match', () => {
    const retries: FieldDefinition = {
      name: 'retries',
      label: 'Retries',
      kind: 'elementMatch',
      elements: [
        { name: 'retryAt', label: 'Retried at', kind: 'datetime' },
        {
          name: 'loggedAt',
          label: 'Logged at',
          kind: 'datetime',
          temporal: { type: 'date' },
        },
      ],
    };
    const compiled = compileOne(retries, 'ELEMENT_MATCH', {
      op: 'and',
      children: [
        { field: 'retries.retryAt', operator: 'BETWEEN', value: today },
        { field: 'retries.loggedAt', operator: 'GTE', value: today },
      ],
    });
    expect(compiled).toEqual({
      op: FilterOperator.ELEMENT_MATCH,
      field: 'retries',
      predicate: {
        op: FilterOperator.AND,
        operands: [
          {
            op: FilterOperator.BETWEEN,
            field: 'retryAt',
            lowerBound: Date.parse(DAY_FROM),
            upperBound: Date.parse(DAY_TO),
          },
          { op: FilterOperator.GTE, field: 'loggedAt', value: DAY_FROM },
        ],
      },
    });
  });

  it('writes a drilled DATE_HISTOGRAM bucket as integers on an epoch field', () => {
    // The bucket key comes back as epoch milliseconds, the drilled condition
    // stores the bucket as ISO text, and the compile writes it as the store
    // keeps it: the bucket's first and last millisecond.
    const fields = [fieldOf()];
    const start = Date.parse(DAY_FROM);
    const conditions = drillConditions(
      analysisConfig({
        groups: [
          {
            alias: 'day',
            field: 'eventTime',
            type: 'DATE_HISTOGRAM',
            unit: 'DAY',
            timeZone: SHANGHAI,
          },
        ],
      }),
      fields,
      builtinFieldKinds,
      { day: start },
      { timeZone: SHANGHAI },
    ) as FilterNode[];
    expect(
      compileFilter(
        fields,
        { op: 'and', children: conditions },
        builtinFieldKinds,
        context,
      ),
    ).toEqual({
      op: FilterOperator.BETWEEN,
      field: 'eventTime',
      lowerBound: start,
      upperBound: Date.parse(DAY_TO),
    });
  });
});

describe('admitting a temporal declaration', () => {
  const codes = (field: Partial<FieldDefinition>) =>
    validateDefinition(
      {
        id: 'executions',
        title: 'Executions',
        kind: 'data',
        source: 'executions',
        fields: [{ name: 'at', label: 'At', kind: 'datetime', ...field }],
      },
      builtinFieldKinds,
    ).map(found => found.code);

  it.each([
    [{ type: 'epoch' }],
    [{ type: 'epoch', timeUnit: 'MILLISECONDS' }],
    [{ type: 'epoch', timeUnit: 'SECONDS' }],
    [{ type: 'date' }],
  ] as const)('accepts %j', temporal => {
    expect(codes({ temporal })).toEqual([]);
    expect(codes({ kind: 'date', temporal })).toEqual([]);
  });

  it.each([
    [{ type: 'formatted', pattern: 'yyyy-MM-dd' }],
    [{ type: 'epoch', timeUnit: 'NANOSECONDS' }],
    ['epoch'],
    [null],
  ])('refuses %j, which it cannot write', temporal => {
    expect(codes({ temporal: temporal as never })).toEqual([
      'definition.field.temporal-invalid',
    ]);
  });

  it('refuses one on a field whose conditions write no time', () => {
    expect(codes({ kind: 'number', temporal: { type: 'epoch' } })).toEqual([
      'definition.field.temporal-misplaced',
    ]);
  });

  it('judges an element field the same way', () => {
    expect(
      codes({
        name: 'retries',
        kind: 'elementMatch',
        elements: [
          {
            name: 'retryAt',
            label: 'Retried at',
            kind: 'datetime',
            temporal: { type: 'epoch', timeUnit: 'MINUTES' as never },
          },
        ],
      }),
    ).toEqual(['definition.field.temporal-invalid']);
  });
});
