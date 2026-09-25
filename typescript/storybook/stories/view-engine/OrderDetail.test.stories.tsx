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
import displayMeta, {
  OrderDetailPage as DisplayOrder,
} from './OrderDetail.stories.js';
import { readColumn, readTotal } from './readTable.js';

/**
 * 订单详情页, as a lightweight twin (docs/scenarios.md 6.1): it draws without a
 * panel refusing its view, and shows what 4.1 says it shows.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/业务场景/订单详情页/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread (see the README).
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/**
 * The order page: one of A7's stuck orders — two lines adding up to what
 * was paid, and an event stream that stops at 付款, never 包裹发出.
 */
export const OrderDetail: Story = {
  ...DisplayOrder,
  name: '订单详情页',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      canvas.getByRole('heading', { level: 1, name: /TO2026091900032/ }),
    ).toBeVisible();
    const tables = await waitFor(
      () => {
        const found = [
          ...canvasElement.querySelectorAll<HTMLTableElement>('table'),
        ].filter(
          table => table.closest('[data-slot="chart-reading"]') === null,
        );
        expect(found).toHaveLength(2);
        return found;
      },
      { timeout: 10_000 },
    );
    const [lines, history] = tables;
    await waitFor(() =>
      expect(readColumn(lines, '商品')).toEqual([
        '天然乳胶枕 标准款',
        '静电除尘掸 单只装',
      ]),
    );
    await expect(readTotal(lines, '实付')).toContain('¥226.88');
    await waitFor(() =>
      expect(readColumn(history, '版本')).toEqual(['1', '2']),
    );
    await expect(readColumn(history, '事件')).toEqual(['下单', '付款成功']);
  },
};
