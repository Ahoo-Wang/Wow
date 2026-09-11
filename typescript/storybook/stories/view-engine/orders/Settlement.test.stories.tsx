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
import { expect, userEvent, within } from 'storybook/test';
import displayMeta, {
  Workbench,
  RefreshRecovery,
} from './Settlement.stories.js';
import { orderJourney } from './lifecycle.play.js';
const meta = {
  ...displayMeta,
  id: 'view-engine-对账与结算-回归',
  title: 'View Engine/入门与业务流程/订单流程/对账与结算/回归',
  tags: ['!dev', '!autodocs', 'test'],
};
export default meta;
export const RefundAndCredit: StoryObj<typeof meta> = {
  ...Workbench,
  play: async ({ canvasElement }) => {
    const { page, open, action, reference, role } = orderJourney(canvasElement);
    await open('SO-202609-1005');
    await action('登记退款', () => reference('REF-1200'));
    await action('登记冲减', () => reference('CREDIT-1200'));
    await action('结算核对');
    await role('manager', 'SO-202609-1005');
    await action('关闭订单');
    await expect(
      within(page.getByRole('dialog')).getByText('已关闭', { exact: true }),
    ).toBeVisible();
  },
};
export const ReadRetry: StoryObj<typeof meta> = {
  ...RefreshRecovery,
  play: async ({ canvasElement }) => {
    const { page, open, action, reference } = orderJourney(canvasElement);
    await open('SO-202609-1003');
    await action('登记收款', () => reference('BANK-ONCE'));
    await expect(
      await within(page.getByRole('dialog')).findByRole('button', {
        name: '重试刷新',
      }),
    ).toBeVisible();
    await userEvent.click(
      within(page.getByRole('dialog')).getByRole('button', {
        name: '重试刷新',
      }),
    );
    await userEvent.click(
      within(page.getByRole('dialog')).getByText('收付款与票据记录'),
    );
    await expect(page.getAllByText(/BANK-ONCE/)).toHaveLength(1);
  },
};
