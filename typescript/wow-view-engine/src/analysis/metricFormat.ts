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
