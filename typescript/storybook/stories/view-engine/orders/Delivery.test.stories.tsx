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
import displayMeta, { Workbench } from './Delivery.stories.js';
import { orderJourney } from './lifecycle.play.js';
const meta = {
  ...displayMeta,
  id: 'view-engine-备货与交付-回归',
  title: 'View Engine/入门与业务流程/订单流程/备货与交付/回归',
  tags: ['!dev', '!autodocs', 'test'],
};
export default meta;
export const PartialShipment: StoryObj<typeof meta> = {
  ...Workbench,
  play: async ({ canvasElement }) => {
    const { page, open, action } = orderJourney(canvasElement);
    await open('SO-202609-1001');
    await action('登记发货', async () => {
      await userEvent.type(
        page.getByRole('textbox', { name: '运单号' }),
        'SF-PARTIAL',
      );
    });
    const row = within(page.getByRole('dialog')).getByRole('row', {
      name: /办公显示器/,
    });
    await expect(row).toHaveTextContent('4');
    await action('签收与拒收');
    await expect(
      within(page.getByRole('dialog')).getByRole('row', { name: /办公显示器/ }),
    ).toHaveTextContent('6');
  },
};
export const RejectedShipment: StoryObj<typeof meta> = {
  ...Workbench,
  play: async ({ canvasElement }) => {
    const { page, open, action } = orderJourney(canvasElement);
    await open('SO-202609-1007');
    await action('拒收回仓');
    await action('登记备货');
    await action('登记发货', async () => {
      await userEvent.type(
        page.getByRole('textbox', { name: '运单号' }),
        'SF-REPLACE',
      );
    });
    await action('签收与拒收');
    await expect(
      within(
        within(page.getByRole('dialog')).getByRole('row', {
          name: /办公显示器/,
        }),
      )
        .getAllByRole('cell')
        .map(c => c.textContent),
    ).toEqual(['办公显示器', '5', '¥1,200.00', '0', '0', '5', '0', '0']);
  },
};
