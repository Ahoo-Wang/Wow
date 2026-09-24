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
  defaultRuntimeEnvironment,
  type DashboardFilters,
  type ViewInstance,
} from '@ahoo-wang/wow-view-engine';
import {
  analysisConfig,
  dashboardConfig,
  recordConfig,
  savedDashboard,
} from './fixtures.js';

/**
 * The boards the embedded-dashboard scenes show (D22, the embedding half):
 * a customer's orders for a customer page, the outbound overview for a wall
 * screen, and a shared board one of whose panels is out.
 */

/** 晨光食品, the customer whose page the board sits on. */
export const CUSTOMER = { id: 'c-03', label: '晨光食品' } as const;

/**
 * A fixed morning, so 「本月」 on the customer page means September 2026
 * whenever the scene runs — the month the fixture's orders were placed in.
 */
export const EMBED_ENVIRONMENT = defaultRuntimeEnvironment({
  now: () => new Date(Date.parse('2026-09-18T10:00:00+08:00')),
  timeZone: 'Asia/Shanghai',
});

/** The views the customer board shows: every order, and by warehouse. */
export const customerViews: ViewInstance[] = [
  {
    id: 'customer-orders',
    definitionId: 'orders',
    title: '订单',
    scope: 'shared',
    revision: '1',
    config: recordConfig({ sort: [{ field: 'amount', direction: 'DESC' }] }),
  },
  {
    id: 'customer-by-warehouse',
    definitionId: 'orders',
    title: '按仓库金额',
    scope: 'shared',
    revision: '1',
    // A table, so a group is a row the keyboard reaches as well.
    config: analysisConfig({ layout: 'table' }),
  },
];

const byCustomer = { globalField: 'customer', panelField: 'customer' };
const byPlaced = { globalField: 'placed', panelField: 'createdAt' };

/**
 * One customer's orders, on any customer's page: a 客户 filter the page
 * locks to whoever the page is about, and a 下单时间 the reader moves,
 * starting at this month.
 */
export const customerBoard: ViewInstance = {
  id: 'customer-board',
  definitionId: 'overview',
  title: '客户订单',
  scope: 'shared',
  revision: '1',
  config: dashboardConfig({
    fields: [
      {
        name: 'customer',
        label: '客户',
        kind: 'reference',
        remote: 'customers',
      },
      {
        name: 'placed',
        label: '下单时间',
        kind: 'datetime',
        default: { type: 'preset', preset: 'thisMonth' },
      },
    ],
    panels: [
      {
        id: 'orders',
        kind: 'view',
        title: '这个客户的订单',
        instanceId: 'customer-orders',
        bindings: [byCustomer, byPlaced],
        layout: { x: 0, y: 0, w: 14, h: 4 },
      },
      {
        id: 'by-warehouse',
        kind: 'view',
        title: '按仓库金额',
        instanceId: 'customer-by-warehouse',
        bindings: [byCustomer, byPlaced],
        layout: { x: 14, y: 0, w: 10, h: 4 },
      },
    ],
  }),
};

/**
 * What the customer page holds: the customer it is about. The page's own —
 * from its route, never from the filters in its address.
 */
export const CUSTOMER_PAGE: DashboardFilters = {
  values: { customer: { items: [{ ...CUSTOMER }] } },
};

/**
 * The outbound overview a wall screen shows: the pending orders and the
 * amount by warehouse under one 仓库 filter, refreshing every minute.
 */
export const wallBoard: ViewInstance = {
  ...savedDashboard,
  id: 'wall-board',
  title: '出库概览',
  scope: 'shared',
  config: dashboardConfig({ refresh: { interval: 60 } }),
};

/**
 * A shared board one of whose panels points at a view that is gone (or out
 * of the reader's reach): that panel alone is out, the others run. The
 * embed used to take its finding for the whole board's (R3).
 */
export const boardWithAPanelOut: ViewInstance = {
  ...savedDashboard,
  id: 'overview-shared',
  scope: 'shared',
  config: dashboardConfig({
    panels: [
      ...dashboardConfig().panels,
      {
        id: 'mine',
        kind: 'view',
        title: '我盯的大额单',
        instanceId: 'orders-deleted',
        bindings: [],
        layout: { x: 8, y: 4, w: 16, h: 2 },
      },
    ],
  }),
};
