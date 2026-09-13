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
  filter,
  FilterOperator as Op,
  type FilterExpression,
} from '@ahoo-wang/fetcher-wow';
import type { ViewDefinition, ViewInstance } from '../contracts/viewModel.js';
import type { DeepReadonly } from '../lib/types.js';
import { copy, freeze } from '../lib/snapshot.js';
import { RuntimeLimitError } from '../lib/runtimeLimits.js';
import { compileFilterConfiguration } from '../filter/filterConfigurationCompiler.js';
import { validateFilterJson } from '../filter/filterConfigurationValidation.js';
import { compileProtocolNode } from '../filter/filterBuiltinCompiler.js';
import {
  build,
  parseFilterOutput,
  type ProtocolNode,
} from '../filter/filterProtocol.js';
import { definition as operatorDefinition } from '../filter/filterOperators.js';
import type {
  FilterCompilerRegistry,
  FilterFieldDefinition,
} from '../filter/filterModel.js';
import { validateDashboardFilterBudget } from './dashboardValidation.js';
import type {
  DashboardFilter,
  DashboardViewPanel,
  DashboardTransforms,
} from './dashboardModel.js';

/** Check expression topology before calling recursive protocol validators. */
export function assertDashboardExpressionBudget(expression: unknown): void {
  const pending: Array<{ value: unknown; depth: number }> = [
    { value: expression, depth: 1 },
  ];
  let count = 0;
  while (pending.length) {
    const { value, depth } = pending.pop()!;
    if (++count > 512 || depth > 32)
      throw new RuntimeLimitError(
        'RESOURCE_LIMIT',
        '仪表盘条件超出深度或节点预算',
      );
    if (!value || typeof value !== 'object' || Array.isArray(value))
      throw new TypeError('过滤表达式必须是对象');
    const descriptors = Object.getOwnPropertyDescriptors(value);
    for (const [key, descriptor] of Object.entries(descriptors)) {
      if (
        !('value' in descriptor) ||
        ['__proto__', 'constructor', 'prototype'].includes(key)
      )
        throw new TypeError('过滤表达式包含不安全属性');
    }
    const operands: unknown = descriptors.operands?.value;
    if (operands !== undefined) {
      if (!Array.isArray(operands)) throw new TypeError('分组条件必须是数组');
      for (const child of operands)
        pending.push({ value: child, depth: depth + 1 });
    }
    const predicate: unknown = descriptors.predicate?.value;
    if (predicate !== undefined)
      pending.push({ value: predicate, depth: depth + 1 });
  }
}
function safePath(path: string): string {
  if (
    typeof path !== 'string' ||
    path
      .split('.')
      .some(
        part =>
          !part || ['__proto__', 'constructor', 'prototype'].includes(part),
      )
  )
    throw new TypeError('字段路径无效');
  return path;
}

export function validateDashboardExpression(
  expression: unknown,
  target: DeepReadonly<ViewDefinition>,
): FilterExpression {
  assertDashboardExpressionBudget(expression);
  validateFilterJson(expression);
  const node = parseFilterOutput(expression as FilterExpression);
  function paths(value: ProtocolNode): void {
    if (value.field !== undefined) safePath(value.field);
    value.fields?.forEach(safePath);
    value.operands?.forEach(paths);
    if (value.predicate) paths(value.predicate);
  }
  paths(node);
  const result = compileProtocolNode(
    node,
    target.fields,
    target.allowedOperators,
    target.timeZone,
  );
  if (result.errors.length || !result.expression)
    throw new TypeError(
      result.errors.map(error => error.message).join('；') || '过滤表达式无效',
    );
  return result.expression;
}

/** Returns the complete bound expression; any missing branch rejects the panel scope. */
export function compileDashboardScope(
  item: DeepReadonly<DashboardFilter>,
  panel: DeepReadonly<DashboardViewPanel>,
  source: DeepReadonly<ViewDefinition>,
  target: DeepReadonly<ViewDefinition>,
  instance: DeepReadonly<ViewInstance>,
  transforms: DashboardTransforms,
  compilers: FilterCompilerRegistry = {},
): FilterExpression {
  const bindings = item.bindings.filter(
    binding => binding.panelId === panel.id,
  );
  if (bindings.length !== 1 || item.excludedPanelIds.includes(panel.id))
    throw new TypeError('面板必须明确选择一种筛选绑定');
  validateDashboardFilterBudget(item.filters.root);
  const compiled = compileFilterConfiguration(
    item.filters,
    source.fields,
    source.allowedOperators,
    compilers,
    source.timeZone,
  );
  if (compiled.errors.length || !compiled.expression)
    throw new TypeError(
      compiled.errors.map(error => error.message).join('；') || '全局筛选无效',
    );
  assertDashboardExpressionBudget(compiled.expression);
  const binding = bindings[0];
  if (binding.kind === 'transform') {
    const transform = Object.prototype.hasOwnProperty.call(
      transforms,
      binding.name,
    )
      ? transforms[binding.name]
      : undefined;
    if (!transform) throw new TypeError(`缺少筛选转换器：${binding.name}`);
    return validateDashboardExpression(
      transform(
        freeze(
          copy({
            expression: compiled.expression,
            source,
            target,
            instance,
            options: binding.options,
          }),
        ),
      ),
      target,
    );
  }
  if (binding.semanticCompatibility !== true)
    throw new TypeError('需要确认字段的业务语义兼容');
  const fieldMappings = binding.fields;
  function map(
    node: ProtocolNode,
    sourceFields: readonly FilterFieldDefinition[],
    targetFields: readonly FilterFieldDefinition[],
    sourcePrefix = '',
    targetPrefix = '',
  ): FilterExpression {
    const category = operatorDefinition(node.op).category;
    if (category === 'logical')
      return build({
        ...node,
        operands: node.operands!.map(child =>
          map(child, sourceFields, targetFields, sourcePrefix, targetPrefix),
        ),
        predicate: undefined,
      });
    function mapped(path: string): {
      path: string;
      sourceField: FilterFieldDefinition;
      targetField: FilterFieldDefinition;
    } {
      const full = safePath(sourcePrefix + path);
      if (!Object.prototype.hasOwnProperty.call(fieldMappings, full))
        throw new TypeError(`缺少字段映射：${full}`);
      const mappedFull = safePath(fieldMappings[full]);
      if (!mappedFull.startsWith(targetPrefix))
        throw new TypeError('字段映射越出元素作用域');
      const relative = mappedFull.slice(targetPrefix.length);
      const sourceField = sourceFields.find(field => field.field === path);
      const targetField = targetFields.find(field => field.field === relative);
      if (
        !sourceField ||
        !targetField ||
        !sourceField.type ||
        sourceField.type !== targetField.type
      )
        throw new TypeError(`字段映射类型或作用域不兼容：${full}`);
      if (
        (sourceField.type === 'date' || sourceField.type === 'datetime') &&
        source.timeZone !== target.timeZone
      )
        throw new TypeError('不同时区需要宿主转换');
      return { path: relative, sourceField, targetField };
    }
    if (category === 'root') {
      if (node.op === Op.MATCH_ALL) return filter.matchAll();
      if (node.op === Op.MATCH_NONE) return filter.matchNone();
      if (node.op === Op.SEARCH && node.fields?.length)
        return build({
          ...node,
          fields: node.fields.map(field => mapped(field).path),
          operands: undefined,
          predicate: undefined,
        });
      throw new TypeError('隐式根条件需要宿主转换');
    }
    const field = mapped(node.field!);
    return build({
      ...node,
      field: field.path,
      operands: undefined,
      predicate: node.predicate
        ? map(
            node.predicate,
            field.sourceField.fields ?? [],
            field.targetField.fields ?? [],
            sourcePrefix + node.field! + '.',
            targetPrefix + field.path + '.',
          )
        : undefined,
    });
  }
  return validateDashboardExpression(
    map(parseFilterOutput(compiled.expression), source.fields, target.fields),
    target,
  );
}
