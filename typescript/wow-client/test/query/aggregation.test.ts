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
type ItemFields = 'status' | 'quantity' | 'productId' | 'amount' | 'createdAt';

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
      metric: ['COUNT', 'NUMERIC'],
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
      function: ['SUM', 'AVG', 'MIN', 'MAX'],
    });
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

  it.each([
    ['invalid field', () => aggregation.field('bad field')],
    ['non-finite constant', () => aggregation.constant(Number.NaN)],
    [
      'zero histogram interval',
      () => aggregation.histogram('amount', { interval: 0, alias: 'band' }),
    ],
    ['multi-segment alias', () => aggregation.terms('status', 'group.status')],
    ['reserved alias', () => aggregation.count('__wow_count')],
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
    void valid;
    void invalidAggregationField;
    void invalidRootFilter;
    void invalidElementFilter;
    void emptyMetrics;
    void invalidMetricExpression;
  };
  expectTypeOf(assertAggregationFields).toBeFunction();
});
