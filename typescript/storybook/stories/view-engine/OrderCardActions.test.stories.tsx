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

import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, userEvent, within } from 'storybook/test';
import { OrderWorkbench } from '../../packages/view-engine/examples/react/sales-order/OrderWorkbench.js';
import { playFailureAndScope } from './libraryDelivery.play.js';
const meta = {
  title: 'View Engine/订单卡片/业务回归',
  component: OrderWorkbench,
  args: { layout: 'card' },
  tags: ['!dev', '!autodocs', 'test'],
} satisfies Meta<typeof OrderWorkbench>;
export default meta;
export const BusinessActions: StoryObj<typeof meta> = {
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    const cards = await canvas.findByRole('list', { name: '记录卡片' });
    await userEvent.click(
      await within(cards).findByRole('button', {
        name: '查看订单 SO-202609-1018',
      }),
    );
    const dialog = await page.findByRole('dialog', {
      name: '订单详情 SO-202609-1018',
    });
    await expect(within(dialog).getByLabelText('订单状态')).toBeVisible();
    await userEvent.click(page.getByRole('button', { name: '关闭详情' }));
    await userEvent.click(canvas.getByRole('button', { name: '创建订单' }));
    await userEvent.click(
      await page.findByRole('button', { name: '确认创建' }),
    );
    await expect(
      await page.findByRole('dialog', { name: '订单详情 SO-202609-1019' }),
    ).toBeVisible();
  },
};
export const RetryBusinessAction: StoryObj<typeof meta> = {
  args: { failFirstQuery: true, failFirstWrite: true },
  play: playFailureAndScope,
};
