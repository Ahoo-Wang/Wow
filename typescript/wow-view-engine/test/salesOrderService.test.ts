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
import { createOrderService } from '../examples/react/sales-order/service.js';
import { defaultDraft } from '../examples/react/sales-order/fixtures.js';
import type { Command } from '../examples/react/sales-order/service.js';
import type { Role } from '../examples/react/sales-order/model.js';

describe('sales order business journeys', () => {
  it('releases prepared stock when cancelling an unshipped order', async () => {
    const service = createOrderService();
    const id = 'SO-202609-1001';
    expect(
      service.read().find(order => order.aggregateId === id)!.state.items[0]
        .prepared,
    ).toBe(6);
    const [cancelled] = await service.execute(
      { type: 'cancel', orderId: id, reason: '客户取消' },
      'sales',
      'cancel',
    );
    expect(cancelled.state.items.every(item => item.prepared === 0)).toBe(true);
  });
  it('rejects prepaid release until fully paid, and deduplicates money writes', async () => {
    const service = createOrderService();
    await expect(
      service.execute(
        { type: 'release', orderId: 'SO-202609-1003' },
        'manager',
        'release',
      ),
    ).rejects.toThrow();
    const cmd = {
      type: 'receive',
      orderId: 'SO-202609-1003',
      amountCents: 500000,
      reference: 'BANK-001',
    } as const;
    await service.execute(cmd, 'finance', 'receipt');
    await service.execute(cmd, 'finance', 'receipt');
    expect(
      service.read().find(o => o.aggregateId === cmd.orderId)!.state
        .netReceived,
    ).toBe(8000);
    await expect(
      service.execute({ ...cmd, amountCents: 100 }, 'finance', 'receipt'),
    ).rejects.toThrow();
    await service.execute(
      { type: 'release', orderId: cmd.orderId },
      'manager',
      'release',
    );
    expect(
      service.read().find(o => o.aggregateId === cmd.orderId)!.state.released,
    ).toBe(true);
  });
  it('allows credit release but rejects a whole mixed batch when one customer is overdue', async () => {
    const service = createOrderService();
    const before = service.read();
    await expect(
      service.execute(
        {
          type: 'batchRelease',
          orderIds: ['SO-202609-1002', 'SO-202609-1008'],
        },
        'manager',
        'batch',
      ),
    ).rejects.toThrow();
    expect(service.read()).toEqual(before);
    await service.execute(
      { type: 'release', orderId: 'SO-202609-1002' },
      'manager',
      'one',
    );
    expect(
      service.read().find(o => o.aggregateId === 'SO-202609-1002')!.state,
    ).toMatchObject({ released: true, netReceived: 0 });
  });
  it('requires both refund and credit before reconciling a partial return', async () => {
    const service = createOrderService();
    const orderId = 'SO-202609-1005';
    await expect(
      service.execute({ type: 'close', orderId }, 'manager', 'close'),
    ).rejects.toThrow();
    await service.execute(
      { type: 'refund', orderId, amountCents: 120000, reference: 'REF-1' },
      'finance',
      'refund',
    );
    await expect(
      service.execute({ type: 'reconcile', orderId }, 'finance', 'reconcile'),
    ).rejects.toThrow();
    await service.execute(
      { type: 'credit', orderId, amountCents: 120000, reference: 'CREDIT-1' },
      'finance',
      'credit',
    );
    await service.execute(
      { type: 'reconcile', orderId },
      'finance',
      'reconcile',
    );
    await service.execute({ type: 'close', orderId }, 'manager', 'close');
    expect(
      service.read().find(o => o.aggregateId === orderId)!.state,
    ).toMatchObject({
      netReceived: 3600,
      netInvoiced: 3600,
      lifecycle: 'closed',
    });
  });
  it('completes a newly entered order and refuses writes after closure', async () => {
    const service = createOrderService();
    let id = 0;
    const run = (command: Command, role: Role) =>
      service.execute(command, role, String(++id));
    const [created] = await run(
      { type: 'create', draft: defaultDraft() },
      'sales',
    );
    const orderId = created.aggregateId;
    await run({ type: 'submit', orderId }, 'sales');
    await run({ type: 'approve', orderId }, 'manager');
    await run(
      { type: 'receive', orderId, amountCents: 240000, reference: 'BANK-1' },
      'finance',
    );
    await run({ type: 'release', orderId }, 'manager');
    const lines = [{ itemId: 'item-1', quantity: 2 }];
    await run({ type: 'prepare', orderId, lines }, 'delivery');
    const [shipped] = await run(
      { type: 'ship', orderId, lines, tracking: 'SF-1' },
      'delivery',
    );
    await run(
      {
        type: 'receipt',
        orderId,
        shipmentId: shipped.state.shipments[0].id,
        accepted: lines,
        rejected: [],
      },
      'delivery',
    );
    await run(
      { type: 'invoice', orderId, amountCents: 240000, reference: 'INV-1' },
      'finance',
    );
    await run({ type: 'reconcile', orderId }, 'finance');
    await run({ type: 'close', orderId }, 'manager');
    expect(
      service.read().find(o => o.aggregateId === orderId)!.state.lifecycle,
    ).toBe('closed');
    await expect(
      run({ type: 'cancel', orderId, reason: '取消' }, 'sales'),
    ).rejects.toThrow();
  });
  it('restocks a rejected shipment and preserves its replacement history', async () => {
    const service = createOrderService();
    const orderId = 'SO-202609-1007';
    const lines = [{ itemId: 'item-1', quantity: 1 }];
    await service.execute(
      { type: 'restock', orderId, shipmentId: 'shipment-1', lines },
      'delivery',
      'restock',
    );
    await service.execute(
      { type: 'prepare', orderId, lines },
      'delivery',
      'prepare',
    );
    const [order] = await service.execute(
      { type: 'ship', orderId, lines, tracking: 'REPLACE' },
      'delivery',
      'ship',
    );
    await service.execute(
      {
        type: 'receipt',
        orderId,
        shipmentId: order.state.shipments[1].id,
        accepted: lines,
        rejected: [],
      },
      'delivery',
      'sign',
    );
    expect(
      service.read().find(o => o.aggregateId === orderId)!.state.items[0],
    ).toMatchObject({ signed: 5, shipped: 6, rejected: 0, remainingToShip: 0 });
  });
  it('requires a cancelled paid order to be refunded and rejects the wrong role', async () => {
    const service = createOrderService();
    const orderId = 'SO-202609-1006';
    await expect(
      service.execute(
        { type: 'refund', orderId, amountCents: 200000, reference: 'R' },
        'sales',
        'bad',
      ),
    ).rejects.toThrow();
    await service.execute(
      { type: 'cancel', orderId, reason: '客户项目取消' },
      'sales',
      'cancel',
    );
    await expect(
      service.execute({ type: 'close', orderId }, 'manager', 'close'),
    ).rejects.toThrow();
    await service.execute(
      { type: 'refund', orderId, amountCents: 200000, reference: 'R' },
      'finance',
      'refund',
    );
    await service.execute(
      { type: 'reconcile', orderId },
      'finance',
      'reconcile',
    );
    await service.execute({ type: 'close', orderId }, 'manager', 'close');
    expect(
      service.read().find(o => o.aggregateId === orderId)!.state
        .receivableAmount,
    ).toBe(0);
  });
});

it('does not accept a forged credit policy or lifecycle in an entered draft', async () => {
  const service = createOrderService();
  const draft = {
    ...defaultDraft(),
    customerId: 'customer-8',
    customer: '北辰服务',
    terms: 'credit' as const,
    overdue: false,
    lifecycle: 'confirmed',
  };
  const [created] = await service.execute(
    { type: 'create', draft },
    'sales',
    'create-forged',
  );
  expect(created.state.lifecycle).toBe('draft');
  await service.execute(
    { type: 'submit', orderId: created.aggregateId },
    'sales',
    'submit',
  );
  await service.execute(
    { type: 'approve', orderId: created.aggregateId },
    'manager',
    'approve',
  );
  await expect(
    service.execute(
      { type: 'release', orderId: created.aggregateId },
      'manager',
      'release',
    ),
  ).rejects.toThrow();
});
it('rejects over-signing without committing and clears reconciliation when return is requested', async () => {
  const service = createOrderService();
  const orderId = 'SO-202609-1015';
  const before = service.read();
  await expect(
    service.execute(
      {
        type: 'receipt',
        orderId,
        shipmentId: 'shipment-1',
        accepted: [{ itemId: 'item-1', quantity: 3 }],
        rejected: [],
      },
      'delivery',
      'oversign',
    ),
  ).rejects.toThrow();
  expect(service.read()).toEqual(before);
  const returned = 'SO-202609-1017';
  await service.execute(
    {
      type: 'requestReturn',
      orderId: returned,
      lines: [{ itemId: 'item-1', quantity: 1 }],
    },
    'support',
    'return',
  );
  expect(
    service.read().find(o => o.aggregateId === returned)!.state.reconciled,
  ).toBe(false);
  await expect(
    service.execute({ type: 'close', orderId: returned }, 'manager', 'close'),
  ).rejects.toThrow();
});

it('keeps cancellation effective after a seeded cancelled order is closed', async () => {
  const service = createOrderService();
  const orderId = 'SO-202609-1018';
  await service.execute(
    { type: 'reconcile', orderId },
    'finance',
    'reconcile-cancelled',
  );
  await service.execute(
    { type: 'close', orderId },
    'manager',
    'close-cancelled',
  );
  expect(
    service.read().find(o => o.aggregateId === orderId)!.state.receivableAmount,
  ).toBe(0);
});

it('keeps an aftersales order in its public queue through reconciliation and closes it only after handoff', async () => {
  const service = createOrderService();
  const orderId = 'SO-202609-1005';
  const state = () =>
    service.read().find(o => o.aggregateId === orderId)!.state;
  await service.execute(
    { type: 'refund', orderId, amountCents: 120000, reference: 'REF' },
    'finance',
    'refund-queue',
  );
  expect(state().aftersaleStatus).toBe('待冲减');
  await service.execute(
    { type: 'credit', orderId, amountCents: 120000, reference: 'CREDIT' },
    'finance',
    'credit-queue',
  );
  expect(state()).toMatchObject({
    aftersalesOpen: true,
    closureStatus: '待结算核对',
  });
  await service.execute(
    { type: 'reconcile', orderId },
    'finance',
    'reconcile-queue',
  );
  expect(state()).toMatchObject({
    aftersalesOpen: true,
    closureStatus: '待关闭',
  });
  await service.execute({ type: 'close', orderId }, 'manager', 'close-queue');
  expect(state()).toMatchObject({
    aftersalesOpen: false,
    closureStatus: '已关闭',
    aftersaleStatus: '售后已结清',
  });
});
