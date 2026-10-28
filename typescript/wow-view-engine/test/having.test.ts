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

import { describe, expect, it } from 'vitest';
import {
  derivedMetric,
  derivedText,
  expressionText,
  formulaMetric,
  havingRows,
  isFormula,
  withHavingRows,
} from '../src/analysis/index.js';
import type { AnalysisHavingExpression } from '../src/model/index.js';
import {
  AggregationExpressionOperator,
  AggregationExpressionType,
  AggregationFunction,
  AggregationMetricType,
  ComparisonOperator,
  DerivedExpressionType,
  HavingExpressionType,
} from '@ahoo-wang/wow-client/dsl';

describe('having rows', () => {
  it('reads one comparison, or several under one AND, as rows', () => {
    expect(havingRows(undefined)).toEqual([]);
    const one: AnalysisHavingExpression = {
      type: HavingExpressionType.CONDITION,
      metric: 'total',
      operator: ComparisonOperator.GT,
      value: 10_000,
    };
    expect(havingRows(one)).toEqual([
      { metric: 'total', operator: ComparisonOperator.GT, value: 10_000 },
    ]);
    expect(
      havingRows({
        type: HavingExpressionType.AND,
        operands: [
          one,
          {
            type: HavingExpressionType.CONDITION,
            metric: 'n',
            operator: ComparisonOperator.LTE,
            value: 5,
          },
        ],
      }),
    ).toEqual([
      { metric: 'total', operator: ComparisonOperator.GT, value: 10_000 },
      { metric: 'n', operator: ComparisonOperator.LTE, value: 5 },
    ]);
  });

  it('declines a shape the rows cannot say rather than flattening it', () => {
    expect(
      havingRows({
        type: HavingExpressionType.BETWEEN,
        metric: 'total',
        lower: 1,
        upper: 2,
      }),
    ).toBeNull();
    expect(
      havingRows({
        type: HavingExpressionType.OR,
        operands: [
          {
            type: HavingExpressionType.CONDITION,
            metric: 'n',
            operator: ComparisonOperator.GT,
            value: 1,
          },
        ],
      }),
    ).toBeNull();
    // A nested AND is a tree, not a list of rows.
    expect(
      havingRows({
        type: HavingExpressionType.AND,
        operands: [
          {
            type: HavingExpressionType.AND,
            operands: [
              {
                type: HavingExpressionType.CONDITION,
                metric: 'n',
                operator: ComparisonOperator.GT,
                value: 1,
              },
            ],
          },
        ],
      }),
    ).toBeNull();
  });

  it('writes the rows back as one condition, an AND of several, or nothing', () => {
    expect(withHavingRows([])).toBeUndefined();
    expect(
      withHavingRows([
        { metric: 'total', operator: ComparisonOperator.GT, value: 1 },
      ]),
    ).toEqual({
      type: HavingExpressionType.CONDITION,
      metric: 'total',
      operator: ComparisonOperator.GT,
      value: 1,
    });
    expect(
      withHavingRows([
        { metric: 'total', operator: ComparisonOperator.GT, value: 1 },
        { metric: 'n', operator: ComparisonOperator.NE, value: 0 },
      ]),
    ).toEqual({
      type: HavingExpressionType.AND,
      operands: [
        {
          type: HavingExpressionType.CONDITION,
          metric: 'total',
          operator: ComparisonOperator.GT,
          value: 1,
        },
        {
          type: HavingExpressionType.CONDITION,
          metric: 'n',
          operator: ComparisonOperator.NE,
          value: 0,
        },
      ],
    });
    // A row still being filled in is the editor's, not the query's.
    expect(
      withHavingRows([
        { metric: 'total', operator: ComparisonOperator.GT, value: null },
        { metric: 'n', operator: ComparisonOperator.NE, value: 0 },
      ]),
    ).toEqual({
      type: HavingExpressionType.CONDITION,
      metric: 'n',
      operator: ComparisonOperator.NE,
      value: 0,
    });
    expect(
      withHavingRows([
        { metric: 'total', operator: ComparisonOperator.GT, value: null },
      ]),
    ).toBeUndefined();
  });
});

describe('formulas', () => {
  it('starts a formula as two fields subtracted and summed, and a derived metric as a quotient', () => {
    const formula = formulaMetric('amount', 'cost', AggregationFunction.SUM, [
      'formula_1',
    ]);
    expect(formula).toEqual({
      type: AggregationMetricType.NUMERIC,
      alias: 'formula_2',
      function: AggregationFunction.SUM,
      expression: {
        type: AggregationExpressionType.BINARY,
        operator: AggregationExpressionOperator.SUBTRACT,
        left: { type: AggregationExpressionType.FIELD, field: 'amount' },
        right: { type: AggregationExpressionType.FIELD, field: 'cost' },
      },
    });
    expect(isFormula(formula)).toBe(true);
    expect(
      isFormula({
        type: AggregationMetricType.NUMERIC,
        alias: 'a',
        function: AggregationFunction.SUM,
        expression: { type: AggregationExpressionType.FIELD, field: 'amount' },
      }),
    ).toBe(false);
    expect(derivedMetric('total', 'customers', [])).toEqual({
      type: AggregationMetricType.DERIVED,
      alias: 'derived_1',
      expression: {
        type: DerivedExpressionType.BINARY,
        operator: AggregationExpressionOperator.DIVIDE,
        left: { type: DerivedExpressionType.METRIC_REF, metric: 'total' },
        right: { type: DerivedExpressionType.METRIC_REF, metric: 'customers' },
      },
    });
  });

  it('says a formula the way its author would, naming fields and metrics', () => {
    const names: Record<string, string> = {
      amount: '金额',
      cost: '成本',
      total: '金额总和',
      customers: '客户数',
    };
    const nameOf = (key: string) => names[key] ?? key;
    expect(
      expressionText(
        {
          type: AggregationExpressionType.BINARY,
          operator: AggregationExpressionOperator.SUBTRACT,
          left: { type: AggregationExpressionType.FIELD, field: 'amount' },
          right: { type: AggregationExpressionType.FIELD, field: 'cost' },
        },
        nameOf,
      ),
    ).toBe('金额 − 成本');
    // Nested arithmetic is parenthesised, a constant printed as it is.
    expect(
      expressionText(
        {
          type: AggregationExpressionType.BINARY,
          operator: AggregationExpressionOperator.MULTIPLY,
          left: {
            type: AggregationExpressionType.BINARY,
            operator: AggregationExpressionOperator.SUBTRACT,
            left: { type: AggregationExpressionType.FIELD, field: 'amount' },
            right: { type: AggregationExpressionType.FIELD, field: 'cost' },
          },
          right: { type: AggregationExpressionType.CONSTANT, value: 1.13 },
        },
        nameOf,
      ),
    ).toBe('(金额 − 成本) × 1.13');
    expect(
      derivedText(
        {
          type: AggregationExpressionType.BINARY,
          operator: AggregationExpressionOperator.DIVIDE,
          left: { type: DerivedExpressionType.METRIC_REF, metric: 'total' },
          right: {
            type: DerivedExpressionType.METRIC_REF,
            metric: 'customers',
          },
        },
        nameOf,
      ),
    ).toBe('金额总和 ÷ 客户数');
  });
});
