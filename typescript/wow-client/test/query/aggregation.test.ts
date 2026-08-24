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
  AggregationExpressionType,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
  filter,
  SortDirection,
  type AggregationElement,
  type AggregationExpression,
  type AggregationQuery,
} from '../../src';

type RootFields = 'state.status' | 'state.orders';

describe('AggregationQuery', () => {
  it('uses the Wow wire enum values', () => {
    expect({
      group: Object.values(AggregationGroupType),
      metric: Object.values(AggregationMetricType),
      expression: Object.values(AggregationExpressionType),
      dateUnit: Object.values(AggregationDateUnit),
      function: Object.values(AggregationFunction),
    }).toEqual({
      group: ['TERMS', 'HISTOGRAM', 'DATE_HISTOGRAM'],
      metric: ['COUNT', 'NUMERIC'],
      expression: ['FIELD'],
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

  it('represents the complete Wow request shape', () => {
    const query: AggregationQuery<RootFields> = {
      filter: filter.eq('state.status', 'COMPLETED'),
      elements: [
        {
          path: 'state.orders',
          filter: filter.eq('status', 'PAID'),
        },
        {
          path: 'lines',
          filter: filter.gt('quantity', 0),
        },
      ],
      groupBy: [
        {
          type: AggregationGroupType.TERMS,
          field: 'productId',
          alias: 'product',
        },
        {
          type: AggregationGroupType.HISTOGRAM,
          field: 'amount',
          alias: 'amountBand',
          interval: 10,
        },
        {
          type: AggregationGroupType.DATE_HISTOGRAM,
          field: 'createdAt',
          alias: 'month',
          unit: AggregationDateUnit.MONTH,
          timeZone: 'UTC',
        },
      ],
      metrics: [
        { type: AggregationMetricType.COUNT, alias: 'count' },
        {
          type: AggregationMetricType.NUMERIC,
          function: AggregationFunction.SUM,
          expression: { field: 'amount' },
          alias: 'total',
        },
      ],
      sort: [{ field: 'total', direction: SortDirection.DESC }],
      limit: 20,
    };

    expect(query).toStrictEqual({
      filter: { op: 'EQ', field: 'state.status', value: 'COMPLETED' },
      elements: [
        {
          path: 'state.orders',
          filter: { op: 'EQ', field: 'status', value: 'PAID' },
        },
        {
          path: 'lines',
          filter: { op: 'GT', field: 'quantity', value: 0 },
        },
      ],
      groupBy: [
        { type: 'TERMS', field: 'productId', alias: 'product' },
        {
          type: 'HISTOGRAM',
          field: 'amount',
          alias: 'amountBand',
          interval: 10,
        },
        {
          type: 'DATE_HISTOGRAM',
          field: 'createdAt',
          alias: 'month',
          unit: 'MONTH',
          timeZone: 'UTC',
        },
      ],
      metrics: [
        { type: 'COUNT', alias: 'count' },
        {
          type: 'NUMERIC',
          function: 'SUM',
          expression: { field: 'amount' },
          alias: 'total',
        },
      ],
      sort: [{ field: 'total', direction: 'DESC' }],
      limit: 20,
    });
  });

  it('accepts defaulted and explicit field expression types', () => {
    const expressions: AggregationExpression[] = [
      { field: 'amount' },
      { type: AggregationExpressionType.FIELD, field: 'amount' },
    ];

    expect(expressions).toEqual([
      { field: 'amount' },
      { type: 'FIELD', field: 'amount' },
    ]);
  });

  it('enforces the static request boundaries', () => {
    const assertInvalidQueries = () => {
      const invalidRootFilter: AggregationQuery<RootFields> = {
        // @ts-expect-error Root filter fields must belong to RootFields.
        filter: filter.eq('state.unknown', 'value'),
        metrics: [{ type: AggregationMetricType.COUNT, alias: 'count' }],
      };
      const invalidElementFilter: AggregationElement = {
        path: 'lines',
        // @ts-expect-error Root metadata filters cannot scope an Element.
        filter: filter.id('snapshot-1'),
      };
      const invalidEmptyMetrics: AggregationQuery = {
        // @ts-expect-error Aggregation metrics must be non-empty.
        metrics: [],
      };
      void invalidRootFilter;
      void invalidElementFilter;
      void invalidEmptyMetrics;
    };

    expectTypeOf(assertInvalidQueries).toBeFunction();
  });
});
