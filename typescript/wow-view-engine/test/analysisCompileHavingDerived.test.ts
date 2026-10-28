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
 * The having and the derived arithmetic of a config compile through
 * wow-client's `aggregation.having` and `aggregation.derived(d => …)`
 * builders: every stored shape becomes the tree Wow reads, and a number Wow
 * would refuse is refused at compile time with Wow's message.
 */

import {
  aggregation,
  type AggregationQuery,
  type HavingExpression,
  HavingExpressionType,
  ComparisonOperator,
  AggregationMetricType,
  AggregationExpressionOperator,
  DerivedExpressionType,
} from '@ahoo-wang/wow-client/dsl';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  compileAnalysis,
  type AnalysisHavingExpression,
  type AnalysisViewConfig,
} from '../src/index.js';
import {
  analysisContext as context,
  analysisDefinition as definition,
  analysisKernelConfig as config,
} from './fixtures/analysis.js';

const { having } = aggregation;

function compile(overrides: Partial<AnalysisViewConfig>): AggregationQuery {
  return compileAnalysis(
    definition(),
    config(overrides),
    builtinFieldKinds,
    context,
  );
}

function compileHaving(stored: AnalysisHavingExpression) {
  return compile({ having: stored }).having;
}

describe('compileAnalysis having', () => {
  it.each<[AnalysisHavingExpression, HavingExpression]>([
    [
      {
        type: HavingExpressionType.CONDITION,
        metric: 'orders',
        operator: ComparisonOperator.EQ,
        value: 1,
      },
      having.eq('orders', 1),
    ],
    [
      {
        type: HavingExpressionType.CONDITION,
        metric: 'orders',
        operator: ComparisonOperator.NE,
        value: 1,
      },
      having.ne('orders', 1),
    ],
    [
      {
        type: HavingExpressionType.CONDITION,
        metric: 'orders',
        operator: ComparisonOperator.GT,
        value: 1,
      },
      having.gt('orders', 1),
    ],
    [
      {
        type: HavingExpressionType.CONDITION,
        metric: 'orders',
        operator: ComparisonOperator.GTE,
        value: 1,
      },
      having.gte('orders', 1),
    ],
    [
      {
        type: HavingExpressionType.CONDITION,
        metric: 'orders',
        operator: ComparisonOperator.LT,
        value: 1,
      },
      having.lt('orders', 1),
    ],
    [
      {
        type: HavingExpressionType.CONDITION,
        metric: 'orders',
        operator: ComparisonOperator.LTE,
        value: 1,
      },
      having.lte('orders', 1),
    ],
    [
      {
        type: HavingExpressionType.BETWEEN,
        metric: 'orders',
        lower: 1,
        upper: 9,
      },
      having.between('orders', 1, 9),
    ],
    [
      { type: HavingExpressionType.IN, metric: 'orders', values: [2, 3] },
      having.isIn('orders', [2, 3]),
    ],
    [
      { type: HavingExpressionType.IS_NULL, metric: 'orders' },
      having.isNull('orders'),
    ],
    [
      { type: HavingExpressionType.IS_NULL, metric: 'orders', negated: false },
      having.isNull('orders'),
    ],
    [
      { type: HavingExpressionType.IS_NULL, metric: 'orders', negated: true },
      having.isNotNull('orders'),
    ],
    [
      {
        type: HavingExpressionType.OR,
        operands: [
          {
            type: HavingExpressionType.CONDITION,
            metric: 'orders',
            operator: ComparisonOperator.GT,
            value: 9,
          },
          {
            type: HavingExpressionType.AND,
            operands: [
              {
                type: HavingExpressionType.CONDITION,
                metric: 'orders',
                operator: ComparisonOperator.LT,
                value: 2,
              },
              {
                type: HavingExpressionType.IS_NULL,
                metric: 'orders',
                negated: true,
              },
            ],
          },
        ],
      },
      having.or([
        having.gt('orders', 9),
        having.and([having.lt('orders', 2), having.isNotNull('orders')]),
      ]),
    ],
  ])('compiles %j', (stored, expected) => {
    expect(compileHaving(stored)).toEqual(expected);
  });

  it("refuses a number Wow would refuse, with Wow's message", () => {
    expect(() =>
      compileHaving({
        type: HavingExpressionType.BETWEEN,
        metric: 'orders',
        lower: 9,
        upper: 1,
      }),
    ).toThrow(
      'having between [orders] lower bound must not exceed upper bound.',
    );
  });
});

describe('compileAnalysis derived metrics', () => {
  it('compiles every operator through the DerivedExpressionDsl', () => {
    const query = compile({
      metrics: [
        { type: AggregationMetricType.COUNT, alias: 'orders' },
        {
          type: AggregationMetricType.DERIVED,
          alias: 'mix',
          expression: {
            type: DerivedExpressionType.BINARY,
            operator: AggregationExpressionOperator.DIVIDE,
            left: {
              type: DerivedExpressionType.BINARY,
              operator: AggregationExpressionOperator.ADD,
              left: {
                type: DerivedExpressionType.METRIC_REF,
                metric: 'orders',
              },
              right: { type: DerivedExpressionType.CONSTANT, value: 1 },
            },
            right: {
              type: DerivedExpressionType.BINARY,
              operator: AggregationExpressionOperator.SUBTRACT,
              left: {
                type: DerivedExpressionType.BINARY,
                operator: AggregationExpressionOperator.MULTIPLY,
                left: {
                  type: DerivedExpressionType.METRIC_REF,
                  metric: 'orders',
                },
                right: { type: DerivedExpressionType.CONSTANT, value: 2 },
              },
              right: { type: DerivedExpressionType.CONSTANT, value: 3 },
            },
          },
        },
      ],
    });
    expect(query.metrics[1]).toEqual(
      aggregation.derived(
        d =>
          d.divide(
            d.add(d.ref('orders'), d.constant(1)),
            d.subtract(
              d.multiply(d.ref('orders'), d.constant(2)),
              d.constant(3),
            ),
          ),
        'mix',
      ),
    );
  });
});
