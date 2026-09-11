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

import { FilterOperator as Op, SortDirection } from '@ahoo-wang/fetcher-wow';
import {
  createFilterConfiguration,
  newFilterNode,
  type RecordViewDefinition,
  type ViewFieldDefinition,
  type ViewInstanceList,
  type RecordViewInstance,
} from '@ahoo-wang/fetcher-view-engine';
import { lifecycleLabels, type Stage } from './model.js';
export const stageLabels: Record<Stage, string> = {
  all: '全部订单',
  review: '接单与审核',
  release: '收款与放行',
  delivery: '备货与交付',
  settlement: '对账与结算',
  aftersales: '售后与关闭',
};
const numeric = [Op.EQ, Op.GT, Op.GTE, Op.LT, Op.LTE, Op.BETWEEN];
const moneyFields = [
  ['totalAmount', '订单金额'],
  ['receivableAmount', '有效应收'],
  ['netReceived', '净收款'],
  ['amountDue', '待收款'],
  ['refundDue', '待退款'],
  ['netInvoiced', '净开票'],
  ['invoiceDue', '待开票'],
  ['creditDue', '待冲减'],
];
const textField = (field: string, label: string): ViewFieldDefinition => ({
  field: `state.${field}`,
  label,
  type: 'string',
  operators: [Op.EQ, Op.IN, Op.CONTAINS],
  sortable: true,
});
export const orderDefinition: RecordViewDefinition = {
  id: 'sales-orders',
  title: '销售订单',
  sourceId: 'sales-orders',
  timeZone: 'Asia/Shanghai',
  allowedOperators: [
    Op.MATCH_ALL,
    Op.AND,
    Op.OR,
    Op.ELEMENT_MATCH,
    Op.EQ,
    Op.NE,
    Op.IN,
    Op.NOT_IN,
    Op.CONTAINS,
    ...numeric,
  ],
  fields: [
    {
      field: 'aggregateId',
      label: '订单编号',
      type: 'string',
      operators: [Op.EQ, Op.CONTAINS],
      sortable: true,
    },
    {
      ...textField('customerId', '客户'),
      editor: {
        name: 'remote-select',
        options: { source: 'customers', pageSize: 5 },
      },
      cellRenderer: { name: 'customer' },
    },
    {
      ...textField('owner', '负责人'),
      options: ['林晨', '顾嘉', '陈宁'].map(value => ({ value, label: value })),
      editor: { name: 'multi-select' },
    },
    textField('region', '区域'),
    ...[
      ['creditStatus', '信用情况'],
      ['releaseStatus', '放行条件'],
      ['closureStatus', '关闭进度'],
      ['settlementStatus', '结算核对'],
    ].map(([field, label]) => ({
      ...textField(field, label),
      cellRenderer: { name: 'status' },
    })),
    {
      ...textField('deliveryRisk', '交期风险'),
      operators: [Op.EQ],
      editor: { name: 'delivery-risk' },
      sortable: false,
    },
    {
      ...textField('lifecycle', '订单状态'),
      options: Object.entries(lifecycleLabels).map(([value, label]) => ({
        value,
        label,
      })),
      editor: { name: 'multi-select' },
      cellRenderer: { name: 'status' },
    },
    {
      ...textField('terms', '结算方式'),
      options: [
        { value: 'prepaid', label: '全额预付' },
        { value: 'credit', label: '月结账期' },
      ],
      cellRenderer: { name: 'text' },
    },
    ...moneyFields.map(([field, label]): ViewFieldDefinition => ({
      field: `state.${field}`,
      label,
      group: '金额与结算',
      type: 'number',
      operators: numeric,
      sortable: true,
      numberFormat: { style: 'currency', currency: 'CNY' },
      cellRenderer: { name: 'number' },
    })),
    ...[
      'paymentStatus',
      'fulfillmentStatus',
      'invoiceStatus',
      'aftersaleStatus',
    ].map((field, index) => ({
      ...textField(
        field,
        ['收款状态', '履约状态', '开票状态', '售后状态'][index],
      ),
      cellRenderer: { name: 'status' },
    })),
    ...[
      ['dueAt', '承诺交期'],
      ['paymentDueAt', '应收到期日'],
    ].map(([field, label]): ViewFieldDefinition => ({
      field: `state.${field}`,
      label,
      group: '日期',
      type: 'datetime',
      operators: [Op.BETWEEN, Op.LT, Op.GTE],
      sortable: true,
      cellRenderer: { name: 'date-time', options: { dateStyle: 'short' } },
    })),
    ...[
      ['released', '已放行'],
      ['deliveryOpen', '交付待办'],
      ['settlementOpen', '结算待办'],
      ['aftersalesOpen', '售后待办'],
    ].map(([field, label]): ViewFieldDefinition => ({
      field: `state.${field}`,
      label,
      type: 'boolean',
      operators: [Op.EQ],
    })),
    {
      field: 'state.remainingToShip',
      label: '待发数量',
      type: 'number',
      operators: numeric,
      sortable: true,
      cellRenderer: { name: 'delivery-progress' },
    },
    {
      field: 'state.items',
      label: '商品明细',
      type: 'array',
      operators: [Op.ELEMENT_MATCH],
      cellRenderer: { name: 'order-items' },
      fields: [
        {
          field: 'productName',
          label: '商品名称',
          type: 'string',
          operators: [Op.EQ, Op.CONTAINS],
        },
        {
          field: 'quantity',
          label: '订购数量',
          type: 'number',
          operators: numeric,
        },
      ],
    },
  ],
  record: {
    rowKey: 'aggregateId',
    allowedLayouts: ['table', 'card'],
    defaultPresentation: {
      card: {
        title: { id: 'id', field: 'aggregateId' },
        fields: [
          { id: 'customer', field: 'state.customerId' },
          { id: 'due', field: 'state.dueAt' },
          { id: 'progress', field: 'state.remainingToShip' },
          { id: 'amount', field: 'state.totalAmount' },
        ],
        actions: {},
      },
    },
    recordActions: {
      global: { name: 'order-create' },
      toolbar: { name: 'order-batch' },
      row: { name: 'order-detail' },
    },
  },
};
export function createOrderViews(
  stage: Stage = 'all',
): ViewInstanceList & { instances: RecordViewInstance[] } {
  return {
    defaultInstanceId: `orders-${stage}`,
    instances: (Object.keys(stageLabels) as Stage[]).map(key => {
      const root =
        key === 'review'
          ? {
              ...newFilterNode(Op.IN, 'state.lifecycle'),
              props: { values: ['draft', 'submitted', 'rejected'] },
            }
          : key === 'release'
            ? {
                ...newFilterNode(Op.AND),
                operands: [
                  {
                    ...newFilterNode(Op.EQ, 'state.lifecycle'),
                    props: { value: 'confirmed' },
                  },
                  {
                    ...newFilterNode(Op.EQ, 'state.released'),
                    props: { value: false },
                  },
                ],
              }
            : key === 'all'
              ? newFilterNode(Op.MATCH_ALL)
              : {
                  ...newFilterNode(
                    Op.EQ,
                    `state.${key === 'delivery' ? 'deliveryOpen' : key === 'settlement' ? 'settlementOpen' : 'aftersalesOpen'}`,
                  ),
                  props: { value: true },
                };
      const columnsByStage: Record<Stage, string[]> = {
        all: [
          'customerId',
          'lifecycle',
          'totalAmount',
          'paymentStatus',
          'fulfillmentStatus',
          'dueAt',
        ],
        review: [
          'customerId',
          'lifecycle',
          'totalAmount',
          'dueAt',
          'terms',
          'owner',
        ],
        release: [
          'customerId',
          'terms',
          'amountDue',
          'releaseStatus',
          'paymentDueAt',
          'netReceived',
          'creditStatus',
        ],
        delivery: [
          'customerId',
          'dueAt',
          'fulfillmentStatus',
          'remainingToShip',
          'items',
        ],
        settlement: [
          'customerId',
          'amountDue',
          'refundDue',
          'creditDue',
          'settlementStatus',
          'netReceived',
          'netInvoiced',
          'totalAmount',
        ],
        aftersales: [
          'customerId',
          'refundDue',
          'creditDue',
          'closureStatus',
          'aftersaleStatus',
          'settlementStatus',
        ],
      };
      const fields = columnsByStage[key];
      return {
        id: `orders-${key}`,
        definitionId: orderDefinition.id,
        title: stageLabels[key],
        kind: 'record',
        revision: 'initial',
        scope: { type: 'public', source: 'system' },
        config: {
          filters: createFilterConfiguration(
            key === 'delivery'
              ? {
                  ...newFilterNode(Op.AND),
                  operands: [
                    root,
                    {
                      ...newFilterNode(Op.EQ, 'state.deliveryRisk', {
                        name: 'delivery-risk',
                      }),
                      props: {},
                    },
                  ],
                }
              : root,
          ),
          sort: [
            {
              field: 'aggregateId',
              direction: key === 'all' ? SortDirection.DESC : SortDirection.ASC,
            },
          ],
          pagination: { mode: 'paged', size: 5 },
          presentation: {
            layout: 'table',
            table: {
              columns: [
                { id: 'id', kind: 'field', field: 'aggregateId', width: 190 },
                ...fields.map(field => ({
                  id: field,
                  kind: 'field' as const,
                  field: `state.${field}`,
                  width:
                    field === 'items'
                      ? 200
                      : field === 'customerId'
                        ? 140
                        : field === 'dueAt' || field === 'paymentDueAt'
                          ? 180
                          : field === 'terms'
                            ? 105
                            : 135,
                  ...(moneyFields.some(([f]) => f === field)
                    ? { summary: ['SUM' as const] }
                    : {}),
                })),
                { id: 'actions', kind: 'actions', width: 110 },
              ],
            },
          },
        },
      };
    }),
  };
}

/** Dedicated persistence/transport story: includes opaque editor state in a writable view. */
export function createProtocolViews(): ViewInstanceList & {
  instances: RecordViewInstance[];
} {
  const views = createOrderViews('all');
  const personal = structuredClone(views.instances[0]);
  personal.id = 'my-orders';
  personal.title = '我的订单';
  personal.scope = { type: 'personal' };
  personal.config.filters = createFilterConfiguration({
    ...newFilterNode(Op.EQ, 'state.lifecycle', { name: 'order-status' }),
    props: {},
  });
  return {
    instances: [personal, ...views.instances],
    defaultInstanceId: personal.id,
  };
}
