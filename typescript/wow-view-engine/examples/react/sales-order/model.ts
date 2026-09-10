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

import type { MaterializedSnapshot } from '@ahoo-wang/fetcher-wow';
import type { RecordData } from '@ahoo-wang/fetcher-view-engine';

export const DEMO_NOW = Date.parse('2026-09-10T10:00:00+08:00');
export const roles = {
  sales: '销售',
  manager: '销售主管',
  delivery: '交付专员',
  finance: '财务',
  support: '客服',
} as const;
export type Role = keyof typeof roles;
export type Stage =
  'all' | 'review' | 'release' | 'delivery' | 'settlement' | 'aftersales';
export type Lifecycle =
  'draft' | 'submitted' | 'rejected' | 'confirmed' | 'cancelled' | 'closed';
export const lifecycleLabels: Record<Lifecycle, string> = {
  draft: '草稿',
  submitted: '待审核',
  rejected: '已驳回',
  confirmed: '已确认',
  cancelled: '已取消',
  closed: '已关闭',
};
export interface Item {
  id: string;
  sku: string;
  name: string;
  quantity: number;
  unitPriceCents: number;
}
export interface QuantityLine {
  itemId: string;
  quantity: number;
}
export interface AmountRecord {
  id: string;
  amountCents: number;
  reference: string;
  at: number;
}
export interface Shipment {
  id: string;
  lines: QuantityLine[];
  tracking: string;
  at: number;
}
export interface Receipt {
  shipmentId: string;
  accepted: QuantityLine[];
  rejected: QuantityLine[];
  at: number;
}
export interface ReturnCase {
  id: string;
  requested: QuantityLine[];
  approved: boolean;
  received: QuantityLine[];
  at: number;
}
export interface OrderDraft {
  customerId: string;
  customer: string;
  creditAllowed: boolean;
  overdue: boolean;
  ownerId: string;
  region: string;
  terms: 'prepaid' | 'credit';
  dueAt: number;
  paymentDueAt: number;
  items: Item[];
}
export interface OrderFacts extends OrderDraft {
  id: string;
  lifecycle: Lifecycle;
  cancelledAt: number | null;
  released: boolean;
  reconciled: boolean;
  prepared: QuantityLine[];
  shipments: Shipment[];
  receipts: Receipt[];
  returnedToWarehouse: { shipmentId: string; lines: QuantityLine[] }[];
  returns: ReturnCase[];
  receiptsOfMoney: AmountRecord[];
  refunds: AmountRecord[];
  invoices: AmountRecord[];
  credits: AmountRecord[];
  history: { action: string; actor: Role; at: number; reason?: string }[];
}
export const quantityOf = (lines: readonly QuantityLine[], id: string) =>
  lines.filter(l => l.itemId === id).reduce((sum, l) => sum + l.quantity, 0);
const money = (records: AmountRecord[]) =>
  records.reduce((sum, r) => sum + r.amountCents, 0);
export function itemProgress(order: OrderFacts, item: Item) {
  const shipped = quantityOf(
    order.shipments.flatMap(s => s.lines),
    item.id,
  );
  const restocked = quantityOf(
    order.returnedToWarehouse.flatMap(s => s.lines),
    item.id,
  );
  const signed = quantityOf(
    order.receipts.flatMap(s => s.accepted),
    item.id,
  );
  const rejectedTotal = quantityOf(
    order.receipts.flatMap(s => s.rejected),
    item.id,
  );
  return {
    id: item.id,
    productId: item.sku,
    productName: item.name,
    quantity: item.quantity,
    price: item.unitPriceCents / 100,
    totalPrice: (item.quantity * item.unitPriceCents) / 100,
    prepared: quantityOf(order.prepared, item.id) - shipped,
    shipped,
    signed,
    rejected: rejectedTotal - restocked,
    returned: quantityOf(
      order.returns.flatMap(r => r.received),
      item.id,
    ),
    returnRequested: quantityOf(
      order.returns.flatMap(r => r.requested),
      item.id,
    ),
    pendingReceipt: shipped - signed - rejectedTotal,
    remainingToShip: item.quantity - shipped + restocked,
  };
}
export function projectOrder(order: OrderFacts) {
  const items = order.items.map(item => itemProgress(order, item));
  const original = order.items.reduce(
    (sum, item) => sum + item.quantity * item.unitPriceCents,
    0,
  );
  const returned = order.items.reduce(
    (sum, item, index) => sum + items[index].returned * item.unitPriceCents,
    0,
  );
  const effective = order.cancelledAt !== null ? 0 : original - returned;
  const received = money(order.receiptsOfMoney) - money(order.refunds);
  const invoiced = money(order.invoices) - money(order.credits);
  const remaining = items.reduce((s, i) => s + i.remainingToShip, 0);
  const pending = items.reduce((s, i) => s + i.pendingReceipt, 0);
  const rejected = items.reduce((s, i) => s + i.rejected, 0);
  const openReturn = order.returns.some(
    r =>
      !r.approved ||
      r.requested.some(l => quantityOf(r.received, l.itemId) < l.quantity),
  );
  const fundsSettled = received === effective && invoiced === effective;
  const fulfilled =
    order.cancelledAt !== null ||
    (pending === 0 &&
      rejected === 0 &&
      items.every(i => i.signed === i.quantity));
  const closureStatus =
    order.lifecycle === 'closed'
      ? '已关闭'
      : openReturn
        ? '待售后处理'
        : !fulfilled
          ? '待履约'
          : !fundsSettled
            ? '待收退款与票据'
            : !order.reconciled
              ? '待结算核对'
              : '待关闭';
  const state = {
    ...structuredClone(order),
    items,
    owner: order.ownerId,
    totalAmount: original / 100,
    receivableAmount: effective / 100,
    netReceived: received / 100,
    amountDue: Math.max(effective - received, 0) / 100,
    refundDue: Math.max(received - effective, 0) / 100,
    netInvoiced: invoiced / 100,
    invoiceDue: Math.max(effective - invoiced, 0) / 100,
    creditDue: Math.max(invoiced - effective, 0) / 100,
    paymentStatus:
      effective === 0 && received === 0
        ? '无需收款'
        : received > effective
          ? '待退款'
          : received === effective
            ? '已收齐'
            : received > 0
              ? '部分收款'
              : '未收款',
    fulfillmentStatus:
      order.cancelledAt !== null
        ? '已取消'
        : rejected
          ? '拒收待处理'
          : remaining === 0
            ? pending
              ? '待签收'
              : '已签收'
            : items.some(i => i.shipped)
              ? '部分发货'
              : '未发货',
    invoiceStatus:
      effective === 0 && invoiced === 0
        ? '无需开票'
        : invoiced > effective
          ? '待冲减'
          : invoiced === effective
            ? '已开齐'
            : invoiced > 0
              ? '部分开票'
              : '未开票',
    aftersaleStatus: openReturn
      ? '退货处理中'
      : received > effective
        ? '待退款'
        : invoiced > effective
          ? '待冲减'
          : order.returns.length
            ? '售后已结清'
            : '无售后',
    deliveryRisk:
      remaining > 0 && order.lifecycle === 'confirmed'
        ? order.dueAt < DEMO_NOW
          ? 'late'
          : order.dueAt <= DEMO_NOW + 3 * 86400000
            ? 'soon'
            : 'none'
        : 'none',
    remainingToShip: remaining,
    pendingReceipt: pending,
    rejected,
    openReturn,
    deliveryOpen:
      order.released &&
      order.lifecycle === 'confirmed' &&
      (remaining > 0 || pending > 0 || rejected > 0),
    settlementOpen:
      order.lifecycle !== 'closed' &&
      ['confirmed', 'cancelled'].includes(order.lifecycle),
    closureStatus,
    settlementStatus: order.reconciled
      ? '已核对'
      : fundsSettled
        ? '待核对'
        : '待收退款与票据',
    creditStatus: !order.creditAllowed
      ? '未授权账期'
      : order.overdue
        ? '存在逾期欠款'
        : '账期授权有效',
    releaseStatus:
      order.cancelledAt !== null
        ? '无需放行'
        : order.released
          ? '已放行'
          : order.lifecycle !== 'confirmed'
            ? '待审核'
            : order.terms === 'prepaid'
              ? received < effective
                ? '待补款'
                : '可放行'
              : !order.creditAllowed || order.overdue
                ? '信用受限'
                : '可放行',
    aftersalesOpen:
      order.lifecycle !== 'closed' &&
      (order.returns.length > 0 ||
        order.cancelledAt !== null ||
        openReturn ||
        received > effective ||
        invoiced > effective),
  };
  return {
    contextName: 'commerce',
    aggregateName: 'order',
    aggregateId: order.id,
    tenantId: 'demo-sales',
    ownerId: order.ownerId,
    spaceId: '(0)',
    version: order.history.length,
    eventId: `${order.id}-${order.history.length}`,
    firstOperator: 'sales',
    operator: order.history[order.history.length - 1]?.actor ?? 'sales',
    firstEventTime: order.history[0]?.at ?? DEMO_NOW,
    eventTime: order.history[order.history.length - 1]?.at ?? DEMO_NOW,
    snapshotTime: DEMO_NOW,
    tags: {},
    deleted: false,
    state,
  } satisfies MaterializedSnapshot<typeof state> & RecordData;
}
export type OrderSnapshot = ReturnType<typeof projectOrder>;
export type OrderState = OrderSnapshot['state'];
export function requireRule(
  condition: unknown,
  message: string,
): asserts condition {
  if (!condition) throw new Error(message);
}
export function validateLines(
  order: OrderFacts,
  lines: QuantityLine[],
  allowEmpty = false,
) {
  requireRule(
    Array.isArray(lines) && (allowEmpty || lines.length > 0),
    '请填写商品数量',
  );
  requireRule(
    new Set(lines.map(l => l.itemId)).size === lines.length,
    '商品明细不能重复',
  );
  for (const line of lines)
    requireRule(
      order.items.some(i => i.id === line.itemId) &&
        Number.isSafeInteger(line.quantity) &&
        line.quantity > 0,
      '商品或数量无效',
    );
}
export function validateOrder(order: OrderFacts) {
  requireRule(
    order.customerId &&
      order.customer.trim() &&
      order.ownerId &&
      Number.isFinite(order.dueAt) &&
      Number.isFinite(order.paymentDueAt),
    '客户、负责人或日期无效',
  );
  requireRule(['prepaid', 'credit'].includes(order.terms), '结算方式无效');
  requireRule(
    order.items.length > 0 &&
      new Set(order.items.map(i => i.id)).size === order.items.length,
    '商品不能为空或重复',
  );
  for (const item of order.items) {
    requireRule(
      item.sku &&
        item.name &&
        Number.isSafeInteger(item.quantity) &&
        item.quantity > 0 &&
        Number.isSafeInteger(item.unitPriceCents) &&
        item.unitPriceCents > 0 &&
        Number.isSafeInteger(item.quantity * item.unitPriceCents),
      '数量或单价无效',
    );
    const p = itemProgress(order, item);
    requireRule(
      p.prepared >= 0 &&
        p.prepared <= p.remainingToShip &&
        p.remainingToShip >= 0 &&
        p.signed <= item.quantity &&
        p.rejected >= 0 &&
        p.pendingReceipt >= 0 &&
        p.returned <= p.signed &&
        p.returnRequested <= p.signed,
      '履约数量不一致',
    );
  }
  for (const s of order.shipments) validateLines(order, s.lines);
  for (const r of order.receipts) {
    requireRule(
      order.shipments.some(s => s.id === r.shipmentId),
      '发货记录不存在',
    );
    validateLines(order, r.accepted, true);
    validateLines(order, r.rejected, true);
  }
  for (const s of order.shipments)
    for (const line of s.lines) {
      const receipts = order.receipts.filter(r => r.shipmentId === s.id);
      const accepted = quantityOf(
        receipts.flatMap(r => r.accepted),
        line.itemId,
      );
      const rejected = quantityOf(
        receipts.flatMap(r => r.rejected),
        line.itemId,
      );
      const restocked = quantityOf(
        order.returnedToWarehouse
          .filter(r => r.shipmentId === s.id)
          .flatMap(r => r.lines),
        line.itemId,
      );
      requireRule(
        accepted + rejected <= line.quantity && restocked <= rejected,
        '签收或回仓超过本次发货数量',
      );
    }
  for (const r of order.returns) {
    validateLines(order, r.requested);
    validateLines(order, r.received, true);
    requireRule(r.received.length === 0 || r.approved, '退货尚未审核');
    for (const line of r.received)
      requireRule(
        line.quantity <= quantityOf(r.requested, line.itemId),
        '退货入库超出申请数量',
      );
  }
  for (const record of [
    ...order.receiptsOfMoney,
    ...order.refunds,
    ...order.invoices,
    ...order.credits,
  ])
    requireRule(
      Number.isSafeInteger(record.amountCents) &&
        record.amountCents > 0 &&
        record.reference.trim(),
      '金额或凭证无效',
    );
  requireRule(
    money(order.refunds) <= money(order.receiptsOfMoney) &&
      money(order.credits) <= money(order.invoices),
    '退款或冲减不能超过原金额',
  );
  requireRule(
    Number.isSafeInteger(
      order.items.reduce((s, i) => s + i.quantity * i.unitPriceCents, 0),
    ),
    '订单金额超出精度范围',
  );
}
