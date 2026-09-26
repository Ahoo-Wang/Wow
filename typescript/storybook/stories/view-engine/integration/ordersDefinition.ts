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

import type { DataViewDefinition } from '@ahoo-wang/wow-view-engine';

// Step 1: declare what can be observed. A definition is code: it ships
// with the application, and every saved view is checked against it.
export const ORDERS = 'orders';

export const ordersDefinition: DataViewDefinition = {
  id: ORDERS,
  title: '订单',
  kind: 'data',
  // The key `resolveSource` is asked for (step 2).
  source: 'order',
  fields: [
    { name: 'aggregateId', label: '订单号', kind: 'string', sortable: true },
    {
      name: 'state.status',
      label: '状态',
      kind: 'enum',
      options: [
        { value: 'PAID', label: '已付款' },
        { value: 'SHIPPED', label: '已发货' },
        { value: 'CANCELLED', label: '已取消' },
      ],
    },
    { name: 'state.warehouse', label: '发货仓', kind: 'string' },
    {
      name: 'state.amount',
      label: '实付',
      kind: 'number',
      summary: ['SUM', 'AVG'],
    },
    {
      name: 'firstEventTime',
      label: '下单时间',
      kind: 'datetime',
      sortable: true,
    },
  ],
  // The row key must be sortable: every record query ends its sort on it,
  // so rows that tie on the chosen sort never repeat or go missing across
  // pages.
  record: { rowKey: 'aggregateId', paging: 'paged', layouts: ['table'] },
  // A system view: deployed with the definition, read-only for everyone,
  // and the starting point a user saves their own views from.
  views: [
    {
      id: 'to-ship',
      title: '待发货',
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
  ],
};
