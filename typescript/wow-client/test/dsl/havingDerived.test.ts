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
 * `aggregation.having.*` (Kotlin's `HavingDsl`) and the callback form of
 * `aggregation.derived` (Kotlin's `DerivedExpressionDsl`). Each builder
 * refuses its own numbers with the message Wow answers, the same message
 * `aggregation.query()` gives for a hand-built tree.
 */

import { describe, expect, it } from 'vitest';
import {
  AggregationExpressionOperator,
  AggregationMetricType,
  ComparisonOperator,
  DerivedExpressionType,
  HavingExpressionType,
  aggregation,
  type DerivedExpression,
  type HavingExpression,
} from '../../src';

const { having } = aggregation;
const grouped = [aggregation.terms('status', 'status')];

describe('aggregation.having', () => {
  it('builds each comparison with its operator', () => {
    const expected = (
      [
        [ComparisonOperator.EQ, 1],
        [ComparisonOperator.NE, 2],
        [ComparisonOperator.GT, 3],
        [ComparisonOperator.GTE, 4],
        [ComparisonOperator.LT, 5],
        [ComparisonOperator.LTE, 6],
      ] as const
    ).map(([operator, value]) => ({
      type: HavingExpressionType.CONDITION,
      metric: 'm',
      operator,
      value,
    }));
    expect([
      having.eq('m', 1),
      having.ne('m', 2),
      having.gt('m', 3),
      having.gte('m', 4),
      having.lt('m', 5),
      having.lte('m', 6),
    ]).toEqual(expected);
  });

  it('builds between, in, the null checks and the logical groups', () => {
    expect(having.between('m', 1, 1)).toEqual({
      type: HavingExpressionType.BETWEEN,
      metric: 'm',
      lower: 1,
      upper: 1,
    });
    expect(having.isNull('m')).toEqual({
      type: HavingExpressionType.IS_NULL,
      metric: 'm',
    });
    expect(having.isNotNull('m')).toEqual({
      type: HavingExpressionType.IS_NULL,
      metric: 'm',
      negated: true,
    });
    expect(having.or([having.gt('m', 1)])).toEqual({
      type: HavingExpressionType.OR,
      operands: [having.gt('m', 1)],
    });
  });

  it('copies the lists it is handed', () => {
    const values = [2, 3];
    const inValues = having.isIn('m', values);
    const operands: HavingExpression[] = [having.isNull('m')];
    const all = having.and(operands);
    values.push(4);
    operands.push(having.isNull('n'));
    expect(inValues).toEqual({
      type: HavingExpressionType.IN,
      metric: 'm',
      values: [2, 3],
    });
    expect(all).toEqual({
      type: HavingExpressionType.AND,
      operands: [having.isNull('m')],
    });
  });

  it.each<[string, () => unknown, string]>([
    [
      'NaN value',
      () => having.gt('m', Number.NaN),
      'having condition [m] value must be finite.',
    ],
    [
      'infinite value',
      () => having.eq('m', Number.POSITIVE_INFINITY),
      'having condition [m] value must be finite.',
    ],
    [
      'NaN upper bound',
      () => having.between('m', 1, Number.NaN),
      'having between [m] bounds must be finite.',
    ],
    [
      'NaN lower bound',
      () => having.between('m', Number.NaN, 1),
      'having between [m] bounds must be finite.',
    ],
    [
      'bounds out of order',
      () => having.between('m', 9, 1),
      'having between [m] lower bound must not exceed upper bound.',
    ],
    [
      'no values',
      () => having.isIn('m', []),
      'having in [m] values must not be empty.',
    ],
    [
      'NaN among values',
      () => having.isIn('m', [1, Number.NaN]),
      'having in [m] values must be finite.',
    ],
    [
      'no AND operands',
      () => having.and([]),
      'having AND operands must not be empty.',
    ],
    [
      'no OR operands',
      () => having.or([]),
      'having OR operands must not be empty.',
    ],
  ])('refuses %s', (_case, build, message) => {
    expect(build).toThrow(new TypeError(message));
  });

  it('is admitted by aggregation.query(), which checks the metric names', () => {
    const query = aggregation.query({
      groupBy: grouped,
      metrics: [
        aggregation.count('orders'),
        aggregation.sum(aggregation.field('amount'), 'revenue'),
      ],
      having: having.and([
        having.gt('orders', 1),
        having.between('revenue', 10, 1000),
      ]),
    });
    expect(query.having?.type).toBe(HavingExpressionType.AND);
    expect(() =>
      aggregation.query({
        groupBy: grouped,
        metrics: [aggregation.count('orders')],
        having: having.gt('nope', 1),
      }),
    ).toThrow(
      'having condition [nope] must reference a declared metric alias.',
    );
  });
});

describe('aggregation.derived with a callback', () => {
  const ref = (metric: string): DerivedExpression => ({
    type: DerivedExpressionType.METRIC_REF,
    metric,
  });
  const constant = (value: number): DerivedExpression => ({
    type: DerivedExpressionType.CONSTANT,
    value,
  });
  const binary = (
    operator: AggregationExpressionOperator,
    left: DerivedExpression,
    right: DerivedExpression,
  ): DerivedExpression => ({
    type: DerivedExpressionType.BINARY,
    operator,
    left,
    right,
  });

  it('builds the tree the DerivedExpressionDsl describes', () => {
    expect(
      aggregation.derived(
        d =>
          d.divide(
            d.subtract(d.ref('revenue'), d.ref('refunds')),
            d.add(d.multiply(d.ref('orders'), d.constant(2)), d.constant(1)),
          ),
        'net',
      ),
    ).toEqual({
      type: AggregationMetricType.DERIVED,
      alias: 'net',
      expression: binary(
        AggregationExpressionOperator.DIVIDE,
        binary(
          AggregationExpressionOperator.SUBTRACT,
          ref('revenue'),
          ref('refunds'),
        ),
        binary(
          AggregationExpressionOperator.ADD,
          binary(
            AggregationExpressionOperator.MULTIPLY,
            ref('orders'),
            constant(2),
          ),
          constant(1),
        ),
      ),
    });
  });

  it('builds the same metric as the tree it stands for', () => {
    expect(
      aggregation.derived(
        d => d.divide(d.ref('revenue'), d.ref('orders')),
        'aov',
      ),
    ).toEqual(
      aggregation.derived(
        binary(
          AggregationExpressionOperator.DIVIDE,
          ref('revenue'),
          ref('orders'),
        ),
        'aov',
      ),
    );
  });

  it("refuses a non-finite constant with Wow's message", () => {
    expect(() => aggregation.derived(d => d.constant(Number.NaN), 'd')).toThrow(
      new TypeError('derived constant must be finite.'),
    );
  });
});
