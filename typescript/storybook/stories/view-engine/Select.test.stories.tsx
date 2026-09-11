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
import '@ahoo-wang/fetcher-view-engine/styles.css';
import displayMeta, {
  Clearable as DisplayClearable,
} from './Select.stories.js';
import type { DemoArgs } from './Select.stories.js';
import type { StoryObj as RegressionStoryObj } from '@storybook/react-vite';

const meta = {
  ...displayMeta,
  id: 'view-engine-专项场景-基础组件-select-回归',
  title: 'View Engine/扩展与组件/Select/回归',
  tags: ['!dev', '!autodocs', 'test'],
};

export default meta;

type Story = RegressionStoryObj<DemoArgs>;

export const Clearable: Story = {
  ...DisplayClearable,
  tags: ['!dev', '!autodocs', 'test'],
  play: async ({ canvasElement, args }) => {
    if (args.disabled) return;
    const canvas = within(canvasElement);
    const page = within(canvasElement.ownerDocument.body);
    const trigger = canvas.getByRole('combobox', { name: '订单状态值' });
    async function select(label: string) {
      await userEvent.click(trigger);
      const popup = (await page.findByRole('listbox')).closest(
        '[data-slot="select-content"]',
      );
      await waitFor(() =>
        expect(popup).not.toHaveAttribute('data-starting-style'),
      );
      await userEvent.click(await page.findByRole('option', { name: label }));
      await expect(trigger).toHaveAttribute('aria-expanded', 'false');
      // Base UI keeps hidden items mounted for closed-trigger keyboard search.
      await waitFor(() =>
        expect(page.queryByRole('listbox')).not.toBeInTheDocument(),
      );
    }
    await select('清空选择');
    await expect(canvas.getByLabelText('当前值')).toHaveTextContent('未设置值');
    await expect(trigger).toHaveTextContent('不限');
    await select('待处理');
    await expect(canvas.getByLabelText('当前值')).toHaveTextContent('待处理');
  },
};
