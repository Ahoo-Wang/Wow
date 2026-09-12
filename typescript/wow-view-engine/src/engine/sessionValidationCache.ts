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
  assertConfigSize,
  validateRuntimeLimits,
} from '../lib/runtimeLimits.js';
import type { FilterValidationError } from '../filter/filterModel.js';

import { compileFilterConfiguration } from '../filter/filterConfiguration.js';
import type {
  FilterCompilerRegistry,
  FilterConfiguration,
  FilterCompileResult,
} from '../filter/filterModel.js';
import type { ViewDefinition } from '../contracts/viewModel.js';
import type { DeepReadonly } from '../lib/types.js';

const filters = new WeakMap<
  object,
  {
    definition: DeepReadonly<ViewDefinition>;
    compilers: FilterCompilerRegistry;
    result: FilterCompileResult;
  }
>();
/** Cache compilation by immutable input and its complete definition/registry context, never by editor validity. */
export function compileSessionFilter(
  config: DeepReadonly<FilterConfiguration>,
  definition: DeepReadonly<ViewDefinition>,
  compilers: FilterCompilerRegistry,
): FilterCompileResult {
  const cached = filters.get(config);
  if (cached?.definition === definition && cached.compilers === compilers)
    return cached.result;
  const result = compileFilterConfiguration(
    config,
    definition.fields,
    definition.allowedOperators,
    compilers,
    definition.timeZone,
  );
  filters.set(config, { definition, compilers, result });
  return result;
}

const defaults = validateRuntimeLimits();
const sizes = new WeakMap<
  object,
  { limit: number; issues: readonly FilterValidationError[] }
>();

/** Configurations are immutable snapshots; cache size independently from derived editor validity. */
export function configSizeIssues(
  config: object,
  limit = defaults.maxConfigBytes,
): readonly FilterValidationError[] {
  const cached = sizes.get(config);
  if (cached?.limit === limit) return cached.issues;
  let issues: readonly FilterValidationError[] = [];
  try {
    assertConfigSize(config, limit);
  } catch (error) {
    issues = [
      {
        id: 'config-size',
        message: error instanceof Error ? error.message : '配置超过资源限制',
      },
    ];
  }
  sizes.set(config, { limit, issues });
  return issues;
}
