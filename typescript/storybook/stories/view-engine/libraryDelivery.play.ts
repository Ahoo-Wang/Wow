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

import type { StoryObj } from '@storybook/react-vite';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import type { OrderWorkbench } from '../../packages/view-engine/examples/react/sales-order/OrderWorkbench.js';
type Play = NonNullable<StoryObj<typeof OrderWorkbench>['play']>;
export const playFailureAndScope: Play = async ({ canvasElement }) => {
  const canvas = within(canvasElement),
    page = within(canvasElement.ownerDocument.body);
  await userEvent.click(
    await canvas.findByRole('button', { name: '重试查询' }),
  );
  await userEvent.click(
    await canvas.findByRole('button', { name: '创建订单' }),
  );
  await userEvent.click(await page.findByRole('button', { name: '确认创建' }));
  await expect(await page.findByRole('alert')).toHaveTextContent(
    '业务服务暂时不可用',
  );
  await userEvent.click(page.getByRole('button', { name: '确认创建' }));
  await expect(
    await page.findByRole('dialog', { name: '订单详情 SO-202609-1019' }),
  ).toBeVisible();
};
export const playRefreshRecovery: Play = async ({ canvasElement }) => {
  const canvas = within(canvasElement),
    page = within(canvasElement.ownerDocument.body);
  await userEvent.click(
    await canvas.findByRole('button', { name: '创建订单' }),
  );
  await userEvent.click(await page.findByRole('button', { name: '确认创建' }));
  await page.findByRole('dialog', { name: '订单详情 SO-202609-1019' });
  await waitFor(() =>
    expect(page.getByRole('button', { name: '关闭详情' })).toBeEnabled(),
  );
  await userEvent.click(page.getByRole('button', { name: '关闭详情' }));
  await userEvent.click(
    await canvas.findByRole('button', { name: '重试刷新' }),
  );
  await expect(await canvas.findByText('共 19 条记录')).toBeVisible();
  await expect(canvas.queryByText('SO-202609-1020')).not.toBeInTheDocument();
};
export const playNarrowDark: Play = async ({ canvasElement }) => {
  const canvas = within(canvasElement),
    page = within(canvasElement.ownerDocument.body);
  await canvas.findByRole('table');
  const root = canvas.getByTestId('library-delivery-container');
  await expect(root.scrollWidth).toBeLessThanOrEqual(root.clientWidth + 1);
  await userEvent.click(canvas.getByRole('button', { name: '创建订单' }));
  const dialog = await page.findByRole('dialog', { name: '创建销售订单' });
  await expect(dialog.getBoundingClientRect().width).toBeLessThanOrEqual(
    window.innerWidth,
  );
  await userEvent.keyboard('{Escape}');
  await expect(page.queryByRole('dialog')).not.toBeInTheDocument();
};
