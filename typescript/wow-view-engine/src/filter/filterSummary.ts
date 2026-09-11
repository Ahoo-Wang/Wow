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
  FilterOperator,
  SearchMode,
  StringComparison,
  type FilterExpression,
} from '@ahoo-wang/fetcher-wow';
import { FILTER_OPERATORS } from './filterCore.js';
import { dateTimeValue, dateTimeToSeconds } from './filterDateTimeValue.js';
import type {
  FilterComponentConfig,
  FilterFieldDefinition,
} from './filterModel.js';
import {
  readFilterOptions,
  type FilterOptionItem,
} from './filterOptionSource.js';
import type { DeepReadonly } from '../lib/types.js';

type FilterSummary = { count: number; text: string };
interface FilterSummaryContext {
  node?: DeepReadonly<FilterComponentConfig>;
  timeZone?: string;
  showTime?: boolean;
  operands?: FilterSummary[];
  predicate?: FilterSummary;
}

/** Component context must come from the applied baseline, never pending edits. */
export function describeFilter(
  expression: DeepReadonly<FilterExpression>,
  fields: readonly FilterFieldDefinition[],
  context: FilterSummaryContext = {},
  root = true,
): FilterSummary {
  const node = context.node;
  const field = fields.find(
    field =>
      field.field === ('field' in expression ? expression.field : node?.field),
  );
  const dateOnly =
    !!node &&
    context.showTime === false &&
    (field?.type === 'date' || field?.type === 'datetime') &&
    ['value', 'values', 'between'].includes(
      FILTER_OPERATORS[node.operator].input,
    );
  // Whole-day queries may expand EQ/NE/IN into groups; keep the selected operation and dates.
  const display = dateOnly ? node.props : expression;
  const operator = FILTER_OPERATORS[dateOnly ? node.operator : expression.op];
  if (!dateOnly && 'operands' in expression) {
    const children =
      context.operands ??
      expression.operands.map(child =>
        describeFilter(
          child as DeepReadonly<FilterExpression>,
          fields,
          context,
          false,
        ),
      );
    return {
      count: children.reduce((sum, child) => sum + child.count, 0),
      text: `${operator.label}（${children.map(child => child.text).join('；')}）`,
    };
  }
  const label =
    dateOnly || 'field' in expression
      ? `${field?.label ?? (dateOnly ? node.field : 'field' in expression ? expression.field : '')} ${operator.label}`
      : operator.label;
  if (!dateOnly && 'predicate' in expression) {
    const child =
      context.predicate ??
      describeFilter(expression.predicate, field?.fields ?? [], context, false);
    return { count: child.count, text: `${label}（${child.text}）` };
  }
  let selectedOptions: FilterOptionItem[] = [];
  try {
    selectedOptions = readFilterOptions(node?.props?.selectedOptions ?? []);
  } catch {
    // Extensions own their property schemas; an unrelated property is not a label snapshot.
  }
  function literal(value: unknown): string {
    const option =
      selectedOptions.find(option => Object.is(option.value, value)) ??
      field?.options?.find(option => Object.is(option.value, value));
    if (option) return option.label;
    if (value === null) return '空值';
    if (value === '') return '空字符串';
    if (typeof value === 'boolean') return value ? '是' : '否';
    if (
      (dateOnly || typeof value === 'number') &&
      (field?.type === 'date' || field?.type === 'datetime')
    ) {
      const date = dateTimeValue(
        context.showTime === true ? dateTimeToSeconds(value) : value,
        context.timeZone,
      );
      if (date.date)
        return dateOnly
          ? date.date
          : [date.date, date.time].filter(Boolean).join(' ');
    }
    return typeof value === 'object' ? JSON.stringify(value) : String(value);
  }
  const values: string[] = [];
  if ('value' in display) values.push(literal(display.value));
  if ('values' in display && Array.isArray(display.values))
    values.push(`[${display.values.map(literal).join('、')}]`);
  if ('lowerBound' in display)
    values.push(
      `${literal(display.lowerBound)} 至 ${literal(display.upperBound)}`,
    );
  if ('query' in expression) {
    values.push(expression.query);
    if (expression.fields?.length)
      values.push(
        `范围：${expression.fields.map(path => fields.find(field => field.field === path)?.label ?? path).join('、')}`,
      );
    if (expression.mode)
      values.push(
        expression.mode === SearchMode.PHRASE ? '短语匹配' : '分词匹配',
      );
  }
  if ('state' in expression)
    values.push(
      {
        [DeletionState.ACTIVE]: '未删除',
        [DeletionState.DELETED]: '已删除',
        [DeletionState.ALL]: '全部',
      }[expression.state],
    );
  if ('time' in expression) values.push(expression.time);
  if ('days' in expression) values.push(`${expression.days} 天`);
  if ('zoneId' in expression && expression.zoneId)
    values.push(`时区：${expression.zoneId}`);
  if ('stringComparison' in expression && expression.stringComparison)
    values.push(
      expression.stringComparison === StringComparison.CASE_INSENSITIVE
        ? '不区分大小写'
        : '区分大小写',
    );
  return {
    count: root && expression.op === FilterOperator.MATCH_ALL ? 0 : 1,
    text: [label, ...values].join(' '),
  };
}
