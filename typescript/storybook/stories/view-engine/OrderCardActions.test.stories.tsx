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
import { OrderExample } from '../../packages/view-engine/examples/react/OrderExample.js';
const meta = {
  title: 'View Engine/订单卡片/业务回归',
  component: OrderExample,
  args: { layout: 'card' },
  tags: ['!dev', '!autodocs', 'test'],
} satisfies Meta<typeof OrderExample>;
export default meta;
type Story = StoryObj<typeof meta>;
const Cards = {};
export const BusinessActions: Story = {
  ...Cards,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const cards = await canvas.findByRole('list', { name: '记录卡片' });
    await userEvent.click(
      await within(cards).findByRole('button', { name: '查看订单 DEMO-1' }),
    );
    const page = within(canvasElement.ownerDocument.body);
    await expect(
      await page.findByRole('dialog', { name: '订单详情 DEMO-1' }),
    ).toBeVisible();
    await userEvent.keyboard('{Escape}');
    await userEvent.click(
      within(cards).getByRole('button', { name: '处理订单 DEMO-1' }),
    );
    await expect(await canvas.findByText('已处理订单 DEMO-1')).toBeVisible();
    await expect(
      within(cards).queryByRole('button', { name: '处理订单 DEMO-1' }),
    ).not.toBeInTheDocument();
    await userEvent.click(
      await canvas.findByRole('button', { name: '选择记录 DEMO-2' }),
    );
    await userEvent.click(
      canvas.getByRole('button', { name: '批量处理', exact: true }),
    );
    await expect(await canvas.findByText('已处理 1 笔订单')).toBeVisible();
    await expect(
      await canvas.findByText('暂无数据', { exact: true }),
    ).toBeVisible();
    await userEvent.click(
      canvas.getByRole('button', { name: '创建订单', exact: true }),
    );
    await expect(
      await canvas.findByRole('button', { name: '处理订单 DEMO-4' }),
    ).toBeVisible();
  },
};
export const RetryBusinessAction: Story = {
  ...Cards,
  args: { failFirstWrite: true },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(
      await canvas.findByRole('button', { name: '处理订单 DEMO-1' }),
    );
    await expect(await canvas.findByRole('alert')).toHaveTextContent(
      '订单处理暂时失败',
    );
    await userEvent.click(canvas.getByRole('button', { name: '重试订单操作' }));
    await expect(await canvas.findByText('已处理订单 DEMO-1')).toBeVisible();
  },
};
