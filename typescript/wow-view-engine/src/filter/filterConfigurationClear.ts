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
import { getBuiltinFilterCompiler } from './builtinFilterCompilers.js';
import { copy } from '../lib/snapshot.js';
import type { DeepReadonly } from '../lib/types.js';
import type {
  FilterCompilerRegistry,
  FilterConfiguration,
  FilterComponentConfig,
  FilterFieldDefinition,
} from './filterModel.js';
import { createFilterConfiguration } from './filterConfigurationState.js';
import { filterCompilerContext } from './filterConfigurationCompiler.js';
import { clearBuiltinFilterProps } from './filterBuiltinCompiler.js';
import { validateFilterConfiguration } from './filterConfigurationValidation.js';

export function filterClearCompiler(
  name: string,
  compilers?: FilterCompilerRegistry,
) {
  if (name === 'builtin') return { clear: clearBuiltinFilterProps };
  const compiler =
    compilers && Object.prototype.hasOwnProperty.call(compilers, name)
      ? compilers[name]
      : getBuiltinFilterCompiler(name);
  if (!compiler) throw new TypeError(`未注册筛选编译器：${name}`);
  return compiler;
}

export function clearFilterValues(
  node: DeepReadonly<FilterComponentConfig>,
  fields: readonly FilterFieldDefinition[],
  compilers?: FilterCompilerRegistry,
  timeZone?: string,
): FilterComponentConfig {
  const config = createFilterConfiguration(node);
  function clear(
    node: FilterConfiguration['root'],
    scope: readonly FilterFieldDefinition[],
  ) {
    if (node.operands) node.operands.forEach(child => clear(child, scope));
    else if (node.predicate)
      clear(
        node.predicate,
        scope.find(field => field.field === node.field)?.fields ?? [],
      );
    else {
      const compiler = filterClearCompiler(node.component.name, compilers);
      if (compiler.clear)
        node.props = compiler.clear(
          copy(node.props),
          filterCompilerContext(node, scope, timeZone),
        );
    }
  }
  clear(config.root, fields);
  validateFilterConfiguration(config);
  return config.root;
}
