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
import { formatMessage, zhCN } from '@ahoo-wang/fetcher-view-engine/ui';
import displayMeta, {
  BarChart as DisplayBarChart,
  CutShort as DisplayCutShort,
  CutShortTable as DisplayCutShortTable,
  EmptyResult as DisplayEmptyResult,
  FollowUps as DisplayFollowUps,
  PieChart as DisplayPieChart,
  PinnedCategoryColor as DisplayPinnedCategoryColor,
  QueryFailed as DisplayQueryFailed,
  TableWithTotals as DisplayTableWithTotals,
  TwoMetrics as DisplayTwoMetrics,
} from './AnalysisWorkbench.stories.js';
import { amountOf, findDataTable, readColumn, readTotal } from './readTable.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/分析视图/分析工作台/回归',
  tags: ['!dev', '!autodocs', 'test'],
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** 「金额 的 合计」: the two parts a metric header is composed of (D20). */
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

/** 追问菜单本身：它弹在文档上，不在画布里。 */
const drillMenu = () =>
  waitFor(() => {
    const found = document.body.querySelector<HTMLElement>(
      '[data-slot="drill-menu"]',
    );
    if (!found) throw new Error('追问菜单没有弹出来');
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

/** The sentence the strip says when a result fills its limit exactly. */
const CUT_SHORT = zhCN['analysis.result.at-limit'].replace('{limit}', '2');

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
 * A pie drawn from a grouping that may not be the whole grouping. Two of the
 * four warehouses are on the chart, and each slice's share is of those two —
 * which is exactly the reading a pie invites and exactly the one that is
 * wrong here, so the line above it says the limit was filled.
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

/**
 * An analysis view can still reach advanced mode.
 *
 * The mode moved into the title bar's fold for the two workbenches whose
 * only editor is the condition panel. This one's editor is that panel *and*
 * the aggregation editor, so it has no such fold and the panel keeps its own
 * control. Without it, `defaultAnalysisConfig` starting at simple would mean
 * an analysis view could never express OR, NOR or a nested group at all —
 * which is a capability lost, not a tidier screen.
 */
export const ReachesAdvancedMode: Story = {
  ...DisplayTableWithTotals,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    const panel = canvas.getByRole('region', {
      name: zhCN['label.filter.panel'],
    });
    await expect(
      within(panel).queryByRole('group', {
        name: zhCN['label.filter.all-conditions'],
      }),
    ).toBeNull();

    await userEvent.click(
      within(panel).getByRole('button', {
        name: zhCN['label.filter.advanced'],
      }),
    );

    // Advanced draws the root as a group, which is what carries the operator.
    await waitFor(() =>
      expect(
        within(
          canvas.getByRole('region', {
            name: zhCN['label.filter.panel'],
          }),
        ).getByRole('group', {
          name: zhCN['label.filter.all-conditions'],
        }),
      ).toBeVisible(),
    );
  },
};

/**
 * The step between the editor's group and metric rows, measured.
 *
 * `AnalysisEditor` draws them in a vendored `FieldGroup`, which carries its
 * own `gap-5`: 20px, a step that is on none of the four the package uses,
 * and wider than the 16px that separates two whole blocks of the main
 * column — a block's own rows stood further apart than the blocks did.
 * `ui/components/**` is upstream's and is not edited by hand, so the seam is
 * closed at the call site.
 *
 * jsdom applies no stylesheet and so can only pin the class the call site
 * passes (`test/analysisUi.test.tsx`); the pixels are this project's to read.
 */
export const EditorRowSpacing: Story = {
  ...DisplayTableWithTotals,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    const editor = canvas.getByRole('region', {
      name: zhCN['label.analysis.editor'],
    });
    const rows = editor.querySelector<HTMLElement>(
      '[data-slot="field-group"]',
    )!;
    await expect(getComputedStyle(rows).rowGap).toBe('12px');
  },
};
