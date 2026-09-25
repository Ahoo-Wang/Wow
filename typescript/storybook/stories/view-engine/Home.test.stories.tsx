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
  DailyReport as DisplayDailyReport,
  Loading as DisplayLoading,
  NoData as DisplayNoData,
  NoPermission as DisplayNoPermission,
  PanelError as DisplayPanelError,
} from './Home.stories.js';
import { chartsDrawn, drawnMarks, pressMark } from './chartDom.js';
import { findDataTable, readColumn } from './readTable.js';
import { DAILY_CARDS } from './retail/boards.js';
import {
  DAILY_GOLDEN,
  OVERDUE_LIVE_ORDERS,
  OVERDUE_ORDERS,
} from './retail/goldens.js';

/**
 * The home page — the operations daily report over the retail data set —
 * as a lightweight twin (docs/scenarios.md 6.1, 6.3): it draws, its numbers
 * are the golden ones the seed decides, the planted anomaly A7 is on its
 * first screen, it is read-only by construction, and the drill path runs
 * down to the order. The generator's own correctness is `generate.test.ts`'s;
 * a change to it updates `retail/goldens.ts` in the same pull request.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/首页/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread (see the README): the display meta's
  // full-screen host application goes with it.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

const label = (key: keyof typeof zhCN, params: Record<string, string> = {}) =>
  Object.entries(params).reduce<string>(
    (text, [name, value]) => text.replace(`{${name}}`, value),
    zhCN[key],
  );

const panelOf = (name: string) => screen.getByRole('group', { name });

const valueOf = (name: string) =>
  panelOf(name).querySelector('[data-slot="metric-value"]')?.textContent;

/**
 * Every panel has answered and drawn: each card a number, each chart its
 * marks in place, the overdue list its rows. What the board looks like
 * before then is loading, not the report.
 */
async function boardDrawn(canvasElement: HTMLElement) {
  await waitFor(
    () => {
      for (const name of DAILY_CARDS) expect(valueOf(name)).toBeTruthy();
    },
    { timeout: 10_000 },
  );
  await chartsDrawn(canvasElement);
  const table = await findOverdue();
  await waitFor(() =>
    expect(readColumn(table, '订单号').length).toBeGreaterThan(0),
  );
}

async function findOverdue() {
  return findDataTable(panelOf('付款超过 48 小时仍未发货'));
}

/**
 * The first screen: every number with its unit and what it is compared
 * against, the golden numbers of 2026-09-21, A7 on it, and the whole board
 * drawn inside the regression guard.
 */
export const DailyReport: Story = {
  ...DisplayDailyReport,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      canvas.getByRole('heading', { level: 1, name: '运营日报' }),
    ).toBeVisible();
    await expect(
      canvasElement.querySelector('[data-host-report-day]'),
    ).toHaveTextContent('2026年9月21日星期一');

    await boardDrawn(canvasElement);
    // How long the whole board took, from the page's first render —
    // generating the data set (when this iframe had not yet), building the
    // engine, every query and every chart. Budget 1 s (6.3), measured
    // locally; the assertion is a guard against an order-of-magnitude
    // regression, since a CI runner is several times slower.
    const page = canvasElement.querySelector<HTMLElement>('[data-host-page]')!;
    const drawnIn = performance.now() - Number(page.dataset.startedAt);
    console.info(`Home daily report drawn in ${drawnIn.toFixed(0)} ms`);
    await expect(drawnIn).toBeLessThan(6_000);

    // The golden numbers of 2026-09-21: each card its value, in its unit.
    for (const [name, value] of Object.entries(DAILY_GOLDEN.cards))
      await expect(valueOf(name)).toBe(value);
    // The trend cards read yesterday against the day before, and say so;
    // the level cards read yesterday against the last 30 days.
    for (const [name, change] of Object.entries(DAILY_GOLDEN.changes)) {
      const card = panelOf(name);
      await expect(
        card.querySelector('[data-slot="metric-period"]'),
      ).toHaveTextContent('2026年9月21日');
      await expect(
        card.querySelector('[data-slot="metric-change"]'),
      ).toHaveTextContent(change);
      await expect(
        card.querySelector('[data-slot="metric-change"]'),
      ).toHaveTextContent(zhCN['label.chart.change.against.DAY']);
    }
    // A7: on-time shipping of 09-21 under its 95% target, as a bar short
    // of full.
    const onTime = panelOf(DAILY_CARDS[7]);
    await expect(
      within(onTime).getByRole('progressbar', {
        name: zhCN['label.chart.target'],
      }),
    ).toHaveAttribute(
      'aria-valuetext',
      label('label.chart.target.reached', DAILY_GOLDEN.onTime),
    );

    // ...and the orders it left behind, all at the East China warehouse,
    // the oldest payment first.
    const table = await findOverdue();
    await expect(readColumn(table, '订单号')).toEqual([...OVERDUE_ORDERS]);
    await expect(new Set(readColumn(table, '发货仓'))).toEqual(
      new Set(['华东（嘉兴）']),
    );
    // The host's own action counts the same orders.
    await expect(
      canvas.getByRole('button', { name: /催发货/ }),
    ).toHaveTextContent(`超时 ${OVERDUE_ORDERS.length} 单`);

    // First glance: the eight cards and the hourly GMV start above the fold.
    const hourly = panelOf('今日与昨日的逐时 GMV');
    await expect(hourly.getBoundingClientRect().top).toBeLessThan(
      window.innerHeight,
    );
    // Nothing scrolls sideways, and no number is cut.
    const area = canvasElement.querySelector<HTMLElement>('.story-app-page')!;
    await expect(area.scrollWidth).toBeLessThanOrEqual(area.clientWidth);
    for (const name of DAILY_CARDS) {
      const value = panelOf(name).querySelector<HTMLElement>(
        '[data-slot="metric-value"]',
      )!;
      await expect(value.scrollWidth).toBeLessThanOrEqual(value.clientWidth);
    }
  },
};

/**
 * Read-only by construction (D36): no 「编辑」, save or save-as anywhere,
 * the panel menus read only; 「铺满屏幕」 fills the screen and Escape puts it
 * back.
 */
export const ReadOnlyReport: Story = {
  ...DisplayDailyReport,
  name: '只读报告',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await boardDrawn(canvasElement);
    for (const name of [
      zhCN['label.dashboard.edit'],
      zhCN['label.save.save'],
      zhCN['label.save.save-as'],
    ])
      await expect(canvas.queryByRole('button', { name })).toBeNull();
    await expect(
      canvasElement.querySelector('[data-slot="dashboard-edit"]'),
    ).toBeNull();
    const expand = canvas.getByRole('button', {
      name: zhCN['label.workbench.expand-view'],
    });
    const surface = expand.closest<HTMLElement>('.fve-root')!;
    await userEvent.click(expand);
    await waitFor(() => {
      const box = surface.getBoundingClientRect();
      expect(Math.abs(box.width - window.innerWidth)).toBeLessThan(1);
      expect(Math.abs(box.height - window.innerHeight)).toBeLessThan(1);
    });
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(surface).not.toHaveAttribute('data-view-expanded'),
    );
    await userEvent.click(
      canvas.getByRole('button', {
        name: label('label.panel.menu', { title: '付款超过 48 小时仍未发货' }),
      }),
    );
    const items = within(await screen.findByRole('menu'))
      .getAllByRole('menuitem')
      .map(item => item.textContent?.trim());
    await expect(items).not.toContain(zhCN['label.panel.remove']);
    await userEvent.keyboard('{Escape}');
  },
};

/**
 * The board's search (a filter of the search kind, D36) reaches the overdue
 * list alone: an order number finds its row, and the panels it does not
 * reach say so.
 */
export const SearchesTheOverdueList: Story = {
  ...DisplayDailyReport,
  name: '搜索订单',
  play: async ({ canvasElement }) => {
    await boardDrawn(canvasElement);
    const search = screen.getByRole('group', { name: '搜索订单' });
    await userEvent.type(within(search).getByRole('textbox'), '欧阳');
    const table = await findOverdue();
    await waitFor(() =>
      expect(readColumn(table, '买家昵称')).toEqual(['欧阳*']),
    );
    await expect(
      panelOf(DAILY_CARDS[0])
        .closest('[data-slot="dashboard-panel"]')!
        .querySelector('[data-slot="panel-not-reached"]'),
    ).toHaveTextContent('搜索订单');
    // A product name reaches the order through its lines.
    await userEvent.clear(within(search).getByRole('textbox'));
    await userEvent.type(within(search).getByRole('textbox'), '乳胶枕');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toContain('TO2026091900032'),
    );
  },
};

/**
 * A press on a channel's bar filters the whole board by it (cross-filtering,
 * D22 I): the overdue list keeps the three live-selling orders alone.
 */
export const ChannelCrossFilters: Story = {
  ...DisplayDailyReport,
  name: '点渠道交叉筛选',
  play: async ({ canvasElement }) => {
    await boardDrawn(canvasElement);
    const channels = panelOf('渠道分布');
    const bars = drawnMarks(channels);
    // Bars run largest first: 自有 App, 微信小程序, 直播间, …
    pressMark(bars[2]);
    const bar = screen.getByRole('group', { name: '渠道' });
    await waitFor(() => expect(bar).toHaveTextContent('直播间'));
    await expect(
      document.querySelector('[data-slot="dashboard-filter-from"]'),
    ).toHaveTextContent('渠道分布');
    const table = await findOverdue();
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual([...OVERDUE_LIVE_ORDERS]),
    );
    await waitFor(() =>
      expect(valueOf(DAILY_CARDS[0])).not.toBe(DAILY_GOLDEN.cards.GMV),
    );
  },
};

/**
 * A press on a product among the after-sales refunds opens 销售复盘 on its
 * 品类 tab (D23 Q17), carrying the board's 日期 over, where the bath towel
 * tops the refund rates (A1).
 */
export const RefundedProductOpensTheSalesReview: Story = {
  ...DisplayDailyReport,
  name: '点商品去销售复盘',
  play: async ({ canvasElement }) => {
    await boardDrawn(canvasElement);
    const refunds = panelOf('售后退款最多的 5 个商品');
    // The bath towel's refunds are the largest by far: its bar is first.
    await expect(refunds).toHaveTextContent('竹纤维浴巾');
    pressMark(drawnMarks(refunds)[0]);
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-host-route="dashboard"]'),
      ).not.toBeNull(),
    );
    const tab = await screen.findByRole('tab', { name: '品类' });
    await expect(tab).toHaveAttribute('aria-selected', 'true');
    const table = await findDataTable(panelOf('退款率最高的商品（近 3 个月）'));
    await waitFor(() =>
      expect(readColumn(table, '商品')[0]).toBe('竹纤维浴巾 70×140 · 米白'),
    );
    // The daily report's 日期 came along: the towel's pieces are the last
    // 30 days' (113), not the three months' the panel reads on its own.
    await expect(readColumn(table, '件数')[0]).toBe('113');
  },
};

/**
 * The drill path (6.3): the overdue list opens in the host's order
 * workbench, where each order carries the host's 「催发货」 and a link to
 * its detail page, whose event stream shows it was never shipped.
 */
export const DrillsToTheOrder: Story = {
  ...DisplayDailyReport,
  name: '一路追到订单',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await boardDrawn(canvasElement);
    await userEvent.click(
      canvas.getByRole('button', {
        name: label('label.panel.menu', { title: '付款超过 48 小时仍未发货' }),
      }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', {
        name: new RegExp(zhCN['label.panel.open']),
      }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-host-route="workbench"]'),
      ).not.toBeNull(),
    );
    await waitFor(() =>
      expect(
        canvas.getAllByText(`共 ${OVERDUE_ORDERS.length} 条记录`).length,
      ).toBeGreaterThan(0),
    );
    const detail = await canvas.findByRole('link', {
      name: `订单详情 ${OVERDUE_ORDERS[4]}`,
    });
    await expect(detail.getAttribute('href')).toContain(
      `args=orderNo:${OVERDUE_ORDERS[4]}`,
    );
    await userEvent.click(
      canvas.getByRole('button', { name: `催发货 ${OVERDUE_ORDERS[4]}` }),
    );
    await expect(
      canvasElement.querySelector('[data-host-status]'),
    ).toHaveTextContent('已通知华东（嘉兴）仓加急处理 1 张单');
    await expect(
      canvas.getByRole('button', { name: `已催 ${OVERDUE_ORDERS[4]}` }),
    ).toBeDisabled();
  },
};

/** Loading: every panel says so on its own, and the host's shell stands. */
export const Loading: Story = {
  ...DisplayLoading,
  play: async ({ canvasElement }) => {
    await waitFor(() =>
      expect(
        canvasElement.querySelectorAll('[data-slot="panel-loading"]').length,
      ).toBeGreaterThanOrEqual(DAILY_CARDS.length),
    );
    await expect(
      within(canvasElement).getByRole('heading', { name: '运营日报' }),
    ).toBeVisible();
  },
};

/**
 * The after-sales service is down: its panels say so and offer 重试;
 * every other panel draws.
 */
export const PanelError: Story = {
  ...DisplayPanelError,
  play: async ({ canvasElement }) => {
    await waitFor(() =>
      expect(
        panelOf('售后退款').querySelector('[data-slot="panel-failed"]'),
      ).not.toBeNull(),
    );
    await expect(
      within(panelOf('售后退款')).getByRole('button', {
        name: zhCN['label.query.retry'],
      }),
    ).toBeInTheDocument();
    await waitFor(() => expect(valueOf('GMV')).toBe(DAILY_GOLDEN.cards.GMV));
    // Both panels over after-sales fail — the refund card and the refunds
    // by product — and nothing else.
    const failed = new Set(
      [...canvasElement.querySelectorAll('[data-slot="panel-failed"]')].map(
        body => body.closest('[data-slot="dashboard-panel"]'),
      ),
    );
    await expect(failed.size).toBe(2);
  },
};

/** Not shared with this reader: the embed says it cannot open the board. */
export const NoPermission: Story = {
  ...DisplayNoPermission,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      await canvas.findByText(zhCN['label.dashboard.open-forbidden']),
    ).toBeInTheDocument();
    await expect(
      canvasElement.querySelector('[data-slot="dashboard-panel"]'),
    ).toBeNull();
  },
};

/** No data yet: every panel answers, and none has a number to show. */
export const NoData: Story = {
  ...DisplayNoData,
  play: async ({ canvasElement }) => {
    await waitFor(() => expect(valueOf('GMV')).toBe('—'));
    // No day has passed with an order in it, so no card has a day to read.
    await expect(valueOf('订单数（单）')).toBe('—');
    await expect(
      within(canvasElement).getByRole('button', { name: /催发货/ }),
    ).toBeDisabled();
    await expect(
      canvasElement.querySelector('[data-slot="panel-failed"]'),
    ).toBeNull();
  },
};

/** On a 375 phone: one column, the filters in a sheet, nothing sideways. */
export const OnAPhone: Story = {
  ...DisplayDailyReport,
  name: '手机',
  parameters: {
    ...DisplayDailyReport.parameters,
    viewport: {
      options: {
        phone: { name: '375×812', styles: { width: '375px', height: '812px' } },
      },
    },
  },
  globals: { viewport: { value: 'phone' } },
  play: async ({ canvasElement }) => {
    await expect(window.innerWidth).toBe(375);
    await waitFor(() => expect(valueOf('GMV')).toBe(DAILY_GOLDEN.cards.GMV));
    await expect(
      within(canvasElement).getByRole('button', {
        name: label('label.filters.sheet-set', { count: '1' }),
      }),
    ).toBeVisible();
    await expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(
      window.innerWidth,
    );
  },
};
