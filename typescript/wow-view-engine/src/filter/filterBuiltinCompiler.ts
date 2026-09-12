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
import { cloneSnapshot, type DeepReadonly } from '../lib/types.js';
import { fixedTimeZoneOffset, validateTimeZone } from '../lib/timeZone.js';
import type {
  FilterCompilerContext,
  FilterComponentProperties,
  FilterCompileResult,
  FilterFieldDefinition,
  FilterValidationError,
} from './filterModel.js';
import { validateFilterNodeContext } from './filterConfigurationValidation.js';
import { getFieldOperators, stringOperators } from './filterOperators.js';
import {
  build,
  checkShape,
  checkBuiltinProps,
  type CompiledNode,
  type ProtocolNode,
} from './filterProtocol.js';
import { numeric, scalar } from './filterScalar.js';
import { TZDate } from '@date-fns/tz';
import {
  dateTimeValue,
  dateTimeToSeconds,
  timeToSeconds,
} from './filterDateTimeValue.js';

/** 类型保持的数组守卫：Array.isArray 的 any[] 谓词会把 readonly 数组退化为 any[]。 */
function isReadonlyArray(value: unknown): value is readonly unknown[] {
  return Array.isArray(value);
}

export function compileProtocolNode(
  draft: DeepReadonly<ProtocolNode>,
  fields: readonly FilterFieldDefinition[],
  allowedOperators?: readonly Op[],
  timeZone?: string,
): FilterCompileResult {
  const errors: FilterValidationError[] = [];
  const visit = (
    node: DeepReadonly<ProtocolNode>,
    scope: readonly FilterFieldDefinition[],
    element = false,
  ): FilterExpression | undefined => {
    try {
      const descriptor = checkShape(node);
      const field = validateFilterNodeContext(
        node.op,
        node.field,
        scope,
        allowedOperators,
        element,
      );
      if (descriptor.category === 'logical') {
        // 类型保持守卫，避免 Array.isArray 把 readonly operands 退化为 any[]。
        if (!isReadonlyArray(node.operands) || node.operands.length === 0)
          throw new TypeError('分组至少需要一个条件');
        const operands = Array.from(node.operands, child =>
          visit(child, scope, element),
        ).filter((child): child is FilterExpression => child !== undefined);
        return operands.length
          ? build({ ...node, operands, predicate: undefined })
          : undefined;
      }
      if (descriptor.category === 'element') {
        if (!node.predicate) throw new TypeError('请补全元素条件');
        const predicate = visit(node.predicate, field?.fields ?? [], true);
        return predicate
          ? build({ ...node, operands: undefined, predicate })
          : undefined;
      }
      const compiled: CompiledNode = {
        ...node,
        operands: undefined,
        predicate: undefined,
      };
      if (node.op === Op.SEARCH && node.fields !== undefined) {
        if (
          !Array.isArray(node.fields) ||
          Array.from(node.fields).some(
            name => !scope.some(candidate => candidate.field === name),
          )
        )
          throw new TypeError('搜索包含当前作用域没有的字段');
      }
      // Validate optional parameters even while the corresponding value is unset.
      if (stringOperators.includes(node.op)) build({ ...compiled, value: '' });
      if (descriptor.relativeTime)
        build({
          ...compiled,
          time: node.time ?? '00:00',
          days: node.days === undefined ? 1 : numeric(node.days),
        });
      if (node.op === Op.SEARCH)
        build({ ...compiled, query: node.query ?? '_' });
      switch (descriptor.input) {
        case 'value':
          compiled.value = scalar(
            node.value,
            stringOperators.includes(node.op) ? undefined : field,
            timeZone,
          );
          if (compiled.value === undefined) return undefined;
          break;
        case 'values':
          if (node.values === undefined) return undefined;
          if (!Array.isArray(node.values))
            throw new TypeError('集合值必须是数组');
          if (node.values.length === 0) return undefined;
          compiled.values = Array.from(node.values, value => {
            const result = scalar(value, field, timeZone);
            if (result === undefined) throw new TypeError('请补全集合中的值');
            return result;
          });
          break;
        case 'between': {
          const lower = scalar(node.lowerBound, field, timeZone);
          const upper = scalar(node.upperBound, field, timeZone);
          if (lower === undefined && upper === undefined) return undefined;
          if (lower === undefined || upper === undefined)
            throw new TypeError('请补全范围上下界');
          if (
            lower === null ||
            upper === null ||
            typeof lower !== typeof upper ||
            lower > upper
          )
            throw new TypeError('范围上下界类型必须相同且下界不能大于上界');
          compiled.lowerBound = lower;
          compiled.upperBound = upper;
          break;
        }
        case 'deletion':
          if (node.state === undefined) return undefined;
          break;
        case 'search':
          if (node.query === undefined) return undefined;
          break;
        case 'time':
          if (node.time === undefined) return undefined;
          break;
        case 'days':
          if (node.days === undefined) return undefined;
          compiled.days = numeric(node.days);
          break;
      }
      return build(compiled);
    } catch (error) {
      errors.push({
        id: node?.id ?? draft.id,
        message: error instanceof Error ? error.message : '过滤条件无效',
      });
      return undefined;
    }
  };
  const expression = visit(draft, fields);
  return errors.length
    ? { errors }
    : { expression: expression ?? filter.matchAll(), errors };
}

export function compileBuiltinFilter(
  props: DeepReadonly<FilterComponentProperties>,
  context: FilterCompilerContext,
): FilterExpression | undefined {
  checkBuiltinProps(props);
  const node = {
    ...props,
    id: 'builtin',
    op: context.operator,
    ...(context.field ? { field: context.field.field } : {}),
  } as ProtocolNode;
  const descriptor = checkShape(node);
  if (
    context.options?.showTime !== undefined &&
    typeof context.options.showTime !== 'boolean'
  )
    throw new TypeError('showTime 必须是布尔值');
  validateTimeZone(context.timeZone);
  if (
    context.field?.type === 'datetime' &&
    !context.field.options &&
    context.options?.showTime !== true &&
    ['value', 'values', 'between'].includes(descriptor.input)
  )
    return compileCalendarDays(node, context);
  if (context.field?.type === 'datetime' && !context.field.options) {
    if ('value' in node) node.value = dateTimeToSeconds(node.value);
    if (Array.isArray(node.values))
      node.values = node.values.map(dateTimeToSeconds);
    if ('lowerBound' in node)
      node.lowerBound = dateTimeToSeconds(node.lowerBound);
    if ('upperBound' in node)
      node.upperBound = dateTimeToSeconds(node.upperBound);
  }
  if (descriptor.relativeTime) {
    node.zoneId =
      context.timeZone ?? Intl.DateTimeFormat().resolvedOptions().timeZone;
    if (typeof node.time === 'string') node.time = timeToSeconds(node.time);
  }
  const result = compileProtocolNode(
    node,
    context.fields,
    undefined,
    context.timeZone,
  );
  if (result.errors.length)
    throw new TypeError(result.errors.map(error => error.message).join('；'));
  return result.expression?.op === Op.MATCH_ALL && node.op !== Op.MATCH_ALL
    ? undefined
    : result.expression;
}

/** Date-only editing keeps calendar dates in props and lowers them at the query boundary. */
function compileCalendarDays(
  node: ProtocolNode,
  context: FilterCompilerContext,
): FilterExpression | undefined {
  const field = context.field!;
  if (!getFieldOperators(field).includes(node.op))
    throw new TypeError(`字段 ${field.label} 不支持操作 ${node.op}`);
  const day = (value: unknown) => {
    if (value === null || value === undefined) return value;
    if (
      typeof value !== 'number' &&
      typeof value !== 'string' &&
      (!value ||
        typeof value !== 'object' ||
        Array.isArray(value) ||
        Object.keys(value).some(
          key => !['date', 'time', 'offsetMinutes'].includes(key),
        ))
    )
      throw new TypeError('日期值无效');
    const date = dateTimeValue(value, context.timeZone).date;
    return date === '' ? undefined : date;
  };
  const normalized = { ...node };
  if ('value' in node) normalized.value = day(node.value);
  if (Array.isArray(node.values)) normalized.values = node.values.map(day);
  if ('lowerBound' in node) normalized.lowerBound = day(node.lowerBound);
  if ('upperBound' in node) normalized.upperBound = day(node.upperBound);
  const result = compileProtocolNode(
    normalized,
    context.fields.map(item =>
      item.field === field.field ? { ...item, type: 'date' } : item,
    ),
    undefined,
    context.timeZone,
  );
  if (result.errors.length)
    throw new TypeError(result.errors.map(error => error.message).join('；'));
  const expression = result.expression!;
  if (expression.op === Op.MATCH_ALL) return undefined;
  const bounds = (value: unknown): [number, number] => {
    const [year, month, day] = String(value).split('-').map(Number);
    const offset = fixedTimeZoneOffset(context.timeZone);
    const timeZone = offset === undefined ? context.timeZone : 'UTC';
    const start = new TZDate(0, timeZone);
    start.setFullYear(year, month - 1, day);
    start.setHours(0, 0, 0, 0);
    if (
      start.getFullYear() !== year ||
      start.getMonth() !== month - 1 ||
      start.getDate() !== day
    )
      throw new TypeError('日期在指定时区不存在');
    const next = new TZDate(start.getTime(), timeZone);
    next.setDate(next.getDate() + 1);
    next.setHours(0, 0, 0, 0);
    const shift = (offset ?? 0) * 60_000;
    return [start.getTime() - shift, next.getTime() - shift - 1];
  };
  if ('value' in expression) {
    if (expression.value === null) return expression;
    const [start, end] = bounds(expression.value);
    switch (expression.op) {
      case Op.EQ:
        return filter.between(field.field, start, end);
      case Op.NE:
        return filter.nor([filter.between(field.field, start, end)]);
      case Op.GT:
        return filter.gt(field.field, end);
      case Op.GTE:
        return filter.gte(field.field, start);
      case Op.LT:
        return filter.lt(field.field, start);
      case Op.LTE:
        return filter.lte(field.field, end);
    }
  }
  if ('values' in expression) {
    const ranges = expression.values.map(value => {
      const [start, end] = bounds(value);
      return filter.between(field.field, start, end);
    });
    return expression.op === Op.IN ? filter.or(ranges) : filter.nor(ranges);
  }
  if ('lowerBound' in expression)
    return filter.between(
      field.field,
      bounds(expression.lowerBound)[0],
      bounds(expression.upperBound)[1],
    );
  return expression;
}

export function clearBuiltinFilterProps(
  props: DeepReadonly<FilterComponentProperties>,
): FilterComponentProperties {
  const next = cloneSnapshot<FilterComponentProperties>(props);
  for (const key of [
    'value',
    'values',
    'lowerBound',
    'upperBound',
    'query',
    'state',
    'time',
    'days',
  ])
    delete next[key];
  return next;
}
