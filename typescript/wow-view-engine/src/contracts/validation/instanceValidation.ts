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

import { validateDashboardConfig } from '../../dashboard/dashboardValidation.js';
import type { ViewDefinition, ViewInstance } from '../viewModel.js';
import { encodeViewResourceId } from '../viewServiceContract.js';
import { validateFilterJson } from '../../filter/filterConfigurationValidation.js';
import { validateAnalysisConfiguration } from '../../analysis/analysisConfigurationValidation.js';
import { validateRecordConfiguration } from '../../record/validation/configurationValidation.js';
import { assertObject, assertText } from './validationPrimitives.js';

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
  if (
    value.kind !== 'record' &&
    value.kind !== 'analysis' &&
    value.kind !== 'dashboard'
  )
    throw new Error('视图类型无效');
  if (!definition[value.kind]) throw new Error('定义未声明此视图能力');
  assertObject(value.scope, '实例范围');
  if (
    value.scope.type !== 'personal' &&
    !(
      value.scope.type === 'public' &&
      (value.scope.source === 'system' || value.scope.source === 'shared')
    )
  )
    throw new Error('实例范围无效');
  assertText(value.revision, '实例 revision');
  assertObject(value.config, '实例配置');
  const config = value.config;
  if (value.kind === 'dashboard') {
    validateDashboardConfig(config, semantic, Number.MAX_SAFE_INTEGER, {
      maxPanels: Number.MAX_SAFE_INTEGER,
      maxFilters: Number.MAX_SAFE_INTEGER,
    });
    return;
  }
  validateFilterJson(config);
  if (value.kind === 'analysis') validateAnalysisConfiguration(config);
  else validateRecordConfiguration(config, definition, semantic);
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
  // 每个实例已经 validateViewInstance 校验，这里收窄为契约返回类型。
  return value.instances as ViewInstance[];
}
