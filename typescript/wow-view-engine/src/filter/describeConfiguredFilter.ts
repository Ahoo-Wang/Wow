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

import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import { compileFilterConfiguration } from './filterCore.js';
import { getBuiltinFilterCompiler } from './builtinFilterCompilers.js';
import { describeFilter } from './filterSummary.js';
import type {
  FilterComponentConfig,
  FilterFieldDefinition,
} from './filterModel.js';
import type { DeepReadonly } from '../lib/types.js';
export function describeConfiguredFilter(
  node: DeepReadonly<FilterComponentConfig>,
  fields: readonly FilterFieldDefinition[],
  allowedOperators?: Parameters<typeof compileFilterConfiguration>[2],
  compilers: NonNullable<Parameters<typeof compileFilterConfiguration>[3]> = {},
  timeZone?: string,
): ReturnType<typeof describeFilter> | undefined {
  const result = compileFilterConfiguration(
    { mode: 'advanced', root: node },
    fields,
    allowedOperators,
    compilers,
    timeZone,
  );
  if (
    !result.expression ||
    (result.expression.op === FilterOperator.MATCH_ALL &&
      node.operator !== FilterOperator.MATCH_ALL)
  )
    return undefined;
  const field = fields.find(field => field.field === node.field);
  const editor = node.component;
  const builtin =
    editor.name === 'builtin' ||
    (!Object.prototype.hasOwnProperty.call(compilers, editor.name) &&
      getBuiltinFilterCompiler(editor.name) !== undefined);
  return describeFilter(
    result.expression,
    fields,
    {
      node,
      timeZone: timeZone,
      showTime: builtin ? editor.options?.showTime === true : undefined,
      operands: node.operands?.flatMap(
        child =>
          describeConfiguredFilter(
            child,
            fields,
            allowedOperators,
            compilers,
            timeZone,
          ) ?? [],
      ),
      predicate: node.predicate
        ? describeConfiguredFilter(
            node.predicate,
            field?.fields ?? [],
            allowedOperators,
            compilers,
            timeZone,
          )
        : undefined,
    },
    false,
  );
}
