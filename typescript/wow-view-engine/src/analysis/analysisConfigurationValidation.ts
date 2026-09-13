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
  SortDirection,
  DerivedExpressionType as D,
  HavingExpressionType as H,
  ComparisonOperator,
  AggregationExpressionOperator,
} from '@ahoo-wang/fetcher-wow';
import { validateFilterConfiguration } from '../filter/filterConfiguration.js';
import { validateFilterConfigurationStructure } from '../filter/filterConfigurationValidation.js';
import {
  assertObject,
  assertText,
  validateReference,
} from '../contracts/validation/validationPrimitives.js';

/** Analysis configuration structure; semantic compilation remains separate. */
export function validateAnalysisConfiguration(
  config: Record<string, unknown>,
): void {
  if (
    !config.filters ||
    !Array.isArray(config.dimensions) ||
    !Array.isArray(config.metrics) ||
    !Array.isArray(config.sort) ||
    !config.presentation ||
    typeof config.presentation !== 'object'
  )
    throw new Error('分析配置结构无效');
  validateFilterConfiguration(config.filters);
  if (typeof config.limit !== 'number' && typeof config.limit !== 'string')
    throw new Error('分析结果行数必须是数字或文本草稿');
  assertObject(config.presentation, '分析展示配置');
  const componentIds = new Set<string>();
  for (const item of [...config.dimensions, ...config.metrics]) {
    assertObject(item, '分析组件');
    assertText(item.id, '组件 ID');
    if (componentIds.has(item.id)) throw new Error('组件 ID 重复');
    componentIds.add(item.id);
    if (typeof item.alias !== 'string' || typeof item.title !== 'string')
      throw new Error('分析组件名称结构无效');
    assertObject(item.component, '分析组件引用');
    validateReference(item.component);
    assertObject(item.props, '分析组件属性');
    if (item.filters !== undefined)
      validateFilterConfigurationStructure(item.filters);
    if (item.derivedExpression !== undefined)
      validateDerivedDraft(item.derivedExpression);
    if (item.field !== undefined && typeof item.field !== 'string')
      throw new Error('分析字段结构无效');
    if (item.label !== undefined) {
      assertObject(item.label, '维度显示字段');
      for (const key of ['field', 'alias', 'title'])
        if (typeof item.label[key] !== 'string')
          throw new Error('维度显示字段结构无效');
    }
  }
  for (const item of config.sort) {
    assertObject(item, '分析排序');
    if (
      typeof item.alias !== 'string' ||
      !Object.values(SortDirection).includes(item.direction as SortDirection)
    )
      throw new Error('分析排序结构无效');
  }
  if (config.scope !== undefined) {
    assertObject(config.scope, '分析范围');
    assertText(config.scope.id, '分析范围 ID');
    if (!Array.isArray(config.scope.filters))
      throw new Error('分析范围筛选结构无效');
    for (const filter of config.scope.filters)
      validateFilterConfiguration(filter);
  }
  if (config.having !== undefined) validateHavingDraft(config.having);
  return;
}

function draftNumber(value: unknown) {
  if (
    typeof value !== 'string' &&
    (typeof value !== 'number' || !Number.isFinite(value))
  )
    throw new Error('分析数值草稿无效');
}
function draftReference(value: unknown) {
  if (typeof value !== 'string') throw new Error('分析引用结构无效');
}
function validateDerivedDraft(value: unknown, depth = 1): void {
  assertObject(value, '派生公式');
  if (depth > 8) throw new Error('派生公式超过 8 层');
  switch (value.type) {
    case D.METRIC_REF:
      draftReference(value.metricId);
      break;
    case D.CONSTANT:
      draftNumber(value.value);
      break;
    case D.BINARY:
      if (
        !Object.values(AggregationExpressionOperator).includes(
          value.operator as AggregationExpressionOperator,
        )
      )
        throw new Error('派生运算符无效');
      validateDerivedDraft(value.left, depth + 1);
      validateDerivedDraft(value.right, depth + 1);
      break;
    default:
      throw new Error('派生公式类型无效');
  }
}
function validateHavingDraft(
  value: unknown,
  depth = 1,
  ids = new Set<string>(),
): void {
  assertObject(value, '结果筛选');
  if (depth > 8 || ids.size >= 256) throw new Error('结果筛选超过前端预算');
  assertText(value.id, '结果筛选 ID');
  if (ids.has(value.id)) throw new Error('结果筛选 ID 重复');
  ids.add(value.id);
  if (value.type === H.AND || value.type === H.OR) {
    if (!Array.isArray(value.operands)) throw new Error('结果条件组结构无效');
    value.operands.forEach(child => validateHavingDraft(child, depth + 1, ids));
    return;
  }
  draftReference(value.metricId);
  switch (value.type) {
    case H.CONDITION:
      if (
        !Object.values(ComparisonOperator).includes(
          value.operator as ComparisonOperator,
        )
      )
        throw new Error('结果比较符无效');
      draftNumber(value.value);
      break;
    case H.BETWEEN:
      draftNumber(value.lower);
      draftNumber(value.upper);
      break;
    case H.IN:
      if (!Array.isArray(value.values)) throw new Error('结果筛选集合结构无效');
      value.values.forEach(draftNumber);
      break;
    case H.IS_NULL:
      if (value.negated !== undefined && typeof value.negated !== 'boolean')
        throw new Error('结果空值条件无效');
      break;
    default:
      throw new Error('结果筛选类型无效');
  }
}
