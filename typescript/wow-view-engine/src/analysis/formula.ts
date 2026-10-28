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

import type { AggregationExpressionType, AggregationMetricType } from '@ahoo-wang/wow-client/dsl';
import type {
  DateDiffUnitName,
  AnalysisDerivedExpression,
  AnalysisExpression,
  AggregationExpressionOperatorName,
  AggregationFunctionName,
  AnalysisMetric,
} from '../model/index.js';
import {
  AggregationExpressionOperators,
  AggregationExpressionTypes,
  AggregationFunctions,
  AggregationMetricTypes,
  DerivedExpressionTypes,
} from '../model/index.js';
import { freeAlias } from './freeAlias.js';
import type { MetricCondition } from './metricCondition.js';
import type { MetricFunction } from './metricFormat.js';

/**
 * The two metrics an analyst writes rather than picks (D20 屏 B): a
 * **formula** — a number computed per record from two fields and summed,
 * averaged, … across the group (Wow's `NUMERIC` over a `BINARY`
 * expression: 毛利 = 金额 − 成本) — and a **derived** metric — arithmetic
 * over two metrics of the same row (Wow's `DERIVED`: 客单价 = 金额总和 ÷
 * 客户数). Both are one operation over two operands; nesting is written
 * by hand in the config, not in the tray.
 */

/**
 * Every operation between two operands, in the order the select lists them;
 * a `Record` over the model's operator type, so an operation Wow adds is a
 * compile error here until it is given a place.
 */
const EXPRESSION_OPERATOR_ORDER: Record<
  AggregationExpressionOperatorName,
  true
> = {
  [AggregationExpressionOperators.ADD]: true,
  [AggregationExpressionOperators.SUBTRACT]: true,
  [AggregationExpressionOperators.MULTIPLY]: true,
  [AggregationExpressionOperators.DIVIDE]: true,
};

export const EXPRESSION_OPERATORS = Object.keys(
  EXPRESSION_OPERATOR_ORDER,
) as readonly AggregationExpressionOperatorName[];

/**
 * How a derived metric's text names another metric it refers to. The kernel
 * holds no catalogue, so it cannot say 「金额的总和」 or 「记录数」: it writes
 * the referenced metric's summary and name between control characters, and
 * `columnTitle` in the UI words each one exactly as that metric's own column
 * is worded (`wordReferences`) — so an operand reads the same in the
 * derived column's header, on the derived metric's card, and in the operand
 * picker beside it. Nothing a definition labels can hold a control
 * character, so a reference cannot collide with a field's or a metric's
 * name.
 *
 * A referenced metric with a condition of its own carries it along
 * (`metricCondition`): 「金额的总和 · 已发运 ÷ 记录数」 is a different ratio
 * from the one without it, and the operand says so as its own column does.
 * Only what a name reads is written — the one value, or that there is a
 * condition at all — as a third segment, empty for the second.
 */
export function metricReferenceText(
  fn: MetricFunction,
  label: string,
  condition?: Pick<MetricCondition, 'value'>,
): string {
  const conditioned =
    condition === undefined ? '' : `${SEPARATOR}${condition.value ?? ''}`;
  return `${OPEN}${fn}${SEPARATOR}${label}${conditioned}${CLOSE}`;
}

/**
 * A derived metric's text with every reference `metricReferenceText`
 * wrote in it replaced by what `word` makes of its summary, its name and
 * the condition it counts under, when it has one.
 */
export function wordReferences(
  text: string,
  word: (
    fn: MetricFunction,
    label: string,
    condition?: Pick<MetricCondition, 'value'>,
  ) => string,
): string {
  const [head = '', ...rest] = text.split(OPEN);
  return rest.reduce((done, part) => {
    const end = part.indexOf(CLOSE);
    const [fn = '', label = '', value] = part.slice(0, end).split(SEPARATOR);
    const condition =
      value === undefined ? undefined : value === '' ? {} : { value };
    return (
      done + word(fn as MetricFunction, label, condition) + part.slice(end + 1)
    );
  }, head);
}

const OPEN = '\u0000';
const SEPARATOR = '\u0001';
const CLOSE = '\u0002';

/** The sign an operator prints as, in every language. */
export const OPERATOR_SIGN: Readonly<
  Record<AggregationExpressionOperatorName, string>
> = {
  [AggregationExpressionOperators.ADD]: '+',
  [AggregationExpressionOperators.SUBTRACT]: '−',
  [AggregationExpressionOperators.MULTIPLY]: '×',
  [AggregationExpressionOperators.DIVIDE]: '÷',
};

/** A formula's first shape: the first two measurable fields, subtracted, summed. */
export function formulaMetric(
  left: string,
  right: string,
  fn: AggregationFunctionName,
  taken: readonly string[],
): AnalysisMetric {
  return {
    type: AggregationMetricTypes.NUMERIC,
    alias: freeAlias('formula', taken),
    function: fn,
    expression: {
      type: AggregationExpressionTypes.BINARY,
      operator: AggregationExpressionOperators.SUBTRACT,
      left: { type: AggregationExpressionTypes.FIELD, field: left },
      right: { type: AggregationExpressionTypes.FIELD, field: right },
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
    type: AggregationMetricTypes.DERIVED,
    alias: freeAlias('derived', taken),
    expression: {
      type: DerivedExpressionTypes.BINARY,
      operator: AggregationExpressionOperators.DIVIDE,
      left: { type: DerivedExpressionTypes.METRIC_REF, metric: left },
      right: { type: DerivedExpressionTypes.METRIC_REF, metric: right },
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
    case AggregationExpressionTypes.FIELD:
      return nameOf(expression.field);
    case AggregationExpressionTypes.CONSTANT:
      return String(expression.value);
    case AggregationExpressionTypes.BINARY: {
      const text = `${expressionText(expression.left, nameOf, true)} ${
        OPERATOR_SIGN[expression.operator]
      } ${expressionText(expression.right, nameOf, true)}`;
      return nested ? `(${text})` : text;
    }
    // From one moment to the other: 「付款时间 → 发货时间」. The unit is the
    // number's to say (`formulaFormat`: 「12.5 小时」), not the name's.
    case AggregationExpressionTypes.DATE_DIFF: {
      const text = `${nameOf(expression.from)} → ${nameOf(expression.to)}`;
      return nested ? `(${text})` : text;
    }
  }
  // No `default`: `AnalysisExpression` is a closed union, so the switch is
  // exhaustive and this end is unreachable. Add a member and it becomes
  // reachable, and the declared `string` return makes that a compile error
  // — which is the guard the old `default: return ''` only looked like,
  // while quietly putting an empty formula on the screen.
}

/** A derived metric as its author would say it, naming each metric it reads. */
export function derivedText(
  expression: AnalysisDerivedExpression,
  nameOf: (metric: string) => string,
  nested = false,
): string {
  switch (expression.type) {
    case DerivedExpressionTypes.METRIC_REF:
      return nameOf(expression.metric);
    case DerivedExpressionTypes.CONSTANT:
      return String(expression.value);
    case DerivedExpressionTypes.BINARY: {
      const text = `${derivedText(expression.left, nameOf, true)} ${
        OPERATOR_SIGN[expression.operator]
      } ${derivedText(expression.right, nameOf, true)}`;
      return nested ? `(${text})` : text;
    }
  }
  // Closed union, exhaustive switch, unreachable end — as above.
}

/**
 * A time between two moments measured as a metric (N3): the average hours
 * from `from` to `to` across the group, which the card can change to a
 * lowest, a highest, a total or a percentile.
 */
export function durationMetric(
  from: string,
  to: string,
  unit: DateDiffUnitName,
  taken: readonly string[],
): AnalysisMetric {
  return {
    type: AggregationMetricTypes.NUMERIC,
    alias: freeAlias('duration', taken),
    function: AggregationFunctions.AVG,
    expression: { type: AggregationExpressionTypes.DATE_DIFF, from, to, unit },
  };
}

/** Whether a metric measures a time between two moments: the duration card's. */
export function isDuration(metric: AnalysisMetric): metric is Extract<
  AnalysisMetric,
  {
    type:
      | `${AggregationMetricType.NUMERIC}`
      | `${AggregationMetricType.PERCENTILE}`;
  }
> & {
  expression: Extract<
    AnalysisExpression,
    { type: `${AggregationExpressionType.DATE_DIFF}` }
  >;
} {
  return (
    (metric.type === AggregationMetricTypes.NUMERIC ||
      metric.type === AggregationMetricTypes.PERCENTILE) &&
    metric.expression?.type === AggregationExpressionTypes.DATE_DIFF
  );
}

/** Whether a metric is one the tray's formula card edits: one operation over two operands. */
export function isFormula(metric: AnalysisMetric): metric is Extract<
  AnalysisMetric,
  { type: `${AggregationMetricType.NUMERIC}` }
> & {
  expression: Extract<
    AnalysisExpression,
    { type: `${AggregationExpressionType.BINARY}` }
  >;
} {
  return (
    metric.type === AggregationMetricTypes.NUMERIC &&
    metric.expression.type === AggregationExpressionTypes.BINARY
  );
}
