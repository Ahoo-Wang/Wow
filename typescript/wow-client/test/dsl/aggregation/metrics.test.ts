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
 * The builders of an aggregation query's metrics: their filters, FIRST and
 * LAST, percentiles, the numeric functions and ANY.
 */

import { describe, expect, it } from 'vitest';
import {
  filter,
  aggregation,
  type AggregationQuery,
} from '../../../src/dsl.js';
import {
  type ItemFields,
  type RootFields,
} from '../../fixtures/aggregationQueries';

describe('AggregationQuery', () => {
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
        type: 'numeric',
        function: 'stddev',
        expression,
        alias: 'stddev',
        filter: predicate,
      },
      {
        type: 'numeric',
        function: 'variance',
        expression,
        alias: 'variance',
        filter: predicate,
      },
      {
        type: 'distinct-count',
        expression,
        alias: 'distinct',
        filter: predicate,
      },
      {
        type: 'percentile',
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

  it('reads the first and last value of a field, ordered on request', () => {
    expect(aggregation.first('state.price', 'open')).toEqual({
      type: 'first',
      field: 'state.price',
      alias: 'open',
    });
    const paid = filter.eq('state.status', 'PAID');
    expect(
      aggregation.last('state.price', 'close', {
        orderBy: 'createdAt',
        filter: paid,
      }),
    ).toEqual({
      type: 'last',
      field: 'state.price',
      alias: 'close',
      orderBy: 'createdAt',
      filter: paid,
    });
    expect(() =>
      aggregation.first('state.price', 'open', { orderBy: '' }),
    ).toThrow(TypeError);
    expect(
      aggregation.query({
        groupBy: [aggregation.terms('state.status', 'status')],
        metrics: [
          aggregation.first('state.price', 'open'),
          aggregation.last('state.price', 'close'),
        ],
      }).metrics,
    ).toHaveLength(2);
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

  it.each([
    ['sum', 'sum', aggregation.sum],
    ['avg', 'average', aggregation.avg],
    ['min', 'minimum', aggregation.min],
    ['max', 'maximum', aggregation.max],
  ] as const)('builds %s numeric metrics', (fn, alias, create) => {
    const field = aggregation.field<'amount'>('amount');

    expect(create(field, alias)).toEqual({
      type: 'numeric',
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
      groupBy: [{ type: 'terms', field: 'productId', alias: 'product' }],
      metrics: [
        { type: 'any', field: 'productName', alias: 'productName' },
        { type: 'count', alias: 'count' },
        {
          type: 'numeric',
          function: 'sum',
          expression: { type: 'field', field: 'amount' },
          alias: 'total',
        },
      ],
    });
  });

  it.each([
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
  ])('rejects %s', (_name, create) => {
    expect(create).toThrow(TypeError);
  });
});
