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

import {
  isDateCell,
  type AnalysisExpression,
  type AnalysisFunction,
  type AnalysisMetric,
  type FieldDefinition,
  type NumberFormat,
} from '../model/index.js';

/**
 * Which summary a metric is, in one token.
 *
 * A `NUMERIC` metric names its function; every other metric type *is* its
 * function, so the two vocabularies are folded into one here — that is what
 * lets a column header, a format rule and a catalogue key all be chosen from
 * the same word.
 */
export type MetricFunction =
  | AnalysisFunction
  | 'COUNT'
  | 'DISTINCT_COUNT'
  | 'PERCENTILE'
  | 'ANY'
  | 'DERIVED';

export function metricFunctionOf(metric: AnalysisMetric): MetricFunction {
  return metric.type === 'NUMERIC' ? metric.function : metric.type;
}

/** A whole number: nothing counted is ever a fraction of one. */
const COUNTED: NumberFormat = { maximumFractionDigits: 0 };

/** Two decimals, which is where an average stops being an integer. */
const AVERAGED = { minimumFractionDigits: 2, maximumFractionDigits: 2 };

/**
 * How a metric's number prints, which is *not* how its field's own values
 * print.
 *
 * A field's `numberFormat` describes one stored value — an integer count of
 * items, an amount in yuan — and applying it to every aggregate of that field
 * said things the query never computed: the average of an integer column came
 * out as an integer, and a distinct count of amounts came out as money. What
 * the aggregate is decides instead:
 *
 * - `COUNT` and `DISTINCT_COUNT` count records, so they are whole numbers in
 *   nobody's currency;
 * - `AVG`, `STDDEV` and `VARIANCE` are computed, so they keep the field's
 *   format — an average of money is still money — and take two decimals from
 *   it rather than the field's own precision;
 * - `SUM`, `MIN`, `MAX` and `PERCENTILE` are values of the field, so they read
 *   exactly as the field does, and so does `ANY`, which *is* one of its values
 *   (of a date field they are no number at all — `readsAsItsField`);
 * - `DERIVED` is arithmetic over other metrics and belongs to no field at all,
 *   so it is a plain number with two decimals.
 */
export function metricFormat(
  metric: AnalysisMetric,
  field?: Pick<FieldDefinition, 'numberFormat'>,
): NumberFormat | undefined {
  const format = field?.numberFormat;
  switch (metricFunctionOf(metric)) {
    case 'COUNT':
    case 'DISTINCT_COUNT':
      return COUNTED;
    case 'AVG':
    case 'STDDEV':
    case 'VARIANCE':
      return { ...format, ...AVERAGED };
    case 'DERIVED':
      return { ...AVERAGED };
    default:
      return format;
  }
}

/**
 * The format a formula's per-record number shares with the fields it is
 * computed from, or none.
 *
 * A formula summarises no one field, so `metricFormat` had nothing to go on
 * and 「金额 − 成本」 — money minus money — came out as a bare number beside
 * the ¥ of every other amount on screen (2026-09-23 audit). The rule is the
 * one a unit follows through arithmetic:
 *
 * - a sum or a difference of operands in **one** format is in that format —
 *   ¥ − ¥ is ¥ — and of two formats is in neither;
 * - a product or a quotient with a plain number (a `CONSTANT`) keeps the
 *   other side's format — ¥ × 1.13 is ¥, ¥ ÷ 2 is ¥ — unless the plain
 *   number is divided by it: 2 ÷ ¥ is no money;
 * - a product or a quotient of two fields is a new quantity — ¥ × ¥ is no
 *   money, ¥ ÷ ¥ is a ratio — and gets no format.
 *
 * `formatOf` answers a field's own format, `undefined` for a plain one.
 */
export function formulaFormat(
  expression: AnalysisExpression,
  formatOf: (field: string) => NumberFormat | undefined,
): NumberFormat | undefined {
  const read = reading(expression, formatOf);
  return read === SCALAR ? undefined : read.format;
}

/** A plain number: no unit of its own, and none taken from it. */
const SCALAR = 'scalar';

/** What an expression's value is measured in: a format, or a plain number. */
type Reading = typeof SCALAR | { format: NumberFormat | undefined };

const NO_FORMAT: Reading = { format: undefined };

function reading(
  expression: AnalysisExpression,
  formatOf: (field: string) => NumberFormat | undefined,
): Reading {
  switch (expression.type) {
    case 'FIELD':
      return { format: formatOf(expression.field) };
    case 'CONSTANT':
      return SCALAR;
    case 'BINARY': {
      const left = reading(expression.left, formatOf);
      const right = reading(expression.right, formatOf);
      if (left === SCALAR && right === SCALAR) return SCALAR;
      // A plain number beside a measured one scales it — 1.13 × ¥, ¥ ÷ 2 —
      // except as a dividend: 2 ÷ ¥ is no money.
      if (right === SCALAR) return left;
      if (left === SCALAR)
        return expression.operator === 'DIVIDE' ? NO_FORMAT : right;
      // Two measured operands.
      if (expression.operator === 'ADD' || expression.operator === 'SUBTRACT')
        return sameFormat(left.format, right.format) ? left : NO_FORMAT;
      return NO_FORMAT;
    }
  }
}

/** Whether two formats print a number alike: the same options, one by one. */
function sameFormat(
  a: NumberFormat | undefined,
  b: NumberFormat | undefined,
): boolean {
  if (a === undefined || b === undefined) return a === b;
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
  return [...keys].every(
    key =>
      (a as Record<string, unknown>)[key] ===
      (b as Record<string, unknown>)[key],
  );
}

/**
 * The one field a metric summarises, when it has one: `ANY` names it, and the
 * three expression-carrying kinds through a `FIELD` expression. A formula, a
 * count and a derived metric summarise no single field.
 */
export function metricFieldOf(metric: AnalysisMetric): string | undefined {
  if (metric.type === 'ANY') return metric.field;
  if (
    metric.type === 'NUMERIC' ||
    metric.type === 'DISTINCT_COUNT' ||
    metric.type === 'PERCENTILE'
  )
    return metric.expression?.type === 'FIELD'
      ? metric.expression.field
      : undefined;
  return undefined;
}

/**
 * Whether a metric's value is one of its field's own values, on the field's
 * own scale — its smallest, its largest, a percentile of it, any one of it —
 * and so reads as the field reads a value: a date as a date, a code as the
 * label the definition gave it. A sum, an average, a deviation and a count
 * are new numbers the query computed, and read as numbers whatever they were
 * computed from.
 */
export function readsAsItsField(metric: AnalysisMetric): boolean {
  if (metricFieldOf(metric) === undefined) return false;
  switch (metricFunctionOf(metric)) {
    case 'MIN':
    case 'MAX':
    case 'PERCENTILE':
    case 'ANY':
      return true;
    default:
      return false;
  }
}

/**
 * The metrics whose value is a moment — the earliest, the latest, a
 * percentile or any one of a date field — by alias.
 *
 * A moment is not a quantity. It has no zero a bar could grow from, no share
 * of a whole a slice could take, nothing two of which add up to; so a chart
 * measures quantities only, and a moment is read where a value is written out
 * — the table, its totals row, a card's headline. `fields` is the analysis
 * scope's (`analysisScope(...).fields`), which is how an element field is
 * found under its path.
 */
export function momentMetrics(
  metrics: readonly AnalysisMetric[],
  fields: ReadonlyMap<string, Pick<FieldDefinition, 'kind' | 'cell'>>,
): Set<string> {
  const moments = new Set<string>();
  for (const metric of metrics) {
    if (!readsAsItsField(metric)) continue;
    const name = metricFieldOf(metric);
    const field = name === undefined ? undefined : fields.get(name);
    if (field && isDateCell(field.cell ?? field.kind))
      moments.add(metric.alias);
  }
  return moments;
}

/**
 * What a metric's number is a quantity of, as one token: two metrics with
 * the same token can share a scale, two with different ones cannot — a
 * count of orders drawn on the scale of their amount lies flat along zero.
 *
 * Read off what the metric is, never off its values (the chart layer that
 * uses it sees no rows): its function and the format its number prints in
 * (`metricFormat`), which carries the field's declared unit.
 *
 * - `COUNT` and `DISTINCT_COUNT` count things: `count`;
 * - `DERIVED` is arithmetic over other metrics — a ratio, a difference —
 *   whose unit nothing declares: `derived`;
 * - everything else is a function of a field, and the token is its scale
 *   and its unit. A sum is a total (`total:`); an average, a bound, a
 *   percentile, a deviation and any one value are on the scale of a single
 *   value (`value:`) — a sum and an average of the same amount are both
 *   money, a hundred times apart; a variance is in the square of the unit
 *   (`square:`). The unit is the declared currency, percent or unit, and
 *   `number` when the field declares none — two plain numbers are not told
 *   apart, since nothing says they differ.
 */
export function metricMeasure(
  fn: MetricFunction | undefined,
  format?: NumberFormat,
): string {
  switch (fn) {
    case 'COUNT':
    case 'DISTINCT_COUNT':
      return 'count';
    case 'DERIVED':
      return 'derived';
    default: {
      const scale =
        fn === 'SUM' ? 'total' : fn === 'VARIANCE' ? 'square' : 'value';
      return `${scale}:${unitOf(format)}`;
    }
  }
}

/** The unit a format declares: a currency, percent, a unit, or none. */
function unitOf(format: NumberFormat | undefined): string {
  switch (format?.style) {
    case 'currency':
      return `currency:${(format.currency ?? '').toUpperCase()}`;
    case 'percent':
      return 'percent';
    case 'unit':
      return `unit:${format.unit ?? ''}`;
    default:
      return 'number';
  }
}

/**
 * `metricMeasure` of every metric, by alias, from the definition: each
 * metric's format as `metricFormat` reads it off its one field. `fields`
 * is the analysis scope's, as `momentMetrics` takes it.
 */
export function metricMeasures(
  metrics: readonly AnalysisMetric[],
  fields: ReadonlyMap<string, Pick<FieldDefinition, 'numberFormat'>>,
): Map<string, string> {
  return new Map(
    metrics.map(metric => {
      const name = metricFieldOf(metric);
      const field = name === undefined ? undefined : fields.get(name);
      return [
        metric.alias,
        metricMeasure(metricFunctionOf(metric), metricFormat(metric, field)),
      ];
    }),
  );
}
