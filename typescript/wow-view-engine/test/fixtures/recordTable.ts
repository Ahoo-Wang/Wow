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

import { cleanup, screen } from '@testing-library/react';
import { vi } from 'vitest';
import { filter, FilterOperator } from '@ahoo-wang/fetcher-wow';
import { newFilterNode } from '../../src/filter/filterCore.js';
import { createFilterConfiguration } from '../../src/filter/filterConfiguration.js';
import type {
  RecordColumn,
  ViewDefinition,
  ViewInstance,
} from '../../src/record/recordModel.js';
import type { RecordTableProps } from '../../src/record/recordReactTypes.js';

export function cleanupTable() {
  cleanup();
  vi.unstubAllGlobals();
}

export function mockTableWidth() {
  let resize!: (width: number) => void;
  vi.stubGlobal(
    'ResizeObserver',
    class {
      constructor(private callback: ResizeObserverCallback) {}
      observe(target: Element) {
        if (!target.querySelector('table')) return;
        resize = width =>
          this.callback(
            [{ contentRect: { width } } as ResizeObserverEntry],
            this as unknown as ResizeObserver,
          );
      }
      disconnect() {}
    },
  );
  return (width: number) => resize(width);
}

export function mockColumnLayout() {
  // jsdom has no DragEvent constructor; MouseEvent preserves client coordinates.
  vi.stubGlobal('DragEvent', MouseEvent);
  const list = screen
    .getByRole('dialog', { name: '列设置' })
    .querySelector('ol')!;
  for (const item of Array.from(list.children)) {
    vi.spyOn(item, 'getBoundingClientRect').mockImplementation(() => {
      const index = Array.from(list.children).indexOf(item);
      return DOMRect.fromRect({
        x: 0,
        y: 100 + index * 56,
        width: 360,
        height: 44,
      });
    });
  }
  return list;
}

export const definition: ViewDefinition = {
  id: 'orders',
  title: '订单',
  sourceId: 'orders',
  rowKey: 'meta.id',
  fields: [
    { field: 'name', label: '名称', type: 'string', sortable: true },
    { field: 'amount', label: '金额', type: 'number', sortable: true },
    {
      field: 'status',
      label: '状态',
      options: [{ value: 1, label: '已付款' }],
    },
    { field: 'active', label: '启用', type: 'boolean' },
    { field: 'detail', label: '详情' },
  ],
};

export const columns: RecordColumn[] = [
  { id: 'name', kind: 'field', field: 'name', width: 180 },
  { id: 'amount', kind: 'field', field: 'amount', width: 180 },
];

export const instance: ViewInstance = {
  id: 'mine',
  definitionId: 'orders',
  title: '我的订单',
  kind: 'record',
  scope: { type: 'personal' },
  config: {
    filters: createFilterConfiguration(newFilterNode(FilterOperator.MATCH_ALL)),
    sort: [],
    pagination: { mode: 'paged', size: 20 },
    presentation: { layout: 'table', table: { columns } },
  },
};

export function props(
  overrides: Partial<RecordTableProps> = {},
): RecordTableProps {
  return {
    definition,
    instance,
    appliedFilter: filter.matchAll(),
    rows: [{ meta: { id: 0 }, name: 'Zulu', amount: 10 }],
    selectedRowKeys: [],
    onSelectionChange: vi.fn(),
    onColumnsChange: vi.fn(),
    onSortChange: vi.fn(),
    refresh: vi.fn(async () => {}),
    ...overrides,
  };
}
