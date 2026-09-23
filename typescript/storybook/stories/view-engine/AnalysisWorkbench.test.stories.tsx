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
import {
  defaultMessages,
  formatMessage,
  zhCN,
} from '@ahoo-wang/fetcher-view-engine/ui';
import displayMeta, {
  BarChart as DisplayBarChart,
  CutShort as DisplayCutShort,
  CutShortTable as DisplayCutShortTable,
  DailyNewestFirst as DisplayDailyNewestFirst,
  DailyTrendCard as DisplayDailyTrendCard,
  EmptyResult as DisplayEmptyResult,
  Expandable as DisplayExpandable,
  FailingAggregates as DisplayFailingAggregates,
  FailingProcessors as DisplayFailingProcessors,
  FollowUps as DisplayFollowUps,
  FreightBands as DisplayFreightBands,
  PieChart as DisplayPieChart,
  PinnedCategoryColor as DisplayPinnedCategoryColor,
  QueryFailed as DisplayQueryFailed,
  TableWithTotals as DisplayTableWithTotals,
  TenCities as DisplayTenCities,
  TwoMetrics as DisplayTwoMetrics,
  LatestPerWarehouse as DisplayLatestPerWarehouse,
  HeatmapChart as DisplayHeatmapChart,
  LineChart as DisplayLineChart,
  OneBar as DisplayOneBar,
  ValueLabels as DisplayValueLabels,
  Loading as DisplayLoading,
  LoadingChart as DisplayLoadingChart,
} from './AnalysisWorkbench.stories.js';
import { converter } from 'culori';
import { aggregateCalls } from './fixtures.js';
import {
  amountOf,
  columnIndex,
  findDataTable,
  readColumn,
  readHeaders,
  readTotal,
} from './readTable.js';
import {
  axisTexts,
  axisTicks,
  axisTitles,
  chartsDrawn,
  drawnMarks,
  legendNames,
  overlaps,
  pressMark,
  slicesInOrder,
  valueLabels,
} from './chartDom.js';

/** Whether no two of these texts are drawn over each other. */
const apart = (texts: readonly Element[]) => {
  const boxes = texts.map(text => text.getBoundingClientRect());
  return boxes.every((box, index) =>
    boxes.slice(index + 1).every(other => !overlaps(box, other)),
  );
};

const meta = {
  ...displayMeta,
  title: 'View Engine/分析视图/分析工作台/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes a file's own
  // description into a `parameters` of its meta, which would replace the
  // display meta's — and with it the full-screen host application the
  // workbench is meant to be exercised in.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/**
 * 「金额的合计」: the two parts a metric header is composed of (D20), and
 * the same sentence anything that *names* that metric says — the funnel,
 * the menu, the removal — since none of them has the summary control
 * beside it the way the card's own title does.
 */
const AMOUNT_HEADER = formatMessage(zhCN, 'label.summary.of', {
  field: '金额',
  fn: zhCN['label.summary.fn.SUM'],
});

/** A count of records is named by what it counts, in one word. */
const COUNT_HEADER = zhCN['label.analysis.row-count'];

const toOklch = converter('oklch');

const bars = (canvas: HTMLElement) => drawnMarks(canvas);

/**
 * Each slice's category and fill, in the order the pie draws them.
 *
 * A slice is a `path` with a `d`, and only the `d` says the pie was drawn: a
 * legend entry per category renders from the same config, so a chart whose
 * container never got a size shows the words and none of the marks.
 */
const slices = (canvas: HTMLElement) => {
  // The legend names the slices in the order the pie drew them.
  const names = legendNames(canvas);
  return slicesInOrder(canvas).map((path, index) => ({
    name: names[index] ?? null,
    fill: path.getAttribute('fill'),
    drawn: (path.getAttribute('d') ?? '').length > 0,
  }));
};

/**
 * One bar per warehouse: the source grouped the rows it was asked to. The
 * numbers read as the column reads them — the amount metric is money, so the
 * axis and the tooltip say ¥ exactly as the table does, in the surface's own
 * language rather than the machine's.
 */
export const BarChart: Story = {
  ...DisplayBarChart,
  play: async ({ canvasElement }) => {
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));

    // The numbers on the axis are the column's: money, written short.
    await waitFor(() =>
      expect(
        axisTicks(canvasElement, 'left')
          .map(tick => tick.textContent)
          .join(' '),
      ).toContain('¥'),
    );
    // The tooltip reads through the same labeller, whole; what it says is
    // pinned in the package (test/cartesianOption.test.ts).
  },
};

/**
 * 数的是记录，刻度就只有整数。
 *
 * 四个仓库各有一到三单，纵轴从 0 到 3：比例尺本来会在中间放 0.5、1.5，而订单数
 * 的格式把它们写成「1」「2」，轴上读成 3、2、2、1、1、0（真实补偿服务上发现，
 * 2026-09-23）。轴上每个数都是整数时就不给小数刻度，所以这里量两件事：刻度都是
 * 整数，且没有两个刻度写成同一个字。
 */
export const WholeTicks: Story = {
  ...DisplayLatestPerWarehouse,
  args: { ...DisplayLatestPerWarehouse.args, layout: 'chart' },
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(bars(canvasElement).length).toBeGreaterThan(0));
    const labels = await waitFor(() => {
      const found = axisTicks(canvasElement, 'left').map(tick =>
        (tick.textContent ?? '').trim(),
      );
      expect(found.length).toBeGreaterThan(1);
      return found;
    });
    await expect(labels.every(label => /^\d+$/.test(label))).toBe(true);
    await expect(new Set(labels).size).toBe(labels.length);
  },
};

/**
 * 报表有个底：结果区最后一行固定写「正在显示 N 组，耗时 X 秒」。
 *
 * 分析结果从前在最后一根柱子、最后一行下面就结束了，下面的空白读起来像报表
 * 掉了下去（用户 2026-09-23）。这里量三件事：那一行说的是屏幕上的行数与耗时，
 * 它是结果区的最后一行，它的下边就是结果区的下边——工作台填满容器，所以这就是
 * 工作区的底。
 */
export const CaptionHoldsTheReport: Story = {
  ...DisplayBarChart,
  play: async ({ canvasElement }) => {
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
    const caption = canvasElement.querySelector<HTMLElement>(
      '[data-slot="analysis-caption"]',
    )!;
    await expect(caption.textContent).toMatch(
      /^正在显示 4 组，耗时 [\d.]+ 秒$/,
    );
    const block = caption.parentElement!;
    await expect(block.dataset.slot).toBe('result-block');
    await expect(block.lastElementChild).toBe(caption);
    await expect(
      Math.abs(
        caption.getBoundingClientRect().bottom -
          block.getBoundingClientRect().bottom,
      ),
    ).toBeLessThanOrEqual(1);
  },
};

/**
 * 每一个刻度的字都在图里。
 *
 * 刻度字以刻度为中心，最后一个会伸出绘图区半个字宽：真实补偿服务上最后一天读成
 * 「2026年9月22E」，横向图最后一个数读成「600,00(」；横向图的分类轴从前是写死的
 * 96px，把长处理器名从左边截成「kEventProcessor」（2026-09-23）。这里量每一个刻度
 * 字的框都在图的 `svg` 之内，且图离结果区的左右两边都留着工作列的 16px。
 */
export const TicksInsideTheChart: Story = {
  ...DisplayBarChart,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
    const surface = canvasElement
      .querySelector('[data-slot="chart-plot"] svg')!
      .getBoundingClientRect();
    // And the drawing keeps the column's 16px gutter: the result band runs
    // to the edge, and the axis numbers used to sit against it.
    const block = canvasElement
      .querySelector('[data-slot="result-block"]')!
      .getBoundingClientRect();
    await expect(surface.left - block.left).toBeGreaterThanOrEqual(15);
    // On both sides: a chart that is the band's full width and then pushed
    // 16px in overhangs the right edge, and the band clips its last tick
    // (「2026年9」 on the real service's monthly line).
    await expect(block.right - surface.right).toBeGreaterThanOrEqual(15);
    const ticks = axisTexts(canvasElement);
    await expect(ticks.length).toBeGreaterThan(0);
    // And no two of them on each other.
    await expect(apart(ticks)).toBe(true);
    for (const tick of ticks) {
      const box = tick.getBoundingClientRect();
      await expect(box.left).toBeGreaterThanOrEqual(surface.left - 1);
      await expect(box.right).toBeLessThanOrEqual(surface.right + 1);
    }
  },
};

/**
 * 每根柱上的数：三十天里写得下的都写了，没有两个压在一起，也没有一个跑出图外。
 * 数写得短——与刻度同一个读法（D21）。
 */
export const ValueLabelsApart: Story = {
  ...DisplayValueLabels,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(bars(canvasElement).length).toBeGreaterThan(10));
    const labels = await waitFor(() => {
      const found = valueLabels(canvasElement);
      expect(found.length).toBeGreaterThan(5);
      return found;
    });
    await expect(apart(labels)).toBe(true);
    const surface = canvasElement
      .querySelector('[data-slot="chart-plot"] svg')!
      .getBoundingClientRect();
    for (const label of labels) {
      const box = label.getBoundingClientRect();
      await expect(box.left).toBeGreaterThanOrEqual(surface.left - 1);
      await expect(box.right).toBeLessThanOrEqual(surface.right + 1);
      await expect(box.top).toBeGreaterThanOrEqual(surface.top - 1);
    }
    // The labels read as the ticks do: whole counts, never 「1.0」.
    await expect(
      labels.every(label => /^\d+$/.test(label.textContent ?? '')),
    ).toBe(true);
  },
};

/**
 * 折线的每个点都有一颗圆点，两端的点离左右两根轴都有距离——线不贴着绘图区的
 * 边（另一会话在真实服务上报：折线碰到两端、没有点），两根数值轴各有标题。
 */
export const LineKeepsOffTheEdges: Story = {
  ...DisplayLineChart,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    // Two metrics over four warehouses: a dot on every point.
    const dots = await waitFor(() => {
      const found = drawnMarks(canvasElement);
      expect(found).toHaveLength(8);
      return found.map(dot => dot.getBoundingClientRect());
    });
    const left = axisTicks(canvasElement, 'left').map(tick =>
      tick.getBoundingClientRect(),
    );
    const right = axisTicks(canvasElement, 'right').map(tick =>
      tick.getBoundingClientRect(),
    );
    const firstDot = Math.min(...dots.map(dot => dot.left));
    const lastDot = Math.max(...dots.map(dot => dot.right));
    await expect(
      firstDot - Math.max(...left.map(tick => tick.right)),
    ).toBeGreaterThan(20);
    await expect(
      Math.min(...right.map(tick => tick.left)) - lastDot,
    ).toBeGreaterThan(20);
    await expect(
      axisTitles(canvasElement).map(title => title.textContent),
    ).toEqual(expect.arrayContaining([AMOUNT_HEADER, COUNT_HEADER]));
  },
};

/**
 * 热力图铺满它的绘图区，格子上的数两两不相交，底下有一条色标：量的是格子占了
 * 图的大半宽，而不是挤在一角。
 */
export const HeatmapFillsItsPlot: Story = {
  ...DisplayHeatmapChart,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const frame = canvasElement.querySelector<HTMLElement>(
      '[data-slot="chart"][data-chart="heatmap"]',
    )!;
    // A cell is as see-through as its value is low, so every shade counts.
    const cells = await waitFor(() => {
      const found = [
        ...frame.querySelectorAll('[data-slot="chart-plot"] svg path'),
      ].filter(
        path =>
          (path.getAttribute('fill') ?? '').startsWith('rgb') &&
          Number(path.getAttribute('fill-opacity') ?? 1) > 0,
      );
      expect(found.length).toBe(Number(frame.getAttribute('data-marks')));
      return found.map(cell => cell.getBoundingClientRect());
    });
    const plot = frame
      .querySelector('[data-slot="chart-plot"]')!
      .getBoundingClientRect();
    const span =
      Math.max(...cells.map(cell => cell.right)) -
      Math.min(...cells.map(cell => cell.left));
    await expect(span / plot.width).toBeGreaterThan(0.6);
    await expect(apart(valueLabels(frame))).toBe(true);
    await expect(valueLabels(frame).length).toBeGreaterThan(0);
  },
};

/** 一组也只是一根柱子的宽，不是一整块（`BAR_MAX_WIDTH`）。 */
export const OneBarKeepsItsWidth: Story = {
  ...DisplayOneBar,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(1));
    const [bar] = bars(canvasElement);
    await expect(bar!.getBoundingClientRect().width).toBeLessThanOrEqual(48.5);
  },
};

/**
 * 按下一根柱子，追问菜单挂在按下的那一点上（D20 追问）：柱子是图库画的，
 * 按下交出的是这一组与指针的位置，与表格的一行交出的是同一个菜单。
 */
export const FollowUpFromABar: Story = {
  ...DisplayFollowUps,
  play: async ({ canvasElement }) => {
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
    await chartsDrawn(canvasElement);
    const [bar] = bars(canvasElement);
    const box = bar!.getBoundingClientRect();
    // The pointer over the bar raises its tooltip first, as a reader's does.
    bar!.dispatchEvent(
      new MouseEvent('mousemove', {
        bubbles: true,
        clientX: box.left + box.width / 2,
        clientY: box.top + box.height / 2,
      }),
    );
    const tooltip = () =>
      canvasElement.querySelector<HTMLElement>('[data-slot="chart-tooltip"]');
    await waitFor(() => expect(tooltip()).toBeVisible());
    pressMark(bar!);
    const menu = await drillMenu();
    const opened = await settled(menu);
    // The menu is the answer to the press: the tooltip steps aside rather
    // than sit over its first items.
    await waitFor(() => expect(tooltip()).not.toBeVisible());
    // Hung from the point pressed — an edge of the menu at it — not from
    // the chart's corner.
    const x = box.left + box.width / 2;
    const y = box.top + box.height / 2;
    const near = (a: number, b: number) => Math.abs(a - b) < 24;
    await expect(near(opened.left, x) || near(opened.right, x)).toBe(true);
    await expect(near(opened.top, y) || near(opened.bottom, y)).toBe(true);
    await expect(
      within(menu)
        .getAllByRole('menuitem')
        .map(item => item.textContent),
    ).toContain(zhCN['label.drill.records']);
    await userEvent.keyboard('{Escape}');
  },
};

/**
 * A day as its label writes it, as numbers to compare: 「2026/9/3」,
 * 「9月3日」 and 「2026-09-03」 all read as their digits in order, which is
 * all an ordering needs — whichever way the surface's language spells a day.
 */
const dayOf = (text: string) => (text.match(/\d+/g) ?? []).map(Number);

/** Whether `later` is a later day than `earlier`, part by part. */
function after(later: number[], earlier: number[]): boolean {
  const differs = later.findIndex((part, index) => part !== earlier[index]);
  return differs !== -1 && later[differs] > earlier[differs];
}

/** Whether each day comes strictly after the one before it. */
const runsForward = (days: number[][]) =>
  days.every((day, index) => index === 0 || after(day, days[index - 1]));

/**
 * 时间轴从左往右走，不管视图怎么排序。
 *
 * 「每日新增失败」按日倒序存着——表格今天在最上面——而同一批行照着这个顺序画成
 * 柱，今天落在原点、昨天在它右边，整张图读反了（2026-09-23 审查）。这里量画出来
 * 的横轴：刻度按屏幕上的左右排好，读出来的日子一天比一天晚；再切到表格，同一批
 * 日子是倒着的——表格仍是视图自己的顺序。
 */
export const TimeRunsForward: Story = {
  ...DisplayDailyNewestFirst,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(bars(canvasElement).length).toBeGreaterThan(10));
    const drawn = await waitFor(() => {
      const ticks = axisTicks(canvasElement, 'bottom');
      expect(ticks.length).toBeGreaterThan(2);
      return ticks
        .map(tick => ({
          left: tick.getBoundingClientRect().left,
          day: dayOf(tick.textContent ?? ''),
        }))
        .sort((a, b) => a.left - b.left)
        .map(tick => tick.day);
    });
    await expect(runsForward(drawn)).toBe(true);

    await userEvent.click(
      within(canvasElement).getByRole('button', {
        name: zhCN['label.layout.table'],
      }),
    );
    const table = await findDataTable(canvasElement);
    // The time dimension's header says what one row spans: 「创建时间（按日）」
    // (2026-09-23 audit) — a column of dates does not say it alone.
    const listed = readColumn(
      table,
      formatMessage(zhCN, 'label.analysis.dated.DAY', { field: '创建时间' }),
    ).map(dayOf);
    await expect(listed.length).toBeGreaterThan(10);
    await expect(runsForward([...listed].reverse())).toBe(true);
  },
};

/**
 * 指标卡的迷你趋势同样从最早的一天画起：它没有刻度，所以读的是图旁边那张读屏
 * 表——它与那根线出自同一份投影，从前它和线一起倒着走。
 */
export const SparklineRunsForward: Story = {
  ...DisplayDailyTrendCard,
  play: async ({ canvasElement }) => {
    const reading = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="chart-reading"] table',
      );
      if (!found) throw new Error('指标卡旁边没有读屏表');
      const days = [...(found as HTMLTableElement).tBodies[0].rows]
        .map(row => dayOf(row.cells[0]?.textContent ?? ''))
        // The headline and its comparison are rows too; a day has three
        // numbers in it, and neither of those names one.
        .filter(day => day.length === 3);
      expect(days.length).toBeGreaterThan(10);
      return days;
    });
    await expect(runsForward(reading)).toBe(true);
  },
};

/**
 * 十个目的城市，八种颜色，不重复。
 *
 * 色板从前只有五色、循环取用，第六个类目起与前面的同色——两片一个颜色，图例分
 * 不出谁是谁（2026-09-23 审查）。现在色板八色，饼图在第八片把尾巴并进「其他」：
 * 这里量浏览器真正画出来的填充色，八片八种；最后一片是「其他」，它是灰的，不占
 * 任何一个类目的颜色。
 */
export const EightColoursThenOther: Story = {
  ...DisplayTenCities,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const drawn = await waitFor(() => {
      const paths = slicesInOrder(canvasElement);
      expect(paths).toHaveLength(8);
      return paths.map(path => getComputedStyle(path).fill);
    });
    await expect(new Set(drawn).size).toBe(8);
    // The remainder is grey — no hue to speak of — and every slot has one.
    const chroma = drawn.map(fill => toOklch(fill)?.c ?? 0);
    await expect(chroma.at(-1)).toBeLessThan(0.02);
    await expect(chroma.slice(0, -1).every(c => c > 0.1)).toBe(true);
    await expect(legendNames(canvasElement)).toContain(
      zhCN['label.chart.other'],
    );
  },
};

/** 追问菜单本身：它弹在文档上，不在画布里。 */
const drillMenu = () =>
  waitFor(() => {
    const found = document.body.querySelector<HTMLElement>(
      '[data-slot="drill-menu"]',
    );
    if (!found) throw new Error('追问菜单没有弹出来');
    // 在文档里还不等于看得见：菜单是淡进来的（`data-open:animate-in
    // fade-in-0`，100ms），弹出的那一帧算出来的 opacity 正好是 0。所以等它淡
    // 进来为止，下一步问「这一条可见吗」问的才是这一条自己的事，而不是撞上
    // 动画的第一帧。
    expect(found).toBeVisible();
    return found;
  });

/** 从一组开出来的视图头上那条「返回 X」。 */
const originBar = () =>
  waitFor(() => {
    const found = document.body.querySelector<HTMLElement>(
      '[data-slot="origin-bar"]',
    );
    if (!found) throw new Error('没有「返回」那一条');
    return found;
  });

/**
 * 从一组开出来的视图每件事只说一遍（2026-09-23 审查）：标题说它是什么，
 * 「返回」那条说从哪来、怎么回去，这一组的条件只在「正在显示」那条上，
 * 编辑器收着。从前来源的名字说两遍（「返回 X · 来自 X」），条件说三遍——
 * 「来自」那条、「正在显示」那条、再加上自动展开的编辑器。
 */
async function saysEachThingOnce(
  canvasElement: HTMLElement,
  title: string,
  condition: string,
) {
  const line = await originBar();
  await expect(line).toHaveTextContent(new RegExp(`^${BACK}$`));
  await expect(
    within(canvasElement).getByRole('heading', { level: 2, name: title }),
  ).toBeVisible();
  await waitFor(() =>
    expect(
      within(appliedBar(canvasElement)).getByText(condition),
    ).toBeVisible(),
  );
  // 屏幕上除了标题里那一次，条件只在「正在显示」那条上出现。
  await expect(
    within(canvasElement)
      .getAllByText(condition)
      .filter(found => found.closest('h2') === null),
  ).toHaveLength(1);
  await expect(
    canvasElement.querySelector(
      '[data-slot="editor-toggle"] [aria-expanded="true"]',
    ),
  ).toBeNull();
  return line;
}

const BACK = formatMessage(zhCN, 'label.origin.back', {
  title: '仓库金额分布',
});

/** 按下的那一组，用菜单标题与「正在显示」那条共用的词说出来。 */
const SOUTH = `仓库 ${zhCN['label.operator.IN']} 华南`;

/** 从一组开出来的视图叫什么：「{是什么} · {这一组}」。 */
const titled = (subject: string, group: string) =>
  formatMessage(zhCN, 'label.drill.titled', { subject, group });

/** 「正在显示」那条：结果的行是在哪些条件下取来的。 */
const appliedBar = (canvasElement: HTMLElement) =>
  within(canvasElement).getByRole('region', {
    name: zhCN['label.applied.title'],
  });

/**
 * 追问（D20 Ⅳ）：按下一根柱子，弹出这一组的三项。
 *
 * 菜单没有自己的触发控件——按下去的那根柱子就是触发——所以它是真的被那根柱子
 * 的点击打开的，而不是被某个按钮打开的；标题是这一组的条件，用的是「正在显示」
 * 那条用的同一套词。
 *
 * 「查看这些记录」在同一个工作台里开出一个未保存的记录视图，叫「订单 · 仓库
 * 属于 华南」——它是订单里华南那一组；标题栏下一颗「返回 仓库金额分布」，条件
 * 只在「正在显示」那条上，编辑器收着；下面是华南那两单。按「返回」回到原来
 * 那次聚合结果——图还在，没有重跑。
 */
export const FollowUpToRecords: Story = {
  ...DisplayFollowUps,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));

    // 第三根是华南：结果按仓库分的四组，顺序就是画上去的顺序。
    await chartsDrawn(canvasElement);
    pressMark(bars(canvasElement)[2]!);

    const menu = await drillMenu();
    await expect(within(menu).getByText(SOUTH)).toBeVisible();
    await expect(
      within(menu)
        .getAllByRole('menuitem')
        .map(item => item.textContent),
    ).toEqual([
      zhCN['label.drill.records'],
      zhCN['label.drill.split'],
      zhCN['label.drill.focus'],
    ]);

    await userEvent.click(
      within(menu).getByRole('menuitem', {
        name: zhCN['label.drill.records'],
      }),
    );

    // 记录视图，不是聚合：华南的两单，按明细列出来。
    const table = await findDataTable(canvasElement);
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(['SO-1004', 'SO-1005']),
    );
    const line = await saysEachThingOnce(
      canvasElement,
      titled('订单', SOUTH),
      SOUTH,
    );
    const ran = aggregateCalls.current;

    await userEvent.click(within(line).getByRole('button', { name: BACK }));

    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
    await expect(
      canvas.getByRole('heading', { level: 2, name: '仓库金额分布' }),
    ).not.toHaveAttribute('data-dirty');
    await expect(
      document.body.querySelector('[data-slot="origin-bar"]'),
    ).toBeNull();
    await expect(aggregateCalls.current).toBe(ran);
  },
};

/**
 * 同一个菜单，从一枚扇区上弹出来——图表家族换了，手势没换。
 *
 * 这个工作台只列分析视图，所以没有「查看这些记录」：下钻开出来的是记录视图，
 * 开不出来的地方就不摆这一项。「只看这一组」是同一个问题只问这一组，开在
 * 原来那个旁边（2026-09-23 审查）：一个未保存的分析视图，叫「仓库金额分布 ·
 * 仓库 属于 华南」，图上只剩华南，和「查看这些记录」一样有一颗「返回」——
 * 按下去是原来那次结果，四个仓库都在，不重跑，原来那个视图也没被改脏。
 */
export const FollowUpFocus: Story = {
  ...DisplayPieChart,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await chartsDrawn(canvasElement);
    // Named once the legend has listed every slice, not as the first one
    // lands: under load the pie drew before its legend had all three.
    const before = await waitFor(() => {
      const names = slices(canvasElement).map(slice => slice.name);
      expect(names).toHaveLength(3);
      expect(names.every(name => name !== null)).toBe(true);
      return names;
    });
    pressMark(slicesInOrder(canvasElement)[0]!);

    const menu = await drillMenu();
    await expect(
      within(menu)
        .getAllByRole('menuitem')
        .map(item => item.textContent),
    ).toEqual([zhCN['label.drill.split'], zhCN['label.drill.focus']]);

    await userEvent.click(
      within(menu).getByRole('menuitem', { name: zhCN['label.drill.focus'] }),
    );

    // 图上只剩这一组；它是一个自己的视图，每件事只说一遍。
    await waitFor(() =>
      expect(slices(canvasElement).map(slice => slice.name)).toEqual(['华南']),
    );
    const line = await saysEachThingOnce(
      canvasElement,
      titled('仓库金额分布', SOUTH),
      SOUTH,
    );
    const ran = aggregateCalls.current;

    await userEvent.click(within(line).getByRole('button', { name: BACK }));

    // 原来那次结果，原样回来：不重跑，也没有什么要保存的。
    await waitFor(() =>
      expect(slices(canvasElement).map(slice => slice.name)).toEqual(before),
    );
    await expect(
      canvas.getByRole('heading', { level: 2, name: '仓库金额分布' }),
    ).not.toHaveAttribute('data-dirty');
    await expect(
      document.body.querySelector('[data-slot="origin-bar"]'),
    ).toBeNull();
    await expect(aggregateCalls.current).toBe(ran);
  },
};

/**
 * 运费区间读成一段一段（2026-09-23 真实后端走查）。
 *
 * 按 500 一档分组，一档的键是它的下界，从前横轴与表格读成「¥0.00」「¥500.00」，
 * 说不出一行是哪一段。这里量画出来的横轴、读屏表、切到表格后的那一列与按下
 * 一行弹出的追问菜单标题：都读成「¥0～500」「¥500～1000」「¥1000～1500」。
 */
export const BandsReadAsRanges: Story = {
  ...DisplayFreightBands,
  play: async ({ canvasElement }) => {
    const bands = ['¥0～500', '¥500～1000', '¥1000～1500'];
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(3));
    const ticks = await waitFor(() => {
      const found = axisTicks(canvasElement, 'bottom')
        .sort(
          (a, b) =>
            a.getBoundingClientRect().left - b.getBoundingClientRect().left,
        )
        .map(tick => tick.textContent);
      expect(found).toHaveLength(3);
      return found;
    });
    await expect(ticks).toEqual(bands);
    const reading = canvasElement.querySelector<HTMLElement>(
      '[data-slot="chart-reading"] table',
    );
    await expect(reading).not.toBeNull();
    for (const band of bands)
      await expect(within(reading!).getByText(band)).toBeInTheDocument();

    await userEvent.click(
      within(canvasElement).getByRole('button', {
        name: zhCN['label.layout.table'],
      }),
    );
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(readColumn(table, '运费')).toEqual(bands));

    const row =
      canvasElement.querySelector<HTMLTableRowElement>('tr[data-pickable]');
    await userEvent.click(row!.cells[1]!);
    const menu = await drillMenu();
    await expect(
      menu.querySelector('[data-slot="drill-group"]'),
    ).toHaveTextContent(
      new RegExp(
        `^${formatMessage(zhCN, 'label.drill.bucket', { field: '运费', bucket: bands[0] })}$`,
      ),
    );
  },
};

/**
 * 按日分组的一行，菜单标题读作表格那一格读的样子——「创建时间 在 2026年9月
 * 21日」——而不是它背后那两个精确到毫秒的时刻（2026-09-23 审查）。只看这一组
 * 开出来的视图也照这个说法起名。
 */
export const FollowUpOnADay: Story = {
  ...DisplayDailyNewestFirst,
  args: { ...DisplayDailyNewestFirst.args, layout: 'table' },
  play: async ({ canvasElement }) => {
    const row = await waitFor(() => {
      const found =
        canvasElement.querySelector<HTMLTableRowElement>('tr[data-pickable]');
      if (!found) throw new Error('结果还没有行');
      return found;
    });
    const day = row.cells[0]!.textContent ?? '';
    await expect(day).toMatch(/^\d{4}年\d{1,2}月\d{1,2}日$/);
    const group = formatMessage(zhCN, 'label.drill.bucket', {
      field: '创建时间',
      bucket: day,
    });

    await userEvent.click(row.cells[1]!);
    const menu = await drillMenu();
    await expect(
      menu.querySelector('[data-slot="drill-group"]'),
    ).toHaveTextContent(new RegExp(`^${group}$`));

    await userEvent.click(
      within(menu).getByRole('menuitem', { name: zhCN['label.drill.focus'] }),
    );
    await originBar();
    await expect(
      within(canvasElement).getByRole('heading', {
        level: 2,
        name: titled('运单分析', group),
      }),
    ).toBeVisible();
  },
};

/**
 * 「按其他维度细分…」是一层子菜单，而子菜单在真浏览器里是**悬停**展开的（点一下
 * 反而是在开与关之间来回）——这是只有真指针验得了的一条，jsdom 里点开与悬停
 * 展开是同一回事。
 *
 * 拆完之后是同一个问题换一个维度问：范围收到这一组，维度换成状态，上一维度
 * 的名字从排序、表列与图表槽位里一并退场（`analysis/drill.ts` 的 `splitBy`）。
 * 它和另外两项一样开在旁边（用户 2026-09-23 拍板）：一个未保存的分析视图，
 * 叫「仓库金额分布 · 仓库 属于 华南」，带「返回」；按下去是原来那次按仓库分的
 * 结果，不重跑，原来那个视图也没被改脏。
 */
export const FollowUpSplit: Story = {
  ...DisplayFollowUps,
  args: { ...DisplayFollowUps.args, layout: 'table' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await findDataTable(canvasElement);
    await waitFor(() =>
      expect(readColumn(table, '仓库')).toEqual([
        '华东',
        '华北',
        '华南',
        '西南',
      ]),
    );

    // 表格布局是键盘走的那条路（F10）：行能聚焦，回车弹出同一个菜单。
    const row =
      canvasElement.querySelectorAll<HTMLElement>('tr[data-pickable]')[2];
    await expect(row).toHaveAttribute('aria-haspopup', 'menu');
    row.focus();
    await userEvent.keyboard('{Enter}');

    const menu = await drillMenu();
    await userEvent.hover(
      within(menu).getByRole('menuitem', { name: zhCN['label.drill.split'] }),
    );
    // 已经分了的那一维不在里面：按它再细分分不出东西来。
    const split = await waitFor(() => {
      const found = document.body.querySelector<HTMLElement>(
        '[data-slot="dropdown-menu-sub-content"]',
      );
      if (!found) throw new Error('子菜单没有展开');
      return found;
    });
    await expect(
      within(split)
        .getAllByRole('menuitem')
        .map(item => item.textContent),
    ).toEqual(['状态']);

    await userEvent.click(
      within(split).getByRole('menuitem', { name: '状态' }),
    );

    const after = await findDataTable(canvasElement);
    await waitFor(() =>
      expect(readColumn(after, '状态')).toEqual(['已发运', '待出库']),
    );
    const line = await saysEachThingOnce(
      canvasElement,
      titled('仓库金额分布', SOUTH),
      SOUTH,
    );
    const ran = aggregateCalls.current;

    await userEvent.click(within(line).getByRole('button', { name: BACK }));

    // 原来那次按仓库分的结果，原样回来：不重跑，标题栏也没有未保存的改动。
    await waitFor(async () =>
      expect(readColumn(await findDataTable(canvasElement), '仓库')).toEqual([
        '华东',
        '华北',
        '华南',
        '西南',
      ]),
    );
    await expect(
      canvas.getByRole('heading', { level: 2, name: '仓库金额分布' }),
    ).not.toHaveAttribute('data-dirty');
    await expect(
      document.body.querySelector('[data-slot="origin-bar"]'),
    ).toBeNull();
    await expect(aggregateCalls.current).toBe(ran);
  },
};

/** A menu's box once it has finished opening: it zooms in from 95%. */
async function settled(menu: HTMLElement): Promise<DOMRect> {
  await Promise.all(menu.getAnimations().map(animation => animation.finished));
  return menu.getBoundingClientRect();
}

/**
 * 追问菜单按自己的字那么宽，挂在按下去的那一格下面（2026-09-23 审查）。
 *
 * 它从前锚在整行上，而弹层配方的宽是 `--anchor-width`，于是菜单和整张表一样
 * 宽——横在结果上的一条带子。这里量两条路：指针按在一格上，菜单从那一格下面
 * 弹出、宽度不到表的一半；键盘在行上回车，菜单挂在这一行的第一格下面，左边
 * 与它对齐。
 */
export const FollowUpMenuFitsItsWords: Story = {
  ...DisplayFollowUps,
  args: { ...DisplayFollowUps.args, layout: 'table' },
  play: async ({ canvasElement }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(readColumn(table, '仓库')).toHaveLength(4));
    const width = table.getBoundingClientRect().width;
    const row =
      canvasElement.querySelectorAll<HTMLTableRowElement>(
        'tr[data-pickable]',
      )[1]!;

    // A pointer on the row's second cell: the menu opens under that cell,
    // as wide as its words and never as wide as the row.
    const cell = row.cells[1]!;
    await userEvent.click(cell);
    let box = await settled(await drillMenu());
    const pressed = cell.getBoundingClientRect();
    await expect(box.width).toBeGreaterThanOrEqual(224 - 0.5);
    await expect(box.width).toBeLessThanOrEqual(320 + 0.5);
    await expect(box.width).toBeLessThan(width / 2);
    await expect(box.top).toBeGreaterThanOrEqual(pressed.bottom);
    await expect(box.left).toBeLessThanOrEqual(pressed.right);
    await expect(box.right).toBeGreaterThanOrEqual(pressed.left);

    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(
        document.body.querySelector('[data-slot="drill-menu"]'),
      ).toBeNull(),
    );
    // Handed back to the row it came from, which is where Enter is pressed.
    await waitFor(() => expect(document.activeElement).toBe(row));

    // A key has no point: the row's first cell, the menu's start on its start.
    await userEvent.keyboard('{Enter}');
    box = await settled(await drillMenu());
    const first = row.cells[0]!.getBoundingClientRect();
    await expect(Math.abs(box.left - first.left)).toBeLessThan(1);
    await expect(box.top).toBeGreaterThanOrEqual(first.bottom);
    await expect(box.width).toBeLessThan(width / 2);
    await userEvent.keyboard('{Escape}');
  },
};

export const TwoMetrics: Story = {
  ...DisplayTwoMetrics,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(8));
    // The count has an axis of its own on the right, beside the amount's.
    await waitFor(() =>
      expect(axisTicks(canvasElement, 'right').length).toBeGreaterThan(1),
    );
    await expect(axisTicks(canvasElement, 'left').length).toBeGreaterThan(1);
  },
};

export const TableWithTotals: Story = {
  ...DisplayTableWithTotals,
  play: async ({ canvasElement }) => {
    const table = await within(canvasElement).findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '仓库')).toEqual([
        '华东',
        '华北',
        '华南',
        '西南',
      ]),
    );
    // A metric is headed by its two parts, never by the alias the query
    // carried: 「金额的合计」, and a count of records by what it counts.
    await expect(readColumn(table, COUNT_HEADER)).toEqual(['2', '1', '2', '1']);
    await expect(readColumn(table, AMOUNT_HEADER).map(amountOf)).toEqual([
      1920, 2450, 4880, 980,
    ]);
    // The totals row comes from its own ungrouped query over the same rows.
    await expect(readTotal(table, COUNT_HEADER)).toBe('6');
    await expect(amountOf(readTotal(table, AMOUNT_HEADER))).toBe(10230);
  },
};

export const PieChart: Story = {
  ...DisplayPieChart,
  play: async ({ canvasElement }) => {
    // The two smallest warehouses merge into one slice.
    await waitFor(() =>
      expect(slices(canvasElement).map(slice => slice.name)).toEqual([
        '华南',
        '华北',
        // The tail the kernel merges is named by the catalogue in force.
        zhCN['label.chart.other'],
      ]),
    );
    // Drawn, not merely present: the legend alone is not a pie.
    await expect(slices(canvasElement).every(slice => slice.drawn)).toBe(true);
  },
};

export const PinnedCategoryColor: Story = {
  ...DisplayPinnedCategoryColor,
  play: async ({ canvasElement }) => {
    await waitFor(() => expect(slices(canvasElement)).toHaveLength(3));
    const [south, ...others] = slices(canvasElement);
    // Pinned as `#7c3aed`, handed to the drawing as the colour it is.
    await expect(south).toEqual({
      name: '华南',
      fill: 'rgb(124, 58, 237)',
      drawn: true,
    });
    for (const slice of others)
      await expect(slice.fill).not.toBe('rgb(124, 58, 237)');
  },
};

/** The sentence the strip says when the probe row came back. */
const CUT_SHORT = zhCN['analysis.result.more-groups'].replace('{limit}', '2');

/** The status-line strip: the one `status` that wears the strip's slot. */
async function findStrip(canvas: ReturnType<typeof within>) {
  const strips = await canvas.findAllByRole('status');
  const strip = strips.find(
    (found: HTMLElement) => found.getAttribute('data-slot') === 'status-strip',
  );
  if (!strip) throw new Error('no status strip on the status line');
  return strip;
}

/**
 * A pie drawn from part of a grouping. Two of the four warehouses are on the
 * chart, and each slice's share is of those two — which is exactly the
 * reading a pie invites and exactly the one that is wrong here. The query
 * asked for three rows and got three, so the line above it says there are
 * more groups rather than guessing that there might be.
 */
export const CutShort: Story = {
  ...DisplayCutShort,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await waitFor(() => expect(slices(canvasElement)).toHaveLength(2));
    await expect(slices(canvasElement).map(slice => slice.name)).toEqual([
      '华南',
      '华北',
    ]);
    await expect(slices(canvasElement).every(slice => slice.drawn)).toBe(true);
    // The shares are of the two shown, and the pie says so where its key is.
    await expect(
      canvasElement.querySelector('[data-slot="pie-measure"]'),
    ).toHaveTextContent(zhCN['label.chart.share-basis']);

    // The strip, not the result's live region: both are `status`, and only
    // the strip is on the status line.
    await expect(await findStrip(canvas)).toHaveTextContent(CUT_SHORT);
  },
};

/** The same cut as rows: the table gets the same line, from the same result. */
export const CutShortTable: Story = {
  ...DisplayCutShortTable,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '仓库')).toEqual(['华南', '华北']),
    );
    // The totals row answers its own ungrouped query, so it still covers
    // every order — which is how two rows can add up to less than the total
    // under them without either number being wrong.
    await expect(amountOf(readTotal(table, AMOUNT_HEADER))).toBe(10230);

    await expect(await findStrip(canvas)).toHaveTextContent(CUT_SHORT);
  },
};

/** The header cell, a body cell and the totals cell of one column. */
function columnCells(table: HTMLElement, header: string): HTMLElement[] {
  const grid = table as HTMLTableElement;
  const index = columnIndex(table, header);
  return [
    grid.tHead!.rows[0]!.cells[index]!,
    ...[...grid.tBodies[0]!.rows].map(row => row.cells[index]!),
    ...(grid.tFoot ? [grid.tFoot.rows[0]!.cells[index]!] : []),
  ];
}

/**
 * How far a cell's text stands from the cell's own right content edge, in
 * pixels: 0 for a number read from the right, the rest of the column for one
 * read from the left.
 */
function gapOnTheRight(cell: HTMLElement): number {
  const range = document.createRange();
  // A header's name is in its label; a cell's text is the cell.
  range.selectNodeContents(
    cell.querySelector('[data-slot="column-label"]') ?? cell,
  );
  const inner =
    cell.getBoundingClientRect().right -
    parseFloat(getComputedStyle(cell).paddingRight);
  return inner - range.getBoundingClientRect().right;
}

/**
 * 一张分析师的表（2026-09-23 审查 P1）：数字靠右、等宽数字，表头与合计格同一
 * 条右边线；维度靠左。「合计」下面看得见一行「范围内全部记录」——它是文字，不是
 * 控件，页脚里没有可聚焦的东西。
 */
export const TableReadsLikeATable: Story = {
  ...DisplayTableWithTotals,
  play: async ({ canvasElement }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() =>
      expect(readColumn(table, AMOUNT_HEADER).map(amountOf)).toEqual([
        1920, 2450, 4880, 980,
      ]),
    );

    for (const header of [AMOUNT_HEADER, COUNT_HEADER])
      for (const cell of columnCells(table, header)) {
        await expect(getComputedStyle(cell).textAlign).toBe('right');
        await expect(getComputedStyle(cell).fontVariantNumeric).toContain(
          'tabular-nums',
        );
        // Right against the edge, not merely declared so: the digits end
        // where the cell's content ends, the header's name with them.
        await expect(gapOnTheRight(cell)).toBeLessThan(4);
      }
    // The dimension reads from the left: its text ends well short of the edge.
    const [, warehouse] = columnCells(table, '仓库');
    await expect(gapOnTheRight(warehouse!)).toBeGreaterThan(20);

    const heading = table.querySelector<HTMLElement>(
      '[data-slot="totals-heading"]',
    )!;
    const scope = heading.querySelector('[data-slot="totals-scope"]');
    await expect(scope).toBeVisible();
    await expect(scope).toHaveTextContent(zhCN['label.analysis.totals-scope']);
    // Under the word, not beside it: a line of its own.
    await expect(scope!.getBoundingClientRect().top).toBeGreaterThan(
      heading.getBoundingClientRect().top +
        parseFloat(getComputedStyle(heading).paddingTop) +
        4,
    );
    await expect(
      (table as HTMLTableElement).tFoot!.querySelectorAll(
        'button, a, input, [tabindex]',
      ),
    ).toHaveLength(0);
  },
};

/**
 * 点表头排序（2026-09-23 审查 P1）：金额 升序 → 降序 → 回到视图自己的次序；
 * `aria-sort` 只在排着的那一列上。整行表头是一个 Tab 停靠点，←／→ 在列间走，
 * 回车按下——与记录视图的表头同一个组件。
 */
export const HeaderSorts: Story = {
  ...DisplayTableWithTotals,
  play: async ({ canvasElement }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() =>
      expect(readColumn(table, '仓库')).toEqual([
        '华东',
        '华北',
        '华南',
        '西南',
      ]),
    );
    const head = (name: string) =>
      (table as HTMLTableElement).tHead!.rows[0]!.cells[
        columnIndex(table, name)
      ]!;
    const sorted = () =>
      [...(table as HTMLTableElement).tHead!.rows[0]!.cells]
        .filter(cell => cell.hasAttribute('aria-sort'))
        .map(cell => cell.getAttribute('aria-sort'));
    const amounts = () => readColumn(table, AMOUNT_HEADER).map(amountOf);

    // One stop for the whole header row.
    const buttons = [...table.querySelectorAll<HTMLElement>('thead button')];
    await expect(buttons.filter(button => button.tabIndex === 0)).toHaveLength(
      1,
    );
    within(head('仓库')).getByRole('button').focus();
    await userEvent.keyboard('{ArrowRight}{ArrowRight}');
    await expect(document.activeElement).toBe(
      within(head(AMOUNT_HEADER)).getByRole('button'),
    );
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(amounts()).toEqual([980, 1920, 2450, 4880]));
    await expect(head(AMOUNT_HEADER)).toHaveAttribute('aria-sort', 'ascending');

    await userEvent.click(within(head(AMOUNT_HEADER)).getByRole('button'));
    await waitFor(() => expect(amounts()).toEqual([4880, 2450, 1920, 980]));
    await expect(head(AMOUNT_HEADER)).toHaveAttribute(
      'aria-sort',
      'descending',
    );

    // The third press is the view's own order again, not "unsorted".
    await userEvent.click(within(head(AMOUNT_HEADER)).getByRole('button'));
    await waitFor(() =>
      expect(readColumn(table, '仓库')).toEqual([
        '华东',
        '华北',
        '华南',
        '西南',
      ]),
    );
    await expect(sorted()).toEqual([]);
  },
};

/**
 * 列宽不随结果跳（2026-09-23 审查 P1）。失败最多的两个处理器名字很长——表一
 * 打开就合身，名字不截断；按表头把次数改成升序，留下的是「Mailer」「Audit」，
 * 而每一列的左边与宽都和按之前一样。自动布局下处理器那一列会跟着名字缩回去，
 * 后面每一列都往左挪。
 */
export const ColumnsHoldStill: Story = {
  ...DisplayFailingProcessors,
  play: async ({ canvasElement }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() =>
      expect(readColumn(table, '处理器')).toEqual([
        'OrderItemReservedTrackEventProcessor',
        'InventorySnapshotProjectionHandler',
      ]),
    );
    const heads = () =>
      [
        ...(
          table as HTMLTableElement
        ).tHead!.rows[0]!.querySelectorAll<HTMLElement>(
          'th:not([aria-hidden])',
        ),
      ].map(cell => {
        const box = cell.getBoundingClientRect();
        return { left: Math.round(box.left), width: Math.round(box.width) };
      });
    // Opened fitting its rows: the long names are whole, not cut.
    for (const cell of columnCells(table, '处理器').slice(1, 3))
      await expect(cell.scrollWidth).toBeLessThanOrEqual(cell.clientWidth);
    const before = heads();

    await userEvent.click(
      within(
        (table as HTMLTableElement).tHead!.rows[0]!.cells[
          columnIndex(table, COUNT_HEADER)
        ]!,
      ).getByRole('button'),
    );
    await waitFor(() =>
      expect(readColumn(table, '处理器')).toEqual(['Mailer', 'Audit']),
    );

    await expect(heads()).toEqual(before);
  },
};

/**
 * ID 维度等宽（2026-09-23 审查 P1）：聚合 ID 在记录视图里读作可复制的值，
 * 分析表里用与它同一个等宽字；数字列不是。
 */
export const IdentifiersInMonospace: Story = {
  ...DisplayFailingAggregates,
  play: async ({ canvasElement }) => {
    const table = await findDataTable(canvasElement);
    const ids = await waitFor(() => {
      const found = [
        ...table.querySelectorAll<HTMLElement>('[data-slot="identifier"]'),
      ];
      if (found.length === 0) throw new Error('no identifier yet');
      return found;
    });
    await expect(ids[0]).toHaveTextContent(/^0b5f\d{4}-7c1e-/);
    for (const id of ids)
      await expect(getComputedStyle(id).fontFamily).toMatch(/mono/i);
    const [, count] = columnCells(table, COUNT_HEADER);
    await expect(getComputedStyle(count!).fontFamily).not.toMatch(/mono/i);
  },
};

/** The result block's toolbar — not the opening skeleton's hidden stand-in. */
const resultToolbar = (canvasElement: HTMLElement) =>
  canvasElement.querySelector<HTMLElement>(
    '[data-slot="result-toolbar"]:not([aria-hidden])',
  );

/** Where a part of the frame stands, to the pixel that matters here. */
const edges = (element: Element) => {
  const rect = element.getBoundingClientRect();
  return { top: Math.round(rect.top), bottom: Math.round(rect.bottom) };
};

/**
 * 第一次的答案在路上时，框已经站好了（2026-09-23 审查 P1）。
 *
 * 从前结果区在数据回来之前是空白的，回来那一刻工具栏、条件带与页脚一起冒出来，
 * 结果被往下推。这里在骨架还在时量一次工具栏、条件带与页脚的位置，数据落地后
 * 再量一次：三者都在原地，骨架是表格那几行灰条，页脚先是一根灰条再换成那句话。
 */
export const LoadingKeepsItsPlace: Story = {
  ...DisplayLoading,
  play: async ({ canvasElement }) => {
    const skeleton = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="analysis-table-skeleton"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    await expect(skeleton.querySelectorAll('tr')).toHaveLength(3);
    const toolbar = resultToolbar(canvasElement)!;
    // The reading is the question's, before any row has said it.
    await expect(toolbar).toHaveTextContent('仓库');
    const applied = canvasElement.querySelector('[data-slot="applied-bar"]')!;
    await expect(applied).toBeVisible();
    const caption = canvasElement.querySelector<HTMLElement>(
      '[data-slot="analysis-caption"]',
    )!;
    await expect(caption.dataset.loading).toBe('');
    const before = {
      toolbar: edges(toolbar),
      applied: edges(applied),
      caption: edges(caption),
    };

    // The source answers after 1.5 s; the skeleton is itself a `table`, so
    // the rows are waited for by their own slot.
    await waitFor(
      () =>
        expect(
          canvasElement.querySelector('[data-slot="analysis-table"]'),
        ).not.toBeNull(),
      { timeout: 5_000 },
    );
    await expect(
      canvasElement.querySelector('[data-slot="analysis-table-skeleton"]'),
    ).toBeNull();
    const landed = canvasElement.querySelector<HTMLElement>(
      '[data-slot="analysis-caption"]',
    )!;
    await expect(landed.dataset.loading).toBeUndefined();
    await expect(resultToolbar(canvasElement)).toBe(toolbar);
    await expect({
      toolbar: edges(toolbar),
      applied: edges(applied),
      caption: edges(landed),
    }).toEqual(before);
  },
};

/** 保存的是图表时，骨架是一块绘图区，工具栏与页脚同样不动。 */
export const LoadingChartKeepsItsPlace: Story = {
  ...DisplayLoadingChart,
  play: async ({ canvasElement }) => {
    const area = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="analysis-chart-skeleton"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    // An area, not a sliver: it takes the height the chart will take.
    await expect(area.getBoundingClientRect().height).toBeGreaterThan(150);
    const toolbar = resultToolbar(canvasElement)!;
    const caption = canvasElement.querySelector(
      '[data-slot="analysis-caption"]',
    )!;
    const before = { toolbar: edges(toolbar), caption: edges(caption) };

    // The source answers after 1.5 s.
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4), {
      timeout: 5_000,
    });
    await chartsDrawn(canvasElement);
    await expect({
      toolbar: edges(resultToolbar(canvasElement)!),
      caption: edges(
        canvasElement.querySelector('[data-slot="analysis-caption"]')!,
      ),
    }).toEqual(before);
  },
};

/**
 * 没有组落进来：工具栏还在，空状态说清是什么情况。没有任何条件时范围已是全部
 * 记录，托盘里做什么都分不出组，所以只有标题与那一句原因，没有按钮（用户对
 * #1800 的裁定）；页脚照样说「正在显示 0 组」。
 */
export const EmptyResult: Story = {
  ...DisplayEmptyResult,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      await canvas.findByText(zhCN['label.analysis.empty']),
    ).toBeVisible();
    await expect(resultToolbar(canvasElement)).toBeVisible();
    await expect(
      canvas.getByText(zhCN['label.analysis.empty-none']),
    ).toBeVisible();
    const empty = canvasElement.querySelector<HTMLElement>(
      '[data-slot="analysis-empty"]',
    )!;
    await expect(within(empty).queryByRole('button')).toBeNull();
    await expect(
      canvasElement.querySelector('[data-slot="analysis-caption"]'),
    ).toHaveTextContent(/^正在显示 0 组/);
  },
};

/**
 * 查询失败：工具栏、条件带都还在，失败说在工具栏下面那一行里，用读者的话，
 * 末尾是「重试」；表格／图表照样能切，重试会重新去问。
 */
export const QueryFailed: Story = {
  ...DisplayQueryFailed,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const alert = await canvas.findByRole('alert');
    await expect(alert).toHaveTextContent('没能加载数据：仓储服务暂时不可用');
    const toolbar = resultToolbar(canvasElement)!;
    await expect(toolbar).toBeVisible();
    // Under the toolbar, inside the result block.
    await expect(edges(alert).top).toBeGreaterThanOrEqual(
      edges(toolbar).bottom,
    );
    await expect(
      canvasElement.querySelector('[data-slot="applied-bar"]'),
    ).toBeVisible();

    await userEvent.click(
      within(toolbar).getByRole('button', { name: zhCN['label.layout.table'] }),
    );
    await expect(
      within(toolbar).getByRole('button', {
        name: zhCN['label.layout.table'],
        pressed: true,
      }),
    ).toBeVisible();

    const asked = aggregateCalls.current;
    await userEvent.click(
      within(alert).getByRole('button', { name: zhCN['label.query.retry'] }),
    );
    await waitFor(() => expect(aggregateCalls.current).toBeGreaterThan(asked));
  },
};

/** The tray's handle in the title bar; the one button of its group. */
function trayToggle(canvasElement: HTMLElement): HTMLElement {
  return within(
    canvasElement.querySelector<HTMLElement>('[data-slot="editor-toggle"]')!,
  ).getByRole('button');
}

const tray = (canvasElement: HTMLElement) =>
  canvasElement.querySelector<HTMLElement>('[data-slot="analysis-tray"]');

/** Presses the tray open and answers it. */
async function openTray(canvasElement: HTMLElement): Promise<HTMLElement> {
  await userEvent.click(trayToggle(canvasElement));
  await waitFor(() => expect(tray(canvasElement)).not.toBeNull());
  return tray(canvasElement)!;
}

/**
 * The tray folds where the record view's filter panel folds (D20, Q2
 * settled): a saved view opens folded, the title bar's 「分析」 opens it, and
 * what is inside is the question — range, dimensions, metrics — under one
 * Apply. Nothing about *how* the result is looked at is in there.
 */
export const TrayFolds: Story = {
  ...DisplayTableWithTotals,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    await expect(tray(canvasElement)).toBeNull();
    await expect(trayToggle(canvasElement)).toHaveAttribute(
      'aria-expanded',
      'false',
    );

    const opened = await openTray(canvasElement);

    await expect(
      [...opened.querySelectorAll('[data-slot^="analysis-slot-"]')].map(slot =>
        slot.getAttribute('data-slot'),
      ),
    ).toEqual([
      'analysis-slot-range',
      'analysis-slot-dimensions',
      'analysis-slot-metrics',
      'analysis-slot-result',
    ]);
    for (const name of [
      zhCN['label.analysis.slot.range'],
      zhCN['label.analysis.slot.dimensions'],
      zhCN['label.analysis.slot.metrics'],
      zhCN['label.analysis.slot.result'],
    ])
      await expect(canvas.getByRole('region', { name })).toBeVisible();

    // One primary on the screen at most, and it is Apply (D17-3): there is
    // no Run any more, because the range and the question are one
    // execution. With auto-run on it rests until something waits for it;
    // switched off, it is the one filled button — read off the pixels,
    // since the fill is what "primary" comes to.
    await userEvent.click(autoRunBox(canvasElement));
    const apply = applyButton(canvasElement);
    await waitFor(() =>
      expect(apply).toHaveAttribute('data-emphasis', 'primary'),
    );
    const fill = getComputedStyle(apply).backgroundColor;
    const sharing = [
      ...canvasElement.querySelectorAll<HTMLElement>('[data-slot="button"]'),
    ].filter(button => getComputedStyle(button).backgroundColor === fill);
    await expect(sharing.map(button => button.textContent?.trim())).toEqual([
      zhCN['label.filter.apply'],
    ]);
  },
};

/** The tray's Apply, in its footer. */
const applyButton = (canvasElement: HTMLElement) =>
  within(
    canvasElement.querySelector<HTMLElement>(
      '[data-slot="analysis-tray-actions"]',
    )!,
  ).getByRole('button', { name: zhCN['label.filter.apply'] });

/** The tray's auto-run checkbox. */
const autoRunBox = (canvasElement: HTMLElement) =>
  within(
    canvasElement.querySelector<HTMLElement>('[data-slot="auto-run"]')!,
  ).getByRole('checkbox');

/**
 * The tray edited: a dimension out of the menu, a metric's summary changed,
 * the toggle wearing the dot while the draft has not run, and one Apply that
 * runs everything and changes the reading on the result's first row.
 */
export const TrayEdits: Story = {
  ...DisplayTableWithTotals,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const reading = () =>
      canvasElement.querySelector<HTMLElement>('[data-slot="analysis-reading"]')
        ?.textContent;
    const before = reading();

    const opened = await openTray(canvasElement);
    await expect(
      opened.querySelectorAll('[data-slot="dimension-card"]'),
    ).toHaveLength(1);
    // Apply is how the question runs in this story: with auto-run on each
    // edit below would run itself (`RunsAsEdited`).
    await userEvent.click(autoRunBox(canvasElement));
    await waitFor(() =>
      expect(autoRunBox(canvasElement)).toHaveAttribute(
        'aria-checked',
        'false',
      ),
    );

    // A dimension from the menu of groupable fields.
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.analysis.add-group'] }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', { name: '状态' }),
    );
    await waitFor(() =>
      expect(
        tray(canvasElement)!.querySelectorAll('[data-slot="dimension-card"]'),
      ).toHaveLength(2),
    );

    // A metric's summary: one list of the ways Wow can measure the field.
    const summary = canvas.getByLabelText(
      formatMessage(zhCN, 'label.analysis.function-of', { name: '金额' }),
    );
    await userEvent.click(summary);
    await userEvent.click(
      await screen.findByRole('option', { name: zhCN['label.summary.fn.AVG'] }),
    );
    await waitFor(() => expect(summary).toHaveTextContent('平均'));

    // Edited, not run: the dot is on the one button that runs it.
    const apply = within(
      canvasElement.querySelector<HTMLElement>(
        '[data-slot="analysis-tray-actions"]',
      )!,
    ).getByRole('button', { name: zhCN['label.filter.apply'] });
    await expect(apply).toHaveAttribute('data-pending');

    await userEvent.click(apply);
    await waitFor(() => expect(apply).not.toHaveAttribute('data-pending'));
    // The reading is the result's, so it only moves once the query lands.
    await waitFor(() => expect(reading()).not.toBe(before));
  },
};

/** 「成本的合计」: the metric the regression below adds. */
const COST_HEADER = formatMessage(zhCN, 'label.summary.of', {
  field: '成本',
  fn: zhCN['label.summary.fn.SUM'],
});

/**
 * 加进来的就是一列（2026-09-23 审查 P0-1）。这个视图钉住了
 * `table.columns`——仓库、记录数、金额——而那份列表只管顺序与宽度：托盘里
 * 加的指标与维度跑完就在表里，接在列出的那几列后面，维度在前、指标在后。
 * 它从前是白名单：读法说了「按仓库、状态」，表里却只有仓库一列，同一个
 * 「华东」出现两行而没有一列分得开。
 */
export const AddedColumnsShow: Story = {
  ...DisplayTableWithTotals,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const before = await findDataTable(canvasElement);
    await waitFor(() =>
      expect(readHeaders(before)).toEqual([
        '仓库',
        COUNT_HEADER,
        AMOUNT_HEADER,
      ]),
    );

    await openTray(canvasElement);
    // One Apply runs both edits, so the table is read once, after both.
    await userEvent.click(autoRunBox(canvasElement));
    await waitFor(() =>
      expect(autoRunBox(canvasElement)).toHaveAttribute(
        'aria-checked',
        'false',
      ),
    );

    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.analysis.add-metric'] }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', { name: '成本' }),
    );
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.analysis.add-group'] }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', { name: '状态' }),
    );
    await userEvent.click(applyButton(canvasElement));

    await waitFor(async () =>
      expect(readHeaders(await findDataTable(canvasElement))).toEqual([
        '仓库',
        COUNT_HEADER,
        AMOUNT_HEADER,
        '状态',
        COST_HEADER,
      ]),
    );
    // Every row says which group it is: no two rows share a warehouse and a
    // status, and 华东 now has a row per status instead of two alike.
    const after = await findDataTable(canvasElement);
    const warehouses = readColumn(after, '仓库');
    const statuses = readColumn(after, '状态');
    const keys = warehouses.map(
      (warehouse, row) => `${warehouse}|${statuses[row]}`,
    );
    await expect(new Set(keys).size).toBe(keys.length);
    await expect(
      warehouses.filter(warehouse => warehouse === '华东').length,
    ).toBeGreaterThan(1);
    await expect(readColumn(after, COST_HEADER).every(Boolean)).toBe(true);
  },
};

/**
 * An analysis view can still reach advanced mode.
 *
 * There is no simple/advanced *tray* (D20): capability grows out of the
 * slots. The one simple/advanced left is the grammar of the condition tree,
 * and it is stated where the conditions are — a ghost menu in the range
 * slot's heading. Without it, `defaultAnalysisConfig` starting at simple
 * would mean an analysis view could never express OR, NOR or a nested group
 * at all — a capability lost, not a tidier screen.
 */
export const ReachesAdvancedMode: Story = {
  ...DisplayTableWithTotals,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    await openTray(canvasElement);

    const range = () =>
      canvas.getByRole('region', {
        name: zhCN['label.analysis.slot.range'],
      });
    await expect(
      within(range()).queryByRole('group', {
        name: zhCN['label.filter.all-conditions'],
      }),
    ).toBeNull();

    await userEvent.click(
      within(range()).getByRole('button', {
        name: formatMessage(zhCN, 'label.analysis.conditions-mode', {
          mode: zhCN['label.filter.simple'],
        }),
      }),
    );
    await userEvent.click(
      await screen.findByRole('menuitemradio', {
        name: zhCN['label.filter.advanced'],
      }),
    );

    // Advanced draws the root as a group, which is what carries the operator.
    await waitFor(() =>
      expect(
        within(range()).getByRole('group', {
          name: zhCN['label.filter.all-conditions'],
        }),
      ).toBeVisible(),
    );
  },
};

/**
 * The steps between the tray's parts, measured.
 *
 * jsdom applies no stylesheet and so can only pin the class the call site
 * passes (`test/analysisTray.test.tsx`); the pixels are this project's to
 * read. The ruler (`ui/README.md`): a slot's cards stand 8px apart, the
 * slots 12px, and the two columns are two blocks at 16px.
 */
export const EditorRowSpacing: Story = {
  ...DisplayTableWithTotals,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const opened = await openTray(canvasElement);

    await expect(getComputedStyle(opened).rowGap).toBe('12px');
    await expect(
      getComputedStyle(opened.querySelector<HTMLElement>('.grid')!).columnGap,
    ).toBe('16px');
    await expect(
      getComputedStyle(
        opened.querySelector<HTMLElement>(
          '[data-slot="analysis-slot-metrics"]',
        )!,
      ).rowGap,
    ).toBe('8px');
  },
};

/**
 * 托盘读得清（2026-09-23 审查，P1）。
 *
 * 一、「只保留」「排序」「前 N 组」自成「结果」一步，排在维度与指标后面，每一项
 * 都有看得见的名字，按 Wow 施加它们的顺序从上到下：只保留在上，排序与前 N 组
 * 同一行、排序在前。
 * 二、「自动运行」开着、没有东西等应用时，应用是描边按钮，不是全屏最实的那一
 * 颗；范围里加了条件（它要等应用）才回到实心。开关名下一行说它管什么。
 * 三、托盘封顶工作列的一半，槽在里面滚，底行（自动运行／清空／应用）不滚、总看
 * 得见；结果不再被挤到它的下限。
 */
export const TrayReadsClearly: Story = {
  ...DisplayTableWithTotals,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const opened = await openTray(canvasElement);

    // 一、the result step, each part labelled on the screen, in Wow's order.
    const result = canvas.getByRole('region', {
      name: zhCN['label.analysis.slot.result'],
    });
    const metrics = canvas.getByRole('region', {
      name: zhCN['label.analysis.slot.metrics'],
    });
    await expect(result.getBoundingClientRect().top).toBeGreaterThanOrEqual(
      metrics.getBoundingClientRect().bottom,
    );
    const keep = within(result).getByRole('group', {
      name: zhCN['label.analysis.having-title'],
    });
    const sort = within(result).getByRole('group', {
      name: zhCN['label.sort.title'],
    });
    const limitBox = within(result).getByLabelText(
      zhCN['label.analysis.row-limit'],
    );
    const limit = limitBox.closest<HTMLElement>(
      '[data-slot="analysis-limit"]',
    )!;
    // The names are text a sighted analyst reads, not only a reader's.
    const visible = [
      keep.querySelector('legend'),
      sort.querySelector('[data-slot="field-label"]'),
      result.querySelector(`label[for="${limitBox.id}"]`),
    ];
    await expect(visible.map(label => label?.textContent)).toEqual([
      zhCN['label.analysis.having-title'],
      zhCN['label.sort.title'],
      zhCN['label.analysis.row-limit'],
    ]);
    for (const label of visible) await expect(label).toBeVisible();
    const box = (element: HTMLElement) => element.getBoundingClientRect();
    await expect(box(keep).bottom).toBeLessThanOrEqual(box(sort).top);
    await expect(Math.abs(box(sort).top - box(limit).top)).toBeLessThan(1);
    await expect(box(sort).right).toBeLessThanOrEqual(box(limit).left);

    // 二、Apply rests while auto-run leaves it nothing to do. The checked
    // box wears the primary fill, which is what a filled Apply would share.
    const primary = getComputedStyle(autoRunBox(canvasElement)).backgroundColor;
    const apply = applyButton(canvasElement);
    await expect(apply).toHaveAttribute('data-emphasis', 'quiet');
    await expect(getComputedStyle(apply).backgroundColor).not.toBe(primary);
    await expect(
      within(
        canvasElement.querySelector<HTMLElement>('[data-slot="auto-run"]')!,
      ).getByText(zhCN['label.analysis.auto-run-hint']),
    ).toBeVisible();

    // A condition in the range waits for Apply, so Apply fills again.
    const range = canvas.getByRole('region', {
      name: zhCN['label.analysis.slot.range'],
    });
    await userEvent.click(
      within(range).getByRole('button', { name: zhCN['label.filter.add'] }),
    );
    const picker = await screen.findByRole('dialog', {
      name: zhCN['label.filter.pick-fields'],
    });
    for (const field of ['订单号', '仓库', '状态', '标记', '备注', '金额'])
      await userEvent.click(
        within(picker).getByRole('checkbox', { name: field }),
      );
    await userEvent.click(
      within(picker).getByRole('button', {
        name: zhCN['label.filter.pick-done'],
      }),
    );
    await waitFor(() =>
      expect(apply).toHaveAttribute('data-emphasis', 'primary'),
    );
    await expect(getComputedStyle(apply).backgroundColor).toBe(primary);

    // Two rows kept on top of six conditions: a tray taller than half.
    for (let i = 0; i < 2; i++)
      await userEvent.click(
        opened.querySelector<HTMLElement>('[data-slot="add-having"]')!,
      );

    // 三、capped at half the work column, the slots scrolling inside and
    // the footer standing where it can be pressed.
    const main = canvasElement.querySelector<HTMLElement>('.fve-root > main')!;
    const band = canvasElement.querySelector<HTMLElement>(
      '[data-slot="editor-band"]',
    )!;
    const slots = opened.querySelector<HTMLElement>(
      '[data-slot="analysis-tray-slots"]',
    )!;
    const footer = opened.querySelector<HTMLElement>(
      '[data-slot="analysis-tray-actions"]',
    )!;
    await waitFor(() =>
      expect(slots.scrollHeight).toBeGreaterThan(slots.clientHeight),
    );
    await expect(box(band).height).toBeLessThanOrEqual(
      main.getBoundingClientRect().height / 2 + 1,
    );
    await expect(box(footer).top).toBeGreaterThanOrEqual(box(band).top);
    await expect(box(footer).bottom).toBeLessThanOrEqual(box(band).bottom);
    await expect(apply).toBeVisible();
  },
};

/** Whether two boxes share any area, a half-pixel of rounding aside. */
const meet = (a: DOMRect, b: DOMRect) =>
  a.left < b.right - 0.5 &&
  b.left < a.right - 0.5 &&
  a.top < b.bottom - 0.5 &&
  b.top < a.bottom - 0.5;

/**
 * The tiles and the way on to the chosen type's options, as a browser lays
 * them out: every tile one height, one button each with nothing inside it,
 * the 「推荐」 mark over neither the tile's icon nor its name nor another tile
 * (`inside` asks it to keep within its own tile's width too, which 「推荐」
 * does; "Recommended" is wider than a third of the sidebar and runs into the
 * gap between the columns, the room it hangs across the edge for); and under
 * the last row one labelled button, visible, inside the panel, the panel's
 * width, over no tile, named after the chosen type.
 */
async function expectPickerLayout(
  panel: HTMLElement,
  type: string,
  inside = true,
) {
  const tiles = [
    ...panel.querySelectorAll<HTMLElement>('[data-slot="chart-tile"]'),
  ];
  const heights = tiles.map(tile =>
    Math.round(tile.getBoundingClientRect().height),
  );
  await expect(new Set(heights).size).toBe(1);
  for (const tile of tiles)
    await expect(tile.querySelector('button')).toBeNull();

  const tile = chartTile(panel, type);
  const mark = tile.querySelector('[data-slot="chart-recommended"]');
  if (mark) {
    const marked = mark.getBoundingClientRect();
    const own = tile.getBoundingClientRect();
    const icon = tile.querySelector('svg')!.getBoundingClientRect();
    const name = document
      .getElementById(tile.getAttribute('aria-labelledby')!)!
      .getBoundingClientRect();
    await expect(meet(marked, name)).toBe(false);
    await expect(meet(marked, icon)).toBe(false);
    for (const other of tiles.filter(each => each !== tile))
      await expect(meet(marked, other.getBoundingClientRect())).toBe(false);
    if (inside) {
      await expect(marked.left).toBeGreaterThanOrEqual(own.left - 0.5);
      await expect(marked.right).toBeLessThanOrEqual(own.right + 0.5);
    }
  }

  const buttons = panel.querySelectorAll<HTMLElement>(
    '[data-slot="chart-options-open"]',
  );
  await expect(buttons).toHaveLength(1);
  const button = buttons[0]!;
  await expect(button).toBeVisible();
  await expect(button).toHaveAccessibleName(
    formatMessage(zhCN, 'label.chart.options', {
      name:
        type === 'table'
          ? zhCN['label.layout.table']
          : zhCN[`label.chart.type.${type}` as keyof typeof zhCN],
    }),
  );
  const box = button.getBoundingClientRect();
  const grid = panel
    .querySelector('[role="radiogroup"]')!
    .getBoundingClientRect();
  const own = panel.getBoundingClientRect();
  await expect(box.height).toBeGreaterThanOrEqual(28);
  await expect(box.left).toBeGreaterThanOrEqual(own.left);
  await expect(box.right).toBeLessThanOrEqual(own.right);
  await expect(Math.abs(box.width - grid.width)).toBeLessThan(1);
  await expect(box.top).toBeGreaterThanOrEqual(grid.bottom);
  for (const each of tiles)
    await expect(meet(box, each.getBoundingClientRect())).toBe(false);
  if (mark) await expect(meet(box, mark.getBoundingClientRect())).toBe(false);
}

/** One tile of the picker, addressed by the chart type it stands for. */
const chartTile = (canvas: HTMLElement, type: string) =>
  canvas.querySelector<HTMLButtonElement>(
    `[data-slot="chart-tile"][data-chart-type="${type}"]`,
  )!;

/**
 * 可视化：结果工具栏呼出左侧栏，先选图型 (D20 屏 I).
 *
 * The one thing only a browser can show about this panel is that picking a
 * type **draws** the new family — jsdom lays nothing out, so a pie there is
 * a container with no sectors in it. Beside that it holds the two halves of
 * the gesture that jsdom can only assert one at a time: the column changes
 * hands, and the source is never asked again, because the layout and the
 * chart draw the rows that already came back.
 */
export const VisualizePanel: Story = {
  ...DisplayBarChart,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
    // The list is in the column, and the panel is not.
    const list = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-sidebar"]',
    );
    await expect(list).not.toBeNull();
    const main = canvasElement.querySelector<HTMLElement>('.fve-root > main')!;
    const listWidth = list!.getBoundingClientRect().width;
    const mainLeft = main.getBoundingClientRect().left;

    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.analysis.visualize'] }),
    );

    // The panel takes the column the list had: one column, two occupants.
    const panel = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-panel"]',
    )!;
    await expect(panel).toBeVisible();
    await expect(
      canvasElement.querySelector('[data-slot="view-sidebar"]'),
    ).toBeNull();
    // At the list's width, so the work area stays where it was: a panel a
    // size wider pushed everything right by 32px as it opened.
    await expect(panel.getBoundingClientRect().width).toBeCloseTo(listWidth, 0);
    await expect(main.getBoundingClientRect().left).toBeCloseTo(mainLeft, 0);

    // Every type the definition declares is a tile, and the table is one too.
    await expect(
      [...panel.querySelectorAll('[data-slot="chart-tile"]')].map(tile =>
        tile.getAttribute('data-chart-type'),
      ),
    ).toEqual([
      'bar',
      'line',
      'area',
      'combo',
      'pie',
      'heatmap',
      'scatter',
      'funnel',
      'metric',
      'table',
    ]);

    // One dimension that is not a date reads best as bars, and the mark says
    // so in a word; a shape a type cannot draw greys its tile and writes
    // what it lacks under it.
    const bar = chartTile(panel, 'bar');
    await expect(bar).toHaveAttribute('aria-checked', 'true');
    await expect(bar).toHaveAttribute('data-recommended');
    await expect(bar).toHaveTextContent(zhCN['label.chart.recommended']);
    const heatmap = chartTile(panel, 'heatmap');
    await expect(heatmap).toHaveAttribute('aria-disabled', 'true');
    await expect(heatmap).toHaveTextContent(
      zhCN['chart.fit.needs-two-dimensions'],
    );

    // The tiles only pick; the way on to the chosen type's options is one
    // labelled button under them (2026-09-23 review: a 24px gear hanging off
    // the tile's corner was seen by nobody). Every tile is one height.
    await expectPickerLayout(panel, 'bar');
    // And the mark keeps clear in English too, the widest word it has: the
    // same tile with "Recommended" written in it, put back afterwards.
    const mark = chartTile(panel, 'bar').querySelector<HTMLElement>(
      '[data-slot="chart-recommended"]',
    )!;
    mark.textContent = defaultMessages['label.chart.recommended'];
    await expectPickerLayout(panel, 'bar', false);
    mark.textContent = zhCN['label.chart.recommended'];

    // Tab after the group lands on the button; the arrows stay the group's.
    chartTile(panel, 'bar').focus();
    await userEvent.tab();
    await expect(
      panel.querySelector('[data-slot="chart-options-open"]'),
    ).toHaveFocus();

    // A pick is a redraw: the pie is drawn, and no aggregation went out.
    const before = aggregateCalls.current;
    await userEvent.click(chartTile(panel, 'pie'));
    await waitFor(() =>
      expect(slices(canvasElement).length).toBeGreaterThan(0),
    );
    await expect(slices(canvasElement).every(slice => slice.drawn)).toBe(true);
    await expect(aggregateCalls.current).toBe(before);
    // The pie measures what the bars measured: a type is how the numbers are
    // drawn, not which (audit P0-10). Its legend leads with that column's
    // title, and each slice says its share.
    await expect(
      canvasElement.querySelector('[data-slot="pie-measure"]'),
    ).toHaveTextContent(AMOUNT_HEADER);
    // The labels land with the sweep; the story browser does not ask for
    // less motion, so the pie sweeps as a reader's would.
    await waitFor(
      () =>
        expect(
          valueLabels(canvasElement).some(label =>
            /%$/.test(label.textContent ?? ''),
          ),
        ).toBe(true),
      { timeout: 4_000 },
    );
    await expect(chartTile(panel, 'pie')).toHaveAttribute(
      'aria-checked',
      'true',
    );
    // The button follows the choice: named after the pie now. A pick opens
    // nothing by itself.
    await expectPickerLayout(panel, 'pie');
    await expect(
      document.querySelector('[data-slot="chart-options"]'),
    ).toBeNull();
    // Nothing is waiting to be applied, so the editor's fold wears no dot.
    await expect(
      canvasElement.querySelector(
        '[data-slot="editor-toggle"] [data-slot="pending-dot"]',
      ),
    ).toBeNull();

    // The table is a tile, so "back to the table" is the same one gesture.
    await userEvent.click(chartTile(panel, 'table'));
    await canvas.findByRole('table');
    await expect(slices(canvasElement)).toHaveLength(0);
    await expect(aggregateCalls.current).toBe(before);

    // The table's options are its totals row: 「表格选项」 opens that page, and
    // back from it lands on the button the user left by.
    await expectPickerLayout(panel, 'table');
    const options = panel.querySelector<HTMLElement>(
      '[data-slot="chart-options-open"]',
    )!;
    await userEvent.click(options);
    const page = await waitFor(() => {
      const found = panel.querySelector<HTMLElement>(
        '[data-slot="chart-options"]',
      );
      if (!found) throw new Error('选项页没有打开');
      return found;
    });
    await expect(
      within(page).getByRole('checkbox', {
        name: zhCN['label.analysis.totals'],
      }),
    ).toBeVisible();
    await userEvent.click(
      within(page).getByRole('button', {
        name: zhCN['label.chart.options-back'],
      }),
    );
    await waitFor(() =>
      expect(
        panel.querySelector('[data-slot="chart-options-open"]'),
      ).toHaveFocus(),
    );

    // And the way back is the panel's own: the list returns to the column.
    await userEvent.click(
      within(panel).getByRole('button', {
        name: zhCN['label.chart.picker-back'],
      }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="view-sidebar"]'),
      ).not.toBeNull(),
    );
    await expect(
      canvasElement.querySelector('[data-slot="view-panel"]'),
    ).toBeNull();
  },
};

/**
 * 卡片自己的菜单（D20 屏 B）：显示名与空值单独一组。
 *
 * 卡片上只放问题本身的那两三个控件，别的都收进一颗按卡片命名的菜单里——
 * 一张摆着六个控件的卡片读起来是张表单，不是一句话。改完名字，列头、结果
 * 那句读法与图例都跟着改（`columnTitle`：给了名字，名字就是整个标题，后面
 * 不再缀「的合计」）；空值单独一组是分析师的选择，勾上 Wow 才会把缺值的
 * 记录单独归一组，而不是悄悄把它们丢掉。
 */
export const TrayCardMenu: Story = {
  ...DisplayTableWithTotals,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    await openTray(canvasElement);

    const cardMenu = (name: string) =>
      canvas.getByRole('button', {
        name: formatMessage(zhCN, 'label.analysis.card-menu', { name }),
      });

    // 改显示名：菜单 → 输入框 → 回车。
    await userEvent.click(cardMenu('仓库'));
    await userEvent.click(
      await screen.findByRole('menuitem', {
        name: zhCN['label.analysis.rename'],
      }),
    );
    const box = await screen.findByLabelText(
      formatMessage(zhCN, 'label.analysis.display-name', { name: '仓库' }),
    );
    await userEvent.clear(box);
    await userEvent.type(box, '门店{Enter}');
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="card-name"]'),
      ).toHaveTextContent('门店'),
    );

    // 名字是问题的一部分，跑过才算数：应用之后列头与读法才改口。
    const apply = within(
      canvasElement.querySelector<HTMLElement>(
        '[data-slot="analysis-tray-actions"]',
      )!,
    ).getByRole('button', { name: zhCN['label.filter.apply'] });
    await userEvent.click(apply);
    await waitFor(() =>
      expect(canvas.getByRole('columnheader', { name: '门店' })).toBeVisible(),
    );
    await expect(
      canvasElement.querySelector('[data-slot="analysis-reading"]'),
    ).toHaveTextContent('门店');

    // 卡片现在按新名字自称，菜单也是——同一张卡上的两个控件不该各叫各的。
    await userEvent.click(cardMenu('门店'));
    const missing = () =>
      screen.getByRole('menuitemcheckbox', {
        name: zhCN['label.analysis.missing-bucket'],
      });
    await waitFor(() =>
      expect(missing()).toHaveAttribute('aria-checked', 'false'),
    );
    await userEvent.click(missing());
    // 勾选项不关菜单——设置是在读它的地方切换的。
    await waitFor(() =>
      expect(missing()).toHaveAttribute('aria-checked', 'true'),
    );
  },
};

/**
 * 指标自己的条件（D20 屏 H）：漏斗在卡片上，条件块就是范围那一套药丸。
 *
 * 一个数是在哪些记录上算出来的，这件事只有两处说得清楚：算它之前，和算它的
 * 那张卡上。所以入口是卡片上的漏斗，而不是菜单里的一项、更不是一个对话框
 * ——条件属于它收窄的那个指标，就长在那儿；写完收起来，卡片上留下一句
 * 「只算 …」，于是一屏卡片里两个「金额的合计」为什么不一样，读得出来。
 * 条件是这一个指标自己的：应用之后金额跟着变，旁边的记录数一颗不落。
 *
 * 它也换了名字（审计 P0-3，D20 显示名）：表头、读法那一行、卡片上每个控件
 * 都说「金额的合计 · 已发运」。从前表头还是「金额的合计」，没有发运的地区
 * 一格 ¥0.00，读起来就是「没有销售」；而「只算 …」那一句只在托盘的卡片上，
 * 打开一个存好的视图时托盘是收着的。表头的说明（悬停与读屏）说出整条条件。
 */
export const MetricCondition: Story = {
  ...DisplayTableWithTotals,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const shippedHeader = formatMessage(zhCN, 'label.analysis.metric-where', {
      metric: AMOUNT_HEADER,
      value: '已发运',
    });
    const amounts = async (header = AMOUNT_HEADER) =>
      readColumn(await findDataTable(canvasElement), header).map(amountOf);
    const counts = async () =>
      readColumn(await findDataTable(canvasElement), COUNT_HEADER);
    const beforeAmounts = await amounts();
    const beforeCounts = await counts();
    await openTray(canvasElement);

    const funnel = (name = AMOUNT_HEADER) =>
      canvas.getByRole('button', {
        name: formatMessage(zhCN, 'label.analysis.condition-of', { name }),
      });
    await expect(funnel()).toHaveAttribute('aria-pressed', 'false');
    await userEvent.click(funnel());
    const block = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="card-conditions"]',
      );
      if (!found) throw new Error('条件块没有打开');
      return found;
    });
    await expect(block).toHaveTextContent(
      zhCN['label.analysis.condition-title'],
    );

    // 条件用的就是范围那一套手势：字段清单勾一个，完成，再选值。
    await userEvent.click(
      within(block).getByRole('button', {
        name: `${AMOUNT_HEADER} ${zhCN['label.filter.add-here']}`,
      }),
    );
    const picker = await screen.findByRole('dialog', {
      name: zhCN['label.filter.pick-fields'],
    });
    await userEvent.click(
      within(picker).getByRole('checkbox', { name: '状态' }),
    );
    await userEvent.click(
      within(picker).getByRole('button', {
        name: zhCN['label.filter.pick-done'],
      }),
    );
    await userEvent.click(
      within(block).getByRole('combobox', {
        name: formatMessage(zhCN, 'label.filter.value-of', { field: '状态' }),
      }),
    );
    // 能装好几个值的单子写完不会自己关上，下一个手势不在它里面。
    await userEvent.click(
      await screen.findByRole('option', { name: '已发运' }),
    );
    await userEvent.keyboard('{Escape}');

    await userEvent.click(
      within(
        canvasElement.querySelector<HTMLElement>(
          '[data-slot="analysis-tray-actions"]',
        )!,
      ).getByRole('button', { name: zhCN['label.filter.apply'] }),
    );
    // 表头按它算的是什么改了名：没有发运的地区那一格 ¥0.00 是「没有已发运
    // 的销售」，不是「没有销售」。
    await waitFor(async () =>
      expect(await amounts(shippedHeader)).not.toEqual(beforeAmounts),
    );
    // 只有这一个指标被收窄：记录数还是全部，名字也还是原来的。
    await expect(await counts()).toEqual(beforeCounts);
    const table = (await findDataTable(canvasElement)) as HTMLTableElement;
    const header =
      table.tHead!.rows[0]!.cells[columnIndex(table, shippedHeader)]!;
    // 整条条件在表头的说明里：悬停读得到，读屏也念得到。
    const described = header.querySelector('[aria-describedby]');
    const sentences = (described?.getAttribute('aria-describedby') ?? '')
      .split(' ')
      .map(id => canvasElement.ownerDocument.getElementById(id)?.textContent);
    await expect(sentences.join('\n')).toContain('只算 状态');
    await expect(sentences.join('\n')).toContain('已发运');
    // 读法那一行说的是同一个名字。
    await expect(
      canvasElement.querySelector('[data-slot="analysis-reading"]'),
    ).toHaveTextContent(shippedHeader);

    // 收起条件，卡片上留下那句「只算 …」——一个数的读法不该藏在图标后面。
    await userEvent.click(
      within(block).getByRole('button', {
        name: zhCN['label.analysis.condition-close'],
      }),
    );
    const line = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="metric-condition-line"]',
      );
      if (!found) throw new Error('卡片上没有「只算 …」那一句');
      return found;
    });
    await expect(line).toHaveTextContent('状态');
    await expect(line).toHaveTextContent('已发运');
    // 卡片上的控件也按新名字自称：两张同字段的卡差在条件上，名字就差在那儿。
    await expect(funnel(shippedHeader)).toHaveAttribute('data-held');
  },
};

/**
 * 展开（D20 屏 G）：一条链，计数单位跟着最内层走。
 *
 * 展开改的是「数的是什么」：展开到明细项，一行就是一个明细项，而仓库是订单
 * 的字段——在明细项里它什么也不指，所以那个维度跟着这一步离开。「展开：…」只
 * 给声明出来的下一步，收起一层连里面的一起带走。故事的数据源不求值
 * `elements`（`rowSource.ts` 明着拒绝），所以这一趟到托盘为止，不按「应用」；
 * 查询里带出去的是什么，由 test/elementsSlot.test.tsx 与
 * test/analysisCompile.test.ts 钉着。
 */
export const TrayExpansion: Story = {
  ...DisplayExpandable,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const opened = await openTray(canvasElement);
    const cards = () =>
      canvasElement.querySelectorAll('[data-slot="element-card"]');
    const unit = () =>
      canvasElement.querySelector('[data-slot="counting-unit"]')?.textContent;

    // 展开夹在范围与那两列之间：它改的是问题问的是什么，不是问题的答案。
    await expect(
      [...opened.querySelectorAll('[data-slot^="analysis-slot-"]')].map(slot =>
        slot.getAttribute('data-slot'),
      ),
    ).toEqual([
      'analysis-slot-range',
      'analysis-slot-elements',
      'analysis-slot-dimensions',
      'analysis-slot-metrics',
      'analysis-slot-result',
    ]);
    await expect(unit()).toBe(
      formatMessage(zhCN, 'label.analysis.unit', { name: '订单' }),
    );

    await userEvent.click(
      canvas.getByRole('button', {
        name: formatMessage(zhCN, 'label.analysis.expand-into', {
          name: '明细项',
        }),
      }),
    );
    await waitFor(() => expect(cards()).toHaveLength(1));
    await expect(unit()).toBe(
      formatMessage(zhCN, 'label.analysis.unit', { name: '明细项' }),
    );
    await expect(
      canvasElement.querySelectorAll('[data-slot="dimension-card"]'),
    ).toHaveLength(0);

    // 一条线，一次一步：能再展开的只有能力声明的下一层。
    await userEvent.click(
      canvas.getByRole('button', {
        name: formatMessage(zhCN, 'label.analysis.expand-into', {
          name: '批次',
        }),
      }),
    );
    await waitFor(() => expect(cards()).toHaveLength(2));
    await expect(
      canvasElement.querySelector('[data-slot="expand-into"]'),
    ).toBeNull();

    // 收起最外那一层，里面那层跟着走：批次只在明细项里存在。
    await userEvent.click(
      canvas.getAllByRole('button', {
        name: formatMessage(zhCN, 'label.analysis.collapse', {
          name: '明细项',
        }),
      })[0],
    );
    await waitFor(() => expect(cards()).toHaveLength(0));
    await expect(unit()).toBe(
      formatMessage(zhCN, 'label.analysis.unit', { name: '订单' }),
    );
  },
};
