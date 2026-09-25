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
 * 框选与追问（D33 批 C）：一个月的每日发货，和一块接着「日期」与「发货仓」
 * 两个筛选的看板。
 *
 * 一个月的日柱每根都够宽，框三天拖得出来、读得出来；看板上一张按日的柱（框它
 * 设「日期」）、一张按仓库的柱（被「日期」筛）、一个按仓库分段的漏斗（点一段
 * 设「发货仓」）。数据与 `dailyShipments.ts` 同一份算法，所以同一天永远是同一
 * 个数。
 * ------------------------------------------------------------------------ */

import {
  emptyDashboardConfig,
  fitChartSlots,
  type DashboardDefinition,
  type DashboardViewConfig,
  type ViewInstance,
  type ViewSource,
} from '@ahoo-wang/wow-view-engine';
import {
  shipmentsConfig,
  shipmentsDefinition,
  shipmentsSource,
  shipmentsView,
} from './dailyShipments.js';
import { analysisConfig } from './fixtures.js';

/**
 * How many aggregations the month's source has answered: a play reads it
 * before a gesture and after, never as an absolute — 「返回不重跑」 is the
 * same number on both sides.
 */
export const brushAggregates = { current: 0 };

/** The month of shipments, counting every aggregation it answers. */
export function brushSource(): ViewSource {
  const source = shipmentsSource('month');
  return {
    ...source,
    aggregate: query => {
      brushAggregates.current += 1;
      return source.aggregate(query);
    },
  };
}

/** 每日发货金额，一个月，按仓库堆叠的柱。 */
export const dailyView: ViewInstance = shipmentsView(shipmentsConfig('month'));

const AMOUNT = {
  alias: 'amount',
  type: 'NUMERIC',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'amount' },
} satisfies ReturnType<typeof analysisConfig>['metrics'][number];

const WAREHOUSE = {
  type: 'TERMS' as const,
  field: 'warehouse',
  alias: 'warehouse',
};

/** 按仓库汇总：被看板的「日期」筛。 */
const byWarehouseView: ViewInstance = {
  id: 'shipments-by-warehouse',
  definitionId: shipmentsDefinition.id,
  title: '按仓库汇总',
  scope: 'shared',
  revision: '1',
  config: analysisConfig({
    layout: 'chart',
    groups: [WAREHOUSE],
    metrics: [AMOUNT],
    chart: fitChartSlots({ type: 'bar' }, [WAREHOUSE], [AMOUNT]),
  }),
};

/** 仓库漏斗：按仓库的值分段，一段可按（批 C）。 */
const funnelView: ViewInstance = {
  id: 'shipments-funnel',
  definitionId: shipmentsDefinition.id,
  title: '仓库漏斗',
  scope: 'shared',
  revision: '1',
  config: analysisConfig({
    layout: 'chart',
    groups: [WAREHOUSE],
    metrics: [AMOUNT],
    chart: {
      type: 'funnel',
      funnel: {
        stages: {
          from: 'group',
          category: 'warehouse',
          value: 'amount',
          order: ['CN-EAST', 'CN-SOUTH'],
        },
      },
    },
  }),
};

/** 发货看板的定义。 */
export const shipmentsBoardDefinition: DashboardDefinition = {
  id: 'shipments-board',
  title: '发货看板',
  kind: 'dashboard',
};

const DAY_BINDING = { globalField: 'day', panelField: 'createdAt' };
const WAREHOUSE_BINDING = { globalField: 'warehouse', panelField: 'warehouse' };

/**
 * 「日期」接三块面板，「发货仓」接按日的柱与漏斗；漏斗的点击设「发货仓」，
 * 按日的柱照旧弹追问菜单——框它才有「设为「日期」」。
 */
function boardConfig(): DashboardViewConfig {
  return {
    ...emptyDashboardConfig(),
    width: 'full',
    fields: [
      { name: 'day', label: '日期', kind: 'datetime' },
      {
        name: 'warehouse',
        label: '发货仓',
        kind: 'enum',
        options: [
          { value: 'CN-EAST', label: '华东仓' },
          { value: 'CN-SOUTH', label: '华南仓' },
        ],
      },
    ],
    panels: [
      {
        id: 'daily',
        kind: 'view',
        title: '每日发货金额',
        instanceId: dailyView.id,
        bindings: [DAY_BINDING, WAREHOUSE_BINDING],
        layout: { x: 0, y: 0, w: 16, h: 5 },
      },
      {
        id: 'by-warehouse',
        kind: 'view',
        title: '按仓库汇总',
        instanceId: byWarehouseView.id,
        bindings: [DAY_BINDING],
        layout: { x: 16, y: 0, w: 8, h: 5 },
      },
      {
        id: 'funnel',
        kind: 'view',
        title: '仓库漏斗',
        instanceId: funnelView.id,
        bindings: [DAY_BINDING, WAREHOUSE_BINDING],
        click: { kind: 'filter', filter: 'warehouse' },
        layout: { x: 0, y: 5, w: 12, h: 4 },
      },
    ],
  };
}

/** 发货看板。 */
export const shipmentsBoard: ViewInstance = {
  id: 'shipments-ops',
  definitionId: shipmentsBoardDefinition.id,
  title: '发货看板',
  scope: 'shared',
  revision: '1',
  config: boardConfig(),
};

/** 这组故事的视图：月柱、按仓库、漏斗与看板。 */
export const brushInstances: ViewInstance[] = [
  dailyView,
  byWarehouseView,
  funnelView,
  shipmentsBoard,
];
