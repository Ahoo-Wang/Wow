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
  AggregationExpressionType as E,
  AggregationExpressionOperator as O,
  AggregationMetricType as M,
  DerivedExpressionType as D,
  AggregationFunction,
  AggregationGroupType,
  type AggregationExpression,
  type AggregationMetric,
  type DerivedExpression,
} from '@ahoo-wang/fetcher-wow';
import type { DeepReadonly } from '../lib/types.js';
import type {
  AnalysisNumericExpression,
  AnalysisDerivedExpression,
  AnalysisCompileContext,
} from './analysisModel.js';
export interface AnalysisExpressionBudget {
  nodes: number;
}
export type AnalysisExpressionPolicy =
  | { kind: 'numeric'; function: AggregationFunction }
  | { kind: 'distinct-count' }
  | { kind: 'percentile' };
export function analysisNumber(value: unknown): number {
  const number =
    typeof value === 'string' &&
    /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(value)
      ? Number(value)
      : value;
  if (typeof number !== 'number' || !Number.isFinite(number))
    throw new TypeError('数值常量无效');
  return number;
}
function visitNode(
  node: unknown,
  depth: number,
  budget: AnalysisExpressionBudget,
) {
  if (
    ++budget.nodes > 256 ||
    depth > 8 ||
    !node ||
    typeof node !== 'object' ||
    Array.isArray(node)
  )
    throw new TypeError('表达式过深或过大：请求最多 256 节点、8 层');
}
function operator(value: O): O {
  if (!Object.values(O).includes(value)) throw new TypeError('数值运算符无效');
  return value;
}
/** A numeric value alone does not grant arithmetic aggregation permission. */
export function supportsAnalysisValueField(
  name: string,
  policy: AnalysisExpressionPolicy,
  context: AnalysisCompileContext,
  arithmetic = false,
): boolean {
  const field = context.fields.find(f => f.field === name),
    cap = context.capability.fields.find(f => f.field === name);
  if (
    !field ||
    !cap ||
    !['string', 'number', 'boolean', 'date', 'datetime'].includes(
      field.type ?? '',
    )
  )
    return false;
  if (policy.kind === 'numeric')
    return field.type === 'number' && cap.functions.includes(policy.function);
  if (policy.kind === 'percentile')
    return field.type === 'number' && cap.percentile === true;
  if (cap.distinctCount !== true) return false;
  return (
    !arithmetic ||
    (field.type === 'number' &&
      (cap.functions.length > 0 ||
        cap.groups.includes(AggregationGroupType.HISTOGRAM) ||
        cap.percentile === true))
  );
}
export function compileAnalysisValueExpression(
  expression: DeepReadonly<AnalysisNumericExpression>,
  policy: AnalysisExpressionPolicy,
  context: AnalysisCompileContext,
  budget: AnalysisExpressionBudget,
): AggregationExpression {
  if (
    policy.kind === 'numeric' &&
    !Object.values(AggregationFunction).includes(policy.function)
  )
    throw new TypeError('数值函数无效');
  function visit(
    node: DeepReadonly<AnalysisNumericExpression>,
    depth: number,
  ): AggregationExpression {
    visitNode(node, depth, budget);
    switch (node.type) {
      case E.FIELD: {
        if (!supportsAnalysisValueField(node.field, policy, context, depth > 1))
          throw new TypeError('表达式字段或函数未授权');
        return aggregation.field(node.field);
      }
      case E.CONSTANT:
        return aggregation.constant(analysisNumber(node.value));
      case E.BINARY:
        return {
          type: E.BINARY,
          operator: operator(node.operator),
          left: visit(node.left, depth + 1),
          right: visit(node.right, depth + 1),
        };
      default:
        throw new TypeError('数值表达式类型无效');
    }
  }
  return visit(expression, 1);
}
export function compileAnalysisExpression(
  expression: DeepReadonly<AnalysisNumericExpression>,
  fn: AggregationFunction,
  context: AnalysisCompileContext,
): AggregationExpression {
  return compileAnalysisValueExpression(
    expression,
    { kind: 'numeric', function: fn },
    context,
    { nodes: 0 },
  );
}
export function compileAnalysisDerivedExpression(
  expression: DeepReadonly<AnalysisDerivedExpression>,
  metrics: ReadonlyMap<string, AggregationMetric>,
  budget: AnalysisExpressionBudget,
): DerivedExpression {
  return derived(expression, metrics, budget, true);
}
export function validateAnalysisDerivedExpression(
  expression: DerivedExpression,
  metrics: ReadonlyMap<string, AggregationMetric>,
  budget: AnalysisExpressionBudget,
): DerivedExpression {
  return derived(expression, metrics, budget, false);
}
function derived(
  expression: DeepReadonly<AnalysisDerivedExpression | DerivedExpression>,
  metrics: ReadonlyMap<string, AggregationMetric>,
  budget: AnalysisExpressionBudget,
  ids: boolean,
): DerivedExpression {
  function visit(
    node: DeepReadonly<AnalysisDerivedExpression | DerivedExpression>,
    depth: number,
  ): DerivedExpression {
    visitNode(node, depth, budget);
    switch (node.type) {
      case D.METRIC_REF: {
        const key = ids
          ? 'metricId' in node
            ? node.metricId
            : undefined
          : 'metric' in node
            ? node.metric
            : undefined;
        const metric = key === undefined ? undefined : metrics.get(key);
        if (!metric || metric.type === M.ANY)
          throw new TypeError('派生指标只能引用前置的非代表值指标');
        return { type: D.METRIC_REF, metric: metric.alias };
      }
      case D.CONSTANT:
        return { type: D.CONSTANT, value: analysisNumber(node.value) };
      case D.BINARY:
        return {
          type: D.BINARY,
          operator: operator(node.operator),
          left: visit(node.left, depth + 1),
          right: visit(node.right, depth + 1),
        };
      default:
        throw new TypeError('派生表达式类型无效');
    }
  }
  return visit(expression, 1);
}
/** null proves dimensionless; undefined means unknown/incompatible, never a percentage. */
export type AnalysisUnit = string | null | undefined;
export function combineAnalysisUnits(
  left: AnalysisUnit,
  right: AnalysisUnit,
  op: O,
): AnalysisUnit {
  if (left === undefined || right === undefined) return undefined;
  if (op === O.ADD || op === O.SUBTRACT)
    return left === right ? left : undefined;
  if (op === O.MULTIPLY)
    return left && right ? `${left}·${right}` : (left ?? right);
  if (left === right) return null;
  return right ? `${left ?? '1'}/(${right})` : left;
}
export function expressionUnit(
  expression: AggregationExpression,
  context: AnalysisCompileContext,
): AnalysisUnit {
  if (expression.type === E.FIELD)
    return context.capability.fields.find(f => f.field === expression.field)
      ?.unit;
  if (expression.type === E.CONSTANT) return null;
  return combineAnalysisUnits(
    expressionUnit(expression.left, context),
    expressionUnit(expression.right, context),
    expression.operator,
  );
}
export function derivedUnit(
  expression: DerivedExpression,
  units: ReadonlyMap<string, AnalysisUnit>,
): AnalysisUnit {
  if (expression.type === D.METRIC_REF) return units.get(expression.metric);
  if (expression.type === D.CONSTANT) return null;
  return combineAnalysisUnits(
    derivedUnit(expression.left, units),
    derivedUnit(expression.right, units),
    expression.operator,
  );
}
