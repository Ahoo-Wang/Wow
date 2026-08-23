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
  DeletionState,
  filter,
  FilterOperator,
  StringComparison,
  type ElementFilterExpression,
} from '../../src';
import { describe, expect, expectTypeOf, it } from 'vitest';

describe('filter', () => {
  it('builds the Wow FilterExpression wire shape', () => {
    expect(
      filter.and(
        filter.deletion(DeletionState.ACTIVE),
        filter.eq('state.status', 'PAID'),
        filter.elementMatch('state.items', filter.gt('quantity', 0)),
        filter.search('wow', 'state.name'),
        filter.today('state.createdAt', { zoneId: 'UTC' }),
      ),
    ).toEqual({
      op: FilterOperator.AND,
      operands: [
        { op: FilterOperator.DELETION, state: DeletionState.ACTIVE },
        { op: FilterOperator.EQ, field: 'state.status', value: 'PAID' },
        {
          op: FilterOperator.ELEMENT_MATCH,
          field: 'state.items',
          predicate: { op: FilterOperator.GT, field: 'quantity', value: 0 },
        },
        { op: FilterOperator.SEARCH, query: 'wow', fields: ['state.name'] },
        {
          op: FilterOperator.TODAY,
          field: 'state.createdAt',
          zoneId: 'UTC',
        },
      ],
    });
  });

  it('uses explicit string comparison semantics', () => {
    expect(
      filter.contains('state.name', 'wow', StringComparison.CASE_INSENSITIVE),
    ).toEqual({
      op: FilterOperator.CONTAINS,
      field: 'state.name',
      value: 'wow',
      stringComparison: StringComparison.CASE_INSENSITIVE,
    });
  });

  it.each<{
    name: string;
    create: () => unknown;
    expected: unknown;
  }>([
    {
      name: 'match all',
      create: () => filter.matchAll(),
      expected: { op: FilterOperator.MATCH_ALL },
    },
    {
      name: 'match none',
      create: () => filter.matchNone(),
      expected: { op: FilterOperator.MATCH_NONE },
    },
    {
      name: 'or',
      create: () => filter.or(filter.eq('status', 'PAID')),
      expected: {
        op: FilterOperator.OR,
        operands: [{ op: FilterOperator.EQ, field: 'status', value: 'PAID' }],
      },
    },
    {
      name: 'nor',
      create: () => filter.nor(filter.ne('status', null)),
      expected: {
        op: FilterOperator.NOR,
        operands: [{ op: FilterOperator.NE, field: 'status', value: null }],
      },
    },
    {
      name: 'greater than or equal',
      create: () => filter.gte('score', 80),
      expected: { op: FilterOperator.GTE, field: 'score', value: 80 },
    },
    {
      name: 'less than',
      create: () => filter.lt('score', 100),
      expected: { op: FilterOperator.LT, field: 'score', value: 100 },
    },
    {
      name: 'less than or equal',
      create: () => filter.lte('score', 100),
      expected: { op: FilterOperator.LTE, field: 'score', value: 100 },
    },
    {
      name: 'starts with',
      create: () => filter.startsWith('name', 'Wow'),
      expected: {
        op: FilterOperator.STARTS_WITH,
        field: 'name',
        value: 'Wow',
        stringComparison: StringComparison.CASE_SENSITIVE,
      },
    },
    {
      name: 'ends with',
      create: () =>
        filter.endsWith('name', 'Wow', StringComparison.CASE_INSENSITIVE),
      expected: {
        op: FilterOperator.ENDS_WITH,
        field: 'name',
        value: 'Wow',
        stringComparison: StringComparison.CASE_INSENSITIVE,
      },
    },
    {
      name: 'in',
      create: () => filter.isIn('status', 'PAID', 'SHIPPED'),
      expected: {
        op: FilterOperator.IN,
        field: 'status',
        values: ['PAID', 'SHIPPED'],
      },
    },
    {
      name: 'not in',
      create: () => filter.notIn('status', 'CANCELLED'),
      expected: {
        op: FilterOperator.NOT_IN,
        field: 'status',
        values: ['CANCELLED'],
      },
    },
    {
      name: 'between',
      create: () => filter.between('score', 60, 100),
      expected: {
        op: FilterOperator.BETWEEN,
        field: 'score',
        lowerBound: 60,
        upperBound: 100,
      },
    },
    {
      name: 'contains all',
      create: () => filter.containsAll('tags', 'wow', 'cqrs'),
      expected: {
        op: FilterOperator.CONTAINS_ALL,
        field: 'tags',
        values: ['wow', 'cqrs'],
      },
    },
    {
      name: 'is empty',
      create: () => filter.isEmpty('tags'),
      expected: { op: FilterOperator.IS_EMPTY, field: 'tags' },
    },
    {
      name: 'is null',
      create: () => filter.isNull('deletedAt'),
      expected: { op: FilterOperator.IS_NULL, field: 'deletedAt' },
    },
    {
      name: 'is not null',
      create: () => filter.isNotNull('createdAt'),
      expected: { op: FilterOperator.IS_NOT_NULL, field: 'createdAt' },
    },
    {
      name: 'exists',
      create: () => filter.exists('metadata.owner'),
      expected: { op: FilterOperator.EXISTS, field: 'metadata.owner' },
    },
    {
      name: 'not exists',
      create: () => filter.notExists('metadata.owner'),
      expected: { op: FilterOperator.NOT_EXISTS, field: 'metadata.owner' },
    },
    {
      name: 'search without fields',
      create: () => filter.search('wow'),
      expected: { op: FilterOperator.SEARCH, query: 'wow', fields: [] },
    },
    {
      name: 'nested element match',
      create: () =>
        filter.elementMatch(
          'groups',
          filter.elementMatch('items', filter.eq('sku', 'product-1')),
        ),
      expected: {
        op: FilterOperator.ELEMENT_MATCH,
        field: 'groups',
        predicate: {
          op: FilterOperator.ELEMENT_MATCH,
          field: 'items',
          predicate: {
            op: FilterOperator.EQ,
            field: 'sku',
            value: 'product-1',
          },
        },
      },
    },
    {
      name: 'today',
      create: () => filter.today('createdAt'),
      expected: { op: FilterOperator.TODAY, field: 'createdAt' },
    },
    {
      name: 'today with JVM boundary options',
      create: () =>
        filter.today('createdAt', {
          zoneId: '+18:00',
          datePattern: 'ppHH[',
        }),
      expected: {
        op: FilterOperator.TODAY,
        field: 'createdAt',
        zoneId: '+18:00',
        datePattern: 'ppHH[',
      },
    },
    {
      name: 'padded fraction separated from a numeric field',
      create: () =>
        filter.today('createdAt', {
          datePattern: 'pS:mm',
        }),
      expected: {
        op: FilterOperator.TODAY,
        field: 'createdAt',
        datePattern: 'pS:mm',
      },
    },
    {
      name: 'padded numeric field without adjacency',
      create: () => filter.today('createdAt', { datePattern: 'py' }),
      expected: {
        op: FilterOperator.TODAY,
        field: 'createdAt',
        datePattern: 'py',
      },
    },
    {
      name: 'wide week-based-year pattern',
      create: () =>
        filter.today('createdAt', {
          datePattern: 'YYYYYYYYYYYYYYYYYYYY',
        }),
      expected: {
        op: FilterOperator.TODAY,
        field: 'createdAt',
        datePattern: 'YYYYYYYYYYYYYYYYYYYY',
      },
    },
    {
      name: 'before today',
      create: () =>
        filter.beforeToday('createdAt', '09:30', {
          zoneId: 'UTC+05:30',
          datePattern: "yyyy-MM-dd 'o''clock'['T'HH:mm:ss]",
        }),
      expected: {
        op: FilterOperator.BEFORE_TODAY,
        field: 'createdAt',
        time: '09:30',
        zoneId: 'UTC+05:30',
        datePattern: "yyyy-MM-dd 'o''clock'['T'HH:mm:ss]",
      },
    },
    {
      name: 'tomorrow',
      create: () => filter.tomorrow('createdAt'),
      expected: { op: FilterOperator.TOMORROW, field: 'createdAt' },
    },
    {
      name: 'this week',
      create: () => filter.thisWeek('createdAt'),
      expected: { op: FilterOperator.THIS_WEEK, field: 'createdAt' },
    },
    {
      name: 'next week',
      create: () => filter.nextWeek('createdAt'),
      expected: { op: FilterOperator.NEXT_WEEK, field: 'createdAt' },
    },
    {
      name: 'last week',
      create: () => filter.lastWeek('createdAt'),
      expected: { op: FilterOperator.LAST_WEEK, field: 'createdAt' },
    },
    {
      name: 'this month',
      create: () => filter.thisMonth('createdAt'),
      expected: { op: FilterOperator.THIS_MONTH, field: 'createdAt' },
    },
    {
      name: 'last month',
      create: () => filter.lastMonth('createdAt'),
      expected: { op: FilterOperator.LAST_MONTH, field: 'createdAt' },
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
  ])('builds the $name wire shape', ({ create, expected }) => {
    expect(create()).toEqual(expected);
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
      expect(expression).toEqual({ zoneId: 'UTC', op, field: 'createdAt' });
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
  });

  it('restricts element predicates recursively', () => {
    const predicate = filter.and(
      filter.eq('sku', 'product-1'),
      filter.gt('quantity', 0),
    );

    expectTypeOf(predicate).toMatchTypeOf<
      ElementFilterExpression<'sku' | 'quantity'>
    >();
    expect(filter.elementMatch('state.items', predicate)).toEqual({
      op: FilterOperator.ELEMENT_MATCH,
      field: 'state.items',
      predicate,
    });

    const invalidElementPredicates = () => {
      // @ts-expect-error DELETION cannot be scoped to an array element.
      filter.elementMatch('state.items', filter.deletion(DeletionState.ACTIVE));
      // @ts-expect-error SEARCH cannot be scoped to an array element.
      filter.elementMatch('state.items', filter.search('wow'));
      filter.elementMatch(
        'state.items',
        // @ts-expect-error Unsupported filters remain invalid inside logical predicates.
        filter.and(filter.eq('sku', 'product-1'), filter.search('wow')),
      );
    };
    expectTypeOf(invalidElementPredicates).toBeFunction();
  });

  it('rejects unsupported element predicates at runtime', () => {
    const deletion = filter.deletion(DeletionState.ACTIVE);
    const nestedSearch = filter.and(
      filter.eq('sku', 'product-1'),
      filter.search('wow'),
    );

    expect(() =>
      filter.elementMatch(
        'state.items',
        deletion as unknown as ElementFilterExpression,
      ),
    ).toThrow();
    expect(() =>
      filter.elementMatch(
        'state.items',
        nestedSearch as unknown as ElementFilterExpression,
      ),
    ).toThrow();
  });

  it('rejects malformed zone offsets with a validation error', () => {
    expect(() => filter.today('createdAt', { zoneId: '+invalid' })).toThrow(
      'zoneId is invalid: [+invalid].',
    );
  });

  it.each([
    ['empty logical operands', () => Reflect.apply(filter.and, null, [])],
    [
      'undefined AND operand',
      () => Reflect.apply(filter.and, null, [undefined]),
    ],
    ['null OR operand', () => Reflect.apply(filter.or, null, [null])],
    [
      'undefined NOR operand',
      () => Reflect.apply(filter.nor, null, [undefined]),
    ],
    [
      'empty collection values',
      () => Reflect.apply(filter.isIn, null, ['status']),
    ],
    ['invalid logical field', () => filter.eq('bad field', 'value')],
    [
      'non-string logical field',
      () => Reflect.apply(filter.eq, null, [1, 'value']),
    ],
    [
      'object equality value',
      () => Reflect.apply(filter.eq, null, ['status', {}]),
    ],
    [
      'null comparison value',
      () => Reflect.apply(filter.gt, null, ['score', null]),
    ],
    ['non-finite value', () => filter.gt('score', Number.POSITIVE_INFINITY)],
    [
      'null collection value',
      () => Reflect.apply(filter.isIn, null, ['status', null]),
    ],
    ['blank search query', () => filter.search(' ')],
    ['non-string search query', () => Reflect.apply(filter.search, null, [1])],
    [
      'non-string string operand',
      () => Reflect.apply(filter.contains, null, ['name', 1]),
    ],
    [
      'invalid string comparison',
      () => Reflect.apply(filter.contains, null, ['name', 'wow', 'INVALID']),
    ],
    [
      'invalid deletion state',
      () => Reflect.apply(filter.deletion, null, ['INVALID']),
    ],
    [
      'invalid before-today time',
      () => filter.beforeToday('createdAt', '25:00'),
    ],
    ['non-positive recent days', () => filter.recentDays('createdAt', 0)],
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
    [
      'blank date pattern',
      () => filter.today('createdAt', { datePattern: '' }),
    ],
    [
      'unexpected closing date pattern bracket',
      () => filter.today('createdAt', { datePattern: ']' }),
    ],
    [
      'unsupported date pattern letter',
      () => filter.today('createdAt', { datePattern: 'jj' }),
    ],
    [
      'invalid date pattern letter count',
      () => filter.today('createdAt', { datePattern: 'cc' }),
    ],
    [
      'date pattern ending with padding',
      () => filter.today('createdAt', { datePattern: 'p' }),
    ],
    [
      'padded adjacent numeric date pattern',
      () => filter.today('createdAt', { datePattern: 'pym' }),
    ],
    [
      'padded fractional date pattern adjacent to a numeric field',
      () => filter.today('createdAt', { datePattern: 'pSH' }),
    ],
    [
      'forbidden date pattern character',
      () => filter.today('createdAt', { datePattern: 'yyyy{MM}' }),
    ],
    [
      'unclosed date pattern quote',
      () => filter.today('createdAt', { datePattern: "yyyy'" }),
    ],
    [
      'non-string date pattern',
      () =>
        Reflect.apply(filter.today, null, ['createdAt', { datePattern: 1 }]),
    ],
  ])('rejects %s', (_name, create) => {
    expect(create).toThrow();
  });
});
