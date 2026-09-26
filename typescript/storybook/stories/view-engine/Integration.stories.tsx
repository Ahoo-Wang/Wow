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
import { expect, waitFor, within } from 'storybook/test';
import { rowSource } from './rowSource.js';
import { OrdersPage } from './integration/OrdersPage.js';
import ordersPageSource from './integration/OrdersPage.tsx?raw';
import { readable } from './hostSource.js';
import { SAMPLE_ORDERS, SAMPLE_TO_SHIP } from './integration/sampleOrders.js';

const idOf = (order: (typeof SAMPLE_ORDERS)[number]) =>
  String(order.aggregateId);
const statusOf = (order: (typeof SAMPLE_ORDERS)[number]) =>
  (order.state as { status: string }).status;

/*
 * The integration walkthrough's example (`Integration.mdx`): the code the
 * page quotes is the code that runs here, file for file, so it compiles and
 * works or the build says so.
 */

const meta = {
  title: 'View Engine/接入导览',
  // The page is `Integration.mdx`, attached to this file.
  tags: ['!autodocs'],
  parameters: {
    layout: 'fullscreen',
    docs: { source: { code: readable(ordersPageSource), language: 'tsx' } },
  },
} satisfies Meta;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 接到示例数据的订单页：`OrdersPage` 原样挂上，数据源换成内存里的十二张单
 * （宿主接的是 `wowOrderSource`）。打开是系统视图「待发货」。
 */
export const Example: Story = {
  name: '订单页',
  render: () => <OrdersPage source={rowSource(SAMPLE_ORDERS)} />,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const paid = SAMPLE_ORDERS.filter(order => statusOf(order) === 'PAID');
    await expect(paid).toHaveLength(SAMPLE_TO_SHIP);
    // The system view opens with its rows: the paid orders, and no other.
    await waitFor(() => expect(canvas.getByText(idOf(paid[0]!))).toBeVisible());
    for (const order of SAMPLE_ORDERS)
      if (statusOf(order) === 'PAID')
        await expect(canvas.getByText(idOf(order))).toBeInTheDocument();
      else await expect(canvas.queryByText(idOf(order))).toBeNull();
  },
};
