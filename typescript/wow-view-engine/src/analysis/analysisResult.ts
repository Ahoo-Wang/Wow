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

import type { DeepReadonly } from '../lib/types.js';
import type {
  AnalysisPlan,
  AnalysisResultColumn,
  AnalysisResultValidation,
  AnalysisRow,
} from './analysisModel.js';
import { copy, message } from '../lib/snapshot.js';
/** Validate the complete response before publishing any row; aliases are literal own keys. */
export function validateAnalysisResult(
  rows: unknown,
  plan: DeepReadonly<AnalysisPlan>,
): AnalysisResultValidation {
  try {
    if (!Array.isArray(rows)) throw new TypeError('分析结果必须为行数组');
    const dimensions = plan.schema.filter(
      column => column.role === 'dimension',
    );
    if (
      (!dimensions.length && rows.length > 1) ||
      rows.length > (plan.query.limit ?? 10000)
    )
      throw new TypeError('分析结果行数超限');
    const tuples = new Set<string>();
    const result: AnalysisRow[] = [];
    for (const row of rows) {
      if (!row || typeof row !== 'object' || Array.isArray(row))
        throw new TypeError('分析结果行必须为对象');
      const output: AnalysisRow = {};
      for (const column of plan.schema) {
        if (!Object.prototype.hasOwnProperty.call(row, column.alias))
          throw new TypeError(`分析结果缺少 ${column.alias}`);
        const value: unknown = row[column.alias];
        const valid =
          value === null
            ? column.nullable
            : column.valueType === 'number' || column.valueType === 'datetime'
              ? typeof value === 'number' &&
                Number.isFinite(value) &&
                (column.valueType !== 'datetime' ||
                  Number.isFinite(new Date(value).getTime()))
              : typeof value === column.valueType;
        if (
          !valid ||
          ((column.aggregation === 'COUNT' || column.format === 'count') &&
            !(
              typeof value === 'number' &&
              Number.isSafeInteger(value) &&
              value >= 0
            ))
        )
          throw new TypeError(`分析结果 ${column.alias} 类型无效`);
        Object.defineProperty(output, column.alias, {
          value,
          enumerable: true,
          writable: true,
          configurable: true,
        });
      }
      if (dimensions.length) {
        const tuple = analysisRowKey(output, dimensions);
        if (tuples.has(tuple)) throw new TypeError('分析结果存在重复分组');
        tuples.add(tuple);
      }
      result.push(output);
    }
    return { rows: copy(result), errors: [] };
  } catch (error) {
    return { errors: [{ id: '', message: message(error) }] };
  }
}

/** Full typed dimension identity, shared by result admission and table reconciliation. */
export function analysisRowKey(
  row: DeepReadonly<AnalysisRow>,
  dimensions: DeepReadonly<readonly AnalysisResultColumn[]>,
): string {
  return JSON.stringify(
    [...dimensions]
      .sort((left, right) =>
        left.alias < right.alias ? -1 : left.alias > right.alias ? 1 : 0,
      )
      .map(column => {
        const value = row[column.alias];
        return [column.alias, value === null ? 'null' : typeof value, value];
      }),
  );
}
