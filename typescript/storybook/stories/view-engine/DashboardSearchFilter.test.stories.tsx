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
import type { ComponentType } from 'react';
import type { StoryObj } from '@storybook/react-vite';
import { expect, screen, userEvent, waitFor, within } from 'storybook/test';
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  BuildASearch as DisplayBuild,
  SearchInAnEmbed as DisplayEmbed,
} from './DashboardSearchFilter.stories.js';
import { findDataTable, readColumn } from './readTable.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/仪表盘视图/Dashboard/板上的搜索/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out: a file's own description would otherwise replace the
  // display meta's parameters, and the host application with them.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** A desk: below `md` the board is one column that nothing is wired in. */
const DESK = (Story: ComponentType) => (
  <div style={{ width: 1280 }}>
    <Story />
  </div>
);

const label = (key: keyof typeof zhCN, params: Record<string, string> = {}) =>
  Object.entries(params).reduce<string>(
    (text, [name, value]) => text.replace(`{${name}}`, value),
    zhCN[key],
  );

/** The 「不受…影响」 badge on the panel titled so, if it wears one. */
function badgeOn(canvasElement: HTMLElement, title: string): string | null {
  const heading = [
    ...canvasElement.querySelectorAll<HTMLElement>('[data-slot="panel-title"]'),
  ].find(entry => entry.textContent === title);
  return (
    heading
      ?.closest('[data-slot="dashboard-panel"]')
      ?.querySelector('[data-slot="panel-not-reached"]')?.textContent ?? null
  );
}

/** The order numbers the pending list shows. */
async function pendingOrders(canvasElement: HTMLElement): Promise<string[]> {
  const body = await within(canvasElement).findByRole('group', {
    name: '待出库明细',
  });
  return readColumn(await findDataTable(body), '订单号');
}

/**
 * 「添加筛选」 → 搜索: its box says 「搜索…」 while empty; wired on the
 * pending list, the other list follows on its own and the chart has
 * nothing to take it; typing an order number runs the lists alone.
 */
export const AddSearchWiresTheLists: Story = {
  ...DisplayBuild,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('heading', { level: 3, name: '按仓库汇总' });
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.dashboard.edit'] }),
    );
    await userEvent.click(
      await canvas.findByRole('button', { name: zhCN['label.filters.add'] }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', {
        name: zhCN['label.filters.type.search'],
      }),
    );
    const search = zhCN['label.filters.type.search'];
    const settings = await screen.findByRole('dialog', {
      name: label('label.filters.settings-of', { filter: search }),
    });
    // One line to look for: no 「可多选」, no list of its own.
    await expect(
      within(settings).queryByText(zhCN['label.filters.multiple']),
    ).toBeNull();
    const chip = canvas.getByRole('group', { name: search });
    await expect(within(chip).getByRole('textbox')).toHaveAttribute(
      'placeholder',
      zhCN['label.filters.search-placeholder'],
    );

    await userEvent.click(
      within(settings).getByRole('button', {
        name: zhCN['label.filters.wire'],
      }),
    );
    await canvas.findByRole('region', {
      name: label('label.filters.wiring', { filter: search }),
    });
    await waitFor(() =>
      expect(
        canvasElement.querySelectorAll('[data-slot="panel-wiring"]'),
      ).toHaveLength(3),
    );
    // The chart counts groups: a search has nothing to reach there.
    await expect(
      canvasElement.querySelectorAll('[data-slot="panel-wiring-none"]'),
    ).toHaveLength(1);

    await userEvent.click(
      canvas.getByRole('combobox', {
        name: label('label.filters.wire-field-of', {
          panel: '待出库明细',
          filter: search,
        }),
      }),
    );
    await userEvent.click(
      await screen.findByRole('option', { name: '订单号或备注' }),
    );
    await expect(
      await canvas.findByText(zhCN['label.filters.auto-wired-search-one']),
    ).toBeVisible();
    await waitFor(() =>
      expect(
        canvas.getByRole('combobox', {
          name: label('label.filters.wire-field-of', {
            panel: '全部订单',
            filter: search,
          }),
        }),
      ).toHaveTextContent('订单号或备注'),
    );
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.filters.wiring-done'] }),
    );

    await userEvent.type(
      within(canvas.getByRole('group', { name: search })).getByRole('textbox'),
      'SO-1001',
    );
    await waitFor(async () =>
      expect(await pendingOrders(canvasElement)).toEqual(['SO-1001']),
    );
    // The chart says the search does not reach it.
    await waitFor(() =>
      expect(badgeOn(canvasElement, '按仓库汇总')).toBe(
        label('label.filters.not-reached', {
          filters: label('label.filters.name-quoted', { name: search }),
        }),
      ),
    );
    await expect(badgeOn(canvasElement, '待出库明细')).toBeNull();
  },
};

/**
 * The saved board embedded at the `interactive` tier: its search invites
 * a query with 「搜索…」, and typing into it narrows the lists.
 */
export const SearchNarrowsInAnEmbed: Story = {
  ...DisplayEmbed,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const bar = await within(canvasElement).findByRole('region', {
      name: zhCN['label.filters.bar'],
    });
    const box = within(
      within(bar).getByRole('group', { name: '搜索订单' }),
    ).getByRole('textbox');
    await expect(box).toHaveAttribute(
      'placeholder',
      zhCN['label.filters.search-placeholder'],
    );
    await waitFor(async () =>
      expect((await pendingOrders(canvasElement)).length).toBeGreaterThan(1),
    );
    await userEvent.type(box, 'SO-1001');
    await waitFor(async () =>
      expect(await pendingOrders(canvasElement)).toEqual(['SO-1001']),
    );
  },
};
