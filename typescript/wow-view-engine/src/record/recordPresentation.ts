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
  RecordSummaryMetric,
  RecordTablePresentation,
} from './recordModel.js';
import type { DeepReadonly } from '../lib/types.js';

/** Maps the implemented presentation to query requirements; visibility and widths do not affect metrics. */
export function getRecordSummaryMetrics(
  presentation: DeepReadonly<RecordTablePresentation>,
): RecordSummaryMetric[] {
  return presentation.table.columns
    .flatMap(column =>
      column.kind === 'field'
        ? (column.summary ?? []).map(fn => ({
            id: column.id,
            field: column.field,
            function: fn,
          }))
        : [],
    )
    .sort(
      (left, right) =>
        left.id.localeCompare(right.id) ||
        left.function.localeCompare(right.function),
    );
}

import type {
  ViewFieldDefinition,
  RecordSummaryFunction,
} from './recordModel.js';

export const RECORD_SUMMARY_LABELS = {
  SUM: '合计',
  AVG: '平均值',
  MIN: '最小值',
  MAX: '最大值',
} as const satisfies Record<RecordSummaryFunction, string>;

export function getRecordSummaryFunctions(
  field: ViewFieldDefinition,
): readonly RecordSummaryFunction[] {
  return field.type === 'number'
    ? (field.summaryFunctions ?? ['SUM', 'AVG', 'MIN', 'MAX'])
    : [];
}
