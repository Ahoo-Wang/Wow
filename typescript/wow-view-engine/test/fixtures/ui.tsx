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

/**
 * What the UI suites share: the view they open, the one whose stored tree
 * the simple editor cannot draw, and an engine wired to a source they can
 * answer with.
 */

import { MemoryViewStore, ViewEngine } from '../../src/index.js';
import type { ViewInstance, ViewSource } from '../../src/index.js';
import type {
  RecordTableController,
  RefreshController,
} from '../../src/react/index.js';
import { ordersDefinition, recordConfig, testSource } from '../fixtures.js';

export const mine: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'Mine',
  scope: 'personal',
  revision: '1',
  config: recordConfig(),
};

/**
 * A simple-mode config holding a tree only the advanced editor can show. It
 * opens, runs and saves; the kernel warns about it, and nothing more.
 */
export const mixed: ViewInstance = {
  ...mine,
  config: recordConfig({
    filterMode: 'simple',
    filter: {
      op: 'or',
      children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
    },
  }),
};

/**
 * A settled record table on the first of three pages, for the suites that
 * open one component on its own rather than a whole workbench. Every state
 * the bar and the table have is this one with an override or two.
 */
export function recordTableController(
  overrides: Partial<RecordTableController> = {},
): RecordTableController {
  return {
    columns: [
      {
        field: 'amount',
        label: 'Amount',
        kind: 'number',
        cell: 'number',
        sortable: false,
      },
    ],
    card: { title: 'amount', fields: [] },
    rows: [
      { key: 'o-1', data: { amount: 1 } },
      { key: 'o-2', data: { amount: 2 } },
    ],
    paging: { mode: 'paged', index: 1, total: 42 },
    summaries: null,
    status: 'success',
    error: null,
    loading: false,
    hasResult: true,
    sort: [],
    sortOf: () => null,
    toggleSort: () => {},
    setSort: () => {},
    maxSortFields: 8,
    layout: 'table',
    layouts: ['table', 'card'],
    setLayout: () => {},
    columnFields: ['amount'],
    setColumns: () => {},
    setColumnOrder: () => {},
    pinnedOf: () => null,
    setPinned: () => {},
    setColumnWidth: () => {},
    summaryOf: () => null,
    setSummary: () => {},
    pageSize: 20,
    pageSizes: [10, 20, 50, 100],
    setPageSize: () => {},
    selection: [],
    selectedRows: [],
    isSelected: () => false,
    toggle: () => {},
    toggleAll: () => {},
    clearSelection: () => {},
    goTo: () => {},
    hasNext: true,
    next: () => {},
    previous: () => {},
    refresh: () => {},
    ...overrides,
  };
}

/**
 * A view that refreshes only when asked, with the default ladder on offer.
 * The suites that assert the interval override `interval` and `setInterval`.
 */
export function refreshController(
  overrides: Partial<RefreshController> = {},
): RefreshController {
  return {
    interval: null,
    chosen: null,
    unsound: false,
    intervals: [30, 60, 300],
    setInterval: () => {},
    now: () => {},
    loading: false,
    // Nothing armed, so nothing counts down; the suites that assert the
    // countdown hand over a due time and a clock to read it against.
    dueAt: null,
    remaining: () => null,
    ...overrides,
  };
}

export function setup(source: ViewSource = testSource()) {
  const store = new MemoryViewStore({ instances: [mine] });
  const engine = new ViewEngine({
    definitions: [ordersDefinition()],
    store,
    resolveSource: () => source,
  });
  return { engine, store, source };
}
