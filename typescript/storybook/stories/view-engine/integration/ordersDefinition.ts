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

import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
import { defineView, text } from '@ahoo-wang/wow-view-engine';
// The service's query descriptor (`GET /order/snapshot/schema`), committed
// beside the definition: the model's facts — paths, kinds, values — are
// read from it when this module loads.
import snapshot from './ordersDescriptor.json';

// Step 1: declare what can be observed. A definition is code: it ships
// with the application, and every saved view is checked against it.
export const ORDERS = 'orders';

export const ordersDescriptor = snapshot as unknown as QueryModelDescriptor;

export const ordersDefinition = defineView(ordersDescriptor, {
  id: ORDERS,
  // The key the engine files this definition's source under (step 2).
  source: 'order',
  // Words are keys, said in the host's language (step 3's `messages`).
  title: text('orders.title'),
  // What a board's time filter reaches its panels through.
  timeField: 'firstEventTime',
  // Only what is listed appears, in this order; the descriptor says what
  // each one is, and what it sorts, filters and aggregates by.
  fields: {
    aggregateId: { label: text('orders.id'), cell: 'copyable' },
    'state.status': {
      label: text('orders.status'),
      cell: 'status',
      options: {
        PAID: { label: text('orders.paid'), tone: 'warning' },
        SHIPPED: { label: text('orders.shipped'), tone: 'success' },
        CANCELLED: { label: text('orders.cancelled'), tone: 'neutral' },
      },
    },
    'state.warehouse': text('orders.warehouse'),
    'state.amount': { label: text('orders.amount'), summary: ['SUM', 'AVG'] },
    firstEventTime: text('orders.placedAt'),
  },
  record: {
    layouts: ['table', 'card'],
    // The actions (step 4) read the status, which 「待发货」's table does not show.
    rowFields: ['state.status'],
  },
  // System views: deployed with the definition, read-only for everyone,
  // and the starting points a reader saves their own views from.
  views: [
    {
      id: 'to-ship',
      title: text('orders.toShip'),
      config: {
        kind: 'record',
        filter: {
          op: 'and',
          children: [
            { field: 'state.status', operator: 'IN', value: ['PAID'] },
          ],
        },
        filterMode: 'simple',
        refresh: { interval: null },
        sort: [{ field: 'firstEventTime', direction: 'ASC' }],
        pageSize: 20,
        summaries: [{ field: 'state.amount', fn: 'SUM' }],
        layout: 'table',
        table: {
          columns: [
            { field: 'aggregateId' },
            { field: 'firstEventTime' },
            { field: 'state.warehouse' },
            { field: 'state.amount' },
          ],
        },
        card: {
          title: 'aggregateId',
          fields: ['state.status', 'state.warehouse', 'state.amount'],
        },
      },
    },
    {
      id: 'all',
      title: text('orders.all'),
      config: {
        kind: 'record',
        filter: { op: 'and', children: [] },
        filterMode: 'simple',
        refresh: { interval: null },
        sort: [{ field: 'firstEventTime', direction: 'DESC' }],
        pageSize: 20,
        summaries: [{ field: 'state.amount', fn: 'SUM' }],
        layout: 'table',
        table: {
          columns: [
            { field: 'aggregateId' },
            { field: 'state.status' },
            { field: 'firstEventTime' },
            { field: 'state.warehouse' },
            { field: 'state.amount' },
          ],
        },
        card: {
          title: 'aggregateId',
          fields: ['state.status', 'state.warehouse', 'state.amount'],
        },
      },
    },
  ],
});

/**
 * The words of the keys above, and of the actions' (step 4). A host with
 * more than one language keeps a table per language; switching redraws
 * what is open, and nothing is rebuilt.
 */
export const ORDERS_WORDS = {
  'orders.title': '订单',
  'orders.id': '订单号',
  'orders.status': '状态',
  'orders.paid': '已付款',
  'orders.shipped': '已发货',
  'orders.cancelled': '已取消',
  'orders.warehouse': '发货仓',
  'orders.amount': '实付',
  'orders.placedAt': '下单时间',
  'orders.toShip': '待发货',
  'orders.all': '全部订单',
  'orders.ship': '发货',
  'orders.shipTitle': '发出 {count} 张订单？',
  'orders.notPaid': '只有已付款的订单能发货',
  'orders.cancel': '取消订单',
  'orders.cancelTitle': '取消 {count} 张订单？',
  'orders.cancelBody': '取消后买家会收到退款，这一步不能撤回。',
  'orders.cannotCancel': '已发货或已取消的订单不能再取消',
} as const;
