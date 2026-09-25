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
  SearchMode,
  StringComparison,
  TimeUnit,
  type ElementFilterExpression,
  type MetadataFilter,
  type QueryField,
} from '../../src';
import { describe, expect, expectTypeOf, it } from 'vitest';

describe('filter', () => {
  it('exposes QueryField as the field-path type', () => {
    expectTypeOf<QueryField<'state.status'>>().toEqualTypeOf<'state.status'>();
  });

  it('types equality filters with scalar values only', () => {
    const assertCanonicalEqualityValues = () => {
      // @ts-expect-error Canonical Wow equality filters do not accept arrays.
      filter.eq('status', ['PAID']);
      // @ts-expect-error Canonical Wow equality filters do not accept arrays.
      filter.ne('status', ['PAID']);
    };
    expectTypeOf(assertCanonicalEqualityValues).toBeFunction();
  });

  it('uses QueryField terminology in validation errors', () => {
    expect(() => filter.eq('bad field', 'value')).toThrow(
      'Query field is invalid: [bad field].',
    );
  });

  it('builds the Wow FilterExpression wire shape', () => {
    expect(
      filter.and([
        filter.deletion(DeletionState.ACTIVE),
        filter.eq('state.status', 'PAID'),
        filter.elementMatch('state.items', filter.gt('quantity', 0)),
        filter.search('wow', { fields: ['state.name'] }),
        filter.today('state.createdAt', { zoneId: 'UTC' }),
      ]),
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
        {
          op: FilterOperator.SEARCH,
          query: 'wow',
          mode: SearchMode.TERMS,
          fields: ['state.name'],
        },
        {
          op: FilterOperator.TODAY,
          field: 'state.createdAt',
          timeUnit: TimeUnit.MILLISECONDS,
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

  it('builds operand-free empty string filters', () => {
    expect([
      filter.isEmptyString('state.name'),
      filter.isNotEmptyString('state.name'),
    ]).toEqual([
      { op: 'IS_EMPTY_STRING', field: 'state.name' },
      { op: 'IS_NOT_EMPTY_STRING', field: 'state.name' },
    ]);
  });

  it('builds search filters with explicit defaults and phrase mode', () => {
    expect(filter.search('wow')).toEqual({
      op: FilterOperator.SEARCH,
      query: 'wow',
      mode: SearchMode.TERMS,
      fields: [],
    });
    expect(
      filter.search('event sourcing', {
        mode: SearchMode.PHRASE,
        fields: ['state.title', 'state.description'],
      }),
    ).toEqual({
      op: FilterOperator.SEARCH,
      query: 'event sourcing',
      mode: SearchMode.PHRASE,
      fields: ['state.title', 'state.description'],
    });
  });

  const assertOldSearchSignatureRemoved = () => {
    // @ts-expect-error The new Filter DSL does not retain rest-field compatibility.
    filter.search('wow', 'state.name');
  };
  expectTypeOf(assertOldSearchSignatureRemoved).toBeFunction();

  it('rejects the removed runtime search signature', () => {
    expect(() =>
      Reflect.apply(filter.search, null, ['wow', 'state.name']),
    ).toThrow(TypeError);
  });

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
      timeUnit: TimeUnit.MILLISECONDS,
    });
  });

  it('emits every Wow relative-time unit', () => {
    expect(
      Object.values(TimeUnit).map(timeUnit =>
        filter.today('createdAt', { timeUnit }),
      ),
    ).toEqual(
      Object.values(TimeUnit).map(timeUnit => ({
        op: FilterOperator.TODAY,
        field: 'createdAt',
        timeUnit,
      })),
    );
  });

  it('builds metadata filters', () => {
    const expressions: MetadataFilter[] = [
      filter.id('snapshot-1'),
      filter.ids(['snapshot-1', 'snapshot-2']),
      filter.aggregateId('order-1'),
      filter.aggregateIds(['order-1', 'order-2']),
      filter.tenantId('tenant-1'),
      filter.ownerId('owner-1'),
      filter.spaceId('space-1'),
    ];

    expect(expressions).toEqual([
      { op: FilterOperator.ID, value: 'snapshot-1' },
      { op: FilterOperator.IDS, values: ['snapshot-1', 'snapshot-2'] },
      { op: FilterOperator.AGGREGATE_ID, value: 'order-1' },
      {
        op: FilterOperator.AGGREGATE_IDS,
        values: ['order-1', 'order-2'],
      },
      { op: FilterOperator.TENANT_ID, value: 'tenant-1' },
      { op: FilterOperator.OWNER_ID, value: 'owner-1' },
      { op: FilterOperator.SPACE_ID, value: 'space-1' },
    ]);
  });

  it('accepts readonly arrays for value-list builders', () => {
    const ids = ['snapshot-1', 'snapshot-2'] as const;
    const aggregateIds = ['order-1', 'order-2'] as const;
    const statuses = ['PAID', 'SHIPPED'] as const;
    const tags = ['wow', 'cqrs'] as const;

    expect([
      filter.ids(ids),
      filter.aggregateIds(aggregateIds),
      filter.isIn('status', statuses),
      filter.notIn('status', statuses),
      filter.containsAll('tags', tags),
    ]).toEqual([
      { op: 'IDS', values: ['snapshot-1', 'snapshot-2'] },
      { op: 'AGGREGATE_IDS', values: ['order-1', 'order-2'] },
      { op: 'IN', field: 'status', values: ['PAID', 'SHIPPED'] },
      { op: 'NOT_IN', field: 'status', values: ['PAID', 'SHIPPED'] },
      { op: 'CONTAINS_ALL', field: 'tags', values: ['wow', 'cqrs'] },
    ]);
    expect(filter.aggregateIds(aggregateIds).values).not.toBe(aggregateIds);
  });

  it('accepts readonly arrays for logical builders', () => {
    const operands = [
      filter.eq('status', 'PAID'),
      filter.isNotNull('paidAt'),
    ] as const;

    expect([
      filter.and(operands),
      filter.or(operands),
      filter.nor(operands),
    ]).toEqual([
      { op: 'AND', operands },
      { op: 'OR', operands },
      { op: 'NOR', operands },
    ]);
    expect(filter.and(operands).operands).not.toBe(operands);
  });

  it.each([
    ['AND', () => filter.and([]), 'AND operands cannot be empty.'],
    ['OR', () => filter.or([]), 'OR operands cannot be empty.'],
    ['NOR', () => filter.nor([]), 'NOR operands cannot be empty.'],
    ['IDS', () => filter.ids([]), 'IDS values cannot be empty.'],
    [
      'AGGREGATE_IDS',
      () => filter.aggregateIds([]),
      'AGGREGATE_IDS values cannot be empty.',
    ],
    ['IN', () => filter.isIn('status', []), 'IN values cannot be empty.'],
    [
      'NOT_IN',
      () => filter.notIn('status', []),
      'NOT_IN values cannot be empty.',
    ],
    [
      'CONTAINS_ALL',
      () => filter.containsAll('tags', []),
      'CONTAINS_ALL values cannot be empty.',
    ],
  ])('rejects an empty %s array', (_name, create, message) => {
    expect(create).toThrow(message);
  });

  it('supports scalar equality values and query fields', () => {
    expect(filter.eq('@metadata.tags', 'wow')).toEqual({
      op: FilterOperator.EQ,
      field: '@metadata.tags',
      value: 'wow',
    });
    expect(filter.eq('state.@metadata.tags', 'wow')).toEqual({
      op: FilterOperator.EQ,
      field: 'state.@metadata.tags',
      value: 'wow',
    });
    expect(filter.ne('state.status', 'CANCELLED')).toEqual({
      op: FilterOperator.NE,
      field: 'state.status',
      value: 'CANCELLED',
    });
  });

  it('rejects array equality values unsupported by canonical Wow REST', () => {
    expect(() => Reflect.apply(filter.eq, null, ['status', ['PAID']])).toThrow(
      'Filter value must be a JSON scalar.',
    );
    expect(() => Reflect.apply(filter.ne, null, ['status', ['PAID']])).toThrow(
      'Filter value must be a JSON scalar.',
    );
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
      create: () => filter.or([filter.eq('status', 'PAID')]),
      expected: {
        op: FilterOperator.OR,
        operands: [{ op: FilterOperator.EQ, field: 'status', value: 'PAID' }],
      },
    },
    {
      name: 'nor',
      create: () => filter.nor([filter.ne('status', null)]),
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
      create: () => filter.isIn('status', ['PAID', 'SHIPPED']),
      expected: {
        op: FilterOperator.IN,
        field: 'status',
        values: ['PAID', 'SHIPPED'],
      },
    },
    {
      name: 'not in',
      create: () => filter.notIn('status', ['CANCELLED']),
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
      create: () => filter.containsAll('tags', ['wow', 'cqrs']),
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
      expected: {
        op: FilterOperator.SEARCH,
        query: 'wow',
        mode: SearchMode.TERMS,
        fields: [],
      },
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
      expected: {
        op: FilterOperator.TODAY,
        field: 'createdAt',
        timeUnit: TimeUnit.MILLISECONDS,
      },
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
        timeUnit: TimeUnit.MILLISECONDS,
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
        timeUnit: TimeUnit.MILLISECONDS,
        datePattern: 'pS:mm',
      },
    },
    {
      name: 'padded numeric field without adjacency',
      create: () => filter.today('createdAt', { datePattern: 'py' }),
      expected: {
        op: FilterOperator.TODAY,
        field: 'createdAt',
        timeUnit: TimeUnit.MILLISECONDS,
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
        timeUnit: TimeUnit.MILLISECONDS,
        datePattern: 'YYYYYYYYYYYYYYYYYYYY',
      },
    },
    {
      name: 'byte order mark, which Kotlin does not count as blank',
      create: () => filter.today('createdAt', { datePattern: '\uFEFF' }),
      expected: {
        op: FilterOperator.TODAY,
        field: 'createdAt',
        timeUnit: TimeUnit.MILLISECONDS,
        datePattern: '\uFEFF',
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
        timeUnit: TimeUnit.MILLISECONDS,
        time: '09:30',
        zoneId: 'UTC+05:30',
        datePattern: "yyyy-MM-dd 'o''clock'['T'HH:mm:ss]",
      },
    },
    {
      name: 'tomorrow',
      create: () => filter.tomorrow('createdAt'),
      expected: {
        op: FilterOperator.TOMORROW,
        field: 'createdAt',
        timeUnit: TimeUnit.MILLISECONDS,
      },
    },
    {
      name: 'this week',
      create: () => filter.thisWeek('createdAt'),
      expected: {
        op: FilterOperator.THIS_WEEK,
        field: 'createdAt',
        timeUnit: TimeUnit.MILLISECONDS,
      },
    },
    {
      name: 'next week',
      create: () => filter.nextWeek('createdAt'),
      expected: {
        op: FilterOperator.NEXT_WEEK,
        field: 'createdAt',
        timeUnit: TimeUnit.MILLISECONDS,
      },
    },
    {
      name: 'last week',
      create: () => filter.lastWeek('createdAt'),
      expected: {
        op: FilterOperator.LAST_WEEK,
        field: 'createdAt',
        timeUnit: TimeUnit.MILLISECONDS,
      },
    },
    {
      name: 'this month',
      create: () => filter.thisMonth('createdAt'),
      expected: {
        op: FilterOperator.THIS_MONTH,
        field: 'createdAt',
        timeUnit: TimeUnit.MILLISECONDS,
      },
    },
    {
      name: 'last month',
      create: () => filter.lastMonth('createdAt'),
      expected: {
        op: FilterOperator.LAST_MONTH,
        field: 'createdAt',
        timeUnit: TimeUnit.MILLISECONDS,
      },
    },
    {
      name: 'recent days',
      create: () => filter.recentDays('createdAt', 7),
      expected: {
        op: FilterOperator.RECENT_DAYS,
        field: 'createdAt',
        timeUnit: TimeUnit.MILLISECONDS,
        days: 7,
      },
    },
    {
      name: 'earlier days',
      create: () => filter.earlierDays('createdAt', 30),
      expected: {
        op: FilterOperator.EARLIER_DAYS,
        field: 'createdAt',
        timeUnit: TimeUnit.MILLISECONDS,
        days: 30,
      },
    },
    {
      name: 'before now, with the zero offset by default',
      create: () => filter.beforeNow('timeoutAt'),
      expected: {
        op: FilterOperator.BEFORE_NOW,
        field: 'timeoutAt',
        timeUnit: TimeUnit.MILLISECONDS,
        offset: 'PT0S',
      },
    },
    {
      name: 'after now, looking back',
      create: () =>
        filter.afterNow('createdAt', '-PT30M', {
          zoneId: 'UTC',
          datePattern: 'yyyy-MM-dd HH:mm:ss',
          timeUnit: TimeUnit.SECONDS,
        }),
      expected: {
        op: FilterOperator.AFTER_NOW,
        field: 'createdAt',
        timeUnit: TimeUnit.SECONDS,
        offset: '-PT30M',
        zoneId: 'UTC',
        datePattern: 'yyyy-MM-dd HH:mm:ss',
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
      timeUnit: TimeUnit.MILLISECONDS,
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
        timeUnit: TimeUnit.MILLISECONDS,
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
        timeUnit: TimeUnit.MILLISECONDS,
      });
    });
    expect(filter.beforeToday('createdAt', '09:30', options)).toEqual({
      zoneId: 'UTC',
      op: FilterOperator.BEFORE_TODAY,
      field: 'createdAt',
      timeUnit: TimeUnit.MILLISECONDS,
      time: '09:30',
    });
    expect(filter.recentDays('createdAt', 7, options)).toEqual({
      zoneId: 'UTC',
      op: FilterOperator.RECENT_DAYS,
      field: 'createdAt',
      timeUnit: TimeUnit.MILLISECONDS,
      days: 7,
    });
    expect(filter.earlierDays('createdAt', 30, options)).toEqual({
      zoneId: 'UTC',
      op: FilterOperator.EARLIER_DAYS,
      field: 'createdAt',
      timeUnit: TimeUnit.MILLISECONDS,
      days: 30,
    });
    expect(filter.beforeNow('createdAt', 'PT1H', options)).toEqual({
      zoneId: 'UTC',
      op: FilterOperator.BEFORE_NOW,
      field: 'createdAt',
      timeUnit: TimeUnit.MILLISECONDS,
      offset: 'PT1H',
    });
  });

  it('restricts element predicates recursively', () => {
    const predicate = filter.and([
      filter.eq('sku', 'product-1'),
      filter.gt('quantity', 0),
    ]);

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
      // @ts-expect-error Metadata filters cannot be scoped to an array element.
      filter.elementMatch('state.items', filter.id('snapshot-1'));
      filter.elementMatch(
        'state.items',
        // @ts-expect-error Unsupported filters remain invalid inside logical predicates.
        filter.and([filter.eq('sku', 'product-1'), filter.search('wow')]),
      );
    };
    expectTypeOf(invalidElementPredicates).toBeFunction();
  });

  it('rejects unsupported element predicates at runtime', () => {
    const deletion = filter.deletion(DeletionState.ACTIVE);
    const nestedSearch = filter.and([
      filter.eq('sku', 'product-1'),
      filter.search('wow'),
    ]);
    const metadata = filter.id('snapshot-1');

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
    expect(() =>
      filter.elementMatch(
        'state.items',
        metadata as unknown as ElementFilterExpression,
      ),
    ).toThrow();
  });

  it('rejects malformed zone offsets with a validation error', () => {
    expect(() => filter.today('createdAt', { zoneId: '+invalid' })).toThrow(
      'zoneId is invalid: [+invalid].',
    );
  });

  it.each([
    ['empty logical operands', () => filter.and([])],
    [
      'undefined AND operand',
      () => Reflect.apply(filter.and, null, [[undefined]]),
    ],
    ['null OR operand', () => Reflect.apply(filter.or, null, [[null]])],
    [
      'undefined NOR operand',
      () => Reflect.apply(filter.nor, null, [[undefined]]),
    ],
    ['empty collection values', () => filter.isIn('status', [])],
    ['empty ids', () => filter.ids([])],
    [
      'non-string aggregate ID',
      () => Reflect.apply(filter.aggregateId, null, [1]),
    ],
    [
      'non-string aggregate IDs value',
      () => Reflect.apply(filter.aggregateIds, null, [['order-1', 2]]),
    ],
    ['invalid query field', () => filter.eq('bad field', 'value')],
    [
      'non-string query field',
      () => Reflect.apply(filter.eq, null, [1, 'value']),
    ],
    [
      'object equality value',
      () => Reflect.apply(filter.eq, null, ['status', {}]),
    ],
    [
      'object equality array value',
      () => Reflect.apply(filter.eq, null, ['status', ['PAID', {}]]),
    ],
    [
      'null comparison value',
      () => Reflect.apply(filter.gt, null, ['score', null]),
    ],
    ['non-finite value', () => filter.gt('score', Number.POSITIVE_INFINITY)],
    [
      'null collection value',
      () => Reflect.apply(filter.isIn, null, ['status', [null]]),
    ],
    ['blank search query', () => filter.search(' ')],
    ['non-string search query', () => Reflect.apply(filter.search, null, [1])],
    [
      'invalid search mode',
      () => Reflect.apply(filter.search, null, ['wow', { mode: 'INVALID' }]),
    ],
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
      'invalid time unit',
      () => filter.today('createdAt', { timeUnit: 'INVALID' as TimeUnit }),
    ],
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
      'date pattern of a separator Kotlin counts as blank',
      () => filter.today('createdAt', { datePattern: '\u001F' }),
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
      'padded numeric day-of-week adjacent to a numeric field',
      () => filter.today('createdAt', { datePattern: 'pcH' }),
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

describe('element scope', () => {
  it('refuses an empty or null operand list inside an element predicate', () => {
    expect(() =>
      filter.elementMatch('items', {
        op: FilterOperator.AND,
        operands: [],
      } as never),
    ).toThrow('AND operands cannot be empty.');
    expect(() =>
      filter.elementMatch('items', {
        op: FilterOperator.OR,
        operands: [null],
      } as never),
    ).toThrow('OR operands cannot contain null.');
  });
});
