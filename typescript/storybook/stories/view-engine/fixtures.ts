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
import {
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/fetcher-wow';
import { rowSource } from './rowSource.js';

// Wow's names for what the analysis side may group and compute by.
const { TERMS } = AggregationGroupType;
const { SUM, AVG } = AggregationFunction;

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
        { value: 'CN-SOUTH', label: '华南' },
        { value: 'CN-WEST', label: '西南' },
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
      { field: 'warehouse', groups: [TERMS], functions: [] },
      { field: 'status', groups: [TERMS], functions: [] },
      { field: 'amount', groups: [], functions: [SUM, AVG] },
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
  {
    id: 'SO-1004',
    warehouse: 'CN-SOUTH',
    status: 'SHIPPED',
    amount: 3120,
    createdAt: '2026-09-16T05:30:00.000Z',
  },
  {
    id: 'SO-1005',
    warehouse: 'CN-SOUTH',
    status: 'PENDING',
    amount: 1760,
    createdAt: '2026-09-17T02:20:00.000Z',
  },
  {
    id: 'SO-1006',
    warehouse: 'CN-WEST',
    status: 'PENDING',
    amount: 980,
    createdAt: '2026-09-17T08:45:00.000Z',
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
          { value: 'CN-SOUTH', label: '华南' },
          { value: 'CN-WEST', label: '西南' },
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
  // Appended, never inserted: stories address the two above by index. It is
  // here so a sidebar has both groups, and both kinds inside one of them.
  {
    id: 'orders-mine',
    definitionId: 'orders',
    title: '我盯的大额单',
    scope: 'personal',
    revision: '1',
    config: recordConfig({
      filter: {
        op: 'and',
        children: [{ field: 'amount', operator: 'GT', value: 5000 }],
      },
      sort: [{ field: 'createdAt', direction: 'DESC' }],
    }),
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

/**
 * What a story wants the backend to do while it is on screen.
 *
 * `no-aggregate` is the half-failure: pages come back, aggregations do not.
 * A record view then keeps its rows and loses the scope of its summary row,
 * which is the one state where a number on screen would otherwise go on
 * meaning something other than what it says.
 */
export type SourceBehaviour =
  'data' | 'empty' | 'slow' | 'failing' | 'no-aggregate';

/**
 * The rows above behind a `ViewSource`, or a backend that refuses to answer.
 * It is the one knob the state stories turn: every state below the workbench
 * follows from what the backend does, and with data it answers the query the
 * engine sent — filtered, sorted, paged and aggregated — so what a story
 * shows is what those conditions select.
 */
export function storySource(behaviour: SourceBehaviour = 'data'): ViewSource {
  const source = rowSource(behaviour === 'empty' ? [] : ORDERS);
  const answer = async <T>(query: () => Promise<T>): Promise<T> => {
    if (behaviour === 'failing')
      throw new ViewStoreError('UNAVAILABLE', '仓储服务暂时不可用');
    // Long enough to look at, short enough that nobody waits for it.
    if (behaviour === 'slow') await delay(1_500);
    return query();
  };

  const refuseAggregate = async (): Promise<never> => {
    throw new ViewStoreError('UNAVAILABLE', '汇总服务暂时不可用');
  };

  return {
    paged: query => answer(() => source.paged(query)),
    cursor: query => answer(() => source.cursor(query)),
    aggregate: query =>
      behaviour === 'no-aggregate'
        ? refuseAggregate()
        : answer(() => source.aggregate(query)),
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
