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

import type {
  AnalysisDerivedExpression,
  AnalysisExpression,
  AnalysisExpressionOperator,
  AnalysisFunction,
  AnalysisMetric,
} from '../model/index.js';
import { freeAlias } from './defaults.js';

/**
 * The two metrics an analyst writes rather than picks (D20 屏 B): a
 * **formula** — a number computed per record from two fields and summed,
 * averaged, … across the group (Wow's `NUMERIC` over a `BINARY`
 * expression: 毛利 = 金额 − 成本) — and a **derived** metric — arithmetic
 * over two metrics of the same row (Wow's `DERIVED`: 客单价 = 金额合计 ÷
 * 客户数). Both are one operation over two operands; nesting is written
 * by hand in the config, not in the tray.
 */

export const EXPRESSION_OPERATORS: readonly AnalysisExpressionOperator[] = [
  'ADD',
  'SUBTRACT',
  'MULTIPLY',
  'DIVIDE',
];

/**
 * How a derived metric's text names a reference to the record count. The
 * kernel holds no catalogue, so it cannot say 「记录数」; it says this, and
 * `columnTitle` in the UI puts the catalogue's word in its place. Nothing
 * a definition labels can start with a control character, so the token
 * cannot collide with a field's or a metric's name.
 */
export const COUNT_NAME_TOKEN = '\u0000count';

/** The sign an operator prints as, in every language. */
export const OPERATOR_SIGN: Readonly<
  Record<AnalysisExpressionOperator, string>
> = { ADD: '+', SUBTRACT: '−', MULTIPLY: '×', DIVIDE: '÷' };

/** A formula's first shape: the first two measurable fields, subtracted, summed. */
export function formulaMetric(
  left: string,
  right: string,
  fn: AnalysisFunction,
  taken: readonly string[],
): AnalysisMetric {
  return {
    type: 'NUMERIC',
    alias: freeAlias('formula', taken),
    function: fn,
    expression: {
      type: 'BINARY',
      operator: 'SUBTRACT',
      left: { type: 'FIELD', field: left },
      right: { type: 'FIELD', field: right },
    },
  };
}

/** A derived metric's first shape: the first metric divided by the second. */
export function derivedMetric(
  left: string,
  right: string,
  taken: readonly string[],
): AnalysisMetric {
  return {
    type: 'DERIVED',
    alias: freeAlias('derived', taken),
    expression: {
      type: 'BINARY',
      operator: 'DIVIDE',
      left: { type: 'METRIC_REF', metric: left },
      right: { type: 'METRIC_REF', metric: right },
    },
  };
}

/**
 * A formula as its author would say it — 「金额 − 成本」 — for a column
 * header, a legend and the reading, naming each field as the definition
 * labels it. A nested formula is parenthesised.
 */
export function expressionText(
  expression: AnalysisExpression,
  nameOf: (field: string) => string,
  nested = false,
): string {
  switch (expression.type) {
    case 'FIELD':
      return nameOf(expression.field);
    case 'CONSTANT':
      return String(expression.value);
    case 'BINARY': {
      const text = `${expressionText(expression.left, nameOf, true)} ${
        OPERATOR_SIGN[expression.operator]
      } ${expressionText(expression.right, nameOf, true)}`;
      return nested ? `(${text})` : text;
    }
    default:
      return '';
  }
}

/** A derived metric as its author would say it, naming each metric it reads. */
export function derivedText(
  expression: AnalysisDerivedExpression,
  nameOf: (metric: string) => string,
  nested = false,
): string {
  switch (expression.type) {
    case 'METRIC_REF':
      return nameOf(expression.metric);
    case 'CONSTANT':
      return String(expression.value);
    case 'BINARY': {
      const text = `${derivedText(expression.left, nameOf, true)} ${
        OPERATOR_SIGN[expression.operator]
      } ${derivedText(expression.right, nameOf, true)}`;
      return nested ? `(${text})` : text;
    }
    default:
      return '';
  }
}

/** Whether a metric is one the tray's formula card edits: one operation over two operands. */
export function isFormula(metric: AnalysisMetric): metric is Extract<
  AnalysisMetric,
  { type: 'NUMERIC' }
> & {
  expression: Extract<AnalysisExpression, { type: 'BINARY' }>;
} {
  return metric.type === 'NUMERIC' && metric.expression.type === 'BINARY';
}
