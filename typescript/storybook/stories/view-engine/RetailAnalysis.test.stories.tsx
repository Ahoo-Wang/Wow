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
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  MemberAnalysis as DisplayMemberAnalysis,
  OrderAnalysis as DisplayOrderAnalysis,
} from './RetailAnalysis.stories.js';
import { chartsDrawn, drawnMarks, pressMark } from './chartDom.js';
import { findDataTable, readColumn, readHeaders } from './readTable.js';
import { ANALYSIS_GOLDEN } from './retail/goldens.js';
import { BATH_TOWEL_TITLE } from './retail/views.js';

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
];

export const OrderAnalysis: Story = {
  ...DisplayOrderAnalysis,
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
    await expect(rates.slice(0, 2)).toEqual(['25.9%', '12.8%']);

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
  },
};

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
