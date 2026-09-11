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
  FilterOperator,
  SortDirection,
  type MaterializedSnapshot,
} from '@ahoo-wang/fetcher-wow';
import {
  createFilterConfiguration,
  newFilterNode,
  type FilterConfiguration,
  type RecordData,
  type RecordViewDefinition,
  type ViewInstance,
  type ViewInstanceList,
} from '@ahoo-wang/fetcher-view-engine';

export const statuses = [
  { value: 'pending', label: '待处理' },
  { value: 'processing', label: '处理中' },
  { value: 'completed', label: '已完成' },
  { value: 'cancelled', label: '已取消' },
] as const;

export const currentUserId = 'storybook-user';
export const users = [
  { value: 'sales-1', label: '林晨' },
  { value: 'sales-2', label: '顾嘉' },
  { value: 'sales-3', label: '陈宁' },
  { value: currentUserId, label: '周宁' },
] as const;

export interface OrderItem {
  id: string;
  productId: string;
  productName: string;
  /** CNY yuan, matching Wow's numeric BigDecimal JSON representation. */
  price: number;
  quantity: number;
  totalPrice: number;
}

export interface OrderState {
  id: string;
  customer: string;
  totalAmount: number;
  paidAmount: number;
  items: OrderItem[];
  status: (typeof statuses)[number]['value'];
  /** Latest payment time in epoch milliseconds; null before the first payment. */
  paidAt: number | null;
  owner: string;
  region: string;
}

export type OrderSnapshot = MaterializedSnapshot<OrderState> & RecordData;

const equalityOperators = [FilterOperator.EQ, FilterOperator.NE];

const numberOperators = [
  ...equalityOperators,
  FilterOperator.GT,
  FilterOperator.GTE,
  FilterOperator.LT,
  FilterOperator.LTE,
];

export const definition: RecordViewDefinition = {
  id: 'order-management',
  title: '订单管理',
  sourceId: 'orders',
  timeZone: 'Asia/Shanghai',
  allowedOperators: [
    FilterOperator.MATCH_ALL,
    FilterOperator.AND,
    FilterOperator.OR,
    FilterOperator.IN,
    FilterOperator.NOT_IN,
    FilterOperator.BETWEEN,
    FilterOperator.ELEMENT_MATCH,
    FilterOperator.CONTAINS,
    ...numberOperators,
  ],
  fields: [
    {
      field: 'aggregateId',
      label: '订单编号',
      type: 'string',
      sortable: true,
      operators: [FilterOperator.IN, FilterOperator.NOT_IN],
      editor: { name: 'text-values' },
      cellRenderer: { name: 'text', options: { ellipsis: true } },
    },
    {
      field: 'state.customer',
      group: '客户信息',
      label: '客户',
      type: 'string',
      sortable: true,
      operators: equalityOperators,
      editor: {
        name: 'remote-select',
        options: { source: 'customers', pageSize: 3 },
      },
      cellRenderer: { name: 'text', options: { ellipsis: true } },
    },
    {
      field: 'state.totalAmount',
      group: '订单信息',
      label: '订单金额',
      type: 'number',
      sortable: true,
      operators: numberOperators,
      numberFormat: { style: 'currency', currency: 'CNY' },
      cellRenderer: { name: 'number' },
    },
    {
      field: 'state.paidAmount',
      group: '订单信息',
      label: '实付金额',
      type: 'number',
      sortable: true,
      operators: numberOperators,
      numberFormat: { style: 'currency', currency: 'CNY' },
      cellRenderer: { name: 'number' },
    },
    {
      field: 'state.items',
      group: '订单信息',
      label: '商品明细',
      type: 'array',
      operators: [FilterOperator.ELEMENT_MATCH],
      fields: [
        {
          field: 'productId',
          label: '商品编码',
          group: '商品信息',
          type: 'string',
          operators: [FilterOperator.IN, FilterOperator.NOT_IN],
          editor: { name: 'text-values' },
        },
        {
          field: 'productName',
          label: '商品名称',
          group: '商品信息',
          type: 'string',
          operators: [FilterOperator.CONTAINS, ...equalityOperators],
        },
        ...[
          { field: 'price', label: '单价' },
          { field: 'quantity', label: '数量' },
          { field: 'totalPrice', label: '小计' },
        ].map(field => ({
          ...field,
          group: '价格与数量',
          type: 'number' as const,
          operators: [...numberOperators, FilterOperator.BETWEEN],
        })),
      ],
      cellRenderer: { name: 'order-items' },
    },
    {
      field: 'state.status',
      group: '订单信息',
      label: '订单状态',
      type: 'string',
      options: statuses,
      operators: [FilterOperator.IN, FilterOperator.NOT_IN],
      editor: { name: 'multi-select' },
      cellRenderer: {
        name: 'status',
        options: {
          tones: [
            { value: 'pending', tone: 'warning' },
            { value: 'processing', tone: 'info' },
            { value: 'completed', tone: 'success' },
          ],
        },
      },
    },
    ...[
      { field: 'firstOperator', label: '创建人' },
      { field: 'operator', label: '最后操作人' },
    ].map(field => ({
      ...field,
      group: '操作信息',
      type: 'string' as const,
      options: users,
      operators: [FilterOperator.IN, FilterOperator.NOT_IN],
      editor: { name: 'multi-select' },
      cellRenderer: { name: 'text', options: { ellipsis: true } },
    })),
    ...[
      { field: 'firstEventTime', label: '下单时间' },
      { field: 'state.paidAt', label: '支付时间' },
    ].map(field => ({
      ...field,
      group: '时间',
      type: 'datetime' as const,
      sortable: true,
      operators: [FilterOperator.BETWEEN],
      editor: { name: 'datetime-range' },
      cellRenderer: {
        name: 'date-time',
        options: { dateStyle: 'short', timeStyle: 'short' },
      },
    })),
  ],
  record: {
    allowedLayouts: ['table', 'card'],
    rowKey: 'aggregateId',
    recordActions: {
      global: { name: 'order-actions' },
      toolbar: { name: 'order-table-actions' },
      row: { name: 'order-row-actions' },
    },
  },
};

const customers = [
  '青岚科技',
  '晨星零售',
  '云杉制造',
  '海川物流',
  '远山商贸',
  '山海设计',
];

function item(
  productId: string,
  productName: string,
  price: number,
  quantity = 1,
): OrderItem {
  return {
    id: productId,
    productId,
    productName,
    price,
    quantity,
    totalPrice: (Math.round(price * 100) * quantity) / 100,
  };
}

const orderItems = [
  [item('KB-01', '机械键盘', 340, 2)],
  [item('MON-01', '办公显示器', 1180), item('HDMI-01', 'HDMI 线', 50, 2)],
  [item('PRT-01', '激光打印机', 2199), item('TONER-01', '硒鼓', 200)],
  [item('MOUSE-01', '无线鼠标', 90, 6)],
  [item('DESK-01', '办公桌', 900, 4)],
  [item('CHAIR-01', '人体工学椅', 899)],
  [item('KB-02', '无线键盘', 280, 6)],
  [item('LAP-01', '办公笔记本', 4799), item('BAG-01', '电脑包', 200)],
  [item('SWITCH-01', '千兆交换机', 1180)],
  [item('HEADSET-01', '会议耳机', 260, 3)],
  [item('PRT-02', '多功能打印机', 2600), item('PAPER-01', '复印纸', 50, 4)],
  [item('SSD-01', '移动固态硬盘', 1399)],
  [item('CAM-01', '会议摄像头', 480, 2)],
  [item('PC-01', '迷你主机', 2800, 2)],
  [item('AP-01', '无线接入点', 700, 3)],
  [item('STAND-01', '显示器支架', 120, 4)],
  [
    item('PROJECTOR-01', '会议投影仪', 3000),
    item('SCREEN-01', '投影幕布', 200),
  ],
  [item('NAS-01', '网络存储', 1880)],
];

export function createOrderSnapshot(
  state: OrderState,
  ownerId: string,
  firstEventTime: number,
  firstOperator = ownerId,
): OrderSnapshot {
  const version = state.paidAt === null ? 1 : 2;
  const eventTime = state.paidAt ?? firstEventTime;
  return {
    contextName: 'commerce',
    aggregateName: 'order',
    aggregateId: state.id,
    tenantId: 'tenant-demo',
    ownerId,
    spaceId: '(0)',
    version,
    eventId: `event-${state.id}-${version}`,
    firstOperator,
    operator: firstOperator,
    firstEventTime,
    eventTime,
    snapshotTime: eventTime,
    tags: { region: [state.region] },
    deleted: false,
    state,
  };
}

export const orders: OrderSnapshot[] = orderItems.map((items, index) => {
  // Sum integer cents so line quantities and the order amount reconcile exactly.
  const totalAmount =
    items.reduce((sum, item) => sum + Math.round(item.totalPrice * 100), 0) /
    100;
  const status = statuses[index % statuses.length].value;
  const firstEventTime = Date.UTC(
    2026,
    8,
    6,
    1 + Math.floor(index / 6),
    (index % 6) * 10,
  );
  return createOrderSnapshot(
    {
      id: `ORD-202609-${1001 + index}`,
      customer: customers[index % customers.length],
      totalAmount,
      paidAmount:
        status === 'completed'
          ? totalAmount
          : status === 'processing'
            ? totalAmount / 2
            : 0,
      items,
      status,
      paidAt:
        status === 'completed'
          ? firstEventTime + 86_400_000
          : status === 'processing'
            ? firstEventTime + 1_800_000
            : null,
      owner: users[index % 3].label,
      region: ['上海', '杭州', '深圳'][index % 3],
    },
    users[index % 3].value,
    firstEventTime,
  );
});

export function makeInstances(
  mode: 'paged' | 'cursor',
  summaries = false,
  pageSize = 5,
  initialFilter: FilterConfiguration = createFilterConfiguration({
    ...newFilterNode(FilterOperator.GTE, 'state.totalAmount'),
    props: { value: 0 },
  }),
): ViewInstanceList {
  const personal: ViewInstance = {
    id: 'my-orders',
    definitionId: definition.id,
    title: '我的订单',
    kind: 'record',
    scope: { type: 'personal' },
    revision: '1',
    config: {
      filters: initialFilter,
      sort: [{ field: 'aggregateId', direction: SortDirection.ASC }],
      pagination: { mode, size: pageSize },
      presentation: {
        layout: 'table',
        table: {
          columns: [
            {
              id: 'aggregateId',
              kind: 'field',
              field: 'aggregateId',
              width: 210,
            },
            { id: 'customer', kind: 'field', field: 'state.customer' },
            {
              id: 'totalAmount',
              kind: 'field',
              field: 'state.totalAmount',
              width: 150,
              ...(summaries ? { summary: ['SUM'] as const } : {}),
            },
            { id: 'status', kind: 'field', field: 'state.status' },
            {
              id: 'paidAmount',
              kind: 'field',
              field: 'state.paidAmount',
              width: 150,
              ...(summaries ? { summary: ['SUM'] as const } : {}),
            },
            { id: 'items', kind: 'field', field: 'state.items', width: 240 },
            {
              id: 'firstOperator',
              kind: 'field',
              field: 'firstOperator',
              width: 140,
            },
            { id: 'operator', kind: 'field', field: 'operator', width: 140 },
            {
              id: 'firstEventTime',
              kind: 'field',
              field: 'firstEventTime',
              width: 190,
            },
            {
              id: 'paidAt',
              kind: 'field',
              field: 'state.paidAt',
              width: 190,
            },
            { id: 'actions', kind: 'actions', title: '操作', width: 110 },
          ],
        },
      },
    },
  };
  const system: ViewInstance = {
    ...structuredClone(personal),
    id: 'all-orders',
    title: '全部订单',
    scope: { type: 'public', source: 'system' },
    config: {
      ...structuredClone(personal.config),
      filters: createFilterConfiguration(
        newFilterNode(FilterOperator.MATCH_ALL),
      ),
    },
  };
  const shared: ViewInstance = {
    ...structuredClone(personal),
    id: 'priority-orders',
    title: '团队重点订单',
    scope: { type: 'public', source: 'shared' },
    config: {
      ...structuredClone(personal.config),
      filters: createFilterConfiguration(
        {
          ...newFilterNode(FilterOperator.AND),
          operands: [
            {
              ...newFilterNode(FilterOperator.IN, 'state.status', {
                name: 'multi-select',
              }),
              props: { values: ['pending', 'processing'] },
            },
            {
              ...newFilterNode(FilterOperator.GTE, 'state.totalAmount'),
              props: { value: 1000 },
            },
          ],
        },
        'simple',
      ),
    },
  };
  return {
    instances: [personal, system, shared],
    defaultInstanceId: personal.id,
  };
}

export const pause = () =>
  new Promise<void>(resolve => setTimeout(resolve, 120));
