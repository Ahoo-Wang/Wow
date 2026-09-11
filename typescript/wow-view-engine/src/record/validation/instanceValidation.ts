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

import { encodeViewResourceId } from '../viewServiceContract.js';
import { SortDirection } from '@ahoo-wang/fetcher-wow';
import { validateFilterConfigurationStructure } from '../../filter/filterConfigurationValidation.js';
import { validateFilterJson } from '../../filter/filterConfigurationValidation.js';
import { validateFilterConfiguration } from '../../filter/filterConfiguration.js';
import {
  type ViewDefinition,
  type ViewInstance,
} from '../../contracts/viewModel.js';
import { validateRecordPresentation } from './presentationValidation.js';
import {
  assertObject,
  assertText,
  validateReference,
} from './validationPrimitives.js';

export function validateViewInstance(
  value: unknown,
  definition: ViewDefinition,
  expectedId?: string,
  semantic = true,
): asserts value is ViewInstance {
  assertObject(value, '视图实例');
  assertText(value.id, '实例 ID');
  encodeViewResourceId(value.id);
  if (expectedId !== undefined && value.id !== expectedId)
    throw new Error('返回的实例 ID 不匹配');
  if (value.definitionId !== definition.id)
    throw new Error('实例不属于当前视图定义');
  assertText(value.title, '实例名称');
  if (value.kind !== 'record' && value.kind !== 'analysis')
    throw new Error('视图类型无效');
  if (!definition[value.kind]) throw new Error('定义未声明此视图能力');
  assertObject(value.scope, '实例范围');
  if (
    value.scope.type !== 'personal' &&
    !(
      value.scope.type === 'public' &&
      ['system', 'shared'].includes(String(value.scope.source))
    )
  )
    throw new Error('实例范围无效');
  assertText(value.revision, '实例 revision');
  assertObject(value.config, '实例配置');
  const config = value.config;
  validateFilterJson(config);
  if (value.kind === 'analysis') {
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
  if ('filter' in config) throw new Error('视图配置必须保存 filters 组件配置');
  if (semantic)
    validateFilterConfiguration(
      config.filters,
      definition.fields,
      definition.allowedOperators,
    );
  else validateFilterConfigurationStructure(config.filters);
  if (!Array.isArray(config.sort) || config.sort.length > 32)
    throw new Error('排序必须为最多 32 项的数组');
  const sorted = new Set<string>();
  for (const sort of config.sort) {
    assertObject(sort, '排序');
    assertText(sort.field, '排序字段');
    if (
      semantic &&
      !definition.fields.some(
        field => field.field === sort.field && field.sortable === true,
      )
    )
      throw new Error(`字段不支持排序：${sort.field}`);
    if (
      ![SortDirection.ASC, SortDirection.DESC].includes(
        sort.direction as SortDirection,
      ) ||
      sorted.has(sort.field)
    )
      throw new Error('排序方向无效或字段重复');
    sorted.add(sort.field);
  }
  assertObject(config.pagination, '分页配置');
  if (
    !['paged', 'cursor'].includes(String(config.pagination.mode)) ||
    !Number.isSafeInteger(config.pagination.size) ||
    Number(config.pagination.size) <= 0
  )
    throw new Error('分页方式或每页数量无效');
  validateRecordPresentation(config.presentation, definition, semantic);
}

/** Shared list trust boundary for initial loading and uncertain-write reconciliation. */
export function readInstanceList(
  value: unknown,
  definition: ViewDefinition,
): ViewInstance[] {
  if (
    !value ||
    typeof value !== 'object' ||
    !('instances' in value) ||
    !Array.isArray(value.instances)
  )
    throw new Error('实例列表必须包含 instances 数组');
  const seen = new Set<string>();
  for (const instance of value.instances) {
    validateViewInstance(instance, definition, undefined, false);
    if (seen.has(instance.id)) throw new Error(`实例 ID 重复：${instance.id}`);
    seen.add(instance.id);
  }
  if (
    !('defaultInstanceId' in value) ||
    (value.defaultInstanceId !== null &&
      (typeof value.defaultInstanceId !== 'string' ||
        !seen.has(value.defaultInstanceId)))
  )
    throw new Error('默认视图必须为当前列表中的实例 ID 或 null');
  return value.instances;
}
