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
import { FilterOperator as Op } from '@ahoo-wang/fetcher-wow';
import type {
  FilterFieldDefinition,
  FilterOperatorDefinition,
} from './filterModel.js';

export const FILTER_OPERATORS: Readonly<Record<Op, FilterOperatorDefinition>> =
  {
    MATCH_ALL: { label: '全部记录', category: 'root', input: 'none' },
    MATCH_NONE: { label: '不匹配记录', category: 'root', input: 'none' },
    ID: { label: '记录标识', category: 'root', input: 'value' },
    IDS: { label: '记录标识集合', category: 'root', input: 'values' },
    AGGREGATE_ID: { label: '聚合标识', category: 'root', input: 'value' },
    AGGREGATE_IDS: { label: '聚合标识集合', category: 'root', input: 'values' },
    TENANT_ID: { label: '租户标识', category: 'root', input: 'value' },
    OWNER_ID: { label: '所有者标识', category: 'root', input: 'value' },
    SPACE_ID: { label: '空间标识', category: 'root', input: 'value' },
    AND: { label: '满足全部条件', category: 'logical', input: 'none' },
    OR: { label: '满足任一条件', category: 'logical', input: 'none' },
    NOR: { label: '全部条件均不满足', category: 'logical', input: 'none' },
    EQ: { label: '等于', category: 'field', input: 'value' },
    NE: { label: '不等于', category: 'field', input: 'value' },
    GT: { label: '大于', category: 'field', input: 'value' },
    GTE: { label: '大于等于', category: 'field', input: 'value' },
    LT: { label: '小于', category: 'field', input: 'value' },
    LTE: { label: '小于等于', category: 'field', input: 'value' },
    CONTAINS: { label: '包含文本', category: 'field', input: 'value' },
    STARTS_WITH: { label: '开头是', category: 'field', input: 'value' },
    ENDS_WITH: { label: '结尾是', category: 'field', input: 'value' },
    IN: { label: '属于', category: 'field', input: 'values' },
    NOT_IN: { label: '不属于', category: 'field', input: 'values' },
    BETWEEN: { label: '介于', category: 'field', input: 'between' },
    CONTAINS_ALL: { label: '包含全部', category: 'field', input: 'values' },
    IS_EMPTY: { label: '集合为空', category: 'field', input: 'none' },
    IS_EMPTY_STRING: { label: '文本为空', category: 'field', input: 'none' },
    IS_NOT_EMPTY_STRING: {
      label: '文本非空',
      category: 'field',
      input: 'none',
    },
    IS_NULL: { label: '为空值', category: 'field', input: 'none' },
    IS_NOT_NULL: { label: '非空值', category: 'field', input: 'none' },
    EXISTS: { label: '存在', category: 'field', input: 'none' },
    NOT_EXISTS: { label: '不存在', category: 'field', input: 'none' },
    DELETION: { label: '删除状态', category: 'root', input: 'deletion' },
    ELEMENT_MATCH: {
      label: '同一元素满足',
      category: 'element',
      input: 'none',
    },
    SEARCH: { label: '全文搜索', category: 'root', input: 'search' },
    TODAY: {
      label: '今天',
      category: 'field',
      input: 'none',
      relativeTime: true,
    },
    BEFORE_TODAY: {
      label: '今天指定时间之前',
      category: 'field',
      input: 'time',
      relativeTime: true,
    },
    TOMORROW: {
      label: '明天',
      category: 'field',
      input: 'none',
      relativeTime: true,
    },
    THIS_WEEK: {
      label: '本周',
      category: 'field',
      input: 'none',
      relativeTime: true,
    },
    NEXT_WEEK: {
      label: '下周',
      category: 'field',
      input: 'none',
      relativeTime: true,
    },
    LAST_WEEK: {
      label: '上周',
      category: 'field',
      input: 'none',
      relativeTime: true,
    },
    THIS_MONTH: {
      label: '本月',
      category: 'field',
      input: 'none',
      relativeTime: true,
    },
    LAST_MONTH: {
      label: '上月',
      category: 'field',
      input: 'none',
      relativeTime: true,
    },
    YESTERDAY: {
      label: '昨天',
      category: 'field',
      input: 'none',
      relativeTime: true,
    },
    NEXT_MONTH: {
      label: '下月',
      category: 'field',
      input: 'none',
      relativeTime: true,
    },
    LAST_YEAR: {
      label: '去年',
      category: 'field',
      input: 'none',
      relativeTime: true,
    },
    THIS_YEAR: {
      label: '今年',
      category: 'field',
      input: 'none',
      relativeTime: true,
    },
    NEXT_YEAR: {
      label: '明年',
      category: 'field',
      input: 'none',
      relativeTime: true,
    },
    RECENT_DAYS: {
      label: '最近天数',
      category: 'field',
      input: 'days',
      relativeTime: true,
    },
    EARLIER_DAYS: {
      label: '早于天数',
      category: 'field',
      input: 'days',
      relativeTime: true,
    },
  };

const common = [
  Op.EQ,
  Op.NE,
  Op.IN,
  Op.NOT_IN,
  Op.IS_NULL,
  Op.IS_NOT_NULL,
  Op.EXISTS,
  Op.NOT_EXISTS,
];
const comparison = [Op.GT, Op.GTE, Op.LT, Op.LTE, Op.BETWEEN];
export const stringOperators = [Op.CONTAINS, Op.STARTS_WITH, Op.ENDS_WITH];
const namedFilterOperators: Readonly<Record<string, readonly Op[]>> = {
  select: [Op.EQ, Op.NE],
  'remote-select': [Op.EQ, Op.NE],
  'multi-select': [Op.IN, Op.NOT_IN],
  'remote-multi-select': [Op.IN, Op.NOT_IN],
  'text-values': [Op.IN, Op.NOT_IN],
  'datetime-range': [Op.BETWEEN],
};
export function getNamedFilterOperators(
  name?: string,
): readonly Op[] | undefined {
  return name &&
    Object.prototype.hasOwnProperty.call(namedFilterOperators, name)
    ? namedFilterOperators[name]
    : undefined;
}
export function getFieldOperators(field: FilterFieldDefinition): readonly Op[] {
  let operators: Op[];
  switch (field.type) {
    case 'string':
      operators = [
        ...common,
        ...comparison,
        ...stringOperators,
        Op.IS_EMPTY_STRING,
        Op.IS_NOT_EMPTY_STRING,
      ];
      break;
    case 'number':
      operators = [...common, ...comparison];
      break;
    case 'boolean':
      operators = common;
      break;
    case 'date':
    case 'datetime':
      operators = [
        ...common,
        ...comparison,
        ...Object.values(Op).filter(op => FILTER_OPERATORS[op].relativeTime),
      ];
      break;
    case 'array':
      operators = [...common, Op.CONTAINS_ALL, Op.IS_EMPTY, Op.ELEMENT_MATCH];
      break;
    default:
      operators = Object.values(Op).filter(op =>
        ['field', 'element'].includes(FILTER_OPERATORS[op].category),
      );
  }
  const allowed = field.operators;
  return allowed ? allowed.filter(op => operators.includes(op)) : operators;
}

export function definition(op: Op): FilterOperatorDefinition {
  if (!Object.prototype.hasOwnProperty.call(FILTER_OPERATORS, op))
    throw new TypeError(`未知操作：${String(op)}`);
  return FILTER_OPERATORS[op];
}
