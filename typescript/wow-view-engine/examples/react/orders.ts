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

import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import {
  createFilterConfiguration,
  newFilterNode,
  type ViewDefinition,
  type ViewInstanceList,
} from '@ahoo-wang/fetcher-view-engine';

export type Order = {
  id: string;
  customer: string;
  amount: number;
  status: 'pending' | 'processed';
};
export const initialOrders: readonly Order[] = [
  { id: 'DEMO-1', customer: '青山商店', amount: 120, status: 'pending' },
  { id: 'DEMO-2', customer: '星河书屋', amount: 85.5, status: 'pending' },
  { id: 'DEMO-3', customer: '远方旅行', amount: 240, status: 'processed' },
];
export const orderDefinition: ViewDefinition = {
  id: 'demo-orders',
  sourceId: 'demo-orders',
  title: '订单工作台',
  allowedLayouts: ['table', 'card'],
  rowKey: 'id',
  defaultPresentation: {
    card: {
      title: { id: 'customer', field: 'customer' },
      fields: [
        { id: 'id', field: 'id' },
        { id: 'amount', field: 'amount' },
        { id: 'status', field: 'status' },
      ],
      actions: {},
    },
  },
  allowedOperators: [
    FilterOperator.MATCH_ALL,
    FilterOperator.AND,
    FilterOperator.EQ,
  ],
  fields: [
    { field: 'id', label: '订单编号', type: 'string', operators: [] },
    { field: 'customer', label: '客户', type: 'string', operators: [] },
    {
      field: 'amount',
      label: '金额',
      type: 'number',
      numberFormat: { style: 'currency', currency: 'CNY' },
      operators: [],
      summaryFunctions: [],
      cellRenderer: { name: 'money' },
    },
    {
      field: 'status',
      label: '状态',
      type: 'string',
      operators: [FilterOperator.EQ],
      editor: { name: 'order-status' },
      cellRenderer: {
        name: 'status',
        options: {
          tones: [
            { value: 'pending', tone: 'warning' },
            { value: 'processed', tone: 'success' },
          ],
        },
      },
      options: [
        { value: 'pending', label: '待处理' },
        { value: 'processed', label: '已处理' },
      ],
    },
  ],
  recordActions: {
    global: { name: 'create-order' },
    toolbar: { name: 'process-orders' },
    row: { name: 'process-order' },
  },
};
const config = {
  filters: createFilterConfiguration({
    id: 'status-selector',
    operator: FilterOperator.EQ,
    field: 'status',
    component: { name: 'order-status' },
    props: { selectedId: 'pending', displayLabel: '待办队列（人工命名）' },
  }),
  sort: [],
  pagination: { mode: 'paged' as const, size: 10 },
  presentation: {
    layout: 'table' as const,
    table: {
      columns: [
        { id: 'id', kind: 'field' as const, field: 'id' },
        { id: 'customer', kind: 'field' as const, field: 'customer' },
        { id: 'amount', kind: 'field' as const, field: 'amount' },
        { id: 'status', kind: 'field' as const, field: 'status' },
        { id: 'actions', kind: 'actions' as const, title: '处理' },
      ],
    },
  },
};
export const orderViews: ViewInstanceList = {
  defaultInstanceId: 'pending',
  instances: [
    {
      id: 'pending',
      definitionId: orderDefinition.id,
      kind: 'record',
      title: '待处理订单',
      scope: { type: 'personal' },
      config,
    },
    {
      id: 'all',
      definitionId: orderDefinition.id,
      kind: 'record',
      title: '全部订单',
      scope: { type: 'public', source: 'system' },
      config: {
        ...config,
        filters: createFilterConfiguration(
          newFilterNode(FilterOperator.MATCH_ALL),
        ),
      },
    },
  ],
};
