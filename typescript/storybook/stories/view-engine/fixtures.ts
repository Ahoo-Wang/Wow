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
  MemoryViewStore,
  ViewEngine,
  ViewStoreError,
  emptyDashboardConfig,
  type AnalysisViewConfig,
  type DashboardViewConfig,
  type DataViewDefinition,
  type DashboardDefinition,
  type RecordData,
  type RecordViewConfig,
  type ViewInstance,
  type ViewSource,
} from '@ahoo-wang/fetcher-view-engine';

/**
 * One warehouse dataset, shared by every View Engine story.
 *
 * The data is shared because it is immutable; the store and the engine are
 * not, so each story builds its own — a scenario must never inherit another's
 * saved views.
 */
export const ordersDefinition: DataViewDefinition = {
  id: 'orders',
  title: '订单',
  kind: 'data',
  source: 'orders',
  fields: [
    { name: 'id', label: '订单号', kind: 'string' },
    {
      name: 'warehouse',
      label: '仓库',
      kind: 'enum',
      options: [
        { value: 'CN-EAST', label: '华东' },
        { value: 'CN-NORTH', label: '华北' },
      ],
    },
    {
      name: 'status',
      label: '状态',
      kind: 'enum',
      options: [
        { value: 'PENDING', label: '待出库' },
        { value: 'SHIPPED', label: '已发运' },
      ],
    },
    {
      name: 'amount',
      label: '金额',
      kind: 'number',
      sortable: true,
      summary: ['SUM', 'AVG'],
      numberFormat: { style: 'currency', currency: 'CNY' },
    },
    { name: 'createdAt', label: '创建时间', kind: 'datetime', sortable: true },
  ],
  record: { rowKey: 'id', paging: 'paged', layouts: ['table', 'card'] },
  analysis: {
    count: true,
    fields: [
      { field: 'warehouse', groups: ['TERMS'], functions: [] },
      { field: 'status', groups: ['TERMS'], functions: [] },
      { field: 'amount', groups: [], functions: ['SUM', 'AVG'] },
    ],
  },
  views: [{ id: 'all', title: '全部订单', config: recordConfig() }],
};

/** Dashboards own no data; the definition is only their catalogue entry. */
export const overviewDefinition: DashboardDefinition = {
  id: 'overview',
  title: '概览',
  kind: 'dashboard',
};

export const ORDERS: RecordData[] = [
  {
    id: 'SO-1001',
    warehouse: 'CN-EAST',
    status: 'PENDING',
    amount: 1280,
    createdAt: '2026-09-15T02:10:00.000Z',
  },
  {
    id: 'SO-1002',
    warehouse: 'CN-EAST',
    status: 'SHIPPED',
    amount: 640,
    createdAt: '2026-09-15T06:40:00.000Z',
  },
  {
    id: 'SO-1003',
    warehouse: 'CN-NORTH',
    status: 'PENDING',
    amount: 2450,
    createdAt: '2026-09-16T01:05:00.000Z',
  },
];

export function recordConfig(
  overrides: Partial<RecordViewConfig> = {},
): RecordViewConfig {
  return {
    kind: 'record',
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    sort: [],
    pageSize: 20,
    layout: 'table',
    summaries: [{ field: 'amount', fn: 'SUM' }],
    table: {
      columns: [
        { field: 'id' },
        { field: 'warehouse' },
        { field: 'status' },
        { field: 'amount' },
      ],
    },
    card: { title: 'id', fields: ['warehouse', 'status', 'amount'] },
    ...overrides,
  };
}

export function analysisConfig(
  overrides: Partial<AnalysisViewConfig> = {},
): AnalysisViewConfig {
  return {
    kind: 'analysis',
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    groups: [{ alias: 'warehouse', field: 'warehouse', type: 'TERMS' }],
    metrics: [
      { alias: 'orders', type: 'COUNT' },
      {
        alias: 'amount',
        type: 'NUMERIC',
        function: 'SUM',
        expression: { type: 'FIELD', field: 'amount' },
      },
    ],
    sort: [],
    limit: 100,
    layout: 'chart',
    table: { columns: [] },
    chart: {
      type: 'bar',
      cartesian: { x: 'warehouse', series: [{ metric: 'amount' }] },
    },
    ...overrides,
  };
}

/** A dashboard over the two saved views below, filtered by one global field. */
export function dashboardConfig(
  overrides: Partial<DashboardViewConfig> = {},
): DashboardViewConfig {
  return {
    ...emptyDashboardConfig(),
    fields: [
      {
        name: 'region',
        label: '仓库',
        kind: 'enum',
        options: [
          { value: 'CN-EAST', label: '华东' },
          { value: 'CN-NORTH', label: '华北' },
        ],
      },
    ],
    panels: [
      {
        id: 'pending',
        kind: 'view',
        title: '待出库明细',
        instanceId: 'orders-pending',
        bindings: [{ globalField: 'region', panelField: 'warehouse' }],
        layout: { x: 0, y: 0, w: 7, h: 4 },
      },
      {
        id: 'by-warehouse',
        kind: 'view',
        title: '按仓库汇总',
        instanceId: 'orders-analysis',
        bindings: [{ globalField: 'region', panelField: 'warehouse' }],
        layout: { x: 7, y: 0, w: 5, h: 4 },
      },
      {
        id: 'runbook',
        kind: 'links',
        title: '值班手册',
        items: [
          {
            label: '出库异常处理',
            href: '/runbook/outbound',
            description: '先看这里',
          },
          { label: '联系仓储值班', href: 'mailto:ops@example.com' },
        ],
        layout: { x: 0, y: 4, w: 4, h: 2 },
      },
    ],
    ...overrides,
  };
}

export const savedViews: ViewInstance[] = [
  {
    id: 'orders-pending',
    definitionId: 'orders',
    title: '待出库订单',
    scope: 'shared',
    revision: '1',
    config: recordConfig({
      filter: {
        op: 'and',
        // An enum's operators take a list, which is what keeps a selection
        // when the operator flips between IN and NOT_IN.
        children: [{ field: 'status', operator: 'IN', value: ['PENDING'] }],
      },
      sort: [{ field: 'amount', direction: 'DESC' }],
    }),
  },
  {
    id: 'orders-analysis',
    definitionId: 'orders',
    title: '仓库金额分布',
    scope: 'shared',
    revision: '1',
    config: analysisConfig(),
  },
];

/** A dashboard with nothing on it, which is a valid starting point. */
export function emptyDashboard(): DashboardViewConfig {
  return emptyDashboardConfig();
}

export const savedDashboard: ViewInstance = {
  id: 'overview-ops',
  definitionId: 'overview',
  title: '出库概览',
  scope: 'personal',
  revision: '1',
  config: dashboardConfig(),
};

/** What a story wants the backend to do while it is on screen. */
export type SourceBehaviour = 'data' | 'empty' | 'slow' | 'failing';

/**
 * A `ViewSource` that answers from the rows above, or refuses to. It is the
 * one knob the state stories turn: every state below the workbench follows
 * from what the backend does.
 */
export function storySource(behaviour: SourceBehaviour = 'data'): ViewSource {
  const rows = behaviour === 'empty' ? [] : ORDERS;
  const answer = async <T>(value: T): Promise<T> => {
    if (behaviour === 'failing')
      throw new ViewStoreError('UNAVAILABLE', '仓储服务暂时不可用');
    // Long enough to look at, short enough that nobody waits for it.
    if (behaviour === 'slow') await delay(1_500);
    return value;
  };

  return {
    paged: () => answer({ total: rows.length, list: rows }),
    cursor: () => answer({ nextCursor: null, list: rows }),
    // An ungrouped query is the totals query the analysis kernel sends
    // separately, and it answers for the whole set rather than for a row.
    aggregate: query =>
      answer(
        rows.length === 0
          ? []
          : (query.groups ?? []).length === 0
            ? [{ orders: 3, amount: 4370 }]
            : [
                { warehouse: 'CN-EAST', orders: 2, amount: 1920 },
                { warehouse: 'CN-NORTH', orders: 1, amount: 2450 },
              ],
      ),
  };
}

function delay(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

export interface StoryEngineOptions {
  behaviour?: SourceBehaviour;
  instances?: ViewInstance[];
  definitions?: (DataViewDefinition | DashboardDefinition)[];
}

/**
 * A fresh engine with a fresh store. Stories call it once per mount, because
 * saving, renaming and deleting are real writes and one scenario's leftovers
 * would be another's starting point.
 */
export function createStoryEngine(
  options: StoryEngineOptions = {},
): ViewEngine {
  return new ViewEngine({
    definitions: options.definitions ?? [ordersDefinition, overviewDefinition],
    store: new MemoryViewStore({ instances: options.instances ?? savedViews }),
    resolveSource: () => storySource(options.behaviour),
  });
}
