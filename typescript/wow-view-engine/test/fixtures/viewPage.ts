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
import { vi } from 'vitest';
import { newFilterNode } from '../../src/filter/filterCore.js';
import { createFilterConfiguration } from '../../src/filter/filterConfiguration.js';
import type {
  ViewDefinition,
  ViewInstance,
} from '../../src/record/recordModel.js';
import type { ViewHost } from '../../src/record/ViewHost.js';

export const definition: ViewDefinition = {
  id: 'orders',
  sourceId: 'orders',
  title: '订单管理',
  allowedLayouts: ['table', 'card'],
  rowKey: 'id',
  fields: [{ field: 'amount', label: '金额', type: 'number', sortable: true }],
};
export const instance: ViewInstance = {
  id: 'mine',
  definitionId: 'orders',
  title: '我的订单',
  kind: 'record',
  scope: { type: 'personal' },
  config: {
    filters: createFilterConfiguration({
      ...newFilterNode(FilterOperator.GTE, 'amount'),
      props: { value: 10 },
    }),
    sort: [],
    pagination: { mode: 'paged', size: 10 },
    presentation: {
      layout: 'table',
      table: { columns: [{ id: 'amount', kind: 'field', field: 'amount' }] },
    },
  },
};
export function setup() {
  const paged = vi
    .fn()
    .mockResolvedValue({ list: [{ id: 0, amount: 42 }], total: 1 });
  const other = {
    ...structuredClone(instance),
    id: 'system',
    title: '所有订单',
    scope: { type: 'public', source: 'system' } as const,
  };
  const host: ViewHost = {
    preference: {},
    resolveSource: () => ({
      paged,
      cursor: vi.fn().mockResolvedValue({ list: [], nextCursor: null }),
    }),
    definition: { load: vi.fn().mockResolvedValue(definition) },
    instance: {
      list: vi.fn().mockResolvedValue({
        instances: [instance, other],
        defaultInstanceId: instance.id,
      }),
      save: vi.fn(async submitted => ({
        ...submitted,
        revision: 'next',
      })),
      create: vi.fn(async submitted => ({
        ...submitted,
        id: 'copy',
        revision: '1',
      })),
    },
    permission: {
      getInstance: () => ({
        save: true,
        saveAsPersonal: true,
        saveAsShared: false,
      }),
    },
  };
  return { host, paged };
}
