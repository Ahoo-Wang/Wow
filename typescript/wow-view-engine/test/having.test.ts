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

describe('having rows', () => {
  it('reads one comparison, or several under one AND, as rows', () => {
    expect(havingRows(undefined)).toEqual([]);
    const one: AnalysisHavingExpression = {
      type: 'CONDITION',
      metric: 'total',
      operator: 'GT',
      value: 10_000,
    };
    expect(havingRows(one)).toEqual([
      { metric: 'total', operator: 'GT', value: 10_000 },
    ]);
    expect(
      havingRows({
        type: 'AND',
        operands: [
          one,
          { type: 'CONDITION', metric: 'n', operator: 'LTE', value: 5 },
        ],
      }),
    ).toEqual([
      { metric: 'total', operator: 'GT', value: 10_000 },
      { metric: 'n', operator: 'LTE', value: 5 },
    ]);
  });

  it('declines a shape the rows cannot say rather than flattening it', () => {
    expect(
      havingRows({ type: 'BETWEEN', metric: 'total', lower: 1, upper: 2 }),
    ).toBeNull();
    expect(
      havingRows({
        type: 'OR',
        operands: [
          { type: 'CONDITION', metric: 'n', operator: 'GT', value: 1 },
        ],
      }),
    ).toBeNull();
    // A nested AND is a tree, not a list of rows.
    expect(
      havingRows({
        type: 'AND',
        operands: [
          {
            type: 'AND',
            operands: [
              { type: 'CONDITION', metric: 'n', operator: 'GT', value: 1 },
            ],
          },
        ],
      }),
    ).toBeNull();
  });

  it('writes the rows back as one condition, an AND of several, or nothing', () => {
    expect(withHavingRows([])).toBeUndefined();
    expect(
      withHavingRows([{ metric: 'total', operator: 'GT', value: 1 }]),
    ).toEqual({ type: 'CONDITION', metric: 'total', operator: 'GT', value: 1 });
    expect(
      withHavingRows([
        { metric: 'total', operator: 'GT', value: 1 },
        { metric: 'n', operator: 'NE', value: 0 },
      ]),
    ).toEqual({
      type: 'AND',
      operands: [
        { type: 'CONDITION', metric: 'total', operator: 'GT', value: 1 },
        { type: 'CONDITION', metric: 'n', operator: 'NE', value: 0 },
      ],
    });
    // A row still being filled in is the editor's, not the query's.
    expect(
      withHavingRows([
        { metric: 'total', operator: 'GT', value: null },
        { metric: 'n', operator: 'NE', value: 0 },
      ]),
    ).toEqual({ type: 'CONDITION', metric: 'n', operator: 'NE', value: 0 });
    expect(
      withHavingRows([{ metric: 'total', operator: 'GT', value: null }]),
    ).toBeUndefined();
  });
});

describe('formulas', () => {
  it('starts a formula as two fields subtracted and summed, and a derived metric as a quotient', () => {
    const formula = formulaMetric('amount', 'cost', 'SUM', ['formula_1']);
    expect(formula).toEqual({
      type: 'NUMERIC',
      alias: 'formula_2',
      function: 'SUM',
      expression: {
        type: 'BINARY',
        operator: 'SUBTRACT',
        left: { type: 'FIELD', field: 'amount' },
        right: { type: 'FIELD', field: 'cost' },
      },
    });
    expect(isFormula(formula)).toBe(true);
    expect(
      isFormula({
        type: 'NUMERIC',
        alias: 'a',
        function: 'SUM',
        expression: { type: 'FIELD', field: 'amount' },
      }),
    ).toBe(false);
    expect(derivedMetric('total', 'customers', [])).toEqual({
      type: 'DERIVED',
      alias: 'derived_1',
      expression: {
        type: 'BINARY',
        operator: 'DIVIDE',
        left: { type: 'METRIC_REF', metric: 'total' },
        right: { type: 'METRIC_REF', metric: 'customers' },
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
          type: 'BINARY',
          operator: 'SUBTRACT',
          left: { type: 'FIELD', field: 'amount' },
          right: { type: 'FIELD', field: 'cost' },
        },
        nameOf,
      ),
    ).toBe('金额 − 成本');
    // Nested arithmetic is parenthesised, a constant printed as it is.
    expect(
      expressionText(
        {
          type: 'BINARY',
          operator: 'MULTIPLY',
          left: {
            type: 'BINARY',
            operator: 'SUBTRACT',
            left: { type: 'FIELD', field: 'amount' },
            right: { type: 'FIELD', field: 'cost' },
          },
          right: { type: 'CONSTANT', value: 1.13 },
        },
        nameOf,
      ),
    ).toBe('(金额 − 成本) × 1.13');
    expect(
      derivedText(
        {
          type: 'BINARY',
          operator: 'DIVIDE',
          left: { type: 'METRIC_REF', metric: 'total' },
          right: { type: 'METRIC_REF', metric: 'customers' },
        },
        nameOf,
      ),
    ).toBe('金额总和 ÷ 客户数');
  });
});
