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

import { SortDirection } from '@ahoo-wang/fetcher-wow';
import { validateFilterConfiguration } from '../filter/filterConfiguration.js';
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
  return;
}
