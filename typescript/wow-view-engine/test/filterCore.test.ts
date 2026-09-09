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
import {
  type DeletionState,
  filter,
  FilterOperator as Op,
  SearchMode,
  StringComparison,
  TimeUnit,
  type FilterExpression,
} from '@ahoo-wang/fetcher-wow';
import { expect, it } from 'vitest';
import {
  compileFilterConfiguration,
  FILTER_OPERATORS,
} from '../src/filter/filterCore';
import type { FilterFieldDefinition } from '../src/filter/filterModel';
import {
  calendar,
  compile,
  expressions,
  configurations,
  fields,
  node,
} from './fixtures/filterCore.js';

it('covers all 50 Wow operators with a global timezone and preserves other optional parameters', () => {
  expect(new Set(expressions.map(value => value.op)).size).toBe(50);
  expect(Object.keys(FILTER_OPERATORS).sort()).toEqual(
    Object.values(Op).sort(),
  );
  for (const [index, expression] of expressions.entries()) {
    const result = compile(configurations[index]);
    expect(result.errors, expression.op).toEqual([]);
    expect(result.expression, expression.op).toStrictEqual(expression);
  }
});

it('keeps nested order, single-child wrappers, explicit options and independent draft ids', () => {
  const expression = filter.and<string>([
    filter.or([
      filter.search('词', { fields: ['name'], mode: SearchMode.PHRASE }),
    ]),
    filter.nor([
      filter.today('created', {
        zoneId: '+08:00',
        datePattern: 'yyyy-MM-dd',
        timeUnit: TimeUnit.DAYS,
      }),
    ]),
  ]);
  const makeRoot = () =>
    node(
      Op.AND,
      undefined,
      {},
      {
        operands: [
          node(
            Op.OR,
            undefined,
            {},
            {
              operands: [
                node(Op.SEARCH, undefined, {
                  query: '词',
                  fields: ['name'],
                  mode: SearchMode.PHRASE,
                }),
              ],
            },
          ),
          node(
            Op.NOR,
            undefined,
            {},
            {
              operands: [
                node(Op.TODAY, 'created', {
                  zoneId: '+08:00',
                  datePattern: 'yyyy-MM-dd',
                  timeUnit: TimeUnit.DAYS,
                }),
              ],
            },
          ),
        ],
      },
    );
  const draft = makeRoot();
  expect(draft.id).not.toBe(makeRoot().id);
  expect(draft.operands![0].id).not.toBe(draft.id);
  expect(compile(draft, undefined, '+08:00').expression).toStrictEqual(
    expression,
  );
  (draft.operands![0].operands![0].props.fields as string[]).push('amount');
  expect(expression.operands[0]).toEqual(
    filter.or([
      filter.search('词', { fields: ['name'], mode: SearchMode.PHRASE }),
    ]),
  );
});

it('omits a cleared deletion draft but rejects invalid states and missing wire states', () => {
  const cleared = node(Op.DELETION, undefined, { state: undefined });
  expect(
    compileFilterConfiguration({ mode: 'advanced', root: cleared }, []),
  ).toEqual({
    expression: { op: Op.MATCH_ALL },
    errors: [],
  });
  expect(
    compile(
      node(
        Op.OR,
        undefined,
        {},
        { operands: [cleared, node(Op.EQ, 'amount', { value: 0 })] },
      ),
    ).expression,
  ).toEqual({
    op: Op.OR,
    operands: [{ op: Op.EQ, field: 'amount', value: 0 }],
  });
  for (const state of ['', 'BAD', null, 0, false]) {
    expect(
      compileFilterConfiguration(
        {
          mode: 'advanced',
          root: node(Op.DELETION, undefined, { state: state as DeletionState }),
        },
        [],
      ).errors,
    ).not.toEqual([]);
  }
  expect(() =>
    parseFilterOutput({ op: Op.DELETION } as FilterExpression),
  ).toThrow();
});

it('does not silently ignore invalid optional parameters or invalid special values', () => {
  for (const draft of [
    node(Op.SEARCH, undefined, { query: '' }),
    node(Op.SEARCH, undefined, { query: 'x', mode: 'BAD' as SearchMode }),
    node(Op.DELETION, undefined, { state: 'BAD' as DeletionState }),
    node(Op.ID, undefined, { value: 0 }),
    node(Op.TODAY, 'created', { timeUnit: 'BAD' as TimeUnit }),
    node(Op.BEFORE_TODAY, 'created', { time: '25:00' }),
    node(Op.RECENT_DAYS, 'created', { days: '1.5' }),
    node(Op.CONTAINS, 'name', {
      value: 'x',
      stringComparison: 'BAD' as StringComparison,
    }),
  ])
    expect(compile(draft).errors).not.toEqual([]);
  for (const timeZone of ['', 'Bad/Zone'])
    expect(
      compile(node(Op.TODAY, 'created'), undefined, timeZone).errors,
    ).not.toEqual([]);
});

it('rejects sparse collection entries, search fields and logical operands', () => {
  const sparse = new Array(1);
  for (const draft of [
    node(Op.IN, 'name', { values: sparse }),
    node(Op.SEARCH, undefined, { query: 'x', fields: sparse }),
    node(Op.AND, undefined, {}, { operands: sparse }),
  ])
    expect(compile(draft).errors).not.toEqual([]);
  expect(() => parseFilterOutput({ op: Op.AND, operands: sparse })).toThrow();
});

it('rejects invalid compiler wire output', () => {
  for (const expression of [
    { op: Op.EQ, field: 'amount', value: { type: 'number', value: '1' } },
    {
      op: Op.EQ,
      field: 'created',
      value: { date: '2024-01-01', time: '12:00' },
    },
    { op: Op.EQ, field: 'amount' },
    { op: Op.AND, operands: [] },
    {
      op: Op.IN,
      field: 'amount',
      values: [1, { type: 'number', value: '2' }],
    },
    {
      op: Op.BETWEEN,
      field: 'amount',
      lowerBound: 1,
      upperBound: { type: 'number', value: '2' },
    },
    { op: Op.RECENT_DAYS, field: 'created', days: '1' },
    { op: Op.EQ, field: 'amount', value: 1, query: 'unexpected' },
    {
      op: Op.ELEMENT_MATCH,
      field: 'items',
      predicate: { op: Op.ID, value: 'x' },
    },
  ])
    expect(() => parseFilterOutput(expression as FilterExpression)).toThrow();
});

it('validates the protocol field path before omitting an unset predicate', () => {
  const invalidFields: FilterFieldDefinition[] = [
    { field: 'bad field', label: '错误字段', type: 'number' },
  ];
  expect(compile(node(Op.EQ, 'bad field'), invalidFields).errors).not.toEqual(
    [],
  );
});

it('validates malformed operands, search scope and extraneous operator parameters', () => {
  for (const draft of [
    node(Op.AND, undefined, {}, { operands: undefined }),
    node(Op.EQ, 'name', { value: 'x', query: 'ignored' }),
    node(Op.SEARCH, undefined, {
      query: 'x',
      fields: null as unknown as string[],
    }),
    node(Op.CONTAINS, 'name', {
      stringComparison: 'BAD' as StringComparison,
    }),
  ])
    expect(compile(draft).errors).not.toEqual([]);
});

it('retains relative-time and string/search options when compiled in the requested global timezone', () => {
  for (const [draft, expression] of [
    [
      node(Op.CONTAINS, 'name', {
        value: '',
        stringComparison: StringComparison.CASE_SENSITIVE,
      }),
      filter.contains('name', '', StringComparison.CASE_SENSITIVE),
    ] as const,
    [
      node(Op.SEARCH, undefined, { query: 'x', fields: [] }, {}),
      { op: Op.SEARCH, query: 'x', fields: [] },
    ] as const,
    [
      node(Op.SEARCH, undefined, { query: 'x', mode: SearchMode.TERMS }, {}),
      { op: Op.SEARCH, query: 'x', mode: SearchMode.TERMS },
    ] as const,
    ...calendar.map(
      op =>
        [
          node(op, 'created', {
            zoneId: 'Europe/London',
            datePattern: 'yyyy-MM-dd',
            timeUnit: TimeUnit.SECONDS,
          }),
          {
            op,
            field: 'created',
            zoneId: 'Europe/London',
            datePattern: 'yyyy-MM-dd',
            timeUnit: TimeUnit.SECONDS,
          },
        ] as const,
    ),
  ])
    expect(compile(draft, undefined, 'Europe/London').expression).toStrictEqual(
      expression,
    );
});

it('uses the global or local timezone for relative filters instead of retained node parameters', () => {
  const draft = node(Op.BEFORE_TODAY, 'created', {
    time: '12:30:59.123456789',
    zoneId: 'UTC',
    datePattern: 'yyyy-MM-dd',
    timeUnit: TimeUnit.SECONDS,
  });
  for (const timeZone of ['America/New_York', undefined])
    expect(
      compileFilterConfiguration(
        { mode: 'advanced', root: draft },
        fields,
        undefined,
        undefined,
        timeZone,
      ).expression,
    ).toEqual({
      op: Op.BEFORE_TODAY,
      field: 'created',
      time: '12:30:59',
      zoneId: timeZone ?? Intl.DateTimeFormat().resolvedOptions().timeZone,
      datePattern: 'yyyy-MM-dd',
      timeUnit: TimeUnit.SECONDS,
    });
  expect(draft.props.zoneId).toBe('UTC');
});

import { parseFilterOutput } from '../src/filter/filterProtocol.js';
