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
 * Aggregation queries as a whole: the wire values of their enums, the order of
 * ROOT groups, derived metrics and HAVING together, and the types a query
 * takes. Each part's builders and each of aggregation.query's rules are tested
 * beside them, in `test/dsl/aggregation/`.
 */

import { describe, expect, expectTypeOf, it } from 'vitest';
import {
  AggregationDatePart,
  AggregationDateUnit,
  AggregationGroupScope,
  type AggregationMetric,
  DerivedExpressionType,
  HavingExpressionType,
  ComparisonOperator,
  DateDiffUnit,
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
} from '../../src/dsl.js';
import {
  type ItemFields,
  type RootFields,
} from '../support/aggregationQueries';

describe('AggregationQuery', () => {
  it('uses the Wow wire enum values', () => {
    expect({
      group: Object.values(AggregationGroupType),
      metric: Object.values(AggregationMetricType),
      expression: Object.values(AggregationExpressionType),
      operator: Object.values(AggregationExpressionOperator),
      dateUnit: Object.values(AggregationDateUnit),
      datePart: Object.values(AggregationDatePart),
      dateDiffUnit: Object.values(DateDiffUnit),
      function: Object.values(AggregationFunction),
    }).toEqual({
      group: ['terms', 'histogram', 'date-histogram', 'date-part'],
      metric: [
        'count',
        'numeric',
        'any',
        'distinct-count',
        'percentile',
        'derived',
        'first',
        'last',
      ],
      expression: ['field', 'constant', 'binary', 'date-diff'],
      dateDiffUnit: ['second', 'minute', 'hour', 'day'],
      operator: ['add', 'subtract', 'multiply', 'divide'],
      dateUnit: [
        'year',
        'quarter',
        'month',
        'week',
        'day',
        'hour',
        'minute',
        'second',
      ],
      datePart: ['day-of-week', 'day-of-month', 'hour-of-day', 'month-of-year'],
      function: ['sum', 'avg', 'min', 'max', 'stddev', 'variance'],
    });
  });

  it('sorts the ROOT groups of an element query before its element groups', () => {
    const day = aggregation.dateHistogram('createTime', 'day', {
      unit: AggregationDateUnit.DAY,
      scope: AggregationGroupScope.ROOT,
    });
    const name = aggregation.terms('name', 'name');
    const elements = [aggregation.element('state.events')];
    const metrics: [AggregationMetric] = [aggregation.count('n')];
    expect(
      aggregation.query({ elements, groupBy: [day, name], metrics }).groupBy,
    ).toHaveLength(2);
    expect(
      aggregation.query({
        elements,
        groupBy: [day, name],
        metrics,
        sort: [{ field: 'n', direction: SortDirection.DESC }],
      }).sort,
    ).toHaveLength(1);
    expect(() =>
      aggregation.query({ elements, groupBy: [name, day], metrics }),
    ).toThrow('groups with a ROOT scope must sort before the element groups.');
    expect(() =>
      aggregation.query({
        elements,
        groupBy: [day, name],
        metrics,
        sort: [{ field: 'name', direction: SortDirection.ASC }],
      }),
    ).toThrow('groups with a ROOT scope must sort before the element groups.');
    expect(
      aggregation.query({ groupBy: [name, day], metrics }).groupBy,
    ).toHaveLength(2);
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
        { type: 'count', alias: 'rows' },
        { type: 'numeric', alias: 'total' },
        {
          type: 'derived',
          alias: 'ratio',
          expression: {
            type: 'binary',
            operator: 'divide',
            left: { type: 'metric-ref', metric: 'total' },
            right: { type: 'constant', value: 100 },
          },
        },
      ],
      having: {
        type: 'and',
        operands: [
          { type: 'condition', metric: 'ratio', operator: 'gt', value: 10 },
          { type: 'between', metric: 'total', lower: 1, upper: 10000 },
          {
            type: 'or',
            operands: [
              { type: 'in', metric: 'rows', values: [1, 2] },
              { type: 'is-null', metric: 'ratio', negated: true },
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
