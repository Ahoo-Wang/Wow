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
  DEMO_NOW,
  type OrderDraft,
  type OrderFacts,
  type Role,
} from './model.js';
export const customers = [
  '启明设计',
  '远航科技',
  '澄明咨询',
  '星河教育',
  '青禾制造',
  '云杉传媒',
  '明川贸易',
  '知行工程',
  '北辰服务',
  '海岚建筑',
  '新程网络',
  '远景实验室',
  '瑞和医疗',
  '融创物流',
  '文禾书院',
  '锦程商贸',
  '观澜文化',
  '东原设计',
];
export const products = [
  { sku: 'MON-01', name: '办公显示器', unitPriceCents: 120000 },
  { sku: 'KB-01', name: '机械键盘', unitPriceCents: 40000 },
  { sku: 'PC-01', name: '办公主机', unitPriceCents: 200000 },
];
export function defaultDraft(): OrderDraft {
  return {
    customerId: 'customer-0',
    customer: customers[0],
    creditAllowed: true,
    overdue: false,
    ownerId: '林晨',
    region: '上海',
    terms: 'prepaid',
    dueAt: DEMO_NOW + 3 * 86400000,
    paymentDueAt: DEMO_NOW + 30 * 86400000,
    items: [{ id: 'item-1', ...products[0], quantity: 2 }],
  };
}
export function newOrder(id: string, draft: OrderDraft): OrderFacts {
  return {
    ...structuredClone(draft),
    id,
    lifecycle: 'draft',
    cancelledAt: null,
    released: false,
    reconciled: false,
    prepared: [],
    shipments: [],
    receipts: [],
    returnedToWarehouse: [],
    returns: [],
    receiptsOfMoney: [],
    refunds: [],
    invoices: [],
    credits: [],
    history: [{ action: '创建订单', actor: 'sales', at: DEMO_NOW - 86400000 }],
  };
}
export function createOrderFixtures(): OrderFacts[] {
  const specs: [number, number, string][] = [
    [10, 120000, 'confirmed'],
    [5, 120000, 'confirmed'],
    [4, 200000, 'confirmed'],
    [4, 120000, 'confirmed'],
    [4, 120000, 'confirmed'],
    [2, 100000, 'confirmed'],
    [5, 120000, 'confirmed'],
    [2, 120000, 'confirmed'],
    [3, 40000, 'submitted'],
    [2, 120000, 'rejected'],
    [5, 40000, 'confirmed'],
    [3, 120000, 'confirmed'],
    [1, 200000, 'closed'],
    [2, 40000, 'draft'],
    [2, 120000, 'confirmed'],
    [4, 40000, 'confirmed'],
    [2, 120000, 'confirmed'],
    [1, 120000, 'cancelled'],
  ];
  return specs.map(([quantity, unitPriceCents, lifecycle], index) => {
    const order = newOrder(`SO-202609-${1001 + index}`, {
      ...defaultDraft(),
      customerId: `customer-${(index + 1) % 18}`,
      customer: customers[(index + 1) % 18],
      ownerId: ['林晨', '顾嘉', '陈宁'][index % 3],
      region: ['上海', '杭州', '深圳'][index % 3],
    });
    order.items = [
      {
        id: 'item-1',
        sku:
          unitPriceCents === 40000
            ? 'KB-01'
            : unitPriceCents === 200000
              ? 'PC-01'
              : 'MON-01',
        name:
          unitPriceCents === 40000
            ? '机械键盘'
            : unitPriceCents === 200000
              ? '办公主机'
              : '办公显示器',
        quantity,
        unitPriceCents,
      },
    ];
    order.lifecycle = lifecycle as OrderFacts['lifecycle'];
    const amount = quantity * unitPriceCents;
    const record = (value: number) => ({
      id: `seed-${index}`,
      amountCents: value,
      reference: `BANK-${1001 + index}`,
      at: DEMO_NOW - 3600000,
    });
    if ([0, 3, 4, 6, 11, 12, 14, 15, 16].includes(index))
      order.receiptsOfMoney = [record(amount)];
    if (index === 2) order.receiptsOfMoney = [record(300000)];
    if (index === 5) order.receiptsOfMoney = [record(200000)];
    if ([1, 7, 10].includes(index)) order.terms = 'credit';
    if (index === 7) order.overdue = true;
    if ([0, 3, 4, 6, 11, 12, 14, 15, 16].includes(index)) order.released = true;
    if (index === 0) order.prepared = [{ itemId: 'item-1', quantity: 6 }];
    if (index === 3 || index === 15)
      order.prepared = [{ itemId: 'item-1', quantity: 2 }];
    if (index === 3) order.dueAt = DEMO_NOW - 86400000;
    if ([4, 6, 12, 14, 16].includes(index)) {
      order.prepared = [{ itemId: 'item-1', quantity }];
      order.shipments = [
        {
          id: 'shipment-1',
          lines: [{ itemId: 'item-1', quantity }],
          tracking: `SF-${index}`,
          at: DEMO_NOW - 3600000,
        },
      ];
      if (index !== 14)
        order.receipts = [
          {
            shipmentId: 'shipment-1',
            accepted: [
              { itemId: 'item-1', quantity: index === 6 ? 4 : quantity },
            ],
            rejected: index === 6 ? [{ itemId: 'item-1', quantity: 1 }] : [],
            at: DEMO_NOW - 1800000,
          },
        ];
    }
    if ([4, 12, 16].includes(index)) order.invoices = [record(amount)];
    if (index === 11) order.invoices = [record(120000)];
    if (index === 4)
      order.returns = [
        {
          id: 'return-1',
          requested: [{ itemId: 'item-1', quantity: 1 }],
          approved: true,
          received: [{ itemId: 'item-1', quantity: 1 }],
          at: DEMO_NOW - 900000,
        },
      ];
    if ([12, 16].includes(index)) order.reconciled = true;
    if (index === 9)
      order.history.push({
        action: '驳回审核',
        actor: 'manager',
        at: DEMO_NOW - 600000,
        reason: '请核对客户要求的交期',
      });
    if (index === 17)
      order.items = [
        { ...products[0], id: 'item-1', quantity: 1 },
        { ...products[1], id: 'item-2', quantity: 6 },
      ];
    if (order.lifecycle === 'cancelled') order.cancelledAt = DEMO_NOW - 3600000;
    const prior: { action: string; actor: Role }[] = [];
    if (order.lifecycle !== 'draft' && order.lifecycle !== 'cancelled')
      prior.push({ action: '提交审核', actor: 'sales' });
    if (['confirmed', 'closed'].includes(order.lifecycle))
      prior.push({ action: '审核通过', actor: 'manager' });
    if (order.receiptsOfMoney.length)
      prior.push({ action: '登记收款', actor: 'finance' });
    if (order.released) prior.push({ action: '放行交付', actor: 'manager' });
    if (order.prepared.length)
      prior.push({ action: '登记备货', actor: 'delivery' });
    if (order.shipments.length)
      prior.push({ action: '登记发货', actor: 'delivery' });
    if (order.receipts.length)
      prior.push({ action: '签收与拒收', actor: 'delivery' });
    if (order.invoices.length)
      prior.push({ action: '登记开票', actor: 'finance' });
    if (order.returns.length)
      prior.push(
        { action: '申请退货', actor: 'support' },
        { action: '审核退货', actor: 'support' },
        { action: '退货入库', actor: 'delivery' },
      );
    if (order.reconciled) prior.push({ action: '结算核对', actor: 'finance' });
    if (order.lifecycle === 'cancelled')
      prior.push({ action: '取消订单', actor: 'sales' });
    if (order.lifecycle === 'closed')
      prior.push({ action: '关闭订单', actor: 'manager' });
    order.history.push(
      ...prior.map((event, i) => ({
        ...event,
        at: DEMO_NOW - 7200000 + i * 60000,
      })),
    );
    order.history.sort((a, b) => a.at - b.at);
    return order;
  });
}
