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
import { expect, screen, waitFor, within } from 'storybook/test';
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  MemberDetailPage as DisplayMember,
} from './MemberDetail.stories.js';
import { findDataTable, readColumn } from './readTable.js';

/**
 * 会员详情页, as a lightweight twin (docs/scenarios.md 6.1): it draws without a
 * panel refusing its view, and shows what 4.1 says it shows.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/业务场景/会员详情页/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread (see the README).
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/**
 * The member page: 买家 locked to 韩* (M101795) and read as that, this
 * year's orders counted under it, and nothing on the board that builds.
 */
export const MemberDetail: Story = {
  ...DisplayMember,
  name: '会员详情页',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const buyer = await canvas.findByRole('group', {
      name: zhCN['label.embed.locked-name'].replace('{filter}', '买家'),
    });
    await expect(buyer).toHaveTextContent('韩*（M101795）');
    // Locked: read as its value, nothing to change it with.
    await expect(within(buyer).queryByRole('combobox')).toBeNull();
    const orders = screen.getByRole('group', { name: '订单数（单）' });
    await waitFor(
      () =>
        expect(
          orders.querySelector('[data-slot="metric-value"]'),
        ).toHaveTextContent('238'),
      { timeout: 10_000 },
    );
    const table = await findDataTable(
      screen.getByRole('group', { name: '他的订单' }),
    );
    await waitFor(() =>
      expect(readColumn(table, '订单号')[0]).toBe('TO2026092200001'),
    );
    await expect(
      canvas.queryByRole('button', { name: zhCN['label.dashboard.edit'] }),
    ).toBeNull();
  },
};
