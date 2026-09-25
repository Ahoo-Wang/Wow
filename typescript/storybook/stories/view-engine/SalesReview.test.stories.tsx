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
import { expect, screen, userEvent, waitFor, within } from 'storybook/test';
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  Category as DisplayCategory,
  OneDay as DisplayOneDay,
  Overview as DisplayOverview,
} from './SalesReview.stories.js';
import { chartsDrawn } from './chartDom.js';
import { findDataTable, readColumn } from './readTable.js';
import { DAILY_GOLDEN } from './retail/goldens.js';
import { label, noPanelOut, panelOf, valueOf } from './retail/twins.js';

/**
 * 销售复盘, as a lightweight twin (docs/scenarios.md 6.1): it draws without a
 * panel refusing its view, and shows what 4.1 says it shows.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/业务场景/销售复盘/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread (see the README).
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/**
 * 销售复盘 概览: the last month that ended against the one before, and every
 * panel drawn.
 */
export const SalesOverview: Story = {
  ...DisplayOverview,
  name: '销售复盘 · 概览',
  play: async ({ canvasElement }) => {
    await waitFor(
      () =>
        expect(
          panelOf('GMV').querySelector('[data-slot="metric-period"]'),
        ).toHaveTextContent('2026年8月'),
      { timeout: 10_000 },
    );
    await noPanelOut(canvasElement);
    await chartsDrawn(canvasElement);
    const monthly = await findDataTable(panelOf('月度指标（今年）'));
    await waitFor(() =>
      expect(readColumn(monthly, '下单时间（按月）')[0]).toBe('2026年9月'),
    );
  },
};

/**
 * The numbers agree across boards (6.3): the sales review narrowed to
 * 2026-09-21 — as the daily report's press carries a date over — reads the
 * daily report's GMV and order count for that day.
 */
export const SalesMatchesTheDailyReport: Story = {
  ...DisplayOneDay,
  name: '销售复盘与日报同一口径',
  play: async () => {
    await waitFor(() => expect(valueOf('GMV')).toBe(DAILY_GOLDEN.cards.GMV), {
      timeout: 10_000,
    });
    await expect(valueOf('订单数（单）')).toBe(
      DAILY_GOLDEN.cards['订单数（单）'],
    );
    await expect(valueOf('实付金额')).toBe(DAILY_GOLDEN.cards.实付金额);
  },
};

/**
 * 品类: the bamboo-fibre bath towel tops the refund rates of the last three
 * months (A1).
 */
export const SalesCategoryShowsTheTowel: Story = {
  ...DisplayCategory,
  name: '销售复盘 · 品类（A1）',
  play: async ({ canvasElement }) => {
    const table = await findDataTable(
      await waitFor(() => panelOf('退款率最高的商品（近 3 个月）'), {
        timeout: 10_000,
      }),
    );
    await waitFor(() =>
      expect(readColumn(table, '商品')[0]).toBe('竹纤维浴巾 70×140 · 米白'),
    );
    const rate = Number(readColumn(table, '退款率（%）')[0]);
    await expect(rate).toBeGreaterThan(20);
    await noPanelOut(canvasElement);
  },
};

/**
 * Building 销售复盘 (D22): 「编辑」, a panel removed at once and brought
 * back by 「撤销」, removed again and the board saved for everyone — the
 * workbench writes, where the home page's embed never does (D36).
 */
export const SalesReviewIsBuilt: Story = {
  ...DisplayOverview,
  name: '销售复盘 · 搭建',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await waitFor(() => expect(valueOf('GMV')).toBeTruthy(), {
      timeout: 10_000,
    });
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.dashboard.edit'] }),
    );
    const remove = async () => {
      await waitFor(() =>
        expect(
          document.querySelector('[data-slot="dropdown-menu-content"]'),
        ).toBeNull(),
      );
      await userEvent.click(
        canvas.getByRole('button', {
          name: label('label.panel.menu', { title: '月度指标（今年）' }),
        }),
      );
      await userEvent.click(
        await screen.findByRole('menuitem', {
          name: zhCN['label.panel.remove'],
        }),
      );
      await waitFor(() =>
        expect(
          canvas.queryByRole('group', { name: '月度指标（今年）' }),
        ).toBeNull(),
      );
    };
    await remove();
    const removal = label('label.history.remove-panel', {
      title: '月度指标（今年）',
    });
    await userEvent.click(
      canvas.getByRole('button', {
        name: label('label.history.undo-step', { what: removal }),
      }),
    );
    await waitFor(() =>
      expect(
        canvas.getByRole('group', { name: '月度指标（今年）' }),
      ).toBeInTheDocument(),
    );
    await remove();
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.dashboard.save'] }),
    );
    const confirm = await screen.findByRole('alertdialog');
    await userEvent.click(
      within(confirm).getByRole('button', {
        name: zhCN['label.save.shared-confirm'],
      }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="dashboard-edit-bar"]'),
      ).toBeNull(),
    );
    await expect(
      canvas.queryByRole('group', { name: '月度指标（今年）' }),
    ).toBeNull();
  },
};
