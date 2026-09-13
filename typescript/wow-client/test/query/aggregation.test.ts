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

import { describe, expect, expectTypeOf, it } from 'vitest';
import {
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
  type AggregationExpression,
  type AggregationQuery,
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
      function: Object.values(AggregationFunction),
    }).toEqual({
      group: ['TERMS', 'HISTOGRAM', 'DATE_HISTOGRAM'],
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
      function: ['SUM', 'AVG', 'MIN', 'MAX', 'STDDEV', 'VARIANCE'],
    });
  });

  it('builds filtered metrics without changing unfiltered JSON', () => {
    const predicate = filter.eq('status', 'PAID');
    const expression = aggregation.field('amount');
    const metrics = [
      aggregation.count('rows', predicate),
      aggregation.any('productName', 'name', predicate),
      aggregation.sum(expression, 'sum', predicate),
      aggregation.avg(expression, 'avg', predicate),
      aggregation.min(expression, 'min', predicate),
      aggregation.max(expression, 'max', predicate),
      aggregation.stddev(expression, 'stddev', predicate),
      aggregation.variance(expression, 'variance', predicate),
      aggregation.distinctCount(expression, 'distinct', predicate),
      aggregation.percentile(expression, 95, 'p95', predicate),
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
    expect(aggregation.percentile(expression, 50, 'p50')).not.toHaveProperty(
      'filter',
    );
  });

  it('supports missing terms and dense date groups explicitly', () => {
    expect(aggregation.terms('productName', 'name', 'Unknown')).toEqual({
      type: 'TERMS',
      field: 'productName',
      alias: 'name',
      missingKey: 'Unknown',
    });
    expect(
      aggregation.dateHistogram('createdAt', {
        unit: AggregationDateUnit.DAY,
        alias: 'day',
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

  it.each([0, 100, -1, Number.NaN, Infinity])(
    'rejects invalid percentile %s',
    p => {
      expect(() =>
        aggregation.percentile(aggregation.field('amount'), p, 'p'),
      ).toThrow(TypeError);
    },
  );

  it('rejects blank missing keys and non-boolean dense options', () => {
    expect(() => aggregation.terms('productName', 'name', '  ')).toThrow(
      TypeError,
    );
    expect(() =>
      aggregation.dateHistogram('createdAt', {
        unit: AggregationDateUnit.DAY,
        alias: 'day',
        dense: 'true' as never,
      }),
    ).toThrow(TypeError);
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
      aggregation.histogram('amount', {
        interval: 10,
        alias: 'amountBand',
      }),
      aggregation.dateHistogram('createdAt', {
        unit: AggregationDateUnit.MONTH,
        alias: 'month',
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
      () => aggregation.histogram('amount', { interval: 0, alias: 'band' }),
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
        aggregation.dateHistogram('createdAt', {
          unit: AggregationDateUnit.DAY,
          alias: 'day',
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
      metrics: [aggregation.count('paid', filter.eq('status', 'PAID'))],
    };
    const invalidMetricFilter: AggregationQuery<RootFields, ItemFields> = {
      metrics: [
        // @ts-expect-error metric filters use the aggregation scope, not the root scope.
        aggregation.count('paid', filter.eq('state.status', 'PAID')),
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
        aggregation.percentile(aggregation.field('unknown'), 95, 'p95'),
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
