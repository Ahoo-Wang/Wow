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
import {
  filter,
  type FilterOperator as Op,
  type FilterExpression,
} from '@ahoo-wang/fetcher-wow';
import { copy } from '../lib/snapshot.js';
import { validateTimeZone } from '../lib/timeZone.js';
import type { DeepReadonly } from '../lib/types.js';
import type {
  FilterCompileResult,
  FilterCompilerContext,
  FilterCompilerRegistry,
  FilterComponentConfig,
  FilterConfiguration,
  FilterFieldDefinition,
  FilterValidationError,
} from './filterModel.js';
import {
  FilterConfigurationError,
  validateFilterConfiguration,
  validateFilterJson,
  validateFilterNodeContext,
} from './filterConfigurationValidation.js';
import {
  compileProtocolNode,
  compileBuiltinFilter,
} from './filterBuiltinCompiler.js';
import { definition } from './filterOperators.js';
import { build, parseFilterOutput } from './filterProtocol.js';

export function filterCompilerContext(
  node: DeepReadonly<FilterComponentConfig>,
  fields: readonly FilterFieldDefinition[],
  timeZone?: string,
): FilterCompilerContext {
  const field = fields.find(field => field.field === node.field);
  return copy({
    operator: node.operator,
    fields,
    timeZone,
    ...(field ? { field } : {}),
    ...(node.component.options ? { options: node.component.options } : {}),
  });
}
function validateOutput(
  expression: FilterExpression,
  node: DeepReadonly<FilterComponentConfig>,
  fields: readonly FilterFieldDefinition[],
  allowedOperators?: readonly Op[],
  timeZone?: string,
  builtin = false,
): FilterExpression {
  validateFilterJson(expression);
  const draft = parseFilterOutput(expression);
  const bound = definition(node.operator).category === 'field';
  function binding(output: DeepReadonly<ReturnType<typeof parseFilterOutput>>) {
    const category = definition(output.op).category;
    if (bound && category === 'logical') output.operands!.forEach(binding);
    else if (
      bound
        ? category !== 'field' || output.field !== node.field
        : output.op !== node.operator || output.field !== node.field
    )
      throw new TypeError('自定义筛选器不能改变绑定字段或条件容器。');
  }
  binding(draft);
  // The chosen built-in operator was checked before lowering calendar days.
  // Generated range/group operators express that same authorized condition.
  const result = compileProtocolNode(
    draft,
    builtin
      ? fields.map(field => ({
          ...field,
          operators: undefined,
          editor: undefined,
        }))
      : fields,
    builtin ? undefined : allowedOperators,
    timeZone,
  );
  if (result.errors.length)
    throw new TypeError(result.errors.map(error => error.message).join('；'));
  return result.expression!;
}

export function compileFilterConfiguration(
  config: DeepReadonly<FilterConfiguration>,
  fields: readonly FilterFieldDefinition[],
  allowedOperators?: readonly Op[],
  compilers?: FilterCompilerRegistry,
  timeZone?: string,
): FilterCompileResult {
  const errors: FilterValidationError[] = [];
  try {
    validateFilterConfiguration(config);
    validateTimeZone(timeZone);
  } catch (error) {
    return {
      errors: [
        {
          id:
            error instanceof FilterConfigurationError
              ? error.id
              : (config?.root?.id ?? ''),
          message: error instanceof Error ? error.message : '筛选配置无效',
        },
      ],
    };
  }
  function visit(
    node: DeepReadonly<FilterComponentConfig>,
    scope: readonly FilterFieldDefinition[],
    element = false,
  ): FilterExpression | undefined {
    try {
      const descriptor = definition(node.operator);
      const field = validateFilterNodeContext(
        node.operator,
        node.field,
        scope,
        allowedOperators,
        element,
      );
      if (descriptor.category === 'logical') {
        if (!node.operands?.length) throw new TypeError('分组至少需要一个条件');
        const operands = node.operands
          .map(child => visit(child, scope, element))
          .filter((value): value is FilterExpression => value !== undefined);
        return operands.length
          ? build({ id: node.id, op: node.operator, operands })
          : undefined;
      }
      if (descriptor.category === 'element') {
        const predicate = visit(node.predicate!, field?.fields ?? [], true);
        return predicate
          ? build({
              id: node.id,
              op: node.operator,
              field: node.field,
              predicate,
            })
          : undefined;
      }
      const compiler =
        node.component.name === 'builtin'
          ? { compile: compileBuiltinFilter }
          : compilers &&
              Object.prototype.hasOwnProperty.call(
                compilers,
                node.component.name,
              )
            ? compilers[node.component.name]
            : getBuiltinFilterCompiler(node.component.name);
      if (!compiler || typeof compiler.compile !== 'function')
        throw new TypeError(`未注册筛选编译器：${node.component.name}`);
      const expression = compiler.compile(
        copy(node.props),
        filterCompilerContext(node, scope, timeZone),
      );
      if (expression === undefined) return undefined;
      return validateOutput(
        expression,
        node,
        scope,
        allowedOperators,
        timeZone,
        node.component.name === 'builtin' ||
          compiler === getBuiltinFilterCompiler(node.component.name),
      );
    } catch (error) {
      errors.push({
        id: node.id,
        message: error instanceof Error ? error.message : '筛选编译失败',
      });
      return undefined;
    }
  }
  const expression = visit(config.root, fields);
  return errors.length
    ? { errors }
    : { expression: expression ?? filter.matchAll(), errors };
}
