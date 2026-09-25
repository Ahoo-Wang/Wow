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
 * 主题一览的那一块（docs/scenarios.md 5.3「数据换成零售的日报」）：运营日报
 * 的一个节选，外加同一批单的卡片视图。
 *
 * 一览把每套预设在每种明暗下都画一遍，一页上十几块，所以这里只取日报里能
 * 让主题露出来的几样：筛选条上一枚有值的筛选（发货仓 = 华东）、一张记录表格
 * 面板（「付款超过 48 小时仍未发货」，A7 困在嘉兴仓的那十来张单）、一张分析
 * 图表面板（近 30 天的渠道分布），和一块卡片视图（待发货的单，卡片头上有
 * 「导出」）。日报本身在首页与「业务场景/运营日报」。
 * ------------------------------------------------------------------------ */

import {
  emptyDashboardConfig,
  type DashboardFilters,
  type ViewEngine,
  type ViewInstance,
} from '@ahoo-wang/wow-view-engine';
import {
  OVERDUE_VIEW,
  RETAIL_BOARDS,
  RETAIL_BOARD_DEFINITIONS,
} from './boards.js';
import { createRetailEngine } from './source.js';
import {
  RETAIL_ORDERS,
  RETAIL_ORDER_ANALYSIS,
  retailOrdersDefinition,
} from './views.js';

const WAREHOUSE = 'state.warehouse';

const warehouseOptions = () => {
  const options = retailOrdersDefinition.fields.find(
    field => field.name === WAREHOUSE,
  )?.options;
  if (!options)
    throw new Error(`The order definition declares no ${WAREHOUSE}.`);
  return options;
};

/** 一览的那块板：日报的节选，筛选条上有一枚「发货仓」。 */
export const GALLERY_BOARD = 'retail-gallery-board';

/** 同一批单的卡片视图。 */
export const GALLERY_CARDS = 'retail-gallery-cards';

/** 一览打开时筛选条上的值：发货仓 = 华东（嘉兴）。 */
export const GALLERY_FILTERS: DashboardFilters = {
  values: { warehouse: ['EAST'] },
};

const bindWarehouse = [{ globalField: 'warehouse', panelField: WAREHOUSE }];

const galleryBoard: ViewInstance = {
  id: GALLERY_BOARD,
  definitionId: RETAIL_BOARDS,
  title: '运营日报（节选）',
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
        id: 'overdue',
        kind: 'view',
        title: '付款超过 48 小时仍未发货',
        instanceId: OVERDUE_VIEW,
        bindings: bindWarehouse,
        layout: { x: 0, y: 0, w: 14, h: 4 },
      },
      {
        id: 'by-channel',
        kind: 'view',
        title: '渠道分布（近 30 天）',
        owned: {
          definitionId: RETAIL_ORDER_ANALYSIS,
          config: {
            kind: 'analysis',
            filter: {
              op: 'and',
              children: [
                {
                  field: 'firstEventTime',
                  operator: 'BETWEEN',
                  value: { type: 'relative', amount: 30, unit: 'day' },
                },
              ],
            },
            filterMode: 'simple',
            refresh: { interval: null },
            layout: 'chart',
            groups: [
              {
                type: 'TERMS',
                field: 'state.channel',
                alias: 'channel',
                label: '渠道',
              },
            ],
            metrics: [
              {
                alias: 'gmv',
                type: 'NUMERIC',
                function: 'SUM',
                label: 'GMV',
                expression: {
                  type: 'FIELD',
                  field: 'state.amounts.payableAmount',
                },
              },
            ],
            sort: [{ alias: 'gmv', direction: 'DESC' }],
            limit: 10,
            table: { columns: [] },
            chart: {
              type: 'bar',
              cartesian: {
                x: 'channel',
                series: [{ metric: 'gmv' }],
                orientation: 'horizontal',
              },
              legend: 'none',
            },
          },
        },
        bindings: bindWarehouse,
        layout: { x: 14, y: 0, w: 10, h: 4 },
      },
    ],
  },
};

const galleryCards: ViewInstance = {
  id: GALLERY_CARDS,
  definitionId: RETAIL_ORDERS,
  title: '待发货订单（卡片）',
  scope: 'shared',
  revision: '1',
  config: {
    kind: 'record',
    filter: {
      op: 'and',
      children: [
        { field: 'state.status', operator: 'IN', value: ['PAID'] },
        { field: WAREHOUSE, operator: 'IN', value: ['EAST'] },
      ],
    },
    filterMode: 'simple',
    refresh: { interval: null },
    sort: [{ field: 'state.timing.paidAt', direction: 'ASC' }],
    pageSize: 4,
    layout: 'card',
    table: { columns: [{ field: 'state.orderNo' }] },
    card: {
      title: 'state.orderNo',
      fields: [
        'state.buyer.nick',
        'state.status',
        'state.items',
        'state.amounts.paidAmount',
        'state.timing.paidAt',
      ],
    },
  },
};

/**
 * 一览的引擎：零售的定义、共享的数据源，存储里只有这块板与卡片视图。
 * `maxQueuedQueries` 放宽到一页上所有的查询：一条带（板上的表格与它的
 * 汇总、图表，卡片与它的计数）至多六个，一页 `bands` 条。
 */
export function createGalleryEngine(bands: number): ViewEngine {
  return createRetailEngine(
    RETAIL_BOARD_DEFINITIONS,
    [galleryBoard, galleryCards],
    { maxQueuedQueries: Math.max(32, bands * 6) },
  );
}
