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
import type { ViewDefinition } from '../../contracts/viewModel.js';
import { validateFilterConfiguration } from '../../filter/filterConfiguration.js';
import { validateFilterConfigurationStructure } from '../../filter/filterConfigurationValidation.js';
import { validateRecordPresentation } from './presentationValidation.js';
import {
  assertObject,
  assertText,
} from '../../contracts/validation/validationPrimitives.js';

export function validateRecordConfiguration(
  config: Record<string, unknown>,
  definition: ViewDefinition,
  semantic: boolean,
): void {
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
    (config.pagination.mode !== 'paged' &&
      config.pagination.mode !== 'cursor') ||
    !Number.isSafeInteger(config.pagination.size) ||
    Number(config.pagination.size) <= 0
  )
    throw new Error('分页方式或每页数量无效');
  validateRecordPresentation(config.presentation, definition, semantic);
}
