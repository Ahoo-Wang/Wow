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
 * The builders of an aggregation query's elements and expressions: fields,
 * constants, time differences and arithmetic.
 */

import { describe, expect, it } from 'vitest';
import {
  DateDiffUnit,
  filter,
  SortDirection,
  aggregation,
  type AggregationQuery,
} from '../../../src/dsl.js';
import {
  type ItemFields,
  type RootFields,
} from '../../fixtures/aggregationQueries';

describe('AggregationQuery', () => {
  it('measures the time between two fields and groups by expressions', () => {
    const days = aggregation.dateDiff('paidAt', 'shippedAt', DateDiffUnit.DAY);
    expect(days).toEqual({
      type: 'date-diff',
      from: 'paidAt',
      to: 'shippedAt',
      unit: 'day',
    });
    expect(() =>
      aggregation.dateDiff('paidAt', 'shippedAt', 'WEEK' as never),
    ).toThrow('date diff unit is invalid.');
    expect(aggregation.terms(days, 'days')).toEqual({
      type: 'terms',
      expression: days,
      alias: 'days',
    });
    expect(aggregation.histogram(days, 'band', { interval: 7 })).toEqual({
      type: 'histogram',
      expression: days,
      interval: 7,
      alias: 'band',
    });
    expect(() =>
      aggregation.terms(days, 'days', { missingKey: 'NONE' }),
    ).toThrow('terms missingKey requires a field input.');
    expect(() => aggregation.terms(null as never, 'days')).toThrow(
      'TERMS group requires exactly one of field and expression.',
    );
    // Hand-built groups are held to the same rules by aggregation.query().
    expect(() =>
      aggregation.query({
        groupBy: [
          {
            type: 'terms',
            field: 'status',
            expression: days,
            alias: 'g',
          } as never,
        ],
        metrics: [aggregation.count('rows')],
      }),
    ).toThrow('TERMS group requires exactly one of field and expression.');
    expect(
      aggregation.query({
        groupBy: [aggregation.terms(days, 'days')],
        metrics: [aggregation.avg(days, 'avgDays')],
      }).groupBy,
    ).toHaveLength(1);
  });

  it('builds aggregation elements', () => {
    expect([
      aggregation.element('state.orders', filter.eq('status', 'PAID')),
    ]).toStrictEqual([
      {
        path: 'state.orders',
        filter: { op: 'eq', field: 'status', value: 'PAID' },
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
      filter: { op: 'eq', field: 'state.status', value: 'COMPLETED' },
      elements: [{ path: 'state.orders' }],
      groupBy: [{ type: 'terms', field: 'productId', alias: 'product' }],
      metrics: [
        { type: 'count', alias: 'count' },
        {
          type: 'numeric',
          function: 'sum',
          expression: {
            type: 'binary',
            operator: 'multiply',
            left: { type: 'field', field: 'amount' },
            right: { type: 'constant', value: 1.2 },
          },
          alias: 'revenue',
        },
      ],
      sort: [{ field: 'revenue', direction: 'desc' }],
      limit: 20,
    });
  });

  it.each([
    ['add', aggregation.add],
    ['subtract', aggregation.subtract],
    ['multiply', aggregation.multiply],
    ['divide', aggregation.divide],
  ] as const)('builds %s arithmetic expressions', (operator, create) => {
    const field = aggregation.field<'amount'>('amount');
    const constant = aggregation.constant(2);

    expect(create(field, constant)).toEqual({
      type: 'binary',
      operator,
      left: { type: 'field', field: 'amount' },
      right: { type: 'constant', value: 2 },
    });
  });

  it.each([
    ['invalid field', () => aggregation.field('bad field')],
    ['non-finite constant', () => aggregation.constant(Number.NaN)],
    [
      'root filter inside element',
      () =>
        aggregation.element('state.items', filter.id('snapshot-1') as never),
    ],
  ])('rejects %s', (_name, create) => {
    expect(create).toThrow(TypeError);
  });
});
