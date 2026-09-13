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
  HavingExpressionType as H,
  ComparisonOperator as C,
  AggregationMetricType as M,
  type HavingExpression,
  type AggregationMetric,
} from '@ahoo-wang/fetcher-wow';
import type { DeepReadonly } from '../lib/types.js';
import type { AnalysisHavingExpression } from './analysisModel.js';
import { FilterConfigurationError } from '../filter/filterConfigurationValidation.js';
import { message } from '../lib/snapshot.js';
import { analysisNumber } from './analysisExpressions.js';
export function compileAnalysisHaving(
  expression: DeepReadonly<AnalysisHavingExpression>,
  metrics: ReadonlyMap<string, AggregationMetric>,
): HavingExpression {
  let nodes = 0;
  const ids = new Set<string>();
  function visit(
    node: DeepReadonly<AnalysisHavingExpression>,
    depth: number,
  ): HavingExpression {
    try {
      if (
        ++nodes > 256 ||
        depth > 8 ||
        !node ||
        typeof node !== 'object' ||
        Array.isArray(node)
      )
        throw new TypeError('结果筛选超过前端预算：256 节点、8 层');
      if (typeof node.id !== 'string' || !node.id.trim() || ids.has(node.id))
        throw new TypeError('结果筛选节点 ID 无效或重复');
      ids.add(node.id);
      if (node.type === H.AND || node.type === H.OR) {
        if (![node.operands].every(Array.isArray) || !node.operands.length)
          throw new TypeError('结果筛选条件组不能为空');
        const operands = node.operands.map(item => visit(item, depth + 1));
        return {
          type: node.type,
          operands: operands as [HavingExpression, ...HavingExpression[]],
        };
      }
      const target = metrics.get('metricId' in node ? node.metricId : '');
      if (!target || target.type === M.ANY)
        throw new TypeError('结果筛选指标已失效或不支持筛选');
      const metric = target.alias;
      switch (node.type) {
        case H.CONDITION:
          if (!Object.values(C).includes(node.operator))
            throw new TypeError('结果比较符无效');
          return {
            type: H.CONDITION,
            metric,
            operator: node.operator,
            value: analysisNumber(node.value),
          };
        case H.BETWEEN: {
          const lower = analysisNumber(node.lower),
            upper = analysisNumber(node.upper);
          if (lower > upper) throw new TypeError('结果筛选下界不能大于上界');
          return { type: H.BETWEEN, metric, lower, upper };
        }
        case H.IN: {
          if (!Array.isArray(node.values) || !node.values.length)
            throw new TypeError('结果筛选集合不能为空');
          const values = node.values.map(analysisNumber);
          return {
            type: H.IN,
            metric,
            values: values as [number, ...number[]],
          };
        }
        case H.IS_NULL:
          if (node.negated !== undefined && typeof node.negated !== 'boolean')
            throw new TypeError('结果空值条件无效');
          return {
            type: H.IS_NULL,
            metric,
            ...(node.negated === undefined ? {} : { negated: node.negated }),
          };
        default:
          throw new TypeError('结果筛选类型无效');
      }
    } catch (error) {
      if (error instanceof FilterConfigurationError) throw error;
      throw new FilterConfigurationError(message(error), node?.id ?? '');
    }
  }
  return visit(expression, 1);
}
