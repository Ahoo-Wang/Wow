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

import type { RecordData } from '@ahoo-wang/fetcher-view-engine';
import { installRecordedWowService } from './recordedWowService.js';

/**
 * The host the trade order scenes' regression stories point at. No network
 * answers it: the installers below do, in the page.
 */
export const RECORDED_TRADING_HOST = 'https://trading.example.test';

const START = Date.parse('2026-09-14T00:00:00.000Z');
const HOUR = 3_600_000;

/** A product line: its model code, and the name the service writes for it. */
interface Product {
  code: string;
  brand: string;
  name: string;
  price: number;
}

// The shapes of the three switches the live service sells, under a
// made-up brand.
const RED: Product = {
  code: 'BTN-22R',
  brand: '示例电器',
  name: 'BTN-22R 示例电器 按钮/开关/指示灯/按钮盒/主令',
  price: 120,
};
const GREEN: Product = {
  code: 'BTN-22G',
  brand: '示例电器',
  name: 'BTN-22G 示例电器 按钮/开关/指示灯/按钮盒/主令',
  price: 97.68,
};
const LAMP: Product = {
  code: 'LMP-16W',
  brand: '示例电器',
  name: 'LMP-16W 示例电器 按钮/开关/指示灯/按钮盒/主令',
  price: 264,
};

interface Line {
  product: Product;
  qty: number;
  reviewStatus?: string;
}

/**
 * Trade orders shaped like the snapshots the trading service returns for
 * `trade_order` — the same members, nulls and zeros where the service
 * writes them — with the customers, recipients and products replaced by
 * neutral ones. `hours` is when each was placed, so a sort by time has
 * something to decide.
 */
export const RECORDED_TRADE_ORDERS: RecordData[] = [
  order('TO-1', 'PENDING_REVIEW', '华东机电', 0, [
    { product: RED, qty: 10, reviewStatus: 'PENDING' },
  ]),
  order('TO-2', 'CONFIRMED', '北方五金', 2, [{ product: GREEN, qty: 1 }], 30),
  order('TO-3', 'PENDING_AMENDMENT', '北方五金', 4, [
    { product: RED, qty: 5, reviewStatus: 'APPROVED' },
    { product: LAMP, qty: 2, reviewStatus: 'REJECTED' },
  ]),
  order('TO-4', 'CONFIRMED', '南方电气', 6, [{ product: LAMP, qty: 1 }], 12),
  order('TO-5', 'CANCELLED', '华东机电', 8, [{ product: GREEN, qty: 3 }]),
  order('TO-6', 'PENDING_REVIEW', '南方电气', 10, [
    { product: RED, qty: 2, reviewStatus: 'PENDING' },
  ]),
];

function order(
  orderNo: string,
  status: string,
  customer: string,
  hours: number,
  lines: Line[],
  // How long a confirmed order has before it cancels itself.
  cancelInHours = 24,
): RecordData {
  const placed = START + hours * HOUR;
  const items = lines.map(({ product, qty, reviewStatus }, index) => ({
    itemId: `item-${String(index + 1).padStart(3, '0')}`,
    skuId: {
      id: `SK-${product.code}`,
      brandId: 'B1',
      code: product.code,
      isComposite: false,
      brandName: product.brand,
      bizId: '',
    },
    price: product.price,
    qty,
    realPrice: product.price,
    realPriceAdjustmentAmount: 0,
    totalRealPrice: product.price * qty,
    totalPrice: product.price * qty,
    deliveryTime: { type: 'SPOT', deliveryCycle: { start: 0, end: 0 } },
    remark: '',
    boundDeliveryOrderItems: [],
    reviewStatus: reviewStatus ?? 'NOT_REQUIRED',
    warehouseId: '',
    commercialInfo: {
      productName: product.name,
      specification: product.code,
      unit: 'PCS',
    },
    allDelivered: false,
    availableDeliveredQty: qty,
    deliveredQuantity: 0,
    isComposite: false,
    reservedDeliveryQuantity: 0,
    signedQuantity: 0,
  }));
  const total = items.reduce((sum, item) => sum + item.totalPrice, 0);
  const customerId = { id: `C-${customer}`, name: customer };
  return {
    contextName: 'trading-service',
    aggregateName: 'trade_order',
    tenantId: 'demo',
    ownerId: '',
    spaceId: '',
    version: 1,
    firstEventTime: placed,
    eventTime: placed + HOUR / 2,
    deleted: false,
    aggregateId: orderNo,
    state: {
      id: orderNo,
      orderNo,
      status,
      orderType: 'NORMAL',
      channel: 'trade-web',
      currency: 'CNY',
      customerId,
      buyerOrderNo: null,
      couponId: null,
      couponDiscountAmount: null,
      operatingEntityId: null,
      deliveryStatus: 'NONE',
      deliveryStrategy: 'TOGETHER',
      paymentStatus: 'NONE',
      items,
      totalProductAmount: total,
      payableAmount: total,
      advancePaymentAmount: total,
      paidAmount: 0,
      discountAmount: 0,
      freightAmount: 0,
      serviceFeeAmount: 0,
      scoreDeductionAmount: 0,
      deductibleDeposit: 0,
      requestPoints: 0,
      deductedPoints: 0,
      autoCancelAt: placed + cancelInHours * HOUR,
      source: { type: 'SALES_ORDER', bizId: '' },
      shippingAddress: {
        consignee: { ...customerId, bizId: '' },
        customerName: customer,
        recipientName: '收货人',
        recipientPhone: '',
        recipientAddress: {
          province: '上海市',
          city: '上海市',
          district: '示例区',
          detail: '示例路 1 号',
        },
      },
      draft: null,
    },
  };
}

const API = 'com.linyikj.trading.api.order';

/** Wow names an event by its type, in snake case. */
function eventName(type: string): string {
  const simple = type.slice(type.lastIndexOf('.') + 1);
  return simple.replace(/(?<!^)([A-Z])/g, '_$1').toLowerCase();
}

/**
 * The event streams of those orders, shaped like the ones the service
 * returns for `trade_order`: a command appends one stream, which often holds
 * two events — an order created and confirmed at once, a line rejected and
 * the order sent back for amendment. The payloads are left empty: nothing
 * the console reads is in them.
 */
export const RECORDED_TRADE_ORDER_STREAMS: RecordData[] = [
  stream('TO-1', 1, 0, ['create.OrderCreated']),
  stream('TO-2', 1, 2, ['create.OrderCreated', 'confirm.OrderConfirmed']),
  stream('TO-3', 1, 4, ['create.OrderCreated']),
  stream('TO-3', 2, 5, [
    'review.OrderItemReviewRejected',
    'amendment.OrderAmendmentPending',
  ]),
  stream('TO-4', 1, 6, ['create.OrderCreated']),
  stream('TO-4', 2, 7, [
    'review.OrderItemReviewPassed',
    'confirm.OrderConfirmed',
  ]),
  stream('TO-5', 1, 8, ['create.OrderCreated', 'confirm.OrderConfirmed']),
  stream('TO-3', 3, 9, ['amendment.OrderChanged']),
  stream('TO-5', 2, 9.5, ['cancel.OrderCancelled']),
  stream('TO-6', 1, 10, ['create.OrderCreated']),
];

function stream(
  aggregateId: string,
  version: number,
  hours: number,
  types: string[],
): RecordData {
  const id = `${aggregateId}-v${version}`;
  return {
    id,
    contextName: 'trading-service',
    aggregateName: 'trade_order',
    header: { command_operator: 'operator' },
    aggregateId,
    tenantId: 'demo',
    ownerId: '',
    spaceId: '',
    commandId: `${id}-command`,
    requestId: `${id}-request`,
    version,
    body: types.map((type, index) => ({
      id: `${id}-event-${index + 1}`,
      name: eventName(type),
      revision: '0.0.1',
      bodyType: `${API}.${type}`,
      body: {},
    })),
    createTime: START + hours * HOUR,
  };
}

/**
 * Answers the snapshot queries the trade order console sends to the
 * recorded host. The console sends no command.
 *
 * Returns the uninstaller, as `beforeEach` expects.
 */
export function installRecordedTradeOrderService(): () => void {
  return installRecordedWowService({
    host: RECORDED_TRADING_HOST,
    resource: 'trade_order/snapshot',
    documents: RECORDED_TRADE_ORDERS,
  });
}

/**
 * Answers the event stream queries the trade order event console sends to
 * the recorded host. The stream is read-only.
 *
 * Returns the uninstaller, as `beforeEach` expects.
 */
export function installRecordedTradeOrderEventService(): () => void {
  return installRecordedWowService({
    host: RECORDED_TRADING_HOST,
    resource: 'trade_order/event',
    documents: RECORDED_TRADE_ORDER_STREAMS,
  });
}
