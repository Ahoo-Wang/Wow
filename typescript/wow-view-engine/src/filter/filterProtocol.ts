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
  type DeletionState,
  type SearchMode,
  type StringComparison,
  type TimeUnit,
  type ComparableFilterLiteral,
  type ElementFilterExpression,
  type FilterExpression,
  type FilterLiteral,
} from '@ahoo-wang/fetcher-wow';
import type { DeepReadonly } from '../lib/types.js';
import type { FilterOperatorDefinition } from './filterModel.js';
import { definition, stringOperators } from './filterOperators.js';

export interface ProtocolNode {
  id: string;
  op: Op;
  field?: string;
  value?: unknown;
  values?: unknown[];
  lowerBound?: unknown;
  upperBound?: unknown;
  operands?: ProtocolNode[];
  predicate?: ProtocolNode;
  query?: string;
  fields?: string[];
  mode?: SearchMode;
  state?: DeletionState;
  time?: string;
  days?: number | string;
  stringComparison?: StringComparison;
  zoneId?: string;
  datePattern?: string;
  timeUnit?: TimeUnit;
}

export function parseFilterOutput(
  expression: DeepReadonly<FilterExpression>,
): ProtocolNode {
  if (
    !expression ||
    typeof expression !== 'object' ||
    Array.isArray(expression)
  )
    throw new TypeError('过滤表达式必须是对象');
  const node: ProtocolNode = {
    ...expression,
    id: 'output',
  } as ProtocolNode;
  const descriptor = checkShape(node);
  if (node.operands !== undefined) {
    if (!Array.isArray(node.operands))
      throw new TypeError('分组条件必须是数组');
    node.operands = Array.from(
      (expression as { operands: DeepReadonly<FilterExpression[]> }).operands,
      parseFilterOutput,
    );
  }
  if (node.predicate !== undefined)
    node.predicate = parseFilterOutput(
      (expression as { predicate: DeepReadonly<FilterExpression> }).predicate,
    );
  if (node.values !== undefined) {
    if (!Array.isArray(node.values)) throw new TypeError('集合值必须是数组');
    node.values = [...node.values];
  }
  if (node.fields !== undefined) {
    if (!Array.isArray(node.fields)) throw new TypeError('搜索字段必须是数组');
    node.fields = [...node.fields];
  }
  // No editor conversion here: remote objects are not protocol literals.
  if (descriptor.input === 'values' && !Array.isArray(node.values))
    throw new TypeError('缺少集合值');
  if (descriptor.category === 'logical' && !Array.isArray(node.operands))
    throw new TypeError('缺少分组条件');
  build(node as unknown as CompiledNode);
  return node;
}

const optionalParameters = [
  'stringComparison',
  'fields',
  'mode',
  'zoneId',
  'datePattern',
  'timeUnit',
] as const;

export function checkShape(
  node: DeepReadonly<ProtocolNode>,
): FilterOperatorDefinition {
  const descriptor = definition(node.op);
  const keys = ['id', 'op'];
  if (descriptor.category === 'field' || descriptor.category === 'element')
    keys.push('field');
  if (descriptor.category === 'logical') keys.push('operands');
  if (descriptor.category === 'element') keys.push('predicate');
  switch (descriptor.input) {
    case 'value':
      keys.push('value');
      break;
    case 'values':
      keys.push('values');
      break;
    case 'between':
      keys.push('lowerBound', 'upperBound');
      break;
    case 'search':
      keys.push('query', 'fields', 'mode');
      break;
    case 'deletion':
      keys.push('state');
      break;
    case 'time':
      keys.push('time');
      break;
    case 'days':
      keys.push('days');
      break;
  }
  if (stringOperators.includes(node.op)) keys.push('stringComparison');
  if (descriptor.relativeTime) keys.push('zoneId', 'datePattern', 'timeUnit');
  for (const key of Object.keys(node)) {
    if (!keys.includes(key) && node[key as keyof ProtocolNode] !== undefined)
      throw new TypeError(`${node.op} 不支持参数 ${key}`);
  }
  return descriptor;
}

export function checkBuiltinProps(
  props: Readonly<Record<string, unknown>>,
): void {
  if (
    Object.keys(props).some(key =>
      [
        'id',
        'op',
        'field',
        'editor',
        'props',
        'operands',
        'predicate',
      ].includes(key),
    )
  )
    throw new TypeError('内置筛选属性不能覆盖组件结构');
}

export type CompiledNode = Omit<
  ProtocolNode,
  'operands' | 'predicate' | 'values' | 'fields'
> & {
  operands?: FilterExpression[];
  predicate?: FilterExpression;
  values?: readonly unknown[];
  fields?: readonly string[];
};

/** The Wow constructors remain the authority for wire-level operator validation. */
export function build(node: CompiledNode): FilterExpression {
  const field = node.field!;
  const value = node.value as ComparableFilterLiteral;
  const values = node.values as ComparableFilterLiteral[];
  let expression: FilterExpression;
  switch (node.op) {
    case Op.MATCH_ALL:
      return filter.matchAll();
    case Op.MATCH_NONE:
      return filter.matchNone();
    case Op.ID:
      return filter.id(value as string);
    case Op.IDS:
      return filter.ids(values as string[]);
    case Op.AGGREGATE_ID:
      return filter.aggregateId(value as string);
    case Op.AGGREGATE_IDS:
      return filter.aggregateIds(values as string[]);
    case Op.TENANT_ID:
      return filter.tenantId(value as string);
    case Op.OWNER_ID:
      return filter.ownerId(value as string);
    case Op.SPACE_ID:
      return filter.spaceId(value as string);
    case Op.AND:
      return filter.and(node.operands!);
    case Op.OR:
      return filter.or(node.operands!);
    case Op.NOR:
      return filter.nor(node.operands!);
    case Op.EQ:
      return filter.eq(field, node.value as FilterLiteral);
    case Op.NE:
      return filter.ne(field, node.value as FilterLiteral);
    case Op.GT:
      return filter.gt(field, value);
    case Op.GTE:
      return filter.gte(field, value);
    case Op.LT:
      return filter.lt(field, value);
    case Op.LTE:
      return filter.lte(field, value);
    case Op.CONTAINS:
      expression = filter.contains(
        field,
        value as string,
        node.stringComparison,
      );
      break;
    case Op.STARTS_WITH:
      expression = filter.startsWith(
        field,
        value as string,
        node.stringComparison,
      );
      break;
    case Op.ENDS_WITH:
      expression = filter.endsWith(
        field,
        value as string,
        node.stringComparison,
      );
      break;
    case Op.IN:
      return filter.isIn(field, values);
    case Op.NOT_IN:
      return filter.notIn(field, values);
    case Op.CONTAINS_ALL:
      return filter.containsAll(field, values);
    case Op.BETWEEN:
      return filter.between(
        field,
        node.lowerBound as ComparableFilterLiteral,
        node.upperBound as ComparableFilterLiteral,
      );
    case Op.IS_EMPTY:
      return filter.isEmpty(field);
    case Op.IS_EMPTY_STRING:
      return filter.isEmptyString(field);
    case Op.IS_NOT_EMPTY_STRING:
      return filter.isNotEmptyString(field);
    case Op.IS_NULL:
      return filter.isNull(field);
    case Op.IS_NOT_NULL:
      return filter.isNotNull(field);
    case Op.EXISTS:
      return filter.exists(field);
    case Op.NOT_EXISTS:
      return filter.notExists(field);
    case Op.DELETION:
      return filter.deletion(node.state!);
    case Op.ELEMENT_MATCH:
      return filter.elementMatch(
        field,
        node.predicate as ElementFilterExpression,
      );
    case Op.SEARCH:
      expression = filter.search(node.query!, {
        fields: node.fields,
        mode: node.mode,
      });
      break;
    case Op.TODAY:
      expression = filter.today(field, node);
      break;
    case Op.BEFORE_TODAY:
      expression = filter.beforeToday(field, node.time!, node);
      break;
    case Op.TOMORROW:
      expression = filter.tomorrow(field, node);
      break;
    case Op.THIS_WEEK:
      expression = filter.thisWeek(field, node);
      break;
    case Op.NEXT_WEEK:
      expression = filter.nextWeek(field, node);
      break;
    case Op.LAST_WEEK:
      expression = filter.lastWeek(field, node);
      break;
    case Op.THIS_MONTH:
      expression = filter.thisMonth(field, node);
      break;
    case Op.LAST_MONTH:
      expression = filter.lastMonth(field, node);
      break;
    case Op.YESTERDAY:
      expression = filter.yesterday(field, node);
      break;
    case Op.NEXT_MONTH:
      expression = filter.nextMonth(field, node);
      break;
    case Op.LAST_YEAR:
      expression = filter.lastYear(field, node);
      break;
    case Op.THIS_YEAR:
      expression = filter.thisYear(field, node);
      break;
    case Op.NEXT_YEAR:
      expression = filter.nextYear(field, node);
      break;
    case Op.RECENT_DAYS:
      expression = filter.recentDays(field, node.days as number, node);
      break;
    case Op.EARLIER_DAYS:
      expression = filter.earlierDays(field, node.days as number, node);
      break;
  }
  // Constructors supply defaults; loading or compiling an omitted option must not add it.
  for (const key of optionalParameters) {
    if (node[key] === undefined)
      delete (expression as unknown as Record<string, unknown>)[key];
  }
  return expression;
}
