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

import { expect, it } from 'vitest';
import {
  filter,
  FilterOperator,
  SearchMode,
  StringComparison,
  DeletionState,
} from '@ahoo-wang/fetcher-wow';
import { describeFilter } from '../src/filter/filterSummary.js';
import type { ViewFieldDefinition } from '../src/contracts/viewModel.js';

const fields: ViewFieldDefinition[] = [
  {
    field: 'amount',
    label: '金额',
    type: 'number',
    numberFormat: { style: 'currency', currency: 'CNY' },
  },
  { field: 'active', label: '启用', type: 'boolean' },
  { field: 'customer', label: '客户', type: 'string' },
  {
    field: 'status',
    label: '状态',
    type: 'string',
    options: [{ value: 'pending', label: '待处理' }],
  },
  {
    field: 'items',
    label: '明细',
    type: 'array',
    fields: [{ field: 'quantity', label: '数量', type: 'number' }],
  },
];

it('preserves boolean branches, exact numeric thresholds and falsey values in applied summaries', () => {
  const result = describeFilter(
    filter.and([
      filter.gte('amount', 1.00001),
      filter.or([filter.eq('active', false), filter.eq('customer', null)]),
      filter.nor([filter.eq('customer', '')]),
    ]),
    fields,
  );
  expect(result.count).toBe(4);
  expect(result.text).toBe(
    '满足全部条件（金额 大于等于 1.00001；满足任一条件（启用 等于 否；客户 等于 空值）；全部条件均不满足（客户 等于 空字符串））',
  );
  expect(describeFilter(filter.matchAll(), fields).count).toBe(0);
  expect(
    describeFilter(
      filter.or([filter.matchAll(), filter.eq('amount', 0)]),
      fields,
    ),
  ).toEqual({ count: 2, text: '满足任一条件（全部记录；金额 等于 0）' });
});

it('resolves enum labels and element-relative fields without losing the container meaning', () => {
  expect(
    describeFilter(
      filter.and([
        filter.isIn('status', ['pending']),
        filter.between('amount', 0, 1000),
        filter.elementMatch('items', filter.gte('quantity', 2)),
      ]),
      fields,
    ),
  ).toEqual({
    count: 3,
    text: '满足全部条件（状态 属于 [待处理]；金额 介于 0 至 1000；明细 同一元素满足（数量 大于等于 2））',
  });
});

it('uses applied option labels with static labels and raw values as fallbacks', () => {
  expect(
    describeFilter(
      filter.isIn('status', ['pending', 'ready', 'unknown']),
      fields,
      {
        node: {
          id: 'status',
          op: FilterOperator.IN,
          field: 'status',
          props: {
            values: ['pending', 'ready', 'unknown'],
            selectedOptions: [{ value: 'ready', label: '已就绪' }],
          },
        },
      },
    ).text,
  ).toBe('状态 属于 [待处理、已就绪、unknown]');
});

it('shows built-in applied datetimes in the view timezone at second precision', () => {
  expect(
    describeFilter(
      filter.between(
        'createdAt',
        Date.parse('2026-09-08T01:00:00.123Z'),
        Date.parse('2026-09-08T02:00:00Z'),
      ),
      [{ field: 'createdAt', label: '创建时间', type: 'datetime' }],
      { timeZone: 'Asia/Shanghai', showTime: true },
    ).text,
  ).toBe('创建时间 介于 2026-09-08 09:00:00 至 2026-09-08 10:00:00');
});

it('describes date-only component intent instead of its expanded query', () => {
  expect(
    describeFilter(
      filter.nor([
        filter.between(
          'createdAt',
          Date.parse('2026-09-07T16:00:00Z'),
          Date.parse('2026-09-08T15:59:59.999Z'),
        ),
      ]),
      [{ field: 'createdAt', label: '创建时间', type: 'datetime' }],
      {
        node: {
          id: 'date',
          operator: FilterOperator.NE,
          component: { name: 'builtin' },
          field: 'createdAt',
          props: { value: { date: '2026-09-08', time: '09:00:00.123' } },
        },
        timeZone: 'Asia/Shanghai',
        showTime: false,
      },
    ).text,
  ).toBe('创建时间 不等于 2026-09-08');
});

it('describes search scope and matching semantics in the applied summary', () => {
  for (const [mode, label] of [
    [SearchMode.PHRASE, '短语匹配'],
    [SearchMode.TERMS, '分词匹配'],
  ] as const) {
    expect(
      describeFilter(
        {
          op: FilterOperator.SEARCH,
          query: '采购订单',
          fields: ['customer', 'legacy'],
          mode,
        },
        fields,
      ).text,
    ).toBe(`全文搜索 采购订单 范围：客户、legacy ${label}`);
  }
  expect(
    describeFilter(
      { op: FilterOperator.DELETION, state: DeletionState.DELETED },
      fields,
    ).text,
  ).toContain('已删除');
});

it('shows case sensitivity and relative-time zones without changing the applied values', () => {
  for (const [comparison, label] of [
    [StringComparison.CASE_INSENSITIVE, '不区分大小写'],
    [StringComparison.CASE_SENSITIVE, '区分大小写'],
  ] as const) {
    expect(
      describeFilter(
        {
          op: FilterOperator.CONTAINS,
          field: 'customer',
          value: 'AbC',
          stringComparison: comparison,
        },
        fields,
      ).text,
    ).toBe(`客户 包含文本 AbC ${label}`);
  }
  const summary = describeFilter(
    {
      op: FilterOperator.RECENT_DAYS,
      field: 'created',
      days: 7,
      zoneId: 'Asia/Shanghai',
    },
    [{ field: 'created', label: '时间', type: 'datetime' }],
  );
  expect(summary.text).toContain('7 天');
  expect(summary.text).toContain('时区：Asia/Shanghai');
});
