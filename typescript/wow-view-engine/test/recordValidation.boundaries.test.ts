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

import { FilterOperator, SortDirection } from '@ahoo-wang/fetcher-wow';
import { expect, it, vi } from 'vitest';
import { validateViewDefinition } from '../src/contracts/validation/definitionValidation.js';
import { validateViewInstance } from '../src/contracts/validation/instanceValidation.js';
import type { ViewHost } from '../src/contracts/ViewHost.js';
import { definition, instance, setup } from './engine/fixtures.js';

it('accepts typed enum identities, nested fields and explicit operator editor bindings', () => {
  const schema = {
    ...definition,
    fields: [
      {
        ...definition.fields[1],
        operators: [FilterOperator.EQ, FilterOperator.IN],
        options: [
          { value: 1, label: 'Numeric one', group: 'Numbers', disabled: false },
          { value: '1', label: 'String one' },
          { value: false, label: 'False', disabled: true },
        ],
      },
      {
        field: 'items',
        label: 'Items',
        type: 'array',
        fields: definition.fields,
      },
    ],
    allowedOperators: [FilterOperator.EQ, FilterOperator.IN],
    filterEditors: {
      [FilterOperator.IN]: { name: 'multi-select', options: { compact: true } },
    },
  };
  expect(() => validateViewDefinition(schema)).not.toThrow();
  expect(() =>
    validateViewDefinition(JSON.parse(JSON.stringify(schema))),
  ).not.toThrow();
});

it.each([
  [{ type: 'decimal' }, '字段类型不支持'],
  [{ sortable: 'true' }, 'sortable'],
  [{ operators: 'EQ' }, '字段操作符'],
  [{ operators: ['UNKNOWN'] }, '字段操作符'],
  [{ options: {} }, '枚举选项必须是数组'],
  [{ options: [null] }, '枚举选项必须是对象'],
  [{ options: [{ value: 1, label: 'One', group: '' }] }, '枚举选项分组'],
  [{ options: [{ value: null, label: 'Null' }] }, '枚举选项值无效'],
  [{ options: [{ value: Infinity, label: 'Infinite' }] }, '枚举选项值无效'],
  [
    {
      options: [
        { value: 1, label: 'One' },
        { value: 1, label: 'Duplicate' },
      ],
    },
    '枚举选项值重复',
  ],
  [
    { options: [{ value: 1, label: 'One', disabled: 'false' }] },
    '枚举 disabled',
  ],
])('rejects malformed field metadata %j', (patch, message) => {
  expect(() =>
    validateViewDefinition({
      ...definition,
      fields: [{ ...definition.fields[1], ...patch }],
    }),
  ).toThrow(message);
});

it.each([
  [{ allowedOperators: 'EQ' }, '定义操作符'],
  [{ allowedOperators: ['UNKNOWN'] }, '定义操作符'],
  [{ filterEditors: [] }, '筛选扩展必须是对象'],
  [{ filterEditors: { UNKNOWN: { name: 'custom' } } }, '筛选扩展操作符'],
  [{ filterEditors: { EQ: { name: '' } } }, '扩展名称'],
])('rejects malformed definition operator bindings %j', (patch, message) => {
  expect(() => validateViewDefinition({ ...definition, ...patch })).toThrow(
    message,
  );
});

it.each([
  [{ scope: { type: 'public', source: 'unknown' } }, '实例范围无效'],
  [{ revision: 2 }, 'revision'],
])(
  'rejects malformed saved instance identity metadata %j',
  (patch, message) => {
    expect(() =>
      validateViewInstance({ ...instance(), ...patch }, definition),
    ).toThrow(message);
  },
);

it.each([
  [{ sort: 'state.amount' }, '排序必须'],
  [
    {
      sort: Array.from({ length: 33 }, () => ({
        field: 'state.amount',
        direction: SortDirection.ASC,
      })),
    },
    '最多 32',
  ],
  [{ sort: [{ field: 'state.amount', direction: 'UP' }] }, '排序方向'],
  [
    {
      sort: [SortDirection.ASC, SortDirection.DESC].map(direction => ({
        field: 'state.amount',
        direction,
      })),
    },
    '字段重复',
  ],
  [{ pagination: { mode: 'offset', size: 10 } }, '分页方式'],
  [{ pagination: { mode: 'paged', size: 0 } }, '每页数量'],
  [{ pagination: { mode: 'paged', size: 1.5 } }, '每页数量'],
  [{ presentation: { layout: 'grid', table: { columns: [] } } }, '展示布局'],
])(
  'rejects saved query configuration that cannot be executed %j',
  (patch, message) => {
    const saved = instance();
    expect(() =>
      validateViewInstance(
        { ...saved, config: { ...saved.config, ...patch } },
        definition,
      ),
    ).toThrow(message);
  },
);

it.each([
  [[], '至少配置一列'],
  [
    [{ id: 'amount', kind: 'field', field: 'state.amount', visible: 'false' }],
    'visible',
  ],
  [[{ id: 'amount', kind: 'field', field: 'state.missing' }], '未知字段'],
  [[{ id: 'amount', kind: 'chart', field: 'state.amount' }], '列类型'],
  [
    [{ id: 'amount', kind: 'field', field: 'state.amount', visible: false }],
    '至少显示一列',
  ],
])('rejects unusable table columns %j', (columns, message) => {
  const saved = instance();
  expect(() =>
    validateViewInstance(
      {
        ...saved,
        config: {
          ...saved.config,
          presentation: { layout: 'table', table: { columns } },
        },
      },
      definition,
    ),
  ).toThrow(message);
});

it('keeps malformed host lists out of sessions and recovers after the host fixes its response', async () => {
  const list = vi
    .fn()
    .mockResolvedValueOnce({ items: [instance()] })
    .mockResolvedValue({ instances: [instance()], defaultInstanceId: 'mine' });
  const { engine, paged } = setup({
    instances: undefined,
    host: { instance: { list } } as ViewHost,
  });
  try {
    await expect(engine.load()).rejects.toThrow('instances 数组');
    expect(engine.getSnapshot()).toMatchObject({
      status: 'error',
      instanceIds: [],
      sessions: {},
    });
    expect(paged).not.toHaveBeenCalled();
    await engine.load();
    expect(engine.getSnapshot()).toMatchObject({
      status: 'ready',
      selectedInstanceId: 'mine',
    });
    expect(paged).toHaveBeenCalledOnce();
  } finally {
    engine.dispose();
  }
});

it.each([
  { pagination: {} },
  { pagination: [] },
  { presentation: {} },
  { presentation: { layout: 'table' } },
  { presentation: { layout: 'table', table: { columns: [null] } } },
  { presentation: { layout: 'card', card: { fields: [] } } },
  {
    presentation: {
      layout: 'card',
      card: { title: { id: 'title', field: 'state.amount' }, fields: null },
    },
  },
  { sort: [null] },
  { sort: [{ field: 'state.amount' }] },
])(
  'rejects structurally unsafe record configuration before recovery publication: %j',
  patch => {
    const saved = instance();
    expect(() =>
      validateViewInstance(
        { ...saved, config: { ...saved.config, ...patch } },
        definition,
        undefined,
        false,
      ),
    ).toThrow();
  },
);

it('retains unknown field references as semantic recovery issues', () => {
  const saved = instance();
  saved.config.sort = [{ field: 'removed', direction: SortDirection.ASC }];
  saved.config.presentation = {
    layout: 'table',
    table: { columns: [{ id: 'removed', kind: 'field', field: 'removed' }] },
  };
  expect(() =>
    validateViewInstance(saved, definition, undefined, false),
  ).not.toThrow();
  expect(() => validateViewInstance(saved, definition)).toThrow(
    '字段不支持排序',
  );
});

it('does not publish or query a default record with missing layout discriminants', async () => {
  const saved = instance();
  const malformed = {
    ...saved,
    config: { ...saved.config, pagination: {}, presentation: {} },
  };
  const { engine, paged } = setup({
    instances: undefined,
    host: {
      instance: {
        list: async () => ({
          instances: [malformed],
          defaultInstanceId: saved.id,
        }),
      },
    } as never,
  });
  try {
    await expect(engine.load()).rejects.toThrow();
    expect(engine.getSnapshot().status).toBe('error');
    expect(engine.getSnapshot().sessions).toEqual({});
    expect(paged).not.toHaveBeenCalled();
  } finally {
    engine.dispose();
  }
});
