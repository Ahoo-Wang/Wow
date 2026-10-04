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

import { useMemo, useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import { memorySource } from '@ahoo-wang/wow-view-engine/testing';
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import { StoryEngine } from './StoryEngine.js';
import { createOrdersEngine } from './integration/ordersEngine.js';
import { OrdersHost } from './integration/OrdersHost.js';
import { OrdersPage } from './integration/OrdersPage.js';
import { useMemoryRouter } from './integration/memoryRouter.js';
import {
  SAMPLE_ORDERS,
  SAMPLE_TO_SHIP,
  sampleCommands,
  sampleOrders,
} from './integration/sampleOrders.js';
import ordersPageSource from './integration/OrdersPage.tsx?raw';
import { readable } from './hostSource.js';

const idOf = (order: (typeof SAMPLE_ORDERS)[number]) =>
  String(order.aggregateId);
const statusOf = (order: (typeof SAMPLE_ORDERS)[number]) =>
  (order.state as { status: string }).status;

/*
 * The integration walkthrough's example (`Integration.mdx`): the code the
 * page quotes is the code that runs here, file for file, so it compiles and
 * works or the build says so. Only the service is swapped: the rows are
 * twelve orders in memory (`memorySource`, the package's own in-memory Wow
 * source) and the commands change them there; the address is a router in
 * memory, since the example lives inside Storybook's frame.
 */
function OrdersExample() {
  const [orders] = useState(sampleOrders);
  const commands = useMemo(() => sampleCommands(orders), [orders]);
  const router = useMemoryRouter('/orders');
  return (
    <StoryEngine create={() => createOrdersEngine(memorySource(orders))}>
      {engine => (
        <OrdersHost
          engine={engine}
          router={router}
          commands={commands}
          // Storybook's toolbar paints light and dark on `<html>`.
          colorMode="host"
        >
          <OrdersPage go={router.go} />
        </OrdersHost>
      )}
    </StoryEngine>
  );
}

const meta = {
  title: 'View Engine/接入导览',
  component: OrdersExample,
  // The page is `Integration.mdx`, attached to this file.
  tags: ['!autodocs'],
  parameters: {
    layout: 'fullscreen',
    docs: { source: { code: readable(ordersPageSource), language: 'tsx' } },
  },
} satisfies Meta<typeof OrdersExample>;

export default meta;

type Story = StoryObj<typeof meta>;

/** The row of an order, by its checkbox. */
function rowOf(canvas: ReturnType<typeof within>, id: string) {
  return within(
    canvas
      .getByLabelText(zhCN['label.record.select'].replace('{key}', id))
      .closest('tr')!,
  );
}

/**
 * 接到示例数据的订单页：`OrdersHost` 与 `OrdersPage` 原样挂上，数据源换成
 * 内存里的十二张单，命令改的也是它们。打开是系统视图「待发货」。
 */
export const Example: Story = {
  name: '订单页',
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

    // The navigation is the engine's data: the resource and its system
    // views, in the definition's words.
    const nav = within(canvas.getByRole('navigation', { name: '应用导航' }));
    await expect(nav.getByRole('link', { name: '订单' })).toHaveAttribute(
      'aria-current',
      'page',
    );

    // The declared action: 「发货」 in the row asks first (shipping is not
    // taken back), and once confirmed the order leaves 「待发货」.
    const first = idOf(paid[0]!);
    await userEvent.click(
      rowOf(canvas, first).getByRole('button', { name: '发货' }),
    );
    const question = within(
      await within(document.body).findByRole('dialog', {
        name: '发出 1 张订单？',
      }),
    );
    await userEvent.click(question.getByRole('button', { name: '发货' }));
    await waitFor(() => expect(canvas.queryByText(first)).toBeNull());

    // Through the router: 「全部订单」 opens in `?view=`, and is current.
    const all = nav.getByRole('link', { name: '全部订单' });
    await userEvent.click(all);
    await waitFor(() => expect(all).toHaveAttribute('aria-current', 'page'));
    await waitFor(() =>
      expect(canvas.getByText(idOf(SAMPLE_ORDERS[1]!))).toBeVisible(),
    );
    for (const order of SAMPLE_ORDERS)
      await expect(canvas.getByText(idOf(order))).toBeInTheDocument();
    await expect(rowOf(canvas, first).getByText('已发货')).toBeVisible();
  },
};
