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
import { expect, userEvent, waitFor, within } from 'storybook/test';
import { formatMessage, zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  MemberAnalysis as DisplayMemberAnalysis,
  OrderAnalysis as DisplayOrderAnalysis,
} from './RetailAnalysis.stories.js';
import {
  brushAcross,
  chartsDrawn,
  drawnMarks,
  legendNames,
  pressMark,
} from './chartDom.js';
import { findDataTable, readColumn, readHeaders } from './readTable.js';
import { ANALYSIS_GOLDEN } from './retail/goldens.js';
import { BATH_TOWEL_TITLE, DOUBLE11_DAY_TARGET } from './retail/views.js';
import { matchScreenshot } from './screenshot.js';

/**
 * 分析工作台的轻量孪生：每个问题的已存分析都在，几处埋下的异常在它们里面
 * 看得见，数字是种子定下的黄金值。图上的数从图的读屏表读（每张图旁边都有
 * 一张），不认「表格／图表」切换上的字。
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/业务场景/分析工作台/回归',
  tags: ['!dev', '!autodocs', 'test'],
  parameters: { ...displayMeta.parameters },
};

export default meta;

/** A view's title as the start of a pattern: its parentheses mean themselves. */
const escaped = (title: string) => title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

type Story = StoryObj<typeof displayMeta>;

/**
 * The regression line for drawing the heaviest view — 25 months of daily
 * GMV with a moving average, over 20 thousand orders: about three times the
 * slowest run seen. The budget is 1 second (docs/scenarios.md 6.3); the
 * measured time is printed on every run.
 */
const DRAW_GUARD_MS = 3_000;

/** The chart's reading table: one row per point, a column per series. */
async function reading(canvasElement: HTMLElement) {
  return waitFor(() => {
    const table = canvasElement.querySelector<HTMLElement>(
      '[data-slot="chart-reading"] table',
    );
    if (!table) throw new Error('No chart reading yet.');
    return table;
  });
}

/**
 * The records a follow-up opened, once they are on screen: how many rows
 * came back, and the applied band they came back under. A drill that could
 * not run opened a title over nothing — no rows, no loading, no word of why
 * (a range on the field the analysis was already scoped by, 2026-09-25) —
 * so the rows are counted and no error strip may stand above them.
 */
async function drilledRecords(canvasElement: HTMLElement) {
  const table = await findDataTable(canvasElement);
  const rows = await waitFor(() => {
    const first = readHeaders(table)[0]!;
    const keys = readColumn(table, first).filter(key => key !== '');
    expect(keys.length).toBeGreaterThan(0);
    return keys.length;
  });
  await expect(
    canvasElement.querySelector(
      '[data-slot="status-strip"][data-tone="error"]',
    ),
  ).toBeNull();
  const applied = within(canvasElement).getByRole('region', {
    name: zhCN['label.applied.title'],
  });
  return { rows, applied: applied.textContent ?? '' };
}

/** The follow-up menu, once it is open under the given title. */
async function drillMenu(title: string) {
  return waitFor(() => {
    const found = document.body.querySelector<HTMLElement>(
      '[data-slot="drill-menu"]',
    );
    expect(found).toHaveAttribute('aria-label', title);
    expect(found).toBeVisible();
    return found!;
  });
}

const ORDER_VIEWS = [
  '本月 GMV（较上月同期）',
  '本月经营概况',
  '客单价（按日，较前一日）',
  '日 GMV 走势（近 25 个月）',
  '月 GMV 与客单价',
  '本月 GMV 较上月同期（分渠道）',
  '本月净销售额的渠道构成',
  '品类构成（近 12 个月实付）',
  '渠道结构（按月 GMV 占比）',
  '省份 GMV 前 15',
  '城市等级 × 渠道',
  '退款率最高的商品（近 3 个月）',
  '大客户 Top 20',
  '商品销量长尾（近 12 个月，对数轴）',
  '下单到完成的漏斗',
  '下单时段热力（星期 × 时段）',
  '双 11 零点：各支付方式的超时率',
  '2025 双 11 前后的日 GMV',
  '各活动的客单价与优惠力度',
  '每周发货超时率',
  '各仓付款到发货（P50 / P90）',
  '直播间：优惠占比 × 退款率（每天一点）',
  '新客与老客的 GMV',
  '成交单价分布（每 100 元一档）',
  '支付方式构成',
  '季度 GMV 与单笔实付的波动',
];

export const OrderAnalysis: Story = {
  ...DisplayOrderAnalysis,
  // One of the key screens with a screenshot baseline (themes.md 5.5).
  tags: ['visual'],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const view = (title: string) =>
      canvas.getByRole('button', {
        name: new RegExp(`^${escaped(title)}`),
      });
    for (const title of ORDER_VIEWS)
      await waitFor(() => expect(view(title)).toBeInTheDocument());

    // A-01: this month so far against the same days of last month.
    await expect(
      await canvas.findByText('¥145,394.65', {}, { timeout: 4_000 }),
    ).toBeVisible();
    await expect(canvas.getByText('+24.4%')).toBeVisible();
    // Said against what it is compared with, and coloured as good.
    const compared = canvasElement.querySelector<HTMLElement>(
      '[data-slot="metric-compare"]',
    )!;
    await expect(compared).toHaveTextContent('较「上月同期 GMV」');
    await expect(compared.querySelector('[data-slot="badge"]')).toHaveAttribute(
      'data-tone',
      'success',
    );
    // The workbench as it opens, before anything is pressed.
    await matchScreenshot(canvasElement, 'analysis-workbench');

    // A-01 (D38): 客单价 as a trend — each day its own sums divided — read
    // on its last whole day against the day before, as money.
    await userEvent.click(view('客单价（按日，较前一日）'));
    const aovCard = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="metric-value"]',
      );
      expect(found).toHaveTextContent(ANALYSIS_GOLDEN.aov.value);
      return found!;
    });
    await expect(aovCard).toBeVisible();
    const aovChange = canvasElement.querySelector<HTMLElement>(
      '[data-slot="metric-change"]',
    );
    await expect(aovChange).toHaveTextContent(ANALYSIS_GOLDEN.aov.change);
    await expect(aovChange).toHaveTextContent('较前一日');

    // A-02, the heaviest view: 750 days drawn with a moving average.
    const started = performance.now();
    await userEvent.click(view('日 GMV 走势（近 25 个月）'));
    await chartsDrawn(canvasElement);
    const drawn = performance.now() - started;
    console.info(`[retail] 日 GMV 走势（750 天）画完：${drawn.toFixed(0)} ms`);
    await expect(drawn).toBeLessThan(DRAW_GUARD_MS);
    await expect(readColumn(await reading(canvasElement), 'GMV').length).toBe(
      751,
    );

    // A-07 (A1): the bath towel leads the refund rates by a street.
    await userEvent.click(view('退款率最高的商品（近 3 个月）'));
    await waitFor(async () =>
      expect(
        readColumn(await reading(canvasElement), '商品').slice(0, 2),
      ).toEqual([BATH_TOWEL_TITLE, '儿童浴巾 米白']),
    );
    // A ratio read as a percent (D38): the value is the ratio itself.
    const rates = readColumn(await reading(canvasElement), '退款率');
    await expect(rates.slice(0, 2)).toEqual(['26.0%', '12.8%']);

    // Its bar follows up to the orders with a line of it (D38): grouped by
    // an expanded element, the records are those with such an element.
    await chartsDrawn(canvasElement);
    const widest = drawnMarks(canvasElement).reduce((wide, bar) =>
      bar.getBoundingClientRect().width > wide.getBoundingClientRect().width
        ? bar
        : wide,
    );
    pressMark(widest);
    const towelMenu = await waitFor(() => {
      const found = document.body.querySelector<HTMLElement>(
        '[data-slot="drill-menu"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    await expect(
      towelMenu.querySelector('[data-slot="drill-group"]'),
    ).toHaveTextContent(BATH_TOWEL_TITLE);
    await userEvent.click(
      within(towelMenu).getByRole('menuitem', {
        name: zhCN['label.drill.records'],
      }),
    );
    await findDataTable(canvasElement);
    await waitFor(() =>
      expect(
        within(canvasElement).getAllByText(
          new RegExp(`^共 ${ANALYSIS_GOLDEN.towelOrders} 条记录$`),
        )[0],
      ).toBeVisible(),
    );

    // A-06 (A5): the orders with no city are a row of their own, all from
    // the mini program.
    await userEvent.click(view('城市等级 × 渠道'));
    await waitFor(async () =>
      expect(readColumn(await reading(canvasElement), '城市等级')).toContain(
        '（空）',
      ),
    );
    const tiers = await reading(canvasElement);
    const missing = readColumn(tiers, '城市等级').indexOf('（空）');
    await expect(readColumn(tiers, '微信小程序')[missing]).toBe('88');
    await expect(readColumn(tiers, '自有 App')[missing]).not.toMatch(/\d/);

    // Pressing that cell follows it up to the 88 orders themselves.
    await chartsDrawn(canvasElement);
    const cells = drawnMarks(canvasElement).filter(mark => {
      const box = mark.getBoundingClientRect();
      return box.width > 20 && box.height > 20;
    });
    const lowest = cells.reduce((low, cell) =>
      cell.getBoundingClientRect().top > low.getBoundingClientRect().top
        ? cell
        : low,
    );
    pressMark(lowest);
    const menu = await waitFor(() => {
      const found = document.body.querySelector<HTMLElement>(
        '[data-slot="drill-menu"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    await userEvent.click(
      within(menu).getByRole('menuitem', { name: zhCN['label.drill.records'] }),
    );
    const noCity = await findDataTable(canvasElement);
    await waitFor(() =>
      expect(new Set(readColumn(noCity, '渠道'))).toEqual(
        new Set(['微信小程序']),
      ),
    );
    await expect(
      within(canvasElement).getAllByText(/^共 88 条记录$/)[0],
    ).toBeVisible();

    // A-10 (N2): when buyers order — the weekday by the hour of the order's
    // own time, read on the Shanghai clock, the whole week by the whole day;
    // the evening is the peak.
    await userEvent.click(view('下单时段热力（星期 × 时段）'));
    await waitFor(async () => {
      const grid = await reading(canvasElement);
      const [corner, ...hours] = readHeaders(grid);
      expect(corner).toBe('下单时间（星期）');
      expect(hours).toEqual(Array.from({ length: 24 }, (_, h) => `${h}时`));
      expect(readColumn(grid, corner!)).toEqual([
        '周一',
        '周二',
        '周三',
        '周四',
        '周五',
        '周六',
        '周日',
      ]);
      // The busiest hour of the week, summed over its seven days.
      const byHour = hours.map(hour =>
        readColumn(grid, hour).reduce(
          (sum, text) => sum + (Number(text.replace(/,/g, '')) || 0),
          0,
        ),
      );
      expect(['20时', '21时', '22时', '23时']).toContain(
        hours[byHour.indexOf(Math.max(...byHour))],
      );
    });

    // A-10 (A4): UnionPay failed at midnight on Double 11.
    await userEvent.click(view('双 11 零点：各支付方式的超时率'));
    await waitFor(async () =>
      expect(readColumn(await reading(canvasElement), '支付方式')).toEqual([
        '云闪付',
        '支付宝',
        '微信支付',
      ]),
    );
    await expect(readColumn(await reading(canvasElement), '超时率')).toEqual([
      '100.0%',
      '20.0%',
      '0.0%',
    ]);

    // A-12 (A6): the Spring Festival week, far over the 5% line.
    await userEvent.click(view('每周发货超时率'));
    await waitFor(async () => {
      const weekly = await reading(canvasElement);
      const at = readColumn(weekly, '付款周').indexOf('2026年2月16日');
      expect(readColumn(weekly, '超时率')[at]).toBe('62.1%');
    });

    // A-14 (A3): the stacked-coupon day stands off to the right.
    await userEvent.click(view('直播间：优惠占比 × 退款率（每天一点）'));
    await waitFor(async () => {
      const points = await reading(canvasElement);
      const shares = readColumn(points, '优惠占比').map(parseFloat);
      const at = readColumn(points, '日期').indexOf('2026年3月8日');
      expect(shares[at]).toBe(43.1);
      expect(
        Math.max(...shares.filter((_, index) => index !== at)),
      ).toBeLessThan(30);
    });

    // A-08: the top buyers with a totals row; the nickname is 「任一值」,
    // which the whole range has none of, so its totals cell stays blank.
    await userEvent.click(view('大客户 Top 20'));
    const totals = await waitFor(() => {
      const row = canvasElement.querySelector<HTMLElement>(
        '[data-slot="totals-row"]',
      );
      if (!row) throw new Error('No totals row yet.');
      return row;
    });
    const buyers = await findDataTable(canvasElement);
    const nickAt = readHeaders(buyers).findIndex(header =>
      header.startsWith('昵称'),
    );
    await expect(nickAt).toBeGreaterThan(0);
    await expect(readColumn(buyers, readHeaders(buyers)[nickAt]!)[0]).toMatch(
      /\S/,
    );
    await expect(totals.children[nickAt]?.textContent).toBe('');

    // A-15: split by the yes/no 「新客」, each series says what it is.
    await userEvent.click(view('新客与老客的 GMV'));
    await waitFor(async () =>
      expect(readHeaders(await reading(canvasElement))).toEqual(
        expect.arrayContaining(['新客：是', '新客：否']),
      ),
    );

    // A-09: the funnel loses most at payment.
    await userEvent.click(view('下单到完成的漏斗'));
    await waitFor(async () =>
      expect(readColumn(await reading(canvasElement), '数值')).toEqual([
        '20,375',
        '18,750',
        '18,317',
        '17,992',
        '17,401',
      ]),
    );
    await funnelReadsAsAFunnel(canvasElement);
  },
};

/**
 * 「这个漏斗图是不是有问题」（2026-09-25）：五段几乎一样宽，读起来像一排柱子，
 * 下单 → 付款 流失 1,625（8%）看不出来；五段画了 761px 高、每段 121px，字小小
 * 地站在右边很远。现在：库自己的漏斗，一段一个梯形；段与段之间写出流失，最大的
 * 那一步用字说出「最大流失」、加粗、带一根引线；图高按段数封顶；字就在段里或紧
 * 挨着漏斗。量的是屏幕上的东西。
 */
async function funnelReadsAsAFunnel(canvasElement: HTMLElement) {
  const funnel = await waitFor(() => {
    const found = canvasElement.querySelector<HTMLElement>(
      '[data-slot="chart"][data-chart="funnel"]',
    );
    if (!found) throw new Error('No funnel drawn yet.');
    return found;
  });
  await chartsDrawn(canvasElement);
  const texts = () =>
    [...funnel.querySelectorAll<SVGTextElement>('svg text')].filter(
      text => (text.textContent ?? '').trim() !== '',
    );
  const said = (words: string) =>
    texts().find(text => text.textContent === words);
  // Between each two stages, what was lost; the largest said in words, and
  // heavier than the rest — never by colour alone.
  await waitFor(() =>
    expect(said('\u22121,625 · \u22128.0% · 最大流失')).toBeDefined(),
  );
  for (const drop of [
    '\u2212433 · \u22122.3%',
    '\u2212325 · \u22121.8%',
    '\u2212591 · \u22123.3%',
  ])
    await expect(said(drop)).toBeDefined();
  await expect(funnel).toHaveAttribute('data-largest-drop', '1');
  const largest = said('\u22121,625 · \u22128.0% · 最大流失')!;
  const quiet = said('\u2212433 · \u22122.3%')!;
  await expect(Number(getComputedStyle(largest).fontWeight)).toBeGreaterThan(
    Number(getComputedStyle(quiet).fontWeight),
  );
  // Said to a screen reader too: the whole, and where it leaks most.
  await expect(
    funnel.querySelector('[data-slot="chart-sentence"]'),
  ).toHaveTextContent(
    '共 5 段，从 下单 20,375 到 交易完成 17,401，总转化 85.4%。流失最多在 下单 → 付款：\u22121,625（\u22128.0%）。',
  );
  // The whole funnel's conversion over it.
  await expect(
    funnel.querySelector('[data-slot="funnel-overall"]'),
  ).toHaveTextContent('总转化 85.4%（交易完成 / 下单）');

  // Five stages, each a trapezoid whose top edge is its number against the
  // largest, bounded in length.
  const stages = drawnMarks(funnel)
    .map(path => path.getBoundingClientRect())
    .sort((a, b) => a.top - b.top);
  await expect(stages).toHaveLength(5);
  for (const stage of stages)
    await expect(stage.height).toBeLessThanOrEqual(60);
  const plot = funnel
    .querySelector('[data-slot="chart-plot"]')!
    .getBoundingClientRect();
  await expect(plot.height).toBeLessThanOrEqual(5 * 60 + 16);
  await expect(funnel.getBoundingClientRect().height).toBeLessThan(400);
  // No band of nothing: the stages fill the plot's height but for its
  // margins, and each drop's words start a leader's length from the widest
  // stage — not a third of the width away.
  await expect(stages[0]!.top - plot.top).toBeLessThan(12);
  await expect(plot.bottom - stages[4]!.bottom).toBeLessThan(12);
  const widest = Math.max(...stages.map(stage => stage.right));
  const gap = largest.getBoundingClientRect().left - widest;
  await expect(gap).toBeGreaterThan(0);
  await expect(gap).toBeLessThanOrEqual(24);
  // A stage's name and numbers sit inside it.
  const paid = said('付款')!.getBoundingClientRect();
  await expect(paid.left).toBeGreaterThan(stages[1]!.left);
  await expect(paid.right).toBeLessThan(stages[1]!.right);
  await expect(paid.top).toBeGreaterThanOrEqual(stages[1]!.top);
  await expect(paid.bottom).toBeLessThanOrEqual(stages[1]!.bottom);
  // The reading table says both conversions and the drops, as the drawing.
  const table = await reading(canvasElement);
  await expect(readColumn(table, '转化率（相对第一段）').at(-1)).toBe('85.4%');
  await expect(readColumn(table, '较上一段流失')).toEqual([
    '—',
    '\u22121,625 · \u22128.0% · 最大流失',
    '\u2212433 · \u22122.3%',
    '\u2212325 · \u22121.8%',
    '\u2212591 · \u22123.3%',
  ]);
}

export const MemberAnalysis: Story = {
  ...DisplayMemberAnalysis,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      await canvas.findByRole('button', { name: /^购买次数分布/ }),
    ).toHaveAttribute('aria-current', 'true');
    await waitFor(async () =>
      expect(
        readColumn(await reading(canvasElement), '人数').slice(0, 3),
      ).toEqual(['3,777', '1,259', '508']),
    );
    // A-08 (D38): the running share along the groups sorted by 人数 — a
    // Pareto line, rising to the whole at the last group.
    const pareto = await reading(canvasElement);
    const share = readHeaders(pareto).find(header =>
      header.includes('累计占比'),
    );
    await expect(share).toBeDefined();
    const shares = readColumn(pareto, share!);
    // Once and twice make up four in five members; the tail runs to 100%.
    await expect(shares.slice(0, 3)).toEqual(['59.4%', '79.1%', '87.1%']);
    await expect(shares.at(-1)).toBe('100.0%');
    await userEvent.click(
      canvas.getByRole('button', { name: /^按首单月的复购率/ }),
    );
    await waitFor(async () =>
      expect(readColumn(await reading(canvasElement), '首单月')).toHaveLength(
        19,
      ),
    );
  },
};

/** The workbench open, a saved analysis pressed, its chart drawn. */
async function openAnalysis(canvasElement: HTMLElement, title: string) {
  const canvas = within(canvasElement);
  await userEvent.click(
    await canvas.findByRole(
      'button',
      { name: new RegExp(`^${escaped(title)}`) },
      { timeout: 10_000 },
    ),
  );
  await waitFor(() =>
    expect(
      canvasElement.querySelector('[data-slot="view-title"]'),
    ).toHaveTextContent(title),
  );
  await chartsDrawn(canvasElement);
  return canvasElement.querySelector<HTMLElement>('[data-slot="chart"]')!;
}

/** The words drawn on the chart: axis ticks, reference labels, extremes. */
const plotTexts = (frame: HTMLElement) =>
  [...frame.querySelectorAll('[data-slot="chart-plot"] svg text')].map(text =>
    (text.textContent ?? '').trim(),
  );

/** A money cell as a number: 「¥45,210.30」 → 45210.3. */
const money = (text: string) => Number(text.replace(/[¥,]/g, ''));

/**
 * 批 B in the scenes: A-11 draws the median and the daily average and the
 * promotion-day target band, which only 2025-11-11 reaches; A-12 draws a
 * 4-week moving average and a target band under the 5% red line; the
 * quarterly review buckets by QUARTER and reads a STDDEV.
 */
export const ReferencesInTheScenes: Story = {
  ...DisplayOrderAnalysis,
  name: '参考线、目标区间、移动平均与季度波动（A-11、A-12）',
  play: async ({ canvasElement }) => {
    const promotion = await openAnalysis(
      canvasElement,
      '2025 双 11 前后的日 GMV',
    );
    await waitFor(() => {
      const texts = plotTexts(promotion);
      expect(texts.some(text => /^中位数/.test(text))).toBe(true);
      expect(texts.some(text => /^日均/.test(text))).toBe(true);
      expect(texts).toContain('大促日目标');
    });
    const days = await reading(canvasElement);
    const gmv = readColumn(days, 'GMV').map(money);
    const inBand = readColumn(days, '日期').filter(
      (_, index) =>
        gmv[index]! >= DOUBLE11_DAY_TARGET.from &&
        gmv[index]! <= DOUBLE11_DAY_TARGET.to,
    );
    // Only the day itself reaches the target band.
    await expect(inBand).toEqual(['2025年11月11日']);

    const sla = await openAnalysis(canvasElement, '每周发货超时率');
    await waitFor(() => {
      const texts = plotTexts(sla);
      expect(texts).toContain('目标 ≤ 3%');
      expect(texts.some(text => /^红线 5%/.test(text))).toBe(true);
    });
    const weeks = await reading(canvasElement);
    const average = readHeaders(weeks).find(header =>
      header.includes('移动平均'),
    );
    await expect(average).toBe('4 期移动平均（算出的）');
    // The moving average smooths the Spring Festival week (62.1%) down.
    const at = readColumn(weeks, '付款周').indexOf('2026年2月16日');
    const smoothed = parseFloat(readColumn(weeks, average!)[at]!);
    await expect(smoothed).toBeGreaterThan(5);
    await expect(smoothed).toBeLessThan(62.1);

    // Quarters, dense, with the spread of one payment (STDDEV): widest in
    // the quarter of the big promotion.
    await openAnalysis(canvasElement, '季度 GMV 与单笔实付的波动');
    const quarters = await reading(canvasElement);
    await expect(readColumn(quarters, '季度')).toHaveLength(9);
    const spread = readColumn(quarters, '单笔实付的标准差').map(money);
    const widest = spread.indexOf(Math.max(...spread));
    await expect(readColumn(quarters, '季度')[widest]).toBe('2025年 Q4');
    await expect(spread[widest]).toBe(219.79);
  },
};

/**
 * 批 C in A-02: dragging across a stretch of the 25 months of daily GMV
 * opens the follow-up menu for that stretch — 「下单时间 介于 A ～ B」 — with
 * 「查看这些记录」 and 「只看这段时间」. The saved view stays as it was.
 */
export const BrushADailyStretch: Story = {
  ...DisplayOrderAnalysis,
  name: '框选一段日子（A-02）',
  play: async ({ canvasElement }) => {
    const frame = await openAnalysis(
      canvasElement,
      '日 GMV 走势（近 25 个月）',
    );
    await expect(frame).toHaveAttribute('data-brush', 'on');
    const plot = frame.querySelector<HTMLElement>('[data-slot="chart-plot"]')!;
    const box = plot.getBoundingClientRect();
    brushAcross(plot, box.left + box.width * 0.5, box.left + box.width * 0.51);
    const menu = await waitFor(() => {
      const found = document.body.querySelector<HTMLElement>(
        '[data-slot="drill-menu"]',
      );
      expect(found).toHaveAttribute(
        'aria-label',
        zhCN['label.drill.menu-span'],
      );
      return found!;
    });
    await expect(menu).toHaveTextContent(
      /下单时间 介于 \d{4}年\d{1,2}月\d{1,2}日 ~ \d{4}年\d{1,2}月\d{1,2}日/,
    );
    const items = within(menu)
      .getAllByRole('menuitem')
      .map(item => item.textContent);
    await expect(items).toEqual(
      expect.arrayContaining([
        zhCN['label.drill.records'],
        zhCN['label.drill.focus-span'],
      ]),
    );
    await userEvent.keyboard('{Escape}');
    // Brushing asked nothing of the view: it is still the saved one.
    await expect(
      canvasElement.querySelector('[data-slot="view-title"]'),
    ).toHaveTextContent('日 GMV 走势（近 25 个月）');

    // The stretch's records, under the view's own 「截至昨日」 on the same
    // field: both hold, and the records come back.
    brushAcross(plot, box.left + box.width * 0.5, box.left + box.width * 0.51);
    const again = await drillMenu(zhCN['label.drill.menu-span']);
    const stretch = again
      .querySelector('[data-slot="drill-group"]')!
      .textContent!.match(
        /\d{4}年\d{1,2}月\d{1,2}日 ~ \d{4}年\d{1,2}月\d{1,2}日/,
      )![0];
    await userEvent.click(
      within(again).getByRole('menuitem', {
        name: zhCN['label.drill.records'],
      }),
    );
    const records = await drilledRecords(canvasElement);
    await expect(records.rows).toBeGreaterThan(0);
    await expect(records.applied).toContain(stretch);
  },
};

/**
 * A-11 is scoped to 2025-10-15 ~ 11-20 on the very field it buckets by, so
 * a day of it is a range on a field the view already bounds. 「查看这些记录」
 * opens that day's orders — the day taking the stretch's place, since the
 * stretch holds it whole — and 「只看这一组」 asks the same question of the
 * day alone: one bar.
 */
export const DrillIntoADayOfDouble11: Story = {
  ...DisplayOrderAnalysis,
  name: '双 11 当天：查看这些记录与只看这一组（A-11）',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const title = '2025 双 11 前后的日 GMV';
    await openAnalysis(canvasElement, title);
    const press = async () => {
      // The day itself is the tallest bar by far (the A-11 golden).
      const tallest = await waitFor(() => {
        // Asked afresh: the chart is drawn again after 「返回」.
        const bars = drawnMarks(
          canvasElement.querySelector<HTMLElement>('[data-slot="chart"]')!,
        );
        expect(bars.length).toBeGreaterThan(30);
        return bars.reduce((one, other) =>
          other.getBoundingClientRect().height >
          one.getBoundingClientRect().height
            ? other
            : one,
        );
      });
      pressMark(tallest);
      const menu = await drillMenu(zhCN['label.drill.menu']);
      await expect(menu).toHaveTextContent('2025年11月11日');
      return menu;
    };

    await userEvent.click(
      within(await press()).getByRole('menuitem', {
        name: zhCN['label.drill.records'],
      }),
    );
    const records = await drilledRecords(canvasElement);
    await expect(records.rows).toBeGreaterThan(0);
    await expect(records.applied).toContain('2025年11月11日');
    // The day said once, in place of the stretch it lies in.
    await expect(records.applied).not.toContain('2025年10月15日');

    await userEvent.click(
      canvas.getByRole('button', {
        name: formatMessage(zhCN, 'label.origin.back', { title }),
      }),
    );
    await chartsDrawn(canvasElement);
    await userEvent.click(
      within(await press()).getByRole('menuitem', {
        name: zhCN['label.drill.focus'],
      }),
    );
    await waitFor(async () =>
      expect(readColumn(await reading(canvasElement), '日期')).toEqual([
        '2025年11月11日',
      ]),
    );
  },
};

/**
 * Every file the page hands the browser, caught before it is saved: the
 * blob and the name it goes under (as 「能力/显示收口/回归」 catches them).
 */
function catchDownloads() {
  const files: { blob: Blob; name: string }[] = [];
  const create = URL.createObjectURL.bind(URL);
  let last: Blob | undefined;
  const originalCreate = URL.createObjectURL;
  const originalClick = HTMLAnchorElement.prototype.click;
  URL.createObjectURL = (blob: Blob | MediaSource) => {
    if (blob instanceof Blob) last = blob;
    return create(blob);
  };
  HTMLAnchorElement.prototype.click = function (this: HTMLAnchorElement) {
    if (last && this.download) files.push({ blob: last, name: this.download });
  };
  return {
    files,
    restore() {
      URL.createObjectURL = originalCreate;
      HTMLAnchorElement.prototype.click = originalClick;
    },
  };
}

/**
 * 批 E in A-08: the 240 products by units sold on a log axis — the head
 * above a thousand, the tail at one, both readable — and the chart taken
 * away as a picture for the weekly report, named after the view and the day.
 */
export const LongTailOnALogAxis: Story = {
  ...DisplayOrderAnalysis,
  name: '对数轴与导出图片（A-08）',
  play: async ({ canvasElement }) => {
    const title = '商品销量长尾（近 12 个月，对数轴）';
    const frame = await openAnalysis(canvasElement, title);
    await expect(frame).toHaveAttribute('data-log', 'left');
    await expect(frame).toHaveAttribute('data-orientation', 'vertical');
    const units = readColumn(await reading(canvasElement), '件数').map(text =>
      Number(text.replace(/,/g, '')),
    );
    await expect(units).toHaveLength(240);
    await expect(units[0]).toBe(1_599);
    await expect(units.at(-1)).toBe(1);
    // Ticks by powers of ten.
    await expect(plotTexts(frame)).toEqual(
      expect.arrayContaining(['1', '10', '100', '1,000']),
    );
    await expect(legendNames(canvasElement)).toEqual([]);

    const downloads = catchDownloads();
    try {
      await userEvent.click(
        canvasElement.querySelector<HTMLElement>('[data-control="export"]')!,
      );
      await userEvent.click(
        await waitFor(() => {
          const found = document.body.querySelector<HTMLElement>(
            '[data-slot="export-image-svg"]',
          );
          if (!found) throw new Error('导出菜单没有打开');
          return found;
        }),
      );
      await waitFor(() => expect(downloads.files).toHaveLength(1));
    } finally {
      downloads.restore();
    }
    const [file] = downloads.files;
    await expect(file!.name).toBe(`${title}-2026-09-22.svg`);
    const svg = await file!.blob.text();
    await expect(svg).toContain(title);
    await expect(svg).toContain('条件');
  },
};
