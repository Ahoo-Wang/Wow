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
 * A formula's number and its name (2026-09-23 audit P1): 「金额 − 成本」 came
 * out as a bare number beside the ¥ of every other amount, and its column
 * 「金额 − 成本的合计」 read as 金额 minus the sum of 成本. The format follows
 * the unit through the arithmetic; the name is bracketed where a summary is
 * put around it.
 */

import {
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import { describe, expect, it } from 'vitest';
import {
  formulaFormat,
  projectAnalysis,
  type AnalysisExpression,
  type AnalysisMetric,
  type NumberFormat,
} from '../src/index.js';
import { analysisConfig, ordersDefinition } from './fixtures.js';

const YUAN: NumberFormat = { style: 'currency', currency: 'CNY' };
const DOLLAR: NumberFormat = { style: 'currency', currency: 'USD' };

const field = (name: string): AnalysisExpression => ({
  type: 'FIELD',
  field: name,
});
const constant = (value: number): AnalysisExpression => ({
  type: 'CONSTANT',
  value,
});
const binary = (
  operator: 'ADD' | 'SUBTRACT' | 'MULTIPLY' | 'DIVIDE',
  left: AnalysisExpression,
  right: AnalysisExpression,
): AnalysisExpression => ({ type: 'BINARY', operator, left, right });

const formats: Record<string, NumberFormat | undefined> = {
  amount: YUAN,
  cost: YUAN,
  freight: DOLLAR,
  qty: undefined,
};
const formatOf = (name: string) => formats[name];

describe('formulaFormat', () => {
  it('keeps the format two operands share across a sum or a difference', () => {
    expect(
      formulaFormat(
        binary('SUBTRACT', field('amount'), field('cost')),
        formatOf,
      ),
    ).toEqual(YUAN);
    expect(
      formulaFormat(binary('ADD', field('amount'), field('cost')), formatOf),
    ).toEqual(YUAN);
  });

  it('gives no format to operands that do not share one', () => {
    expect(
      formulaFormat(binary('ADD', field('amount'), field('freight')), formatOf),
    ).toBeUndefined();
    expect(
      formulaFormat(binary('ADD', field('amount'), field('qty')), formatOf),
    ).toBeUndefined();
  });

  it('keeps the format through a plain factor, and drops it for a new quantity', () => {
    // ¥ × 1.13 and ¥ ÷ 2 are still money.
    expect(
      formulaFormat(
        binary('MULTIPLY', field('amount'), constant(1.13)),
        formatOf,
      ),
    ).toEqual(YUAN);
    expect(
      formulaFormat(binary('DIVIDE', field('amount'), constant(2)), formatOf),
    ).toEqual(YUAN);
    expect(
      formulaFormat(
        binary('MULTIPLY', constant(1.13), field('amount')),
        formatOf,
      ),
    ).toEqual(YUAN);
    // 2 ÷ ¥, ¥ × ¥ and ¥ ÷ ¥ are not.
    expect(
      formulaFormat(binary('DIVIDE', constant(2), field('amount')), formatOf),
    ).toBeUndefined();
    expect(
      formulaFormat(
        binary('MULTIPLY', field('amount'), field('cost')),
        formatOf,
      ),
    ).toBeUndefined();
    expect(
      formulaFormat(binary('DIVIDE', field('amount'), field('cost')), formatOf),
    ).toBeUndefined();
    expect(
      formulaFormat(binary('ADD', constant(1), constant(2)), formatOf),
    ).toBeUndefined();
  });

  it('follows a nested formula through each step', () => {
    // (金额 − 成本) × 1.13 is money; (金额 − 成本) ÷ 成本 is a ratio.
    const margin = binary('SUBTRACT', field('amount'), field('cost'));
    expect(
      formulaFormat(binary('MULTIPLY', margin, constant(1.13)), formatOf),
    ).toEqual(YUAN);
    expect(
      formulaFormat(binary('DIVIDE', margin, field('cost')), formatOf),
    ).toBeUndefined();
  });
});

describe('a formula column', () => {
  const definition = ordersDefinition({
    fields: [
      { name: 'warehouse', label: '仓库', kind: 'string' },
      { name: 'amount', label: '金额', kind: 'number', numberFormat: YUAN },
      { name: 'cost', label: '成本', kind: 'number', numberFormat: YUAN },
    ],
    analysis: {
      count: true,
      expressions: true,
      fields: [
        {
          field: 'warehouse',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
        { field: 'amount', groups: [], functions: [AggregationFunction.SUM] },
        { field: 'cost', groups: [], functions: [AggregationFunction.SUM] },
      ],
    },
  });
  const metric = (fn: 'SUM' | 'AVG'): AnalysisMetric => ({
    alias: 'margin',
    type: 'NUMERIC',
    function: fn,
    expression: binary('SUBTRACT', field('amount'), field('cost')),
  });
  const column = (fn: 'SUM' | 'AVG') =>
    projectAnalysis(definition, analysisConfig({ metrics: [metric(fn)] }), [
      { warehouse: 'CN', margin: 12.5 },
    ]).columns.find(entry => entry.alias === 'margin')!;

  it('reads in the money its operands are in', () => {
    expect(column('SUM').numberFormat).toEqual(YUAN);
    // An average of money is still money, at two decimals.
    expect(column('AVG').numberFormat).toEqual({
      ...YUAN,
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
  });

  it('is named as the whole formula a summary is of', () => {
    expect(column('SUM').label).toBe('(金额 − 成本)');
  });
});
