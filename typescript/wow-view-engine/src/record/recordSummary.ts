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
  aggregation,
  type AggregationQuery,
  type FilterExpression,
} from '@ahoo-wang/fetcher-wow';
import type {
  RecordSummaryMetric,
  RecordData,
  RecordSummaryFunction,
  RecordSummaryResult,
} from '../contracts/viewModel.js';
import { readRecordValue } from './recordValidation.js';
import { RECORD_SUMMARY_LABELS } from './recordPresentation.js';
import { cloneSnapshot, type DeepReadonly } from '../lib/types.js';

export const EMPTY_RECORD_SUMMARY: RecordSummaryResult = {
  status: 'idle',
  values: {},
  error: null,
};
/** 类型保持的数组守卫：Array.isArray 的 any[] 谓词会把 readonly 数组退化为 any[]。 */
function isReadonlyArray(value: unknown): value is readonly unknown[] {
  return Array.isArray(value);
}
/** Validate public query input and keep aliases independent of presentation ordering. */
function orderedMetrics(
  metrics: readonly RecordSummaryMetric[],
): RecordSummaryMetric[] {
  // 类型保持守卫，避免 Array.isArray 把 readonly 参数退化为 any[]。
  if (!isReadonlyArray(metrics) || metrics.length > 64)
    throw new Error('最多配置 64 个汇总指标');
  const seen = new Set<string>();
  for (const metric of metrics) {
    if (
      !metric ||
      typeof metric.id !== 'string' ||
      !metric.id.trim() ||
      typeof metric.field !== 'string' ||
      !metric.field.trim() ||
      !Object.prototype.hasOwnProperty.call(
        RECORD_SUMMARY_LABELS,
        metric.function,
      )
    )
      throw new Error('汇总指标需要有效的 ID、字段与统计函数');
    const key = JSON.stringify([metric.id, metric.function]);
    if (seen.has(key)) throw new Error('汇总指标重复');
    seen.add(key);
  }
  return [...metrics].sort(
    (left, right) =>
      left.id.localeCompare(right.id) ||
      left.function.localeCompare(right.function),
  );
}
/** Current-page values use the loaded record snapshot, never a second record query. */
export function calculateRecordSummary(
  rows: readonly RecordData[],
  metrics: readonly RecordSummaryMetric[],
): RecordSummaryResult['values'] {
  const result: Record<
    string,
    Partial<Record<RecordSummaryFunction, number | null>>
  > = Object.create(null);
  for (const metric of orderedMetrics(metrics)) {
    const values: number[] = [];
    for (const row of rows) {
      const value = readRecordValue(row, metric.field);
      if (value === null || value === undefined) continue;
      if (typeof value !== 'number' || !Number.isFinite(value))
        throw new Error(`${metric.field} 包含非有限数值，无法汇总`);
      values.push(value);
    }
    if (!values.length) {
      (result[metric.id] ??= {})[metric.function] = null;
      continue;
    }
    let value: number;
    switch (metric.function) {
      case 'SUM':
        value = values.reduce((sum, item) => sum + item, 0);
        break;
      case 'AVG':
        value = values.reduce((sum, item) => sum + item, 0);
        value = Number.isFinite(value)
          ? value / values.length
          : values.reduce((sum, item) => sum + item / values.length, 0);
        break;
      case 'MIN':
        value = values.reduce((min, item) => Math.min(min, item));
        break;
      case 'MAX':
        value = values.reduce((max, item) => Math.max(max, item));
        break;
      default:
        throw new Error('汇总函数不支持');
    }
    if (!Number.isFinite(value))
      throw new Error(`${metric.field} 汇总结果超出数值范围`);
    (result[metric.id] ??= {})[metric.function] = value;
  }
  return result;
}
export function createRecordSummaryQuery(
  filter: DeepReadonly<FilterExpression>,
  metrics: readonly RecordSummaryMetric[],
): AggregationQuery {
  const expressions = orderedMetrics(metrics).map((metric, index) => {
    const alias = `summary${index}`;
    const expression = aggregation.field(metric.field);
    switch (metric.function) {
      case 'SUM':
        return aggregation.sum(expression, alias);
      case 'AVG':
        return aggregation.avg(expression, alias);
      case 'MIN':
        return aggregation.min(expression, alias);
      case 'MAX':
        return aggregation.max(expression, alias);
      default:
        throw new Error('汇总函数不支持');
    }
  });
  const [first, ...rest] = expressions;
  if (!first) throw new Error('汇总指标需要 1–64 项');
  return {
    filter: cloneSnapshot<FilterExpression>(filter),
    metrics: [first, ...rest],
  };
}
/** Wow's ungrouped contract always returns one row; missing aliases are errors, not zero. */
export function readRecordSummaryResult(
  value: unknown,
  metrics: readonly RecordSummaryMetric[],
): RecordSummaryResult['values'] {
  if (
    !Array.isArray(value) ||
    value.length !== 1 ||
    !value[0] ||
    typeof value[0] !== 'object' ||
    Array.isArray(value[0])
  )
    throw new Error('所有汇总应返回一行聚合结果');
  const result: Record<
    string,
    Partial<Record<RecordSummaryFunction, number | null>>
  > = Object.create(null);
  orderedMetrics(metrics).forEach((metric, index) => {
    const alias = `summary${index}`;
    if (!Object.prototype.hasOwnProperty.call(value[0], alias))
      throw new Error(`汇总结果缺少 ${alias}`);
    const resultValue: unknown = value[0][alias];
    if (
      resultValue !== null &&
      (typeof resultValue !== 'number' || !Number.isFinite(resultValue))
    )
      throw new Error(`汇总结果 ${alias} 必须是合法数值`);
    (result[metric.id] ??= {})[metric.function] = resultValue;
  });
  return result;
}
