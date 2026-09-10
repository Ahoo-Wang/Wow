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
import displayMeta from './Release.stories.js';
const meta = {
  ...displayMeta,
  id: 'view-engine-收款与放行-回归',
  title: 'View Engine/订单业务流程/收款与放行/回归',
  tags: ['!dev', '!autodocs', 'test'],
};
export default meta;
export const AtomicBatch: StoryObj<typeof meta> = {
  args: { initialRole: 'manager' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    for (const id of ['1002', '1008'])
      await userEvent.click(
        await canvas.findByRole('checkbox', {
          name: `选择记录 SO-202609-${id}`,
        }),
      );
    await userEvent.click(canvas.getByRole('button', { name: '批量放行' }));
    await expect(await canvas.findByRole('alert')).toHaveTextContent('逾期');
    await expect(
      canvas.getByRole('button', { name: '查看订单 SO-202609-1002' }),
    ).toBeVisible();
    await userEvent.click(
      canvas.getByRole('checkbox', { name: '选择记录 SO-202609-1008' }),
    );
    await userEvent.click(canvas.getByRole('button', { name: '批量放行' }));
    await waitFor(() =>
      expect(
        canvas.queryByRole('button', { name: '查看订单 SO-202609-1002' }),
      ).not.toBeInTheDocument(),
    );
  },
};
