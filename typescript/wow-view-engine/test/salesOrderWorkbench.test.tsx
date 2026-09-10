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

import { afterEach, expect, it, vi } from 'vitest';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  within,
  waitFor,
} from '@testing-library/react';
import { OrderWorkbench } from '../examples/react/sales-order/OrderWorkbench.js';
import { OrderForms } from '../examples/react/sales-order/OrderForms.js';
import {
  createOrderService,
  type Command,
} from '../examples/react/sales-order/service.js';
afterEach(cleanup);
it('updates layout in both directions without resetting orders', async () => {
  const view = render(<OrderWorkbench localDefinition layout="table" />);
  fireEvent.click(await screen.findByRole('button', { name: '创建订单' }));
  fireEvent.click(await screen.findByRole('button', { name: '确认创建' }));
  await screen.findByRole('dialog', { name: '订单详情 SO-202609-1019' });
  await waitFor(() =>
    expect(
      (screen.getByRole('button', { name: '关闭详情' }) as HTMLButtonElement)
        .disabled,
    ).toBe(false),
  );
  fireEvent.click(screen.getByRole('button', { name: '关闭详情' }));
  await screen.findByRole('table', { name: '全部订单' });
  view.rerender(<OrderWorkbench localDefinition layout="card" />);
  expect(await screen.findByRole('list', { name: '记录卡片' })).toBeTruthy();
  expect(screen.queryByRole('table')).toBeNull();
  expect(await screen.findByText('SO-202609-1019')).toBeTruthy();
  view.rerender(<OrderWorkbench localDefinition layout="table" />);
  expect(await screen.findByRole('table', { name: '全部订单' })).toBeTruthy();
  expect(screen.queryByRole('list', { name: '记录卡片' })).toBeNull();
  expect(await screen.findByText('SO-202609-1019')).toBeTruthy();
});
it('rebuilds the default view when the requested stage changes', async () => {
  const view = render(<OrderWorkbench stage="all" />);
  await screen.findByRole('table', { name: '全部订单' });
  view.rerender(<OrderWorkbench stage="release" />);
  expect(await screen.findByRole('table', { name: '收款与放行' })).toBeTruthy();
  expect(screen.queryByRole('table', { name: '全部订单' })).toBeNull();
});
it('allocates rejected units from the default accepted quantity', async () => {
  const service = createOrderService();
  const [order] = await service.execute(
    {
      type: 'ship',
      orderId: 'SO-202609-1001',
      tracking: 'TEST-SHIP',
      lines: [{ itemId: 'item-1', quantity: 6 }],
    },
    'delivery',
    'ship',
  );
  const submit = vi.fn(async (command: Command) => {
    await service.execute(command, 'delivery', 'receipt');
  });
  render(
    <OrderForms
      commandType="receipt"
      order={order}
      busy={false}
      onCancel={() => {}}
      onSubmit={submit}
    />,
  );
  fireEvent.change(screen.getByLabelText('拒收数量 办公显示器'), {
    target: { value: '1' },
  });
  expect(
    (screen.getByLabelText('签收数量 办公显示器') as HTMLInputElement).value,
  ).toBe('5');
  fireEvent.click(screen.getByRole('button', { name: '确认签收与拒收' }));
  await waitFor(() =>
    expect(
      service.read().find(row => row.aggregateId === order.aggregateId)!.state
        .items[0],
    ).toMatchObject({ signed: 5, rejected: 1 }),
  );
});
it('keeps business state for appearance changes but resets it for a new scope', async () => {
  const view = render(<OrderWorkbench scopeKey="first-user" />);
  fireEvent.click(await screen.findByRole('button', { name: '创建订单' }));
  fireEvent.click(await screen.findByRole('button', { name: '确认创建' }));
  await screen.findByRole('dialog', { name: '订单详情 SO-202609-1019' });
  view.rerender(<OrderWorkbench scopeKey="first-user" appearance="dark" />);
  expect(
    screen.getByRole('dialog', { name: '订单详情 SO-202609-1019' }),
  ).toBeTruthy();
  view.rerender(<OrderWorkbench scopeKey="second-user" appearance="dark" />);
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(await screen.findByText('共 18 条记录')).toBeTruthy();
});

it('creates a real 2400 yuan order and preserves it when changing role', async () => {
  render(<OrderWorkbench />);
  fireEvent.click(await screen.findByRole('button', { name: '创建订单' }));
  const dialog = await screen.findByRole('dialog', { name: '创建销售订单' });
  expect(within(dialog).getByLabelText('订单合计').textContent).toContain(
    '2,400',
  );
  fireEvent.click(within(dialog).getByRole('button', { name: '确认创建' }));
  const details = await screen.findByRole('dialog', {
    name: '订单详情 SO-202609-1019',
  });
  fireEvent.click(within(details).getByRole('button', { name: '关闭详情' }));
  fireEvent.change(screen.getByLabelText('演示岗位'), {
    target: { value: 'manager' },
  });
  expect(await screen.findByText('SO-202609-1019')).toBeTruthy();
});

it('keeps the form usable when either required date is cleared and re-entered', async () => {
  render(<OrderWorkbench />);
  fireEvent.click(await screen.findByRole('button', { name: '创建订单' }));
  const dialog = await screen.findByRole('dialog', { name: '创建销售订单' });
  for (const name of ['承诺交期', '应收到期日']) {
    const input = within(dialog).getByLabelText(name) as HTMLInputElement;
    fireEvent.change(input, { target: { value: '' } });
    expect(input.value).toBe('');
    fireEvent.change(input, { target: { value: '2026-09-20' } });
    expect(input.value).toBe('2026-09-20');
  }
  fireEvent.click(within(dialog).getByRole('button', { name: '确认创建' }));
  expect(
    await screen.findByRole('dialog', { name: '订单详情 SO-202609-1019' }),
  ).toBeTruthy();
});

it('keeps the order open when handing submission to its next role', async () => {
  render(<OrderWorkbench stage="review" initialRole="sales" />);
  fireEvent.click(
    await screen.findByRole('button', { name: '查看订单 SO-202609-1014' }),
  );
  let dialog = await screen.findByRole('dialog', {
    name: '订单详情 SO-202609-1014',
  });
  fireEvent.click(
    within(dialog).getByRole('button', { name: '提交审核', exact: true }),
  );
  fireEvent.click(await screen.findByRole('button', { name: '确认提交审核' }));
  dialog = await screen.findByRole('dialog', {
    name: '订单详情 SO-202609-1014',
  });
  const handoff = await within(dialog).findByRole('button', {
    name: '交给销售主管',
  });
  await waitFor(() =>
    expect((handoff as HTMLButtonElement).disabled).toBe(false),
  );
  fireEvent.click(handoff);
  expect(
    await within(dialog).findByRole('button', {
      name: '审核通过',
      exact: true,
    }),
  ).toBeTruthy();
  expect(
    (within(dialog).getByLabelText('处理岗位') as HTMLSelectElement).value,
  ).toBe('manager');
});

it('exposes a failed refresh and its retry inside the order dialog without replaying money', async () => {
  render(
    <OrderWorkbench
      stage="release"
      initialRole="finance"
      failRefreshAfterWrite
    />,
  );
  fireEvent.click(
    await screen.findByRole('button', { name: '查看订单 SO-202609-1003' }),
  );
  let dialog = await screen.findByRole('dialog', {
    name: '订单详情 SO-202609-1003',
  });
  fireEvent.click(
    within(dialog).getByRole('button', { name: '登记收款', exact: true }),
  );
  fireEvent.change(await screen.findByRole('textbox', { name: '凭证号' }), {
    target: { value: 'RETRY-IN-DIALOG' },
  });
  fireEvent.click(screen.getByRole('button', { name: '确认登记收款' }));
  dialog = await screen.findByRole('dialog', {
    name: '订单详情 SO-202609-1003',
  });
  await within(dialog).findByRole('button', { name: '重试刷新' });
  expect(within(dialog).getByRole('alert').textContent).toContain('操作已完成');
  fireEvent.click(within(dialog).getByRole('button', { name: '关闭详情' }));
  expect(
    (screen.getByLabelText('演示岗位') as HTMLSelectElement).disabled,
  ).toBe(true);
  fireEvent.click(
    await screen.findByRole('button', { name: '查看订单 SO-202609-1003' }),
  );
  dialog = await screen.findByRole('dialog', {
    name: '订单详情 SO-202609-1003',
  });
  fireEvent.click(within(dialog).getByRole('button', { name: '重试刷新' }));
  fireEvent.click(within(dialog).getByText('收付款与票据记录'));
  expect(await within(dialog).findAllByText(/RETRY-IN-DIALOG/)).toHaveLength(1);
});
