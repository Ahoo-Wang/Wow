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

import { describe, expect, it } from 'vitest';
import { createOrderFixtures } from '../examples/react/sales-order/fixtures.js';
import {
  projectOrder,
  validateOrder,
} from '../examples/react/sales-order/model.js';

describe('sales order facts', () => {
  it('keeps original value and derives refund and invoice credit after a partial return', () => {
    const order = createOrderFixtures().find(o => o.id === 'SO-202609-1005')!;
    expect(projectOrder(order).state).toMatchObject({
      totalAmount: 4800,
      receivableAmount: 3600,
      refundDue: 1200,
      creditDue: 1200,
    });
  });
  it('validates all seeded histories and rejects fractional and negative quantities', () => {
    const orders = createOrderFixtures();
    expect(orders).toHaveLength(18);
    orders.forEach(validateOrder);
    for (const quantity of [-1, 0, 1.5]) {
      const bad = structuredClone(orders[0]);
      bad.items[0].quantity = quantity;
      expect(() => validateOrder(bad)).toThrow();
    }
  });
  it('retains rejected quantities until warehouse return, then allows replacement delivery', () => {
    const order = createOrderFixtures().find(o => o.id === 'SO-202609-1007')!;
    expect(projectOrder(order).state.items[0]).toMatchObject({
      signed: 4,
      rejected: 1,
      remainingToShip: 0,
    });
    order.returnedToWarehouse.push({
      shipmentId: order.shipments[0].id,
      lines: [{ itemId: 'item-1', quantity: 1 }],
    });
    expect(projectOrder(order).state.items[0]).toMatchObject({
      signed: 4,
      rejected: 0,
      remainingToShip: 1,
    });
    validateOrder(order);
  });
});

it('distinguishes no payment or invoice obligation from actual collection and invoicing', () => {
  const cancelled = createOrderFixtures().find(o => o.id === 'SO-202609-1018')!;
  expect(projectOrder(cancelled).state).toMatchObject({
    paymentStatus: '无需收款',
    invoiceStatus: '无需开票',
  });
});
