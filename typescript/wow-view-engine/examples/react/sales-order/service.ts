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

import { createOrderFixtures, newOrder, customers } from './fixtures.js';
import {
  DEMO_NOW,
  itemProgress,
  projectOrder,
  quantityOf,
  requireRule,
  validateLines,
  validateOrder,
  type OrderDraft,
  type OrderFacts,
  type OrderSnapshot,
  type QuantityLine,
  type Role,
} from './model.js';
export type Command =
  | { type: 'create'; draft: OrderDraft }
  | { type: 'edit'; orderId: string; draft: OrderDraft }
  | {
      type: 'submit' | 'approve' | 'release' | 'reconcile' | 'close';
      orderId: string;
    }
  | { type: 'reject' | 'cancel'; orderId: string; reason: string }
  | {
      type: 'receive' | 'refund' | 'invoice' | 'credit';
      orderId: string;
      amountCents: number;
      reference: string;
    }
  | { type: 'prepare'; orderId: string; lines: QuantityLine[] }
  | { type: 'ship'; orderId: string; lines: QuantityLine[]; tracking: string }
  | {
      type: 'receipt';
      orderId: string;
      shipmentId: string;
      accepted: QuantityLine[];
      rejected: QuantityLine[];
    }
  | {
      type: 'restock';
      orderId: string;
      shipmentId: string;
      lines: QuantityLine[];
    }
  | { type: 'requestReturn'; orderId: string; lines: QuantityLine[] }
  | { type: 'approveReturn'; orderId: string; returnId: string }
  | {
      type: 'receiveReturn';
      orderId: string;
      returnId: string;
      lines: QuantityLine[];
    }
  | { type: 'batchApprove' | 'batchRelease'; orderIds: string[] };
export type Action = Command['type'];
export const actionLabels: Record<Action, string> = {
  create: '创建订单',
  edit: '修改订单',
  submit: '提交审核',
  approve: '审核通过',
  release: '放行交付',
  reconcile: '结算核对',
  close: '关闭订单',
  reject: '驳回审核',
  cancel: '取消订单',
  receive: '登记收款',
  refund: '登记退款',
  invoice: '登记开票',
  credit: '登记冲减',
  prepare: '登记备货',
  ship: '登记发货',
  receipt: '签收与拒收',
  restock: '拒收回仓',
  requestReturn: '申请退货',
  approveReturn: '审核退货',
  receiveReturn: '退货入库',
  batchApprove: '批量审核',
  batchRelease: '批量放行',
};
export const actionRoles: Record<Action, Role> = {
  create: 'sales',
  edit: 'sales',
  submit: 'sales',
  approve: 'manager',
  reject: 'manager',
  release: 'manager',
  batchApprove: 'manager',
  batchRelease: 'manager',
  cancel: 'sales',
  receive: 'finance',
  refund: 'finance',
  invoice: 'finance',
  credit: 'finance',
  reconcile: 'finance',
  close: 'manager',
  prepare: 'delivery',
  ship: 'delivery',
  receipt: 'delivery',
  restock: 'delivery',
  requestReturn: 'support',
  approveReturn: 'support',
  receiveReturn: 'delivery',
};
export interface ServiceOptions {
  failFirstWrite?: boolean;
}
export interface OrderService {
  read(): OrderSnapshot[];
  execute(
    command: Command,
    actor: Role,
    requestId: string,
  ): Promise<OrderSnapshot[]>;
}
export function actionReason(
  order: OrderFacts,
  action: Action,
  role: Role,
): string | null {
  if (actionRoles[action] !== role) return '请切换到负责此操作的岗位';
  if (order.lifecycle === 'closed') return '订单已关闭，只能查看';
  const state = projectOrder(order).state;
  const confirmed = order.lifecycle === 'confirmed';
  switch (action) {
    case 'edit':
    case 'submit':
      return ['draft', 'rejected'].includes(order.lifecycle)
        ? null
        : '仅草稿或驳回订单可修改和提交';
    case 'approve':
    case 'reject':
      return order.lifecycle === 'submitted' ? null : '订单尚未提交审核';
    case 'cancel':
      return order.shipments.length
        ? '已发货订单请走售后流程'
        : order.lifecycle === 'cancelled'
          ? '订单已取消'
          : null;
    case 'release':
      if (!confirmed || order.released) return '仅已确认且未放行订单可以放行';
      return order.terms === 'prepaid'
        ? state.amountDue > 0
          ? '预付款尚未收齐'
          : null
        : !order.creditAllowed || order.overdue
          ? '客户账期未授权或存在逾期欠款'
          : null;
    case 'receive':
      return confirmed && state.amountDue > 0 ? null : '没有待收款';
    case 'refund':
      return state.refundDue > 0 ? null : '没有待退款';
    case 'invoice':
      return confirmed && state.invoiceDue > 0 ? null : '没有待开票';
    case 'credit':
      return state.creditDue > 0 ? null : '没有待冲减';
    case 'prepare':
      return confirmed &&
        order.released &&
        state.items.some(i => i.remainingToShip > i.prepared)
        ? null
        : '尚未放行或没有待备货数量';
    case 'ship':
      return confirmed &&
        order.released &&
        state.items.some(i => i.prepared > 0)
        ? null
        : '没有可发出的备货';
    case 'receipt':
      return confirmed && state.pendingReceipt > 0 ? null : '没有待签收商品';
    case 'restock':
      return confirmed && state.rejected > 0 ? null : '没有拒收待回仓商品';
    case 'requestReturn':
      return confirmed && state.items.some(i => i.signed > i.returnRequested)
        ? null
        : '没有可申请退货的已签收商品';
    case 'approveReturn':
      return order.returns.some(r => !r.approved) ? null : '没有待审核退货';
    case 'receiveReturn':
      return order.returns.some(
        r =>
          r.approved &&
          r.requested.some(l => quantityOf(r.received, l.itemId) < l.quantity),
      )
        ? null
        : '没有已批准的待入库退货';
    case 'reconcile':
      return !['confirmed', 'cancelled'].includes(order.lifecycle)
        ? '订单尚未确认'
        : state.amountDue ||
            state.refundDue ||
            state.invoiceDue ||
            state.creditDue
          ? '收退款或开票冲减尚未完成'
          : order.reconciled
            ? '已完成结算核对'
            : null;
    case 'close':
      return !order.reconciled
        ? '请先完成结算核对'
        : state.openReturn ||
            state.rejected ||
            state.pendingReceipt ||
            (order.lifecycle !== 'cancelled' &&
              state.items.some(i => i.signed !== i.quantity))
          ? '履约或售后尚未完成'
          : null;
    default:
      return null;
  }
}
function normalizeDraft(input: OrderDraft): OrderDraft {
  const index = Number(input.customerId.replace('customer-', ''));
  requireRule(
    Number.isInteger(index) &&
      index >= 0 &&
      index < customers.length &&
      input.customerId === `customer-${index}`,
    '请选择有效客户',
  );
  return {
    customerId: input.customerId,
    customer: customers[index],
    creditAllowed: true,
    overdue: index === 8,
    ownerId: input.ownerId,
    region: input.region,
    terms: input.terms,
    dueAt: input.dueAt,
    paymentDueAt: input.paymentDueAt,
    items: input.items.map(i => ({
      id: i.id,
      sku: i.sku,
      name: i.name,
      quantity: i.quantity,
      unitPriceCents: i.unitPriceCents,
    })),
  };
}
export function createOrderService(options: ServiceOptions = {}): OrderService {
  let orders = createOrderFixtures();
  let failed = false;
  let nextId = 1019;
  const receipts = new Map<string, { body: string; result: OrderSnapshot[] }>();
  function apply(
    order: OrderFacts,
    command: Exclude<
      Command,
      { type: 'create' | 'batchApprove' | 'batchRelease' }
    >,
    actor: Role,
    key: string,
  ) {
    const reason = actionReason(order, command.type, actor);
    requireRule(!reason, `${order.id}：${reason}`);
    const now = DEMO_NOW + order.history.length * 1000;
    const lines = 'lines' in command ? command.lines : [];
    if ('lines' in command) validateLines(order, lines);
    switch (command.type) {
      case 'edit':
        Object.assign(order, normalizeDraft(command.draft));
        order.lifecycle = 'draft';
        break;
      case 'submit':
        order.lifecycle = 'submitted';
        break;
      case 'approve':
        order.lifecycle = 'confirmed';
        break;
      case 'reject':
        requireRule(command.reason.trim(), '请填写驳回原因');
        order.lifecycle = 'rejected';
        break;
      case 'cancel':
        requireRule(command.reason.trim(), '请填写取消原因');
        order.lifecycle = 'cancelled';
        order.cancelledAt = now;
        order.prepared = [];
        order.released = false;
        order.reconciled = false;
        break;
      case 'release':
        order.released = true;
        break;
      case 'receive':
      case 'refund':
      case 'invoice':
      case 'credit': {
        const field = {
          receive: 'amountDue',
          refund: 'refundDue',
          invoice: 'invoiceDue',
          credit: 'creditDue',
        } as const;
        const target = {
          receive: 'receiptsOfMoney',
          refund: 'refunds',
          invoice: 'invoices',
          credit: 'credits',
        } as const;
        requireRule(
          Number.isSafeInteger(command.amountCents) &&
            command.amountCents > 0 &&
            command.amountCents <=
              Math.round(projectOrder(order).state[field[command.type]] * 100),
          '金额必须大于零且不超过待处理金额',
        );
        requireRule(command.reference.trim(), '请填写凭证号');
        order[target[command.type]].push({
          id: key,
          amountCents: command.amountCents,
          reference: command.reference.trim(),
          at: now,
        });
        order.reconciled = false;
        break;
      }
      case 'prepare':
        for (const line of lines) {
          const p = itemProgress(
            order,
            order.items.find(i => i.id === line.itemId)!,
          );
          requireRule(
            line.quantity <= p.remainingToShip - p.prepared,
            '备货超过剩余数量',
          );
          const previous = order.prepared.find(l => l.itemId === line.itemId);
          if (previous) previous.quantity += line.quantity;
          else order.prepared.push({ ...line });
        }
        break;
      case 'ship':
        requireRule(command.tracking.trim(), '请填写运单号');
        for (const line of lines)
          requireRule(
            line.quantity <=
              itemProgress(
                order,
                order.items.find(i => i.id === line.itemId)!,
              ).prepared,
            '发货超过已备货数量',
          );
        order.shipments.push({
          id: key,
          lines: structuredClone(lines),
          tracking: command.tracking.trim(),
          at: now,
        });
        break;
      case 'receipt': {
        validateLines(order, command.accepted, true);
        validateLines(order, command.rejected, true);
        requireRule(
          command.accepted.length + command.rejected.length > 0,
          '请填写签收或拒收数量',
        );
        const shipment = order.shipments.find(s => s.id === command.shipmentId);
        requireRule(shipment, '发货记录不存在');
        for (const line of [...command.accepted, ...command.rejected])
          requireRule(
            shipment.lines.some(l => l.itemId === line.itemId),
            '商品不属于这次发货',
          );
        order.receipts.push({
          shipmentId: command.shipmentId,
          accepted: structuredClone(command.accepted),
          rejected: structuredClone(command.rejected),
          at: now,
        });
        break;
      }
      case 'restock': {
        const shipment = order.shipments.find(s => s.id === command.shipmentId);
        requireRule(shipment, '发货记录不存在');
        for (const line of lines)
          requireRule(
            shipment.lines.some(l => l.itemId === line.itemId),
            '商品不属于这次发货',
          );
        order.returnedToWarehouse.push({
          shipmentId: command.shipmentId,
          lines: structuredClone(lines),
        });
        break;
      }
      case 'requestReturn':
        for (const line of lines) {
          const p = itemProgress(
            order,
            order.items.find(i => i.id === line.itemId)!,
          );
          requireRule(
            line.quantity <= p.signed - p.returnRequested,
            '申请退货超过可退数量',
          );
        }
        order.returns.push({
          id: key,
          requested: structuredClone(lines),
          approved: false,
          received: [],
          at: now,
        });
        order.reconciled = false;
        break;
      case 'approveReturn': {
        const ret = order.returns.find(r => r.id === command.returnId);
        requireRule(ret && !ret.approved, '退货记录不存在或已审核');
        ret.approved = true;
        break;
      }
      case 'receiveReturn': {
        const ret = order.returns.find(r => r.id === command.returnId);
        requireRule(ret?.approved, '退货尚未批准');
        for (const line of lines) {
          requireRule(
            line.quantity <=
              quantityOf(ret.requested, line.itemId) -
                quantityOf(ret.received, line.itemId),
            '退货入库超过申请数量',
          );
          const previous = ret.received.find(l => l.itemId === line.itemId);
          if (previous) previous.quantity += line.quantity;
          else ret.received.push({ ...line });
        }
        order.reconciled = false;
        break;
      }
      case 'reconcile':
        order.reconciled = true;
        break;
      case 'close':
        order.lifecycle = 'closed';
        break;
    }
    order.history.push({
      action: actionLabels[command.type],
      actor,
      at: now,
      ...('reason' in command ? { reason: command.reason } : {}),
    });
    validateOrder(order);
  }
  return {
    read: () => orders.map(projectOrder),
    async execute(command, actor, requestId) {
      requireRule(requestId.trim(), '缺少请求标识');
      requireRule(
        actionRoles[command.type] === actor,
        '当前岗位无权执行此操作',
      );
      const key = `${actor}:${requestId}`,
        body = JSON.stringify(command),
        previous = receipts.get(key);
      if (previous) {
        requireRule(previous.body === body, '请求标识已用于其他操作');
        return structuredClone(previous.result);
      }
      if (options.failFirstWrite && !failed) {
        failed = true;
        throw new Error('业务服务暂时不可用，请重试');
      }
      const candidate = structuredClone(orders);
      let affected: OrderFacts[];
      if (command.type === 'create') {
        const order = newOrder(
          `SO-202609-${nextId}`,
          normalizeDraft(command.draft),
        );
        validateOrder(order);
        candidate.unshift(order);
        affected = [order];
      } else if (
        command.type === 'batchApprove' ||
        command.type === 'batchRelease'
      ) {
        requireRule(
          command.orderIds.length &&
            new Set(command.orderIds).size === command.orderIds.length,
          '请选择有效且不重复的订单',
        );
        affected = command.orderIds.map(id => {
          const order = candidate.find(o => o.id === id);
          requireRule(order, `订单 ${id} 不存在`);
          return order;
        });
        for (const order of affected)
          apply(
            order,
            {
              type: command.type === 'batchApprove' ? 'approve' : 'release',
              orderId: order.id,
            },
            actor,
            key,
          );
      } else if ('orderId' in command) {
        const order = candidate.find(o => o.id === command.orderId);
        requireRule(order, '订单不存在');
        apply(order, command, actor, key);
        affected = [order];
      } else {
        throw new Error('未知业务命令');
      }
      orders = candidate;
      if (command.type === 'create') nextId++;
      const result = affected.map(projectOrder);
      receipts.set(key, { body, result: structuredClone(result) });
      return result;
    },
  };
}

/** Suggested business handoff; the command guard remains authoritative. */
export function nextOrderAction(order: OrderFacts) {
  if (order.lifecycle === 'closed') return null;
  const state = projectOrder(order).state;
  let action: Action;
  if (order.lifecycle === 'draft') action = 'submit';
  else if (order.lifecycle === 'rejected') action = 'edit';
  else if (order.lifecycle === 'submitted') action = 'approve';
  else if (state.openReturn)
    action = order.returns.some(r => !r.approved)
      ? 'approveReturn'
      : 'receiveReturn';
  else if (state.refundDue > 0) action = 'refund';
  else if (state.creditDue > 0) action = 'credit';
  else if (order.cancelledAt !== null)
    action = order.reconciled ? 'close' : 'reconcile';
  else if (!order.released)
    action =
      order.terms === 'prepaid' && state.amountDue > 0 ? 'receive' : 'release';
  else if (state.rejected > 0) action = 'restock';
  else if (state.pendingReceipt > 0) action = 'receipt';
  else if (state.items.some(i => i.prepared > 0)) action = 'ship';
  else if (state.remainingToShip > 0) action = 'prepare';
  else if (state.amountDue > 0) action = 'receive';
  else if (state.invoiceDue > 0) action = 'invoice';
  else action = order.reconciled ? 'close' : 'reconcile';
  return {
    action,
    role: actionRoles[action],
    reason: actionReason(order, action, actionRoles[action]),
  };
}
