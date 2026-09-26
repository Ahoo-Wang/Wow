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
import {
  findReading,
  label,
  noPanelOut,
  panelOf,
  rowsOf,
  valueOf,
} from './retail/twins.js';
import { densitySettled } from './panelEdges.js';
import { expectBandsHeld } from './stickyBands.js';

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
    // No date set: the bar says each panel reads its own dates, not
    // 「指定日期 · 选择日期 – 选择日期」 (2026-09-26 review, P1-10).
    await waitFor(() =>
      expect(
        within(canvasElement).getByRole('combobox', {
          name: label('label.date.shape-of', { field: '日期' }),
        }),
      ).toHaveTextContent(zhCN['label.filters.date-own']),
    );
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
 * 月度指标（今年）holds its header and its totals against the panel's body,
 * which is what scrolls on a board, as a record panel does: scrolled, the
 * header stays at the body's top and the totals — 「合计」 over 「范围内全部
 * 记录」, whole — at its bottom, at every density.
 */
export const MonthlyTableHoldsItsBands: Story = {
  ...DisplayOverview,
  name: '月度指标的表头与合计贴住面板',
  play: async ({ canvasElement }) => {
    const body = await waitFor(() => panelOf('月度指标（今年）'), {
      timeout: 10_000,
    });
    const port = await waitFor(() => {
      const found = body.querySelector<HTMLElement>(
        '[data-slot="analysis-table"]',
      );
      expect(found?.querySelector('[data-slot="totals-row"]')).not.toBeNull();
      expect(found?.querySelectorAll('tbody tr[data-index]')).toHaveLength(9);
      return found!;
    });
    // As the board lays it out: nine months and the totals overflow it.
    await expectBandsHeld(body, port);
    // And at each density, in a body held shorter, so the rows overflow it
    // however short the density makes them.
    body.style.maxHeight = '240px';
    for (const density of ['compact', 'default', 'comfortable']) {
      canvasElement.dataset.fveDensity = density;
      await densitySettled();
      await expectBandsHeld(body, port);
    }
    delete canvasElement.dataset.fveDensity;
    body.style.removeProperty('max-height');
  },
};

/**
 * The numbers agree across boards (6.3): the sales review narrowed to
 * 2026-09-21 by a reader — reads the
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

/** What a metric card says its number covers. */
const periodOf = (name: string) =>
  panelOf(name).querySelector('[data-slot="metric-period"]')?.textContent;

/**
 * A card names the span its number covers, not the bucket it came from
 * (2026-09-26 review, P0-1): the board narrowed to 9 月 21 日 by the month
 * reads that day, and 「上月同期」 — no day of it on the board — reads
 * empty, with a note saying why, rather than ¥0.
 */
export const OneDayNamesTheDay: Story = {
  ...DisplayOneDay,
  name: '某一天：卡片写那一天，上月同期不适用',
  play: async () => {
    await waitFor(() => expect(periodOf('GMV')).toBe('2026年9月21日'), {
      timeout: 10_000,
    });
    const reading = await findReading(panelOf('本月 GMV 较上月同期（分渠道）'));
    await waitFor(() =>
      expect(rowsOf(reading).map(row => row[1])).toEqual(
        Array(rowsOf(reading).length).fill('—'),
      ),
    );
  },
};

/** 「过去 7 天」 by the month: the seven days so far, not 「2026年9月」. */
export const LastSevenDaysNamesTheDays: Story = {
  ...DisplayOverview,
  name: '过去 7 天：卡片写那七天',
  args: {
    filters: {
      values: { date: { type: 'relative', amount: 7, unit: 'day' } },
    },
  },
  play: async () => {
    await waitFor(
      () => expect(periodOf('GMV')).toBe('2026年9月16日–22日（至今）'),
      { timeout: 10_000 },
    );
  },
};

/** 8 月 1 日至 20 日 by the month: twenty days, named as such. */
export const RangeNamesItsDays: Story = {
  ...DisplayOverview,
  name: '指定区间：卡片写区间里的日子',
  args: {
    filters: {
      values: {
        date: { type: 'absolute', from: '2026-08-01', to: '2026-08-20' },
      },
    },
  },
  play: async () => {
    await waitFor(() => expect(periodOf('GMV')).toBe('2026年8月1日–20日'), {
      timeout: 10_000,
    });
  },
};

/**
 * 按日 with no dates: more days than the cards' 400-row limit, sorted
 * earliest first, so the latest day is not among them (P0-2). The card
 * shows no number and says why, and the trend's axis is named by its new
 * unit rather than the view's 「月份」.
 */
export const DailyPastTheLimitSaysSo: Story = {
  ...DisplayOverview,
  name: '按日不设日期：卡片不拿第 400 天当最新',
  args: { filters: { values: {}, unit: 'DAY' } },
  play: async () => {
    await waitFor(
      () =>
        expect(
          panelOf('GMV').querySelector('[data-slot="metric-cut"]'),
        ).toHaveTextContent(label('label.chart.period.cut', { limit: '400' })),
      { timeout: 10_000 },
    );
    await expect(valueOf('GMV')).toBe('—');
    await expect(periodOf('GMV')).toBeUndefined();
    const reading = await findReading(panelOf('月 GMV 与客单价'));
    await expect(reading.tHead?.rows[0].cells[0].textContent).not.toBe('月份');
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
    const rate = parseFloat(readColumn(table, '退款率')[0]!);
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
