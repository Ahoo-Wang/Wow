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
  AnalysisFunction,
  AnalysisMetric,
  FieldDefinition,
  NumberFormat,
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
 *   exactly as the field does, and so does `ANY`, which *is* one of its values;
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
