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
import displayMeta, {
  Workbench,
  ReturnFromReceipt,
} from './Aftersales.stories.js';
import { orderJourney } from './lifecycle.play.js';
const meta = {
  ...displayMeta,
  id: 'view-engine-售后与关闭-回归',
  title: 'View Engine/入门与业务流程/订单流程/售后与关闭/回归',
  tags: ['!dev', '!autodocs', 'test'],
};
export default meta;
export const SheetNavigation: StoryObj<typeof meta> = {
  ...Workbench,
  play: async ({ canvasElement }) => {
    const { canvas, page, open } = orderJourney(canvasElement);
    const dialog = await open('SO-202609-1005');
    const window = canvasElement.ownerDocument.defaultView!;
    await waitFor(() => {
      const bounds = dialog.getBoundingClientRect();
      expect(bounds.top).toBe(0);
      expect(bounds.right).toBe(window.innerWidth);
      expect(bounds.height).toBe(window.innerHeight);
    });
    await userEvent.click(
      within(dialog).getByRole('button', {
        name: '登记退款',
        exact: true,
      }),
    );
    await userEvent.click(page.getByRole('button', { name: '返回详情' }));
    await expect(page.getByRole('dialog')).toBe(dialog);
    await expect(dialog).toHaveAccessibleName('订单详情 SO-202609-1005');
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(page.queryByRole('dialog')).not.toBeInTheDocument(),
    );
    await expect(
      canvas.getByRole('button', {
        name: '查看订单 SO-202609-1005',
      }),
    ).toHaveFocus();
  },
};
export const ReturnToClosure: StoryObj<typeof meta> = {
  ...ReturnFromReceipt,
  play: async ({ canvasElement }) => {
    const { page, open, action, role, reference } = orderJourney(canvasElement);
    const id = 'SO-202609-1017';
    await open(id);
    await action('申请退货', async () => {
      const input = page.getByRole('spinbutton', {
        name: '本次数量 办公显示器',
      });
      await userEvent.clear(input);
      await userEvent.type(input, '1');
    });
    await action('审核退货');
    await role('delivery', id);
    await action('退货入库');
    await role('finance', id);
    await action('登记退款', () => reference('REF-RETURN'));
    await action('登记冲减', () => reference('CREDIT-RETURN'));
    await action('结算核对');
    await role('manager', id);
    await action('关闭订单');
    await expect(
      within(page.getByRole('dialog')).getByLabelText('订单状态'),
    ).toHaveTextContent('已关闭');
  },
};

export const PublicClosure: StoryObj<typeof meta> = {
  ...Workbench,
  play: async ({ canvasElement }) => {
    const { canvas, page, open, action, reference, role } =
      orderJourney(canvasElement);
    await open('SO-202609-1005');
    await action('登记退款', () => reference('PUBLIC-REFUND'));
    await action('登记冲减', () => reference('PUBLIC-CREDIT'));
    await action('结算核对');
    // Verify the public queue itself, not only an already-open detail.
    await userEvent.click(page.getByRole('button', { name: '关闭详情' }));
    await expect(
      await canvas.findByRole('button', { name: '查看订单 SO-202609-1005' }),
    ).toBeVisible();
    await open('SO-202609-1005');
    await role('manager', 'SO-202609-1005');
    await action('关闭订单');
    await expect(
      within(page.getByRole('dialog')).getByLabelText('订单状态'),
    ).toHaveTextContent('已关闭');
  },
};
