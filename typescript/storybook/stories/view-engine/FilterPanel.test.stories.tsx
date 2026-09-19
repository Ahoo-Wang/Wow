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
import { expect, waitFor, within } from 'storybook/test';
import { defaultMessages } from '@ahoo-wang/fetcher-view-engine/ui';
import displayMeta, {
  Advanced as DisplayAdvanced,
  Simple as DisplaySimple,
} from './FilterPanel.stories.js';
import { amountOf, readColumn, readTotal } from './readTable.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/数据视图/筛选编辑器/回归',
  tags: ['!dev', '!autodocs', 'test'],
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** Pending and at least 100: every pending order, in the order they came. */
export const Simple: Story = {
  ...DisplaySimple,
  play: async ({ canvasElement }) => {
    const table = await within(canvasElement).findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual([
        'SO-1001',
        'SO-1003',
        'SO-1005',
        'SO-1006',
      ]),
    );
    await expect(amountOf(readTotal(table, '金额'))).toBe(6470);
  },
};

/**
 * The rich tree asks for 华东 and, in its OR group, for 华北 or more than
 * 20,000 at once: no order is both, and the table says so.
 */
export const Advanced: Story = {
  ...DisplayAdvanced,
  play: async ({ canvasElement }) => {
    await expect(
      await within(canvasElement).findByText(
        defaultMessages['label.record.empty-hint'],
      ),
    ).toBeVisible();
  },
};
