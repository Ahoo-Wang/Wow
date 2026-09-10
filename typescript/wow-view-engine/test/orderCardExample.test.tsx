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

import { afterEach, it, expect } from 'vitest';
import {
  render,
  screen,
  fireEvent,
  cleanup,
  within,
  waitFor,
} from '@testing-library/react';
import { OrderWorkbench } from '../examples/react/sales-order/OrderWorkbench.js';
afterEach(cleanup);
it('opens a business order from cards and creates an order with real quantities', async () => {
  render(<OrderWorkbench layout="card" />);
  const cards = await screen.findByRole('list', { name: '记录卡片' });
  fireEvent.click(
    await within(cards).findByRole('button', {
      name: '查看订单 SO-202609-1018',
    }),
  );
  const dialog = await screen.findByRole('dialog', {
    name: '订单详情 SO-202609-1018',
  });
  expect(within(dialog).getByLabelText('订单状态')).toBeTruthy();
  fireEvent.click(within(dialog).getByRole('button', { name: '关闭详情' }));
  fireEvent.click(screen.getByRole('button', { name: '创建订单' }));
  fireEvent.click(await screen.findByRole('button', { name: '确认创建' }));
  expect(
    await screen.findByRole('dialog', { name: '订单详情 SO-202609-1019' }),
  ).toBeTruthy();
  const close = screen.getByRole('button', {
    name: '关闭详情',
  }) as HTMLButtonElement;
  await waitFor(() => expect(close.disabled).toBe(false));
  fireEvent.click(close);
  expect(
    await within(
      await screen.findByRole('list', { name: '记录卡片' }),
    ).findByRole('button', { name: '查看订单 SO-202609-1019' }),
  ).toBeTruthy();
});
