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
import { expect, userEvent, waitFor, within } from 'storybook/test';
import displayMeta, {
  Minimal as DisplayMinimal,
} from './QuickStart.stories.js';
import type { StoryObj as RegressionStoryObj } from '@storybook/react-vite';

const meta = {
  ...displayMeta,
  id: 'view-engine-扩展接入-最小接入-回归',
  title: 'View Engine/入门与业务流程/最小接入/回归',
  tags: ['!dev', '!autodocs', 'test'],
};

export default meta;

type Story = RegressionStoryObj<typeof displayMeta>;

export const Minimal: Story = {
  ...DisplayMinimal,
  tags: ['!dev', '!autodocs', 'test'],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(await canvas.findByText('SO-202609-1001')).toBeVisible();
    await expect(canvas.getByText('SO-202609-1002')).toBeVisible();
    await userEvent.click(canvas.getByRole('button', { name: '下一页' }));
    await expect(await canvas.findByText('SO-202609-1003')).toBeVisible();
    const amount = canvas.getByRole('textbox', { name: '金额值' });
    await userEvent.clear(amount);
    await userEvent.type(amount, '10000');
    await expect(canvas.getByText('SO-202609-1003')).toBeVisible();
    await userEvent.keyboard('{Enter}');
    await expect(await canvas.findByText('SO-202609-1001')).toBeVisible();
    await waitFor(() =>
      expect(canvas.queryByText('SO-202609-1003')).toBeNull(),
    );
    await userEvent.click(
      canvas.getByRole('button', { name: /清空条件值：金额/ }),
    );
    await expect(await canvas.findByText('SO-202609-1001')).toBeVisible();
    await userEvent.click(canvas.getByRole('button', { name: /金额排序/ }));
    await expect(await canvas.findByText('SO-202609-1003')).toBeVisible();
    await expect(canvas.queryByText('SO-202609-1001')).toBeNull();
  },
};
