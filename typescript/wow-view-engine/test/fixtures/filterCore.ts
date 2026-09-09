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
  DeletionState,
  filter,
  FilterOperator as Op,
  StringComparison,
  TimeUnit,
  type FilterExpression,
} from '@ahoo-wang/fetcher-wow';
import { compileFilterConfiguration } from '../../src/filter/filterCore';
import type {
  FilterComponentConfig,
  FilterComponentProperties,
  FilterFieldDefinition,
} from '../../src/filter/filterModel';

export const fields: FilterFieldDefinition[] = [
  { field: 'name', label: '名称', type: 'string' },
  { field: 'amount', label: '金额', type: 'number' },
  { field: 'enabled', label: '启用', type: 'boolean' },
  { field: 'day', label: '日期', type: 'date' },
  {
    field: 'created',
    label: '时间',
    type: 'datetime',
    editor: { name: 'builtin', options: { showTime: true } },
  },
  {
    field: 'items',
    label: '明细',
    type: 'array',
    fields: [{ field: 'quantity', label: '数量', type: 'number' }],
  },
  {
    field: 'status',
    label: '状态',
    options: [
      { value: 0, label: '零' },
      { value: false, label: '否' },
      { value: '', label: '空' },
    ],
  },
];
export const calendar = [
  Op.TODAY,
  Op.TOMORROW,
  Op.THIS_WEEK,
  Op.NEXT_WEEK,
  Op.LAST_WEEK,
  Op.THIS_MONTH,
  Op.LAST_MONTH,
  Op.YESTERDAY,
  Op.NEXT_MONTH,
  Op.LAST_YEAR,
  Op.THIS_YEAR,
  Op.NEXT_YEAR,
] as const;
export const expressions: FilterExpression[] = [
  filter.matchAll(),
  filter.matchNone(),
  filter.id('001'),
  filter.ids(['001', '002']),
  filter.aggregateId('001'),
  filter.aggregateIds(['001']),
  filter.tenantId('01'),
  filter.ownerId('02'),
  filter.spaceId('03'),
  filter.and([filter.eq('amount', 0)]),
  filter.or([filter.eq('name', '')]),
  filter.nor([filter.eq('enabled', false)]),
  filter.eq('amount', null),
  filter.ne('name', null),
  filter.gt('amount', 1),
  filter.gte('amount', 1),
  filter.lt('amount', 2),
  filter.lte('amount', 2),
  { op: Op.CONTAINS, field: 'name', value: '' },
  {
    op: Op.STARTS_WITH,
    field: 'name',
    value: 'a',
    stringComparison: StringComparison.CASE_INSENSITIVE,
  },
  { op: Op.ENDS_WITH, field: 'name', value: 'z' },
  filter.isIn('amount', [0, 2]),
  filter.notIn('enabled', [false]),
  filter.between('amount', 0, 10),
  filter.containsAll('items', ['a', 0, false]),
  filter.isEmpty('items'),
  filter.isEmptyString('name'),
  filter.isNotEmptyString('name'),
  filter.isNull('amount'),
  filter.isNotNull('amount'),
  filter.exists('name'),
  filter.notExists('name'),
  filter.deletion(DeletionState.ALL),
  filter.elementMatch('items', filter.and([filter.gt('quantity', 0)])),
  { op: Op.SEARCH, query: '订单' },
  ...calendar.map(op => ({ op, field: 'created', zoneId: 'Asia/Shanghai' })),
  {
    op: Op.BEFORE_TODAY,
    field: 'created',
    time: '12:30:59',
    zoneId: 'Asia/Shanghai',
    datePattern: 'yyyy-MM-dd',
    timeUnit: TimeUnit.SECONDS,
  },
  { op: Op.RECENT_DAYS, field: 'created', days: 7, zoneId: 'Asia/Shanghai' },
  { op: Op.EARLIER_DAYS, field: 'created', days: 1, zoneId: 'Asia/Shanghai' },
];
export const compile = (
  root: FilterComponentConfig,
  definitions = fields,
  timeZone = 'Asia/Shanghai',
) =>
  compileFilterConfiguration(
    { mode: 'advanced', root },
    definitions,
    undefined,
    undefined,
    timeZone,
  );
export function node(
  operator: Op,
  field?: string,
  props: Record<string, unknown> = {},
  children: Pick<FilterComponentConfig, 'operands' | 'predicate'> = {},
): FilterComponentConfig {
  return {
    id: crypto.randomUUID(),
    operator,
    component: { name: 'builtin', options: { showTime: true } },
    ...(field === undefined ? {} : { field }),
    props: props as FilterComponentProperties,
    ...children,
  };
}

export const configurations = [
  node(Op.MATCH_ALL),
  node(Op.MATCH_NONE),
  node(Op.ID, undefined, { value: '001' }),
  node(Op.IDS, undefined, { values: ['001', '002'] }),
  node(Op.AGGREGATE_ID, undefined, { value: '001' }),
  node(Op.AGGREGATE_IDS, undefined, { values: ['001'] }),
  node(Op.TENANT_ID, undefined, { value: '01' }),
  node(Op.OWNER_ID, undefined, { value: '02' }),
  node(Op.SPACE_ID, undefined, { value: '03' }),
  node(
    Op.AND,
    undefined,
    {},
    { operands: [node(Op.EQ, 'amount', { value: 0 })] },
  ),
  node(
    Op.OR,
    undefined,
    {},
    { operands: [node(Op.EQ, 'name', { value: '' })] },
  ),
  node(
    Op.NOR,
    undefined,
    {},
    { operands: [node(Op.EQ, 'enabled', { value: false })] },
  ),
  node(Op.EQ, 'amount', { value: null }),
  node(Op.NE, 'name', { value: null }),
  node(Op.GT, 'amount', { value: 1 }),
  node(Op.GTE, 'amount', { value: 1 }),
  node(Op.LT, 'amount', { value: 2 }),
  node(Op.LTE, 'amount', { value: 2 }),
  node(Op.CONTAINS, 'name', { value: '' }, {}),
  node(
    Op.STARTS_WITH,
    'name',
    { value: 'a', stringComparison: StringComparison.CASE_INSENSITIVE },
    {},
  ),
  node(Op.ENDS_WITH, 'name', { value: 'z' }, {}),
  node(Op.IN, 'amount', { values: [0, 2] }),
  node(Op.NOT_IN, 'enabled', { values: [false] }),
  node(Op.BETWEEN, 'amount', { lowerBound: 0, upperBound: 10 }),
  node(Op.CONTAINS_ALL, 'items', { values: ['a', 0, false] }),
  node(Op.IS_EMPTY, 'items', {}),
  node(Op.IS_EMPTY_STRING, 'name', {}),
  node(Op.IS_NOT_EMPTY_STRING, 'name', {}),
  node(Op.IS_NULL, 'amount', {}),
  node(Op.IS_NOT_NULL, 'amount', {}),
  node(Op.EXISTS, 'name', {}),
  node(Op.NOT_EXISTS, 'name', {}),
  node(Op.DELETION, undefined, { state: DeletionState.ALL }),
  node(
    Op.ELEMENT_MATCH,
    'items',
    {},
    {
      predicate: node(
        Op.AND,
        undefined,
        {},
        { operands: [node(Op.GT, 'quantity', { value: 0 })] },
      ),
    },
  ),
  node(Op.SEARCH, undefined, { query: '订单' }, {}),
  ...calendar.map(op => node(op, 'created', { zoneId: 'Asia/Shanghai' })),
  node(
    Op.BEFORE_TODAY,
    'created',
    {
      time: '12:30:59',
      zoneId: 'Asia/Shanghai',
      datePattern: 'yyyy-MM-dd',
      timeUnit: TimeUnit.SECONDS,
    },
    {},
  ),
  node(Op.RECENT_DAYS, 'created', { days: 7, zoneId: 'Asia/Shanghai' }, {}),
  node(Op.EARLIER_DAYS, 'created', { days: 1, zoneId: 'Asia/Shanghai' }, {}),
];
