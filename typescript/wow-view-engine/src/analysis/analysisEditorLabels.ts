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
  AggregationGroupType as Group,
  AggregationMetricType as Metric,
} from '@ahoo-wang/fetcher-wow';
import type { DeepReadonly } from '../lib/types.js';
import { copy } from '../lib/snapshot.js';
import type {
  AnalysisViewConfig,
  AnalysisComponentConfig,
  AnalysisCompileContext,
} from './analysisModel.js';

export const groupNames: Record<Group, string> = {
  [Group.TERMS]: 'terms',
  [Group.HISTOGRAM]: 'histogram',
  [Group.DATE_HISTOGRAM]: 'date-histogram',
};
export const names: Record<string, string> = Object.assign(
  Object.create(null),
  {
    terms: '按值分组',
    histogram: '数值分桶',
    'date-histogram': '日期分桶',
    count: '记录数',
    numeric: '数值统计',
    'distinct-count': '去重计数',
    percentile: '百分位',
    derived: '指标公式',
    any: '代表值',
  },
);
export const dateLabels: Record<string, string> = {
  YEAR: '年',
  QUARTER: '季度',
  MONTH: '月',
  WEEK: '周',
  DAY: '日',
  HOUR: '小时',
  MINUTE: '分钟',
  SECOND: '秒',
};

export function analysisOutputs(value: DeepReadonly<AnalysisViewConfig>) {
  return [
    ...value.dimensions,
    ...value.metrics,
    ...value.dimensions.flatMap(item => (item.label ? [item.label] : [])),
  ];
}

/** Resolve custom contribution types once for both reference pickers; compilers are pure. */
export function referenceableAnalysisMetrics(
  metrics: DeepReadonly<readonly AnalysisComponentConfig[]>,
  context: AnalysisCompileContext,
) {
  return metrics.filter(item => {
    if (
      ['count', 'numeric', 'distinct-count', 'percentile', 'derived'].includes(
        item.component.name,
      )
    )
      return true;
    if (names[item.component.name]) return false;
    const compiler =
      context.compilers &&
      Object.prototype.hasOwnProperty.call(
        context.compilers,
        item.component.name,
      )
        ? context.compilers[item.component.name]
        : undefined;
    if (
      !compiler ||
      !Array.isArray(compiler.roles) ||
      !compiler.roles.includes('metric')
    )
      return false;
    try {
      const output = compiler.compile(copy(item), {
        ...context,
        role: 'metric',
      });
      return (
        output.alias === item.alias &&
        [
          Metric.COUNT,
          Metric.NUMERIC,
          Metric.DISTINCT_COUNT,
          Metric.PERCENTILE,
          Metric.DERIVED,
        ].some(type => type === output.type)
      );
    } catch {
      return false;
    }
  });
}
