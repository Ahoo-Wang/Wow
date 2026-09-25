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

import { effectiveSort } from '../../src/dsl/aggregation/sort';
import { describe, expect, expectTypeOf, it } from 'vitest';
import {
  AggregationDatePart,
  AggregationDateUnit,
  DerivedExpressionType,
  HavingExpressionType,
  ComparisonOperator,
  type DerivedExpression,
  type HavingExpression,
  AggregationExpressionOperator,
  AggregationExpressionType,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
  filter,
  SortDirection,
  aggregation,
  AGGREGATION_LIMITS,
  type AggregationExpression,
  type AggregationQuery,
  type FilterExpression,
} from '../../src';

type RootFields = 'state.status' | 'state.orders';
type ItemFields =
  'status' | 'quantity' | 'productId' | 'productName' | 'amount' | 'createdAt';

describe('AggregationQuery', () => {
  it('uses the Wow wire enum values', () => {
    expect({
      group: Object.values(AggregationGroupType),
      metric: Object.values(AggregationMetricType),
      expression: Object.values(AggregationExpressionType),
      operator: Object.values(AggregationExpressionOperator),
      dateUnit: Object.values(AggregationDateUnit),
      datePart: Object.values(AggregationDatePart),
      function: Object.values(AggregationFunction),
    }).toEqual({
      group: ['TERMS', 'HISTOGRAM', 'DATE_HISTOGRAM', 'DATE_PART'],
      metric: [
        'COUNT',
        'NUMERIC',
        'ANY',
        'DISTINCT_COUNT',
        'PERCENTILE',
        'DERIVED',
      ],
      expression: ['FIELD', 'CONSTANT', 'BINARY'],
      operator: ['ADD', 'SUBTRACT', 'MULTIPLY', 'DIVIDE'],
      dateUnit: [
        'YEAR',
        'QUARTER',
        'MONTH',
        'WEEK',
        'DAY',
        'HOUR',
        'MINUTE',
        'SECOND',
      ],
      datePart: ['DAY_OF_WEEK', 'DAY_OF_MONTH', 'HOUR_OF_DAY', 'MONTH_OF_YEAR'],
      function: ['SUM', 'AVG', 'MIN', 'MAX', 'STDDEV', 'VARIANCE'],
    });
  });

  it('builds filtered metrics without changing unfiltered JSON', () => {
    const predicate = filter.eq('status', 'PAID');
    const expression = aggregation.field('amount');
    const metrics = [
      aggregation.count('rows', { filter: predicate }),
      aggregation.any('productName', 'name', { filter: predicate }),
      aggregation.sum(expression, 'sum', { filter: predicate }),
      aggregation.avg(expression, 'avg', { filter: predicate }),
      aggregation.min(expression, 'min', { filter: predicate }),
      aggregation.max(expression, 'max', { filter: predicate }),
      aggregation.stddev(expression, 'stddev', { filter: predicate }),
      aggregation.variance(expression, 'variance', { filter: predicate }),
      aggregation.distinctCount(expression, 'distinct', { filter: predicate }),
      aggregation.percentile(expression, 'p95', {
        percentile: 95,
        filter: predicate,
      }),
    ];
    expect(metrics.map(metric => metric.filter)).toEqual(
      metrics.map(() => predicate),
    );
    expect(metrics.slice(6)).toEqual([
      {
        type: 'NUMERIC',
        function: 'STDDEV',
        expression,
        alias: 'stddev',
        filter: predicate,
      },
      {
        type: 'NUMERIC',
        function: 'VARIANCE',
        expression,
        alias: 'variance',
        filter: predicate,
      },
      {
        type: 'DISTINCT_COUNT',
        expression,
        alias: 'distinct',
        filter: predicate,
      },
      {
        type: 'PERCENTILE',
        expression,
        percentile: 95,
        alias: 'p95',
        filter: predicate,
      },
    ]);
    expect(
      aggregation.distinctCount(expression, 'distinct'),
    ).not.toHaveProperty('filter');
    expect(
      aggregation.percentile(expression, 'p50', { percentile: 50 }),
    ).not.toHaveProperty('filter');
  });

  it('supports missing terms and dense date groups explicitly', () => {
    expect(
      aggregation.terms('productName', 'name', { missingKey: 'Unknown' }),
    ).toEqual({
      type: 'TERMS',
      field: 'productName',
      alias: 'name',
      missingKey: 'Unknown',
    });
    expect(
      aggregation.dateHistogram('createdAt', 'day', {
        unit: AggregationDateUnit.DAY,
        dense: true,
      }),
    ).toEqual({
      type: 'DATE_HISTOGRAM',
      field: 'createdAt',
      unit: 'DAY',
      alias: 'day',
      timeZone: 'UTC',
      dense: true,
    });
  });

  it('groups by a calendar part, dense only on request', () => {
    expect(
      aggregation.datePart('createdAt', 'weekday', {
        part: AggregationDatePart.DAY_OF_WEEK,
      }),
    ).toEqual({
      type: 'DATE_PART',
      field: 'createdAt',
      part: 'DAY_OF_WEEK',
      alias: 'weekday',
      timeZone: 'UTC',
    });
    expect(
      aggregation.datePart('createdAt', 'hour', {
        part: AggregationDatePart.HOUR_OF_DAY,
        timeZone: 'Asia/Shanghai',
        dense: true,
      }),
    ).toEqual({
      type: 'DATE_PART',
      field: 'createdAt',
      part: 'HOUR_OF_DAY',
      alias: 'hour',
      timeZone: 'Asia/Shanghai',
      dense: true,
    });
  });

  it('rejects an invalid date part, a blank zone and a non-boolean dense', () => {
    expect(() =>
      aggregation.datePart('createdAt', 'd', { part: 'WEEK' as never }),
    ).toThrow('date part is invalid.');
    expect(() =>
      aggregation.datePart('createdAt', 'd', {
        part: AggregationDatePart.DAY_OF_MONTH,
        timeZone: ' ',
      }),
    ).toThrow('date part timeZone cannot be blank.');
    expect(() =>
      aggregation.datePart('createdAt', 'd', {
        part: AggregationDatePart.MONTH_OF_YEAR,
        dense: 1 as never,
      }),
    ).toThrow('date part dense must be boolean.');
  });

  it.each([0, 100, -1, Number.NaN, Infinity])(
    'rejects invalid percentile %s',
    p => {
      expect(() =>
        aggregation.percentile(aggregation.field('amount'), 'p', {
          percentile: p,
        }),
      ).toThrow(TypeError);
    },
  );

  it('rejects blank missing keys and non-boolean dense options', () => {
    expect(() =>
      aggregation.terms('productName', 'name', { missingKey: '  ' }),
    ).toThrow(TypeError);
    expect(() =>
      aggregation.dateHistogram('createdAt', 'day', {
        unit: AggregationDateUnit.DAY,
        dense: 'true' as never,
      }),
    ).toThrow(TypeError);
    expect(() =>
      aggregation.dateHistogram('createdAt', 'day', {
        unit: 'FORTNIGHT' as never,
      }),
    ).toThrow('date histogram unit is invalid.');
    expect(() =>
      aggregation.dateHistogram('createdAt', 'day', {
        unit: AggregationDateUnit.DAY,
        timeZone: ' ',
      }),
    ).toThrow('date histogram timeZone cannot be blank.');
  });

  it('builds derived metrics and typed HAVING trees using metric aliases', () => {
    const expression: DerivedExpression = {
      type: DerivedExpressionType.BINARY,
      operator: AggregationExpressionOperator.DIVIDE,
      left: { type: DerivedExpressionType.METRIC_REF, metric: 'total' },
      right: { type: DerivedExpressionType.CONSTANT, value: 100 },
    };
    const having: HavingExpression = {
      type: HavingExpressionType.AND,
      operands: [
        {
          type: HavingExpressionType.CONDITION,
          metric: 'ratio',
          operator: ComparisonOperator.GT,
          value: 10,
        },
        {
          type: HavingExpressionType.BETWEEN,
          metric: 'total',
          lower: 1,
          upper: 10000,
        },
        {
          type: HavingExpressionType.OR,
          operands: [
            { type: HavingExpressionType.IN, metric: 'rows', values: [1, 2] },
            {
              type: HavingExpressionType.IS_NULL,
              metric: 'ratio',
              negated: true,
            },
          ],
        },
      ],
    };
    const query: AggregationQuery = {
      groupBy: [aggregation.terms('status', 'status')],
      metrics: [
        aggregation.count('rows'),
        aggregation.sum(aggregation.field('amount'), 'total'),
        aggregation.derived(expression, 'ratio'),
      ],
      having,
    };
    expect(JSON.parse(JSON.stringify(query))).toMatchObject({
      metrics: [
        { type: 'COUNT', alias: 'rows' },
        { type: 'NUMERIC', alias: 'total' },
        {
          type: 'DERIVED',
          alias: 'ratio',
          expression: {
            type: 'BINARY',
            operator: 'DIVIDE',
            left: { type: 'METRIC_REF', metric: 'total' },
            right: { type: 'CONSTANT', value: 100 },
          },
        },
      ],
      having: {
        type: 'AND',
        operands: [
          { type: 'CONDITION', metric: 'ratio', operator: 'GT', value: 10 },
          { type: 'BETWEEN', metric: 'total', lower: 1, upper: 10000 },
          {
            type: 'OR',
            operands: [
              { type: 'IN', metric: 'rows', values: [1, 2] },
              { type: 'IS_NULL', metric: 'ratio', negated: true },
            ],
          },
        ],
      },
    });
    expect(query.metrics[2]).not.toHaveProperty('filter');
    expect(() => aggregation.derived(expression, '__wow_ratio')).toThrow(
      TypeError,
    );
  });

  it('builds aggregation elements', () => {
    expect([
      aggregation.element('state.orders', filter.eq('status', 'PAID')),
    ]).toStrictEqual([
      {
        path: 'state.orders',
        filter: { op: 'EQ', field: 'status', value: 'PAID' },
      },
    ]);
  });

  it('builds aggregation groups', () => {
    expect([
      aggregation.terms('productId', 'product'),
      aggregation.histogram('amount', 'amountBand', { interval: 10 }),
      aggregation.dateHistogram('createdAt', 'month', {
        unit: AggregationDateUnit.MONTH,
      }),
    ]).toStrictEqual([
      { type: 'TERMS', field: 'productId', alias: 'product' },
      {
        type: 'HISTOGRAM',
        field: 'amount',
        interval: 10,
        alias: 'amountBand',
      },
      {
        type: 'DATE_HISTOGRAM',
        field: 'createdAt',
        unit: 'MONTH',
        alias: 'month',
        timeZone: 'UTC',
      },
    ]);
  });

  it('builds an arithmetic aggregation query', () => {
    const revenue = aggregation.multiply(
      aggregation.field<ItemFields>('amount'),
      aggregation.constant(1.2),
    );
    const query: AggregationQuery<RootFields, ItemFields> = {
      filter: filter.eq('state.status', 'COMPLETED'),
      elements: [aggregation.element('state.orders')],
      groupBy: [aggregation.terms('productId', 'product')],
      metrics: [
        aggregation.count('count'),
        aggregation.sum(revenue, 'revenue'),
      ],
      sort: [{ field: 'revenue', direction: SortDirection.DESC }],
      limit: 20,
    };

    expect(query).toStrictEqual({
      filter: { op: 'EQ', field: 'state.status', value: 'COMPLETED' },
      elements: [{ path: 'state.orders' }],
      groupBy: [{ type: 'TERMS', field: 'productId', alias: 'product' }],
      metrics: [
        { type: 'COUNT', alias: 'count' },
        {
          type: 'NUMERIC',
          function: 'SUM',
          expression: {
            type: 'BINARY',
            operator: 'MULTIPLY',
            left: { type: 'FIELD', field: 'amount' },
            right: { type: 'CONSTANT', value: 1.2 },
          },
          alias: 'revenue',
        },
      ],
      sort: [{ field: 'revenue', direction: 'DESC' }],
      limit: 20,
    });
  });

  it.each([
    ['ADD', aggregation.add],
    ['SUBTRACT', aggregation.subtract],
    ['MULTIPLY', aggregation.multiply],
    ['DIVIDE', aggregation.divide],
  ] as const)('builds %s arithmetic expressions', (operator, create) => {
    const field = aggregation.field<'amount'>('amount');
    const constant = aggregation.constant(2);

    expect(create(field, constant)).toEqual({
      type: 'BINARY',
      operator,
      left: { type: 'FIELD', field: 'amount' },
      right: { type: 'CONSTANT', value: 2 },
    });
  });

  it.each([
    ['SUM', 'sum', aggregation.sum],
    ['AVG', 'average', aggregation.avg],
    ['MIN', 'minimum', aggregation.min],
    ['MAX', 'maximum', aggregation.max],
  ] as const)('builds %s numeric metrics', (fn, alias, create) => {
    const field = aggregation.field<'amount'>('amount');

    expect(create(field, alias)).toEqual({
      type: 'NUMERIC',
      function: fn,
      expression: field,
      alias,
    });
  });

  it('builds an ANY metric without adding a group', () => {
    const query: AggregationQuery<RootFields, ItemFields> = {
      groupBy: [aggregation.terms('productId', 'product')],
      metrics: [
        aggregation.any('productName', 'productName'),
        aggregation.count('count'),
        aggregation.sum(aggregation.field('amount'), 'total'),
      ],
    };

    expect(query).toStrictEqual({
      groupBy: [{ type: 'TERMS', field: 'productId', alias: 'product' }],
      metrics: [
        { type: 'ANY', field: 'productName', alias: 'productName' },
        { type: 'COUNT', alias: 'count' },
        {
          type: 'NUMERIC',
          function: 'SUM',
          expression: { type: 'FIELD', field: 'amount' },
          alias: 'total',
        },
      ],
    });
  });

  it.each([
    ['invalid field', () => aggregation.field('bad field')],
    ['non-finite constant', () => aggregation.constant(Number.NaN)],
    [
      'zero histogram interval',
      () => aggregation.histogram('amount', 'band', { interval: 0 }),
    ],
    ['multi-segment alias', () => aggregation.terms('status', 'group.status')],
    ['reserved alias', () => aggregation.count('__wow_count')],
    ['invalid ANY field', () => aggregation.any('bad field', 'productName')],
    [
      'multi-segment ANY alias',
      () => aggregation.any('productName', 'product.name'),
    ],
    [
      'reserved ANY alias',
      () => aggregation.any('productName', '__wow_productName'),
    ],
    [
      'blank time zone',
      () =>
        aggregation.dateHistogram('createdAt', 'day', {
          unit: AggregationDateUnit.DAY,
          timeZone: ' ',
        }),
    ],
    [
      'missing date histogram unit',
      () =>
        Reflect.apply(aggregation.dateHistogram, null, [
          'createdAt',
          { alias: 'day' },
        ]),
    ],
    [
      'invalid date histogram unit',
      () =>
        Reflect.apply(aggregation.dateHistogram, null, [
          'createdAt',
          { unit: 'INVALID', alias: 'day' },
        ]),
    ],
    [
      'root filter inside element',
      () =>
        aggregation.element('state.items', filter.id('snapshot-1') as never),
    ],
  ])('rejects %s', (_name, create) => {
    expect(create).toThrow(TypeError);
  });

  const assertFieldTypeIsRequired = () => {
    // @ts-expect-error FIELD expressions require their discriminator in 3.18.
    const expression: AggregationExpression = { field: 'amount' };
    void expression;
  };
  expectTypeOf(assertFieldTypeIsRequired).toBeFunction();

  const assertAggregationFields = () => {
    const valid: AggregationQuery<RootFields, ItemFields> = {
      filter: filter.eq('state.status', 'PAID'),
      groupBy: [aggregation.terms('productId', 'product')],
      metrics: [aggregation.sum(aggregation.field('amount'), 'total')],
    };
    const invalidAggregationField: AggregationQuery<RootFields, ItemFields> = {
      filter: filter.eq('state.status', 'PAID'),
      groupBy: [
        // @ts-expect-error unknown is not an ItemFields member.
        aggregation.terms('unknown', 'unknown'),
      ],
      metrics: [aggregation.count('count')],
    };
    const invalidRootFilter: AggregationQuery<RootFields, ItemFields> = {
      // @ts-expect-error status is not a RootFields member.
      filter: filter.eq('status', 'PAID'),
      metrics: [aggregation.count('count')],
    };
    const invalidElementFilter: AggregationQuery<RootFields, ItemFields> = {
      elements: [
        {
          path: 'state.orders',
          // @ts-expect-error root metadata filters are not element predicates.
          filter: filter.id('snapshot-1'),
        },
      ],
      metrics: [aggregation.count('count')],
    };
    const emptyMetrics: AggregationQuery<RootFields, ItemFields> = {
      // @ts-expect-error aggregation queries require at least one metric.
      metrics: [],
    };
    const invalidMetricExpression: AggregationQuery<RootFields, ItemFields> = {
      groupBy: [aggregation.terms('productId', 'product')],
      metrics: [
        // @ts-expect-error unknown is not an ItemFields member.
        aggregation.sum(aggregation.field('unknown'), 'total'),
      ],
    };
    const invalidAnyMetric: AggregationQuery<RootFields, ItemFields> = {
      metrics: [
        // @ts-expect-error unknown is not an ItemFields member.
        aggregation.any('unknown', 'name'),
      ],
    };
    const filtered: AggregationQuery<RootFields, ItemFields> = {
      metrics: [
        aggregation.count('paid', { filter: filter.eq('status', 'PAID') }),
      ],
    };
    const invalidMetricFilter: AggregationQuery<RootFields, ItemFields> = {
      metrics: [
        // @ts-expect-error metric filters use the aggregation scope, not the root scope.
        aggregation.count('paid', {
          filter: filter.eq('state.status', 'PAID'),
        }),
      ],
    };
    const invalidDistinct: AggregationQuery<RootFields, ItemFields> = {
      metrics: [
        // @ts-expect-error unknown is not an ItemFields member.
        aggregation.distinctCount(aggregation.field('unknown'), 'distinct'),
      ],
    };
    const invalidPercentile: AggregationQuery<RootFields, ItemFields> = {
      metrics: [
        // @ts-expect-error unknown is not an ItemFields member.
        aggregation.percentile(aggregation.field('unknown'), 'p95', {
          percentile: 95,
        }),
      ],
    };
    const invalidDerived: DerivedExpression = {
      // @ts-expect-error derived expressions reference metrics, not source fields.
      type: AggregationExpressionType.FIELD,
      field: 'amount',
    };
    const invalidHaving: HavingExpression = {
      type: HavingExpressionType.IN,
      metric: 'rows',
      // @ts-expect-error HAVING IN requires at least one value.
      values: [],
    };
    void filtered;
    void invalidMetricFilter;
    void invalidDistinct;
    void invalidPercentile;
    void invalidDerived;
    void invalidHaving;
    void valid;
    void invalidAggregationField;
    void invalidRootFilter;
    void invalidElementFilter;
    void emptyMetrics;
    void invalidMetricExpression;
    void invalidAnyMetric;
  };
  expectTypeOf(assertAggregationFields).toBeFunction();
});

/**
 * Wow admits an `AggregationQuery` in its constructor and answers one that
 * fails with a 400. Each case here is one of those `require` calls, checked
 * against the object the client is about to send.
 */
describe('aggregation.query', () => {
  const count = (alias: string, predicate?: FilterExpression) =>
    aggregation.count(alias, { filter: predicate });
  const chain = (depth: number): AggregationExpression =>
    depth <= 1
      ? aggregation.constant(1)
      : aggregation.add(chain(depth - 1), aggregation.constant(1));
  const fullTree = (depth: number): AggregationExpression =>
    depth <= 1
      ? aggregation.constant(1)
      : aggregation.add(fullTree(depth - 1), fullTree(depth - 1));
  const derivedChain = (depth: number): DerivedExpression =>
    depth <= 1
      ? { type: DerivedExpressionType.CONSTANT, value: 1 }
      : {
          type: DerivedExpressionType.BINARY,
          operator: AggregationExpressionOperator.ADD,
          left: derivedChain(depth - 1),
          right: { type: DerivedExpressionType.CONSTANT, value: 1 },
        };
  const derivedFullTree = (depth: number): DerivedExpression =>
    depth <= 1
      ? { type: DerivedExpressionType.CONSTANT, value: 1 }
      : {
          type: DerivedExpressionType.BINARY,
          operator: AggregationExpressionOperator.ADD,
          left: derivedFullTree(depth - 1),
          right: derivedFullTree(depth - 1),
        };
  const metricRef = (metric: string): DerivedExpression => ({
    type: DerivedExpressionType.METRIC_REF,
    metric,
  });

  it('returns a query that breaks no rule', () => {
    const query = aggregation.query({
      filter: filter.eq('state.status', 'PAID'),
      groupBy: [aggregation.terms('state.status', 'status')],
      metrics: [
        count('rows'),
        aggregation.sum(aggregation.field('amount'), 'total'),
      ],
      sort: [{ field: 'total', direction: SortDirection.DESC }],
      limit: 50,
    });

    expect(query.metrics).toHaveLength(2);
    expect(query.limit).toBe(50);
  });

  it('publishes the sizes it enforces', () => {
    expect(AGGREGATION_LIMITS).toEqual({
      DEFAULT_LIMIT: 100,
      MAX_LIMIT: 10_000,
      MAX_ELEMENTS: 5,
      MAX_GROUPS: 32,
      MAX_METRICS: 64,
      MAX_SORT_FIELDS: 32,
      MAX_EXPRESSION_DEPTH: 8,
      MAX_EXPRESSION_NODES: 256,
    });
  });

  it.each([
    [
      'metrics must not be empty.',
      { metrics: [] as unknown as AggregationQuery['metrics'] },
    ],
    [
      'metrics must contain at most 64 entries.',
      {
        metrics: Array.from({ length: 65 }, (_, index) =>
          count(`m${index}`),
        ) as unknown as AggregationQuery['metrics'],
      },
    ],
    [
      'elements must contain at most 5 paths.',
      {
        elements: Array.from({ length: 6 }, (_, index) =>
          aggregation.element(`items${index}`),
        ),
        metrics: [count('rows')] as AggregationQuery['metrics'],
      },
    ],
    [
      'groupBy must contain at most 32 dimensions.',
      {
        groupBy: Array.from({ length: 33 }, (_, index) =>
          aggregation.terms(`f${index}`, `g${index}`),
        ),
        metrics: [count('rows')] as AggregationQuery['metrics'],
      },
    ],
  ])('refuses a query with %s', (message, query) => {
    expect(() => aggregation.query(query as AggregationQuery)).toThrow(message);
  });

  it.each([0, -1, 10_001, 1.5])('refuses the limit %s', limit => {
    expect(() =>
      aggregation.query({ metrics: [count('rows')], limit }),
    ).toThrow('limit must be between 1 and 10000.');
  });

  it('takes a limit at either end of the range', () => {
    expect(
      aggregation.query({ metrics: [count('rows')], limit: 1 }).limit,
    ).toBe(1);
    expect(
      aggregation.query({ metrics: [count('rows')], limit: 10_000 }).limit,
    ).toBe(10_000);
  });

  it('refuses two aliases that collide', () => {
    expect(() =>
      aggregation.query({
        groupBy: [aggregation.terms('state.status', 'total')],
        metrics: [count('total')],
      }),
    ).toThrow('aggregation aliases must be unique.');
  });

  describe('sort', () => {
    it('refuses a sort with nothing grouped to sort', () => {
      // One row comes back, so there is no order to choose.
      expect(() =>
        aggregation.query({
          metrics: [count('rows')],
          sort: [{ field: 'rows', direction: SortDirection.DESC }],
        }),
      ).toThrow('sort requires at least one groupBy.');
    });

    it('refuses the same field twice', () => {
      expect(() =>
        aggregation.query({
          groupBy: [aggregation.terms('state.status', 'status')],
          metrics: [count('rows')],
          sort: [
            { field: 'rows', direction: SortDirection.DESC },
            { field: 'rows', direction: SortDirection.ASC },
          ],
        }),
      ).toThrow('sort fields must be unique.');
    });

    it('refuses a field that is not an alias', () => {
      // A result row holds aliases, not the fields they came from.
      expect(() =>
        aggregation.query({
          groupBy: [aggregation.terms('state.status', 'status')],
          metrics: [count('rows')],
          sort: [{ field: 'state.status', direction: SortDirection.ASC }],
        }),
      ).toThrow('sort fields must reference aggregation aliases.');
    });

    it('counts the groups it appends when checking the ceiling', () => {
      expect(() =>
        aggregation.query({
          groupBy: Array.from({ length: 32 }, (_, index) =>
            aggregation.terms(`f${index}`, `g${index}`),
          ),
          metrics: [count('rows')],
          sort: [{ field: 'rows', direction: SortDirection.DESC }],
        }),
      ).toThrow('effective sort must contain at most 32 fields.');
    });

    it('has nothing to apply to an ungrouped query', () => {
      expect(effectiveSort({})).toEqual([]);
    });

    it('appends each unsorted group ascending', () => {
      expect(
        effectiveSort({
          groupBy: [
            aggregation.terms('state.status', 'status'),
            aggregation.terms('state.region', 'region'),
          ],
          sort: [{ field: 'region', direction: SortDirection.DESC }],
        }),
      ).toEqual([
        { field: 'region', direction: SortDirection.DESC },
        { field: 'status', direction: SortDirection.ASC },
      ]);
    });
  });

  it('refuses a dense date histogram beside another dimension', () => {
    // Dense fills in the buckets its range implies rather than only the ones
    // with rows, and a second dimension would multiply that filling out.
    expect(() =>
      aggregation.query({
        groupBy: [
          aggregation.dateHistogram('createdAt', 'day', {
            unit: AggregationDateUnit.DAY,
            dense: true,
          }),
          aggregation.terms('state.status', 'status'),
        ],
        metrics: [count('rows')],
      }),
    ).toThrow('dense requires DATE_HISTOGRAM to be the only groupBy.');
  });

  it('refuses a dense date part beside another dimension', () => {
    expect(() =>
      aggregation.query({
        groupBy: [
          aggregation.datePart('createdAt', 'weekday', {
            part: AggregationDatePart.DAY_OF_WEEK,
            dense: true,
          }),
          aggregation.terms('state.status', 'status'),
        ],
        metrics: [count('rows')],
      }),
    ).toThrow('dense requires DATE_PART to be the only groupBy.');
    expect(
      aggregation.query({
        groupBy: [
          aggregation.datePart('createdAt', 'weekday', {
            part: AggregationDatePart.DAY_OF_WEEK,
          }),
          aggregation.datePart('createdAt', 'hour', {
            part: AggregationDatePart.HOUR_OF_DAY,
          }),
        ],
        metrics: [count('rows')],
      }).groupBy,
    ).toHaveLength(2);
  });

  describe('having', () => {
    const grouped = [aggregation.terms('state.status', 'status')];

    it('refuses having with nothing grouped', () => {
      expect(() =>
        aggregation.query({
          metrics: [count('rows')],
          having: {
            type: HavingExpressionType.CONDITION,
            metric: 'rows',
            operator: ComparisonOperator.GT,
            value: 10,
          },
        }),
      ).toThrow('having requires at least one groupBy.');
    });

    it('refuses a metric it never declared', () => {
      expect(() =>
        aggregation.query({
          groupBy: grouped,
          metrics: [count('rows')],
          having: {
            type: HavingExpressionType.CONDITION,
            metric: 'ghost',
            operator: ComparisonOperator.GT,
            value: 10,
          },
        }),
      ).toThrow(
        'having condition [ghost] must reference a declared metric alias.',
      );
    });

    it('refuses an ANY metric, whose value is a sample', () => {
      expect(() =>
        aggregation.query({
          groupBy: grouped,
          metrics: [aggregation.any('state.status', 'sample')],
          having: {
            type: HavingExpressionType.IS_NULL,
            metric: 'sample',
          },
        }),
      ).toThrow('having condition [sample] cannot reference ANY metric.');
    });

    it('refuses bounds the wrong way round', () => {
      expect(() =>
        aggregation.query({
          groupBy: grouped,
          metrics: [count('rows')],
          having: {
            type: HavingExpressionType.BETWEEN,
            metric: 'rows',
            lower: 10,
            upper: 1,
          },
        }),
      ).toThrow(
        'having between [rows] lower bound must not exceed upper bound.',
      );
    });

    it('refuses an empty IN', () => {
      expect(() =>
        aggregation.query({
          groupBy: grouped,
          metrics: [count('rows')],
          having: {
            type: HavingExpressionType.IN,
            metric: 'rows',
            values: [] as unknown as [number, ...number[]],
          },
        }),
      ).toThrow('having in [rows] values must not be empty.');
    });

    it('takes a having that names a real metric', () => {
      const query = aggregation.query({
        groupBy: grouped,
        metrics: [count('rows')],
        having: {
          type: HavingExpressionType.AND,
          operands: [
            {
              type: HavingExpressionType.CONDITION,
              metric: 'rows',
              operator: ComparisonOperator.GT,
              value: 10,
            },
            {
              type: HavingExpressionType.BETWEEN,
              metric: 'rows',
              lower: 1,
              upper: 9,
            },
            { type: HavingExpressionType.IN, metric: 'rows', values: [1, 2] },
            {
              type: HavingExpressionType.IS_NULL,
              metric: 'rows',
              negated: true,
            },
          ],
        },
      });

      expect(query.having?.type).toBe(HavingExpressionType.AND);
    });

    it.each([
      [
        'having condition [rows] value must be finite.',
        {
          type: HavingExpressionType.CONDITION,
          metric: 'rows',
          operator: ComparisonOperator.GT,
          value: Number.NaN,
        },
      ],
      [
        'having between [rows] bounds must be finite.',
        {
          type: HavingExpressionType.BETWEEN,
          metric: 'rows',
          lower: 1,
          upper: Number.POSITIVE_INFINITY,
        },
      ],
      [
        'having in [rows] values must be finite.',
        {
          type: HavingExpressionType.IN,
          metric: 'rows',
          values: [1, Number.NaN],
        },
      ],
      [
        'having AND operands must not be empty.',
        { type: HavingExpressionType.AND, operands: [] },
      ],
      [
        'having OR operands must not be empty.',
        { type: HavingExpressionType.OR, operands: [] },
      ],
    ])('refuses a having with %s', (message, having) => {
      expect(() =>
        aggregation.query({
          groupBy: grouped,
          metrics: [count('rows')],
          having: having as HavingExpression,
        }),
      ).toThrow(message);
    });

    it('refuses conditions nested past the depth Wow compiles', () => {
      const nest = (depth: number): HavingExpression =>
        depth <= 1
          ? { type: HavingExpressionType.IS_NULL, metric: 'rows' }
          : { type: HavingExpressionType.AND, operands: [nest(depth - 1)] };

      expect(() =>
        aggregation.query({
          groupBy: grouped,
          metrics: [count('rows')],
          having: nest(9),
        }),
      ).toThrow('having expression depth must be at most 8.');
    });

    it('descends into AND and OR', () => {
      expect(() =>
        aggregation.query({
          groupBy: grouped,
          metrics: [count('rows')],
          having: {
            type: HavingExpressionType.AND,
            operands: [
              {
                type: HavingExpressionType.OR,
                operands: [
                  {
                    type: HavingExpressionType.CONDITION,
                    metric: 'ghost',
                    operator: ComparisonOperator.GT,
                    value: 1,
                  },
                ],
              },
            ],
          },
        }),
      ).toThrow(
        'having condition [ghost] must reference a declared metric alias.',
      );
    });
  });

  describe('expressions', () => {
    it('refuses arithmetic nested past the depth Wow compiles', () => {
      expect(() =>
        aggregation.query({
          metrics: [aggregation.sum(chain(9), 'total')],
        }),
      ).toThrow('aggregation expression depth must be at most 8.');
    });

    it('takes arithmetic at the depth Wow compiles', () => {
      expect(
        aggregation.query({ metrics: [aggregation.sum(chain(8), 'total')] })
          .metrics,
      ).toHaveLength(1);
    });

    it.each([Number.NaN, Number.POSITIVE_INFINITY])(
      'refuses the constant %s, which JSON cannot carry',
      value => {
        // It would serialise to null and be refused on arrival instead.
        expect(() =>
          aggregation.query({
            metrics: [
              aggregation.sum(
                {
                  type: AggregationExpressionType.CONSTANT,
                  value,
                } as AggregationExpression,
                'total',
              ),
            ],
          }),
        ).toThrow('aggregation constant must be finite.');
      },
    );

    it('spends one node budget across every metric', () => {
      // 255 nodes each: neither metric is too large on its own.
      expect(() =>
        aggregation.query({
          metrics: [
            aggregation.sum(fullTree(8), 'a'),
            aggregation.sum(fullTree(8), 'b'),
          ],
        }),
      ).toThrow('aggregation expressions must contain at most 256 nodes.');
    });
  });

  describe('derived metrics', () => {
    it('refuses a reference to a metric declared after it', () => {
      // Derived arithmetic runs over values the query already produced, so
      // the list has to be evaluable in one pass.
      expect(() =>
        aggregation.query({
          metrics: [
            aggregation.derived(metricRef('total'), 'share'),
            aggregation.sum(aggregation.field('amount'), 'total'),
          ],
        }),
      ).toThrow(
        'derived metric [share] must reference a metric declared before it, but was [total].',
      );
    });

    it('refuses a reference to an ANY metric', () => {
      expect(() =>
        aggregation.query({
          metrics: [
            aggregation.any('state.status', 'sample'),
            aggregation.derived(metricRef('sample'), 'share'),
          ],
        }),
      ).toThrow('derived metric [share] cannot reference ANY metric [sample].');
    });

    it('takes a reference to a metric declared before it', () => {
      expect(
        aggregation.query({
          metrics: [
            aggregation.sum(aggregation.field('amount'), 'total'),
            aggregation.derived(metricRef('total'), 'share'),
          ],
        }).metrics,
      ).toHaveLength(2);
    });

    it('refuses arithmetic nested past the depth Wow compiles', () => {
      expect(() =>
        aggregation.query({
          metrics: [aggregation.derived(derivedChain(9), 'share')],
        }),
      ).toThrow('derived expression depth must be at most 8.');
    });

    it.each([Number.NaN, Number.NEGATIVE_INFINITY])(
      'refuses the constant %s, which JSON cannot carry',
      value => {
        expect(() =>
          aggregation.query({
            metrics: [
              aggregation.derived(
                { type: DerivedExpressionType.CONSTANT, value },
                'share',
              ),
            ],
          }),
        ).toThrow('derived constant must be finite.');
      },
    );

    it('refuses a metric that references itself', () => {
      // Its own alias is declared only once it has been admitted, so the
      // cycle cannot be written.
      expect(() =>
        aggregation.query({
          metrics: [aggregation.derived(metricRef('share'), 'share')],
        }),
      ).toThrow(
        'derived metric [share] must reference a metric declared before it, but was [share].',
      );
    });

    it('spends one node budget across every derived metric', () => {
      // 255 nodes each: neither is too large on its own.
      expect(() =>
        aggregation.query({
          metrics: [
            aggregation.derived(derivedFullTree(8), 'a'),
            aggregation.derived(derivedFullTree(8), 'b'),
          ],
        }),
      ).toThrow('derived expressions must contain at most 256 nodes.');
    });
  });

  describe('metric filters', () => {
    // A metric filter is a whole-value predicate on one record, so element
    // matching and full text have no reading there. Both backends refuse them
    // before looking at a schema.
    it('refuses a search', () => {
      expect(() =>
        aggregation.query({
          metrics: [count('rows', filter.search('premium'))],
        }),
      ).toThrow('Aggregation metric filters do not support search filters.');
    });

    it('refuses an element match', () => {
      expect(() =>
        aggregation.query({
          metrics: [
            count(
              'rows',
              filter.elementMatch('items', filter.eq('status', 'OK')),
            ),
          ],
        }),
      ).toThrow('Aggregation metric filters do not support [ELEMENT_MATCH].');
    });

    it('descends into a logical filter', () => {
      expect(() =>
        aggregation.query({
          metrics: [
            count(
              'rows',
              filter.and([
                filter.eq('state.status', 'PAID'),
                filter.or([filter.search('premium')]),
              ]),
            ),
          ],
        }),
      ).toThrow('Aggregation metric filters do not support search filters.');
    });

    it('takes the metadata filters, which name the record it already has', () => {
      expect(
        aggregation.query({
          metrics: [count('rows', filter.ownerId('u-1'))],
        }).metrics,
      ).toHaveLength(1);
    });
  });

  // A query may be rebuilt from a stored config, so a node can carry a `type`
  // this version does not know. That is a finding, not a crash.
  it.each([
    [
      'Unsupported aggregation expression: WINDOW.',
      {
        metrics: [
          aggregation.sum(
            { type: 'WINDOW' } as unknown as AggregationExpression,
            'total',
          ),
        ] as AggregationQuery['metrics'],
      },
    ],
    [
      'Unsupported derived expression: RATIO.',
      {
        metrics: [
          aggregation.derived(
            { type: 'RATIO' } as unknown as DerivedExpression,
            'share',
          ),
        ] as AggregationQuery['metrics'],
      },
    ],
    [
      'Unsupported having expression: REGEX.',
      {
        groupBy: [aggregation.terms('state.status', 'status')],
        metrics: [aggregation.count('rows')] as AggregationQuery['metrics'],
        having: { type: 'REGEX' } as unknown as HavingExpression,
      },
    ],
  ])('refuses a node it cannot read: %s', (message, query) => {
    expect(() => aggregation.query(query as AggregationQuery)).toThrow(message);
  });

  it('names the aggregation element when its filter reaches past an element', () => {
    // Not "ELEMENT_MATCH predicate": nobody wrote an ELEMENT_MATCH here.
    expect(() =>
      aggregation.element('items', filter.ownerId('u-1') as never),
    ).toThrow('Aggregation element filter cannot contain root filters.');
  });

  it('refuses any SEARCH in an aggregation element filter, even one naming fields', () => {
    expect(() =>
      aggregation.element(
        'items',
        filter.search('usb', { fields: ['productName'] }),
      ),
    ).toThrow('Aggregation element filter cannot contain root filters.');
  });
});
