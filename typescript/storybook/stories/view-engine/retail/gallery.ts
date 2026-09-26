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

/* --------------------------------------------------------------------------
 * 主题一览的三块（themes.md 4.5，T5；docs/scenarios.md 5.3）：华东（嘉兴）仓
 * 的单，画成三种视图，每套预设在亮、暗两种明暗下各画一遍。
 *
 * - **记录视图**：华东仓近 30 天的单，表格，单号钉在左边，状态与售后状态是
 *   带色的徽章，可以勾选（导出所选），一行选中、一个复选框获焦由故事做。
 * - **分析视图**：本月至今与上月同期的 GMV，分渠道的两组柱，带图例。
 * - **仪表盘**：筛选条上一枚有值的筛选（发货仓 = 华东），一张带走势与涨跌的
 *   GMV 指标卡，一张净销售额按渠道累加的瀑布图，末根是合计。
 *
 * 三块都只读华东仓的单，所以一览的订单数据源只装华东仓近 70 天的单
 * （`galleryOrderSource`，覆盖「上月同期」与「近 30 天」），答案与全量相同，
 * 每套预设一个故事也不必把两万张单筛十几遍。
 * ------------------------------------------------------------------------ */

import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  emptyDashboardConfig,
  type AnalysisViewConfig,
  type DashboardFilters,
  type FilterNode,
  type FilterTree,
  type RecordData,
  type ViewInstance,
  type ViewSource,
} from '@ahoo-wang/wow-view-engine';
import { RETAIL_BOARDS, RETAIL_BOARD_DEFINITIONS } from './boards.js';
import { RETAIL_NOW } from './generate.js';
import {
  RETAIL_SOURCES,
  retailData,
  retailEnvironment,
  retailNow,
  retailSource,
  type RetailSourceKey,
} from './source.js';
import { rowSource } from '../rowSource.js';
import {
  RETAIL_ORDERS,
  RETAIL_ORDER_ANALYSIS,
  retailOrdersDefinition,
} from './views.js';

const WAREHOUSE = 'state.warehouse';
const EAST = 'EAST';
const GMV = 'state.amounts.payableAmount';
const PAID = 'state.amounts.paidAmount';
const REFUNDED = 'state.amounts.refundedAmount';

const and = (...children: FilterNode[]): FilterTree => ({
  op: 'and',
  children,
});
const inEast: FilterNode = { field: WAREHOUSE, operator: 'IN', value: [EAST] };
const period = (preset: string): FilterNode => ({
  field: 'firstEventTime',
  operator: 'BETWEEN',
  value: { type: 'preset', preset },
});
const recentDays = (amount: number): FilterNode => ({
  field: 'firstEventTime',
  operator: 'BETWEEN',
  value: { type: 'relative', amount, unit: 'day' },
});

const warehouseOptions = () => {
  const options = retailOrdersDefinition.fields.find(
    field => field.name === WAREHOUSE,
  )?.options;
  if (!options)
    throw new Error(`The order definition declares no ${WAREHOUSE}.`);
  return options;
};

/** 记录视图：华东仓近 30 天的单。 */
export const GALLERY_ORDERS = 'retail-gallery-orders';

/** 分析视图：本月至今与上月同期的 GMV，分渠道。 */
export const GALLERY_CHANNELS = 'retail-gallery-channels';

/** 仪表盘：筛选条、指标卡、瀑布图。 */
export const GALLERY_BOARD = 'retail-gallery-board';

export const GALLERY_FILTERS: DashboardFilters = {
  values: { warehouse: [EAST] },
};

const galleryOrders: ViewInstance = {
  id: GALLERY_ORDERS,
  definitionId: RETAIL_ORDERS,
  title: '华东仓近 30 天的单',
  scope: 'shared',
  revision: '1',
  config: {
    kind: 'record',
    filter: and(inEast, recentDays(30)),
    filterMode: 'simple',
    refresh: { interval: null },
    sort: [{ field: 'firstEventTime', direction: 'DESC' }],
    pageSize: 5,
    layout: 'table',
    summaries: [],
    table: {
      columns: [
        { field: 'state.orderNo', pinned: true },
        { field: 'firstEventTime' },
        { field: 'state.buyer.nick' },
        { field: 'state.status' },
        { field: 'state.afterSaleStatus' },
        { field: 'state.channel' },
        { field: PAID },
      ],
    },
    card: {
      title: 'state.orderNo',
      fields: ['state.buyer.nick', 'state.status', PAID, 'firstEventTime'],
    },
  },
};

const sum = (
  alias: string,
  field: string,
  label: string,
  filter?: FilterTree,
) =>
  ({
    alias,
    type: 'NUMERIC',
    function: 'SUM',
    expression: { type: 'FIELD', field },
    label,
    ...(filter ? { filter } : {}),
  }) as const;

const byChannel = {
  type: 'TERMS',
  field: 'state.channel',
  alias: 'channel',
  label: '渠道',
} as const;

function analysis(
  config: Pick<AnalysisViewConfig, 'filter' | 'groups' | 'metrics' | 'chart'> &
    Partial<AnalysisViewConfig>,
): AnalysisViewConfig {
  return {
    kind: 'analysis',
    filterMode: 'simple',
    refresh: { interval: null },
    sort: [],
    limit: 20,
    layout: 'chart',
    table: { columns: [] },
    ...config,
  };
}

const galleryChannels: ViewInstance = {
  id: GALLERY_CHANNELS,
  definitionId: RETAIL_ORDER_ANALYSIS,
  title: '本月 GMV 较上月同期（华东仓，分渠道）',
  scope: 'shared',
  revision: '1',
  config: analysis({
    filter: and(inEast),
    groups: [byChannel],
    metrics: [
      sum('gmvLast', GMV, '上月同期', and(period('lastMonthToDate'))),
      sum('gmv', GMV, '本月至今', and(period('monthToDate'))),
    ],
    sort: [{ alias: 'gmv', direction: 'DESC' }],
    chart: {
      type: 'bar',
      cartesian: {
        x: 'channel',
        series: [{ metric: 'gmvLast' }, { metric: 'gmv' }],
      },
      legend: 'top',
    },
  }),
};

const bindWarehouse = [{ globalField: 'warehouse', panelField: WAREHOUSE }];

const galleryBoard: ViewInstance = {
  id: GALLERY_BOARD,
  definitionId: RETAIL_BOARDS,
  title: '华东仓看板',
  scope: 'shared',
  revision: '1',
  config: {
    ...emptyDashboardConfig(),
    width: 'full',
    fields: [
      {
        name: 'warehouse',
        label: '发货仓',
        kind: 'enum',
        options: warehouseOptions(),
        multiple: true,
      },
    ],
    panels: [
      {
        id: 'gmv',
        kind: 'view',
        title: 'GMV',
        owned: {
          definitionId: RETAIL_ORDER_ANALYSIS,
          // 近 30 天按日，读作最后一个过完的日与前一日之比：卡上有走势，
          // 变化的徽章是涨跌色。
          config: analysis({
            filter: and(recentDays(30)),
            groups: [
              {
                type: 'DATE_HISTOGRAM',
                field: 'firstEventTime',
                alias: 'day',
                unit: 'DAY',
                label: '日期',
              },
            ],
            metrics: [sum('gmv', GMV, 'GMV')],
            sort: [{ alias: 'day', direction: 'ASC' }],
            limit: 62,
            chart: {
              type: 'metric',
              metric: { metric: 'gmv', trend: { x: 'day' } },
            },
          }),
        },
        bindings: bindWarehouse,
        layout: { x: 0, y: 0, w: 8, h: 4 },
      },
      {
        id: 'net-sales',
        kind: 'view',
        title: '本月净销售额的渠道构成',
        owned: {
          definitionId: RETAIL_ORDER_ANALYSIS,
          config: analysis({
            filter: and(period('monthToDate')),
            groups: [byChannel],
            metrics: [
              {
                alias: 'net',
                type: 'NUMERIC',
                function: 'SUM',
                label: '净销售额',
                expression: {
                  type: 'BINARY',
                  operator: 'SUBTRACT',
                  left: { type: 'FIELD', field: PAID },
                  right: { type: 'FIELD', field: REFUNDED },
                },
              },
            ],
            sort: [{ alias: 'net', direction: 'DESC' }],
            chart: {
              type: 'waterfall',
              waterfall: { x: 'channel', value: 'net', total: true },
            },
          }),
        },
        bindings: bindWarehouse,
        layout: { x: 8, y: 0, w: 16, h: 4 },
      },
    ],
  },
};

const DAY_MS = 86_400_000;

let eastOrders: ViewSource | undefined;

/** 华东仓近 70 天的单：三块面读得到的全部，答案与全量数据源一样。 */
function galleryOrderSource(): ViewSource {
  if (!eastOrders) {
    const since = RETAIL_NOW - 70 * DAY_MS;
    const rows = retailData().orders.filter(
      ({ state, firstEventTime }) =>
        state.warehouse === EAST && firstEventTime >= since,
    );
    eastOrders = rowSource(rows as readonly object[] as RecordData[], {
      timeField: 'firstEventTime',
      now: retailNow,
    });
  }
  return eastOrders;
}

/**
 * 一览的引擎：零售的定义，订单读 `galleryOrderSource`，存储里只有这三块。
 * 一个故事是一套预设的亮与暗两条带，同时开六块面、十来个查询，在引擎为一屏
 * 排队的上限之内。
 */
export function createGalleryEngine(): ViewEngine {
  return new ViewEngine({
    definitions: RETAIL_BOARD_DEFINITIONS,
    limits: { ...DEFAULT_RUNTIME_LIMITS, maxPageSize: 100 },
    store: new MemoryViewStore({
      instances: [galleryOrders, galleryChannels, galleryBoard],
    }),
    resolveSource: key =>
      key === RETAIL_SOURCES.orders
        ? galleryOrderSource()
        : retailSource(key as RetailSourceKey),
    environment: retailEnvironment(),
  });
}
