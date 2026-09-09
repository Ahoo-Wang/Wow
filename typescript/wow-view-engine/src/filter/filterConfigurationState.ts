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
import type { FilterOperator } from '@ahoo-wang/fetcher-wow';
import type { DeepReadonly } from '../lib/types.js';
import type {
  FilterComponentConfig,
  FilterConfiguration,
  FilterEditorReference,
  FilterFieldDefinition,
  FilterMode,
} from './filterModel.js';
import { isSimpleFilter } from './filterNodes.js';
import { definition } from './filterOperators.js';
import {
  validateFilterJson,
  validateFilterConfiguration,
} from './filterConfigurationValidation.js';

/** Resolve defaults only when constructing a new node. Saved references are authoritative. */
export function resolveFilterComponent(
  operator: FilterOperator,
  field?: DeepReadonly<FilterFieldDefinition>,
  editors?: Readonly<Partial<Record<FilterOperator, FilterEditorReference>>>,
): FilterEditorReference {
  if (['logical', 'element'].includes(definition(operator).category))
    return { name: 'builtin' };
  return structuredClone(
    field?.editor ?? editors?.[operator] ?? { name: 'builtin' },
  );
}

export function createFilterConfiguration(
  root: DeepReadonly<FilterComponentConfig>,
  mode?: FilterMode,
): FilterConfiguration {
  validateFilterJson(root);
  const config = {
    mode: mode ?? (isSimpleFilter(root) ? 'simple' : 'advanced'),
    root: structuredClone(root) as FilterComponentConfig,
  };
  validateFilterConfiguration(config);
  return config;
}
