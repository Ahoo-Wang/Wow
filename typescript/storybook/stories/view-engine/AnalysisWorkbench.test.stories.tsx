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
import { formatMessage, zhCN } from '@ahoo-wang/fetcher-view-engine/ui';
import displayMeta, {
  BarChart as DisplayBarChart,
  CutShort as DisplayCutShort,
  CutShortTable as DisplayCutShortTable,
  EmptyResult as DisplayEmptyResult,
  Expandable as DisplayExpandable,
  FollowUps as DisplayFollowUps,
  PieChart as DisplayPieChart,
  PinnedCategoryColor as DisplayPinnedCategoryColor,
  QueryFailed as DisplayQueryFailed,
  TableWithTotals as DisplayTableWithTotals,
  TwoMetrics as DisplayTwoMetrics,
  LatestPerWarehouse as DisplayLatestPerWarehouse,
} from './AnalysisWorkbench.stories.js';
import { aggregateCalls } from './fixtures.js';
import { amountOf, findDataTable, readColumn, readTotal } from './readTable.js';

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
 * 「金额 的 合计」: the two parts a metric header is composed of (D20), and
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

const bars = (canvas: HTMLElement) =>
  canvas.querySelectorAll('.recharts-bar-rectangle');

/**
 * Each slice's category and fill, in the order the pie draws them.
 *
 * A slice is a `path` with a `d`, and only the `d` says the pie was drawn: a
 * legend entry per category renders from the same config, so a chart whose
 * container never got a size shows the words and none of the marks.
 */
const slices = (canvas: HTMLElement) =>
  [...canvas.querySelectorAll('.recharts-pie-sector path')].map(path => ({
    name: path.getAttribute('name'),
    fill: path.getAttribute('fill'),
    drawn: (path.getAttribute('d') ?? '').length > 0,
  }));

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

    // The ticks sit in their own layer beside the axis, not inside it.
    const ticks = canvasElement.querySelector('.recharts-yAxis-tick-labels');
    await waitFor(() => expect(ticks?.textContent ?? '').toContain('¥'));

    // The legend a second series earns is named by its column too — never by
    // the alias the query carried. The tooltip reads the same two through the
    // same labeller; synthesised pointer events do not open a recharts
    // tooltip, so what it says is pinned in the package
    // (test/analysisChart.test.tsx) and looked at in a browser by hand.
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
    await waitFor(() => expect(bars(canvasElement).length).toBeGreaterThan(0));
    const labels = await waitFor(() => {
      const found = [
        ...canvasElement.querySelectorAll(
          '.recharts-yAxis-tick-labels .recharts-cartesian-axis-tick-value',
        ),
      ].map(tick => (tick.textContent ?? '').trim());
      expect(found.length).toBeGreaterThan(1);
      return found;
    });
    await expect(labels.every(label => /^\d+$/.test(label))).toBe(true);
    await expect(new Set(labels).size).toBe(labels.length);
  },
};

/**
 * 报表有个底：结果区最后一行固定写「正在显示 N 行，耗时 X 秒」。
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
      /^正在显示 4 行，耗时 [\d.]+ 秒$/,
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

/** 下钻出来的视图头上那条「返回／来自」。 */
const originBar = () =>
  waitFor(() => {
    const found = document.body.querySelector<HTMLElement>(
      '[data-slot="origin-bar"]',
    );
    if (!found) throw new Error('没有「来自」那一条');
    return found;
  });

const FROM = formatMessage(zhCN, 'label.origin.from', {
  title: '仓库金额分布',
});
const BACK = formatMessage(zhCN, 'label.origin.back', {
  title: '仓库金额分布',
});

/**
 * 追问（D20 Ⅳ）：按下一根柱子，弹出这一组的三项。
 *
 * 菜单没有自己的触发控件——按下去的那根柱子就是触发——所以它是真的被那根柱子
 * 的点击打开的，而不是被某个按钮打开的；标题是这一组的条件，用的是「正在显示」
 * 那条用的同一套词。
 *
 * 「查看这些记录」在同一个工作台里开出一个未保存的记录视图：标题栏下多一条
 * 「返回 仓库金额分布 · 来自 仓库金额分布 · 仓库 属于 华南」，下面是华南那两单。
 * 按「返回」回到原来那次聚合结果——图还在，没有重跑。
 */
export const FollowUpToRecords: Story = {
  ...DisplayFollowUps,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));

    // 第三根是华南：结果按仓库分的四组，顺序就是画上去的顺序。
    await userEvent.click(bars(canvasElement)[2]);

    const menu = await drillMenu();
    await expect(
      within(menu).getByText(`仓库 ${zhCN['label.operator.IN']} 华南`),
    ).toBeVisible();
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

    const line = await originBar();
    await expect(within(line).getByText(FROM)).toBeVisible();
    await expect(
      within(line).getByRole('button', { name: BACK }),
    ).toBeVisible();
    await expect(
      [...line.querySelectorAll('[data-slot="origin-condition"]')].map(
        badge => badge.textContent,
      ),
    ).toEqual([`仓库 ${zhCN['label.operator.IN']} 华南`]);

    // 记录视图，不是聚合：华南的两单，按明细列出来。
    const table = await findDataTable(canvasElement);
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(['SO-1004', 'SO-1005']),
    );

    await userEvent.click(within(line).getByRole('button', { name: BACK }));

    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
    await expect(
      canvas.getByRole('heading', { level: 2, name: '仓库金额分布' }),
    ).toBeVisible();
    await expect(
      document.body.querySelector('[data-slot="origin-bar"]'),
    ).toBeNull();
  },
};

/**
 * 同一个菜单，从一枚扇区上弹出来——图表家族换了，手势没换。
 *
 * 这个工作台只列分析视图，所以没有「查看这些记录」：下钻开出来的是记录视图，
 * 开不出来的地方就不摆这一项。「只看这一组」改的是当前这个视图：条件进范围、
 * 立刻重跑，「正在显示」那条随即说出它。
 */
export const FollowUpFocus: Story = {
  ...DisplayPieChart,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await waitFor(() => expect(slices(canvasElement)).toHaveLength(3));

    await userEvent.click(
      canvasElement.querySelectorAll('.recharts-pie-sector path')[0],
    );

    const menu = await drillMenu();
    await expect(
      within(menu)
        .getAllByRole('menuitem')
        .map(item => item.textContent),
    ).toEqual([zhCN['label.drill.split'], zhCN['label.drill.focus']]);

    await userEvent.click(
      within(menu).getByRole('menuitem', { name: zhCN['label.drill.focus'] }),
    );

    // 范围里多了这一组，图上只剩它自己。
    const applied = canvas.getByRole('region', {
      name: zhCN['label.applied.title'],
    });
    await waitFor(() =>
      expect(
        within(applied).getByText(`仓库 ${zhCN['label.operator.IN']} 华南`),
      ).toBeVisible(),
    );
    await waitFor(() =>
      expect(slices(canvasElement).map(slice => slice.name)).toEqual(['华南']),
    );
  },
};

/**
 * 「再按…拆一层」是一层子菜单，而子菜单在真浏览器里是**悬停**展开的（点一下
 * 反而是在开与关之间来回）——这是只有真指针验得了的一条，jsdom 里点开与悬停
 * 展开是同一回事。
 *
 * 拆完之后是同一个问题换一个维度问：范围收到这一组，维度换成状态，上一维度
 * 的名字从排序、表列与图表槽位里一并退场（`analysis/drill.ts` 的 `splitBy`）。
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
    // 已经分了的那一维不在里面：按它再拆一层拆不出东西来。
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
    const applied = canvas.getByRole('region', {
      name: zhCN['label.applied.title'],
    });
    await expect(
      within(applied).getByText(`仓库 ${zhCN['label.operator.IN']} 华南`),
    ).toBeVisible();
  },
};

export const TwoMetrics: Story = {
  ...DisplayTwoMetrics,
  play: async ({ canvasElement }) => {
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(8));
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
    // carried: 「金额 的 合计」, and a count of records by what it counts.
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
    await expect(south).toEqual({ name: '华南', fill: '#7c3aed', drawn: true });
    for (const slice of others) await expect(slice.fill).not.toBe('#7c3aed');
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

export const EmptyResult: Story = {
  ...DisplayEmptyResult,
  play: async ({ canvasElement }) => {
    await expect(
      await within(canvasElement).findByText(zhCN['label.analysis.empty']),
    ).toBeVisible();
  },
};

export const QueryFailed: Story = {
  ...DisplayQueryFailed,
  play: async ({ canvasElement }) => {
    // The strip says the failure itself, in one line above the result.
    const alert = await within(canvasElement).findByRole('alert');
    await expect(alert).toHaveTextContent('仓储服务暂时不可用');
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
    ]);
    for (const name of [
      zhCN['label.analysis.slot.range'],
      zhCN['label.analysis.slot.dimensions'],
      zhCN['label.analysis.slot.metrics'],
    ])
      await expect(canvas.getByRole('region', { name })).toBeVisible();

    // One primary on the screen, and it is Apply (D17-3): there is no Run
    // any more, because the range and the question are one execution. The
    // fill is what "primary" comes to, so it is read off the pixels here
    // and nothing else on screen may share it.
    const apply = within(
      canvasElement.querySelector<HTMLElement>(
        '[data-slot="analysis-tray-actions"]',
      )!,
    ).getByRole('button', { name: zhCN['label.filter.apply'] });
    const fill = getComputedStyle(apply).backgroundColor;
    const sharing = [
      ...canvasElement.querySelectorAll<HTMLElement>('[data-slot="button"]'),
    ].filter(button => getComputedStyle(button).backgroundColor === fill);
    await expect(sharing.map(button => button.textContent?.trim())).toEqual([
      zhCN['label.filter.apply'],
    ]);
  },
};

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
    await expect(
      canvasElement.querySelector('[data-slot="view-sidebar"]'),
    ).not.toBeNull();

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

    // A pick is a redraw: the pie is drawn, and no aggregation went out.
    const before = aggregateCalls.current;
    await userEvent.click(chartTile(panel, 'pie'));
    await waitFor(() =>
      expect(slices(canvasElement).length).toBeGreaterThan(0),
    );
    await expect(slices(canvasElement).every(slice => slice.drawn)).toBe(true);
    await expect(aggregateCalls.current).toBe(before);
    await expect(chartTile(panel, 'pie')).toHaveAttribute(
      'aria-checked',
      'true',
    );
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
 * 不再缀「的 合计」）；空值单独一组是分析师的选择，勾上 Wow 才会把缺值的
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
 * 「只算 …」，于是一屏卡片里两个「金额 的 合计」为什么不一样，读得出来。
 * 条件是这一个指标自己的：应用之后金额跟着变，旁边的记录数一颗不落。
 */
export const MetricCondition: Story = {
  ...DisplayTableWithTotals,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const amounts = async () =>
      readColumn(await findDataTable(canvasElement), AMOUNT_HEADER).map(
        amountOf,
      );
    const counts = async () =>
      readColumn(await findDataTable(canvasElement), COUNT_HEADER);
    const beforeAmounts = await amounts();
    const beforeCounts = await counts();
    await openTray(canvasElement);

    const funnel = () =>
      canvas.getByRole('button', {
        name: formatMessage(zhCN, 'label.analysis.condition-of', {
          name: AMOUNT_HEADER,
        }),
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
    await waitFor(async () =>
      expect(await amounts()).not.toEqual(beforeAmounts),
    );
    // 只有这一个指标被收窄：记录数还是全部。
    await expect(await counts()).toEqual(beforeCounts);

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
    await expect(funnel()).toHaveAttribute('data-held');
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
