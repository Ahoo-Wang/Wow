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
  AllPanels as DisplayAllPanels,
  EmptyDashboard as DisplayEmptyDashboard,
  GlobalFilter as DisplayGlobalFilter,
  PanelUnavailable as DisplayPanelUnavailable,
  QueryFailed as DisplayQueryFailed,
} from './Dashboard.stories.js';
import { amountOf, readColumn, readTotal } from './readTable.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/仪表盘视图/Dashboard/回归',
  tags: ['!dev', '!autodocs', 'test'],
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

const bars = (canvas: HTMLElement) =>
  canvas.querySelectorAll('.recharts-bar-rectangle');

export const AllPanels: Story = {
  ...DisplayAllPanels,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    // The record panel runs its own view: pending orders, largest first.
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual([
        'SO-1003',
        'SO-1005',
        'SO-1001',
        'SO-1006',
      ]),
    );
    await expect(amountOf(readTotal(table, '金额'))).toBe(6470);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
    await expect(
      canvas.getByRole('link', { name: '出库异常处理' }),
    ).toBeVisible();
  },
};

export const GlobalFilter: Story = {
  ...DisplayGlobalFilter,
  play: async ({ canvasElement }) => {
    const table = await within(canvasElement).findByRole('table');
    // 华南 reaches both panels through their own warehouse field.
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(['SO-1005']),
    );
    await expect(amountOf(readTotal(table, '金额'))).toBe(1760);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(1));
  },
};

export const PanelUnavailable: Story = {
  ...DisplayPanelUnavailable,
  play: async ({ canvasElement }) => {
    await expect(
      await within(canvasElement).findByText(
        defaultMessages['label.panel.unavailable'],
      ),
    ).toBeVisible();
    // The other data panel is not taken down with it.
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
  },
};

export const QueryFailed: Story = {
  ...DisplayQueryFailed,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await waitFor(() =>
      expect(
        canvas.getAllByText(defaultMessages['label.query.failed']),
      ).toHaveLength(2),
    );
    await expect(
      canvas.getByRole('link', { name: '出库异常处理' }),
    ).toBeVisible();
  },
};

export const EmptyDashboard: Story = {
  ...DisplayEmptyDashboard,
  play: async ({ canvasElement }) => {
    await expect(
      await within(canvasElement).findByText(
        defaultMessages['label.dashboard.empty'],
      ),
    ).toBeVisible();
  },
};
