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
import { DAILY_CARDS, REFUND_SKUS } from './retail/boards.js';
import {
  DAILY_GOLDEN,
  OVERDUE_LIVE_ORDERS,
  OVERDUE_ORDERS,
} from './retail/goldens.js';
import { matchScreenshot } from './screenshot.js';
import {
  expectColumnsCue,
  expectCueIsNoStop,
  expectNoPanelsOverlap,
  expectShownWhole,
  panelParts,
} from './panelFit.js';

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

/** The host's own 「催发货」 in its page head, not a row's on the board. */
const HOST_NUDGE = /^催发货\s*超时/;

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
  // One of the key screens with a screenshot baseline (themes.md 5.5).
  tags: ['visual'],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      canvas.getByRole('heading', { level: 1, name: '运营日报' }),
    ).toBeVisible();
    // The platform's own long date: macOS's ICU sets a space before the
    // weekday that the ICU the browsers bundle does not.
    await expect(
      canvasElement.querySelector('[data-host-report-day]'),
    ).toHaveTextContent(/^2026年9月21日\s*星期一$/);

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
    // Every card is anchored to the board's 「昨日」 (D39): it reads
    // yesterday against the day before, and says so — the three ratios as
    // each day's own sums divided (D38).
    await expect(
      screen.getByRole('group', { name: /^日期/ }),
    ).toHaveTextContent(zhCN['label.relative.preset.yesterday']);
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
    // Each card is filled to its foot: the trend takes the height its
    // words leave, rather than standing at a fixed 64px over a quarter of
    // the card left empty (「底部留白是不是太多了」).
    for (const name of DAILY_CARDS) {
      const body = panelOf(name);
      const trend = body.querySelector<HTMLElement>(
        '[data-chart="sparkline"]',
      )!;
      const foot = body.getBoundingClientRect().bottom;
      await expect(foot - trend.getBoundingClientRect().bottom).toBeLessThan(1);
      await expect(trend.getBoundingClientRect().height).toBeGreaterThan(64);
    }

    // ...and the orders it left behind, all at the East China warehouse,
    // the oldest payment first.
    const table = await findOverdue();
    await expect(readColumn(table, '订单号')).toEqual([...OVERDUE_ORDERS]);
    await expect(new Set(readColumn(table, '发货仓'))).toEqual(
      new Set(['华东（嘉兴）']),
    );
    // The panel says how many there are, under its rows (D39), and each
    // row carries the host's 「催发货」.
    const overdue = panelOf('付款超过 48 小时仍未发货').closest<HTMLElement>(
      '[data-slot="dashboard-panel"]',
    )!;
    await expect(
      overdue.querySelector('[data-slot="panel-paging"]'),
    ).toHaveTextContent(`共 ${OVERDUE_ORDERS.length} 条记录`);
    await expect(
      within(overdue).getByRole('button', {
        name: `催发货 ${OVERDUE_ORDERS[0]}`,
      }),
    ).toBeVisible();
    // The host's own action counts the same orders.
    await expect(
      canvas.getByRole('button', { name: HOST_NUDGE }),
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
    await matchScreenshot(canvasElement, 'home-daily-report');
  },
};

/**
 * The same page in `porcelain`, which layers it as a grouped page (D43):
 * the host's page and the board are one grey ground and the cards are
 * white on it, lifted by a shadow rather than ringed; a filter picked from
 * a list is a filled chip with no edge, while the one typed into keeps its
 * 3:1 edge; the titles are heavier. Measured on the cascaded styles, since
 * that is where a preset either reaches or does not.
 */
export const InPorcelain: Story = {
  ...DisplayDailyReport,
  name: 'porcelain：分组底与填色控件',
  globals: { fvePreset: 'porcelain' },
  play: async ({ canvasElement }) => {
    await waitFor(() => expect(valueOf('GMV')).toBe(DAILY_GOLDEN.cards.GMV));
    const style = (element: Element) => getComputedStyle(element);
    const transparent = /\/ 0\)|rgba\(0, 0, 0, 0\)/;
    const root = canvasElement.querySelector(
      '.fve-root[data-kind="dashboard"]',
    )!;
    const page = canvasElement.querySelector('[data-host-page]')!;
    const card = panelOf('GMV').closest('[data-slot="dashboard-panel"]')!;
    await expect(style(root).backgroundColor).toBe(style(page).backgroundColor);
    await expect(style(card).backgroundColor).not.toBe(
      style(root).backgroundColor,
    );
    // The ring is there, in a transparent colour, and the lift after it.
    await expect(style(card).boxShadow).toMatch(
      /oklch\(0 0 0 \/ 0\) 0px 0px 0px 1px, .*0px 2px 8px 0px/,
    );
    await expect(
      style(card.querySelector('[data-slot="card-title"]')!).fontWeight,
    ).toBe('600');

    // Each filter on the bar is its chip (`ControlFrame`).
    const chips = [
      ...canvasElement.querySelectorAll('[data-slot="dashboard-filter"]'),
    ];
    const typed = chips.filter(chip => chip.querySelector('[data-slot=input]'));
    const picked = chips.filter(chip => !typed.includes(chip));
    await expect(typed.length).toBeGreaterThan(0);
    await expect(picked.length).toBeGreaterThan(0);
    for (const chip of picked) {
      await expect(style(chip).borderTopColor).toMatch(transparent);
      await expect(style(chip).backgroundColor).not.toMatch(transparent);
    }
    for (const chip of typed)
      await expect(style(chip).borderTopColor).not.toMatch(transparent);
  },
};

/**
 * 「铺满屏幕」 shares the filter bar's row, after 「清空」, rather than a row
 * of its own between the page's head and the filters (the user on
 * 2026-09-25: 「这个需要独占一行吗？」). The page asks for no title and no
 * time, so the embed has no first row: the filter row is the first thing
 * on the surface, and the button stands on its first line, centred with the
 * chips (or, below `md`, with the one button that opens them).
 */
export const ExpandSharesARow: Story = {
  ...DisplayDailyReport,
  name: '铺满屏幕与筛选同一行',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await waitFor(() => expect(valueOf('GMV')).toBeTruthy());
    const expand = canvas.getByRole('button', {
      name: zhCN['label.workbench.expand-view'],
    });
    const surface = expand.closest<HTMLElement>('.fve-root')!;
    await expect(surface.querySelector('[data-slot="embed-head"]')).toBeNull();
    const row = expand.closest<HTMLElement>(
      '[data-slot="dashboard-filter-row"]',
    )!;
    await expect(row).not.toBeNull();
    // Nothing above it on the surface: no empty row.
    await expect(
      row.getBoundingClientRect().top - surface.getBoundingClientRect().top,
    ).toBeLessThanOrEqual(1);
    // On the first line of the bar, centred with what stands there.
    const bar = within(row).getByRole('region', {
      name: zhCN['label.filters.bar'],
    });
    const first = [...bar.children]
      .map(child => child.getBoundingClientRect())
      .filter(box => box.height > 0)
      .sort((a, b) => a.top - b.top)[0];
    const box = expand.getBoundingClientRect();
    await expect(
      Math.abs(box.top + box.height / 2 - (first.top + first.height / 2)),
    ).toBeLessThanOrEqual(4);
    // Outside the filters' region, after everything in it.
    await expect(bar.contains(expand)).toBe(false);
    await expect(box.left).toBeGreaterThanOrEqual(
      Math.max(...[...bar.children].map(c => c.getBoundingClientRect().right)),
    );
    await expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(
      window.innerWidth,
    );
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
 * 键盘停在哪，哪就看得见（WCAG 2.2 2.4.11；2026-09-25 纯键盘走查）：超时明细
 * 在面板里滚，表头与「全部」那一行都粘在滚动口的两头。浏览器把获得焦点的控件
 * 刚好滚进滚动口，从前最底下几行的「复制」整个落在「全部」那一行底下。每一颗
 * 按顺序聚焦，它正中那一点上必须就是它自己。
 */
export const FocusClearsTheStickyBands: Story = {
  ...DisplayDailyReport,
  name: '焦点不被粘性表头表尾遮住',
  play: async ({ canvasElement }) => {
    await boardDrawn(canvasElement);
    const panel = panelOf('付款超过 48 小时仍未发货');
    const copies = [
      ...panel.querySelectorAll<HTMLElement>('button[aria-label^="复制 "]'),
    ];
    await expect(copies.length).toBeGreaterThan(5);
    for (const copy of [...copies, ...[...copies].reverse()]) {
      copy.focus();
      await waitFor(() => {
        const box = copy.getBoundingClientRect();
        const hit = document.elementFromPoint(
          box.left + box.width / 2,
          box.top + box.height / 2,
        );
        expect(hit !== null && (hit === copy || copy.contains(hit))).toBe(true);
      });
    }
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
    // Bars run largest first, as the chart's reading table lists them; the
    // day's order is the day's.
    const reading = channels.querySelector<HTMLElement>(
      '[data-slot="chart-reading"] table',
    )!;
    const order = readColumn(reading, '渠道');
    pressMark(drawnMarks(channels)[order.indexOf('直播间')]);
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
 * The board's day off the calendar (「选择 指定日期 无效」, 2026-09-25): the
 * starred 日期 turns from 「时间段」 into 「指定日期」 without a blank in
 * between — the board puts a required filter's default straight back — and
 * the day picked runs: every card reads it against the day before (D39).
 * Back to 「时间段」 is the same select.
 */
export const PicksASpecificDay: Story = {
  ...DisplayDailyReport,
  name: '日期改为指定日期',
  play: async ({ canvasElement }) => {
    await boardDrawn(canvasElement);
    const date = screen.getByRole('group', { name: /^日期/ });
    // A day filter offers days: the three that have come by name, and no
    // window (2026-09-26 review, P1-1).
    await userEvent.click(
      within(date).getByRole('combobox', {
        name: label('label.date.period-of', { field: '日期' }),
      }),
    );
    await expect(
      (await screen.findAllByRole('option')).map(option => option.textContent),
    ).toEqual(['今天', '昨天', '前天']);
    await userEvent.keyboard('{Escape}');
    const kind = within(date).getByRole('combobox', {
      name: label('label.date.shape-of', { field: '日期' }),
    });
    await userEvent.click(kind);
    await expect(
      (await screen.findAllByRole('option')).map(option => option.textContent),
    ).toEqual([zhCN['label.date.absolute'], zhCN['label.date.preset']]);
    await userEvent.click(
      await screen.findByRole('option', { name: zhCN['label.date.absolute'] }),
    );
    await expect(kind).toHaveTextContent(zhCN['label.date.absolute']);
    const calendar = within(date).getByRole('button', { name: '日期' });
    await expect(calendar).toHaveTextContent(zhCN['label.date.pick']);
    // Nothing picked yet: the value in force is not the one on the bar, so
    // the panels wired to it ask for a day rather than show 9 月 21 日's
    // numbers (2026-09-26 review, P1-1).
    await waitFor(() =>
      expect(
        panelOf('GMV').querySelector('[data-slot="panel-awaiting-date"]'),
      ).toHaveTextContent(zhCN['label.panel.awaiting-date']),
    );

    await userEvent.click(calendar);
    const popup = await screen.findByRole('dialog', { name: '日期' });
    // The calendar opens on the reader's own month; walk it to 2026-09.
    const day = /^2026年9月15日/;
    const step = new Date() > new Date(2026, 8, 30) ? '上个月' : '下个月';
    for (let turn = 0; turn < 60; turn++) {
      if (within(popup).queryByRole('button', { name: day })) break;
      await userEvent.click(within(popup).getByRole('button', { name: step }));
    }
    await userEvent.click(within(popup).getByRole('button', { name: day }));
    await userEvent.keyboard('{Escape}');

    await waitFor(
      () => {
        for (const name of DAILY_CARDS)
          expect(
            panelOf(name).querySelector('[data-slot="metric-period"]'),
          ).toHaveTextContent('2026年9月15日');
      },
      { timeout: 10_000 },
    );
    await expect(calendar).toHaveTextContent('2026年9月15日');
    await expect(
      panelOf('GMV').querySelector('[data-slot="metric-change"]'),
    ).toHaveTextContent(zhCN['label.chart.change.against.DAY']);
    await waitFor(() =>
      expect(valueOf('GMV')).not.toBe(DAILY_GOLDEN.cards.GMV),
    );

    await userEvent.click(kind);
    await userEvent.click(
      await screen.findByRole('option', { name: zhCN['label.date.preset'] }),
    );
    await expect(kind).toHaveTextContent(zhCN['label.date.preset']);
  },
};

/**
 * A press on a product among the after-sales refunds opens 销售复盘 on its
 * 品类 tab (D23 Q17), carrying the board's 渠道 over — not its day, which
 * says nothing about three months' refund rates — where the bath towel tops
 * the refund rates (A1).
 */
export const RefundedProductOpensTheSalesReview: Story = {
  ...DisplayDailyReport,
  name: '点商品去销售复盘',
  play: async ({ canvasElement }) => {
    await boardDrawn(canvasElement);
    const refunds = panelOf(REFUND_SKUS);
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
    // Its own three months, not the report's day: the towel's rate there.
    await expect(readColumn(table, '退款率')[0]).toBe('26.0%');
  },
};

/**
 * The host's 「催发货」 on the board itself (D39): two orders picked on the
 * overdue panel and nudged at once — the host's command, which the host
 * runs and says; the board writes nothing (D36).
 */
export const NudgesFromTheBoard: Story = {
  ...DisplayDailyReport,
  name: '在板上催发货',
  play: async ({ canvasElement }) => {
    await boardDrawn(canvasElement);
    const overdue = panelOf('付款超过 48 小时仍未发货').closest<HTMLElement>(
      '[data-slot="dashboard-panel"]',
    )!;
    const boxes = within(overdue).getAllByRole('checkbox');
    // The first box picks the page; the next two are the first two orders.
    await userEvent.click(boxes[1]);
    await userEvent.click(boxes[2]);
    const bar = overdue.querySelector<HTMLElement>(
      '[data-slot="panel-selection"]',
    )!;
    await userEvent.click(within(bar).getByRole('button', { name: /催发货/ }));
    await expect(
      canvasElement.querySelector('[data-host-status]'),
    ).toHaveTextContent('已通知华东（嘉兴）仓加急处理 2 张单');
    await expect(
      within(overdue).getByRole('button', {
        name: `已催 ${OVERDUE_ORDERS[0]}`,
      }),
    ).toBeDisabled();
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
      within(canvasElement).getByRole('button', { name: HOST_NUDGE }),
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
    const opener = within(canvasElement).getByRole('button', {
      name: label('label.filters.sheet-set', { count: '1' }),
    });
    await expect(opener).toBeVisible();
    // 「铺满屏幕」 on the same row as that button, at its end — not a row of
    // its own above it.
    const expand = within(canvasElement)
      .getByRole('button', { name: zhCN['label.workbench.expand-view'] })
      .getBoundingClientRect();
    const opens = opener.getBoundingClientRect();
    await expect(Math.abs(expand.top - opens.top)).toBeLessThanOrEqual(4);
    await expect(expand.left).toBeGreaterThan(opens.right);
    await expect(expand.right).toBeLessThanOrEqual(window.innerWidth);
    await expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(
      window.innerWidth,
    );
    // The cards two to a row in the one-column reading (batch 6): eight at
    // their desktop height one per row were a long scroll past white.
    const frame = (name: string) =>
      panelOf(name)
        .closest<HTMLElement>('[data-slot="dashboard-panel"]')!
        .getBoundingClientRect();
    await waitFor(() => expect(valueOf('客单价')).toBeTruthy());
    const [gmv, paid, orders] = ['GMV', '实付金额', '订单数（单）'].map(frame);
    await expect(Math.round(paid.top)).toBe(Math.round(gmv.top));
    await expect(paid.left).toBeGreaterThan(gmv.right);
    await expect(orders.top).toBeGreaterThanOrEqual(gmv.bottom);
    await expect(gmv.width).toBeLessThan(window.innerWidth / 2);
    // And nothing on a card runs past its edge: the figure a step smaller,
    // the change badge wrapped.
    for (const name of DAILY_CARDS) {
      const card = panelOf(name).closest<HTMLElement>(
        '[data-slot="dashboard-panel"]',
      )!;
      const body = card.querySelector<HTMLElement>(
        '[data-slot="card-content"]',
      )!;
      await expect(body.scrollWidth).toBeLessThanOrEqual(body.clientWidth);
    }

    // In the filter sheet: every filter stays inside it — the date's kind
    // and its calendar wrap rather than running under the reset (W11). The
    // daily report's date is one day, so it has no relative window to
    // offer (2026-09-26 review, P1-1).
    await userEvent.click(opener);
    const sheet = await screen.findByRole('dialog', {
      name: zhCN['label.filters.bar'],
    });
    await userEvent.click(
      within(sheet).getByRole('combobox', {
        name: label('label.date.shape-of', { field: '日期' }),
      }),
    );
    await userEvent.click(
      await screen.findByRole('option', { name: zhCN['label.date.absolute'] }),
    );
    const calendar = await within(sheet).findByRole('button', {
      name: '日期',
    });
    await expect(getComputedStyle(calendar.closest('div.flex')!).flexWrap).toBe(
      'wrap',
    );
    const inside = (chip: Element) => {
      const edge = chip.getBoundingClientRect().right;
      return [...chip.querySelectorAll('*')].every(
        part => part.getBoundingClientRect().right <= edge + 0.5,
      );
    };
    for (const chip of sheet.querySelectorAll(
      '[data-slot="dashboard-filter"]',
    )) {
      await expect(chip.getBoundingClientRect().right).toBeLessThanOrEqual(
        window.innerWidth,
      );
      await expect(inside(chip)).toBe(true);
    }

    // A search the cards do not take: each half-width card says so in a
    // badge that wraps inside the card rather than losing both ends.
    await userEvent.type(
      within(sheet).getByRole('textbox', { name: '搜索订单' }),
      'TO2026',
    );
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    for (const name of DAILY_CARDS) {
      const card = panelOf(name).closest<HTMLElement>(
        '[data-slot="dashboard-panel"]',
      )!;
      const badge = await waitFor(() => {
        const found = card.querySelector<HTMLElement>(
          '[data-slot="panel-not-reached"]',
        );
        expect(found).toBeTruthy();
        return found!;
      });
      await expect(badge.scrollWidth).toBeLessThanOrEqual(badge.clientWidth);
      const [inner, outer] = [badge, card].map(node =>
        node.getBoundingClientRect(),
      );
      await expect(inner.left).toBeGreaterThanOrEqual(outer.left);
      await expect(inner.right).toBeLessThanOrEqual(outer.right);
    }
  },
};

/**
 * The overdue list and the duty notes on the board read (2026-09-26 review,
 * P1-3): the eleven orders all in view — six showed, the seventh cut in
 * half — and the notes' last line with them (183px of content in a 118px
 * body). The columns past the list's end are counted in a cue, which is no
 * Tab stop, and the panels under the list moved down rather than under it.
 */
export const TablePanelsShowTheirRows: Story = {
  ...DisplayDailyReport,
  name: '表格面板按行长高',
  play: async ({ canvasElement }) => {
    await boardDrawn(canvasElement);
    await expectShownWhole('付款超过 48 小时仍未发货', OVERDUE_ORDERS.length);
    await expectColumnsCue('付款超过 48 小时仍未发货');
    await expectCueIsNoStop('付款超过 48 小时仍未发货');
    await expectShownWhole('值班手册');
    await expectNoPanelsOverlap(canvasElement);
  },
};

/** Whether two boxes share any area. */
const overlap = (a: DOMRect, b: DOMRect) =>
  a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;

/**
 * D51 in a board panel, where the panel's body is what scrolls sideways: the
 * overdue list's totals row names 实付, which is off past the panel's end,
 * at its left end — clear of the cue on the body's other corner — and the
 * button scrolls the body to it.
 */
export const OffscreenTotalsInAPanel: Story = {
  ...DisplayDailyReport,
  name: '面板里的视野外合计',
  play: async ({ canvasElement }) => {
    await boardDrawn(canvasElement);
    const name = '付款超过 48 小时仍未发货';
    const hint = await waitFor(
      () => {
        const found = panelParts(name).card.querySelector<HTMLButtonElement>(
          'tfoot [data-slot="summary-offscreen"]',
        );
        expect(found).not.toBeNull();
        return found!;
      },
      { timeout: 10_000 },
    );
    // Named by the words it shows, then what they leave unsaid: the row,
    // that it is out of view, and where the press goes (WCAG 2.5.3).
    // (The hidden part is out of the flow, so a browser puts a space
    // before it.)
    await expect(hint).toHaveAccessibleName(
      /^实付 总和 ¥[\d,.]+ ?，全部，不在视野内，滚动到实付$/,
    );
    const { body, cue } = panelParts(name);
    await waitFor(() => expect(cue).not.toBeNull());
    await expect(
      overlap(hint.getBoundingClientRect(), cue!.getBoundingClientRect()),
    ).toBe(false);

    await userEvent.click(hint);
    await waitFor(() => expect(body.scrollLeft).toBeGreaterThan(0));
    await waitFor(() =>
      expect(
        panelParts(name).card.querySelector(
          'tfoot [data-slot="summary-offscreen"]',
        ),
      ).toBeNull(),
    );
    const reading = body.querySelector(
      'tfoot [data-summary-field="state.amounts.paidAmount"]',
    )!;
    const edge = body.getBoundingClientRect();
    const at = reading.getBoundingClientRect();
    await expect(at.left).toBeGreaterThanOrEqual(edge.left);
    await expect(at.right).toBeLessThanOrEqual(edge.right);
  },
};
