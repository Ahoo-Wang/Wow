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
  DailyNewestFirst as DisplayDailyNewestFirst,
} from './AnalysisWorkbench.stories.js';
import {
  axisTexts,
  axisTicks,
  axisTitles,
  chartsDrawn,
  drawnMarks,
  valueLabels,
} from './chartDom.js';
import { formatRgb, parse } from 'culori';

const meta = {
  ...displayMeta,
  title: 'View Engine/分析视图/图型/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes a file's own
  // description into a `parameters` of its meta, which would replace the
  // display meta's — and with it the full-screen host application the
  // workbench is meant to be exercised in.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** 「金额 的 合计」 and 「订单数」: the two columns a scatter plots. */
const AMOUNT_HEADER = formatMessage(zhCN, 'label.summary.of', {
  field: '金额',
  fn: zhCN['label.summary.fn.SUM'],
});
const COUNT_HEADER = zhCN['label.analysis.row-count'];
const WAREHOUSES = ['华东', '华南', '华北', '西南'];

const bars = (canvas: HTMLElement) => drawnMarks(canvas);

const chartTile = (canvas: HTMLElement, type: string) =>
  canvas.querySelector<HTMLButtonElement>(
    `[data-slot="chart-tile"][data-chart-type="${type}"]`,
  )!;

/** Opens the visualization panel from the result's toolbar. */
async function visualize(canvasElement: HTMLElement) {
  await userEvent.click(
    within(canvasElement).getByRole('button', {
      name: zhCN['label.analysis.visualize'],
    }),
  );
  return canvasElement.querySelector<HTMLElement>('[data-slot="view-panel"]')!;
}

/** Whether `inner` lies inside `outer`, a pixel of rounding allowed. */
function inside(inner: DOMRect, outer: DOMRect): boolean {
  return (
    inner.left >= outer.left - 1 &&
    inner.right <= outer.right + 1 &&
    inner.top >= outer.top - 1 &&
    inner.bottom <= outer.bottom + 1
  );
}

/**
 * The elements among `found` that stick out of `outer`, each as its text
 * and its box — so a failure says which one and by how much.
 */
const outside = (found: readonly Element[], outer: DOMRect) =>
  found
    .map(element => ({ element, box: element.getBoundingClientRect() }))
    .filter(({ box }) => !inside(box, outer))
    .map(({ element, box }) => ({
      text: element.textContent,
      box: [box.left, box.top, box.right, box.bottom].map(Math.round),
      outer: [outer.left, outer.top, outer.right, outer.bottom].map(Math.round),
    }));

/** The tick texts of one axis, as drawn: under the points, or beside them. */
const ticksOf = (canvas: HTMLElement, axis: 'x' | 'y') =>
  axisTicks(canvas, axis === 'x' ? 'bottom' : 'left').map(tick =>
    (tick.textContent ?? '').trim(),
  );

/**
 * 散点：刻度不重复，点不出界，两根轴各有标题，点说得出自己是哪一组。
 *
 * 审查（2026-09-23）在散点上看到四件事：横轴读成「0 1 1 2 2」——订单数是整数，
 * 比例尺却在中间放了 0.5、1.5，订单数的格式把它们写成 1、2；落在最大值上的点半个
 * 在绘图区外、被图自己的框切掉；两根轴都没有标题，看不出哪根是订单数、哪根是金额；
 * 点上没有名字，也说不出是哪个仓库。这里从柱状图经图型网格换成散点——用户走的就是
 * 这条路——然后量：两根轴的刻度都各不相同，每个点的框都在绘图区里，两个标题是表头
 * 那两句、都在图里且不压刻度，四个点各带仓库名。四个仓库的极值不正好落在最末
 * 一个刻度上；落在上面的那一种（点心压在绘图区边上）由包里的测试按坐标量着。
 */
export const ScatterReadsItsPoints: Story = {
  ...DisplayBarChart,
  play: async ({ canvasElement }) => {
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
    const panel = await visualize(canvasElement);
    await expect(chartTile(panel, 'scatter')).not.toHaveAttribute(
      'aria-disabled',
    );
    await userEvent.click(chartTile(panel, 'scatter'));

    await chartsDrawn(canvasElement);
    const symbols = await waitFor(() => {
      const found = drawnMarks(canvasElement);
      expect(found).toHaveLength(4);
      return found;
    });

    // Every tick once, on both axes.
    for (const axis of ['x', 'y'] as const) {
      const ticks = await waitFor(() => {
        const found = ticksOf(canvasElement, axis);
        expect(found.length).toBeGreaterThan(1);
        return found;
      });
      await expect(new Set(ticks).size).toBe(ticks.length);
    }

    // Every point inside the drawing, whole; that each sits a radius in from
    // the plot's edges is measured by coordinate in the package
    // (test/analysisChart.test.tsx「a scatter」).
    const surface = canvasElement
      .querySelector('[data-slot="chart-plot"] svg')!
      .getBoundingClientRect();
    await expect(outside(symbols, surface)).toEqual([]);

    // Each axis titled as the table heads its column, inside the drawing.
    const titles = axisTitles(canvasElement);
    await expect(titles.map(title => title.textContent).sort()).toEqual(
      [AMOUNT_HEADER, COUNT_HEADER].sort(),
    );
    await expect(outside(titles, surface)).toEqual([]);
    // And clear of the numbers on its own axis.
    const tickBoxes = axisTexts(canvasElement)
      .filter(text => !titles.includes(text))
      .map(tick => tick.getBoundingClientRect());
    for (const title of titles) {
      const box = title.getBoundingClientRect();
      await expect(
        tickBoxes.filter(
          tick =>
            tick.left < box.right &&
            box.left < tick.right &&
            tick.top < box.bottom &&
            box.top < tick.bottom,
        ),
      ).toEqual([]);
    }

    // Four points are four named things, each name inside the drawing.
    // They land once the points have finished moving in.
    const names = await waitFor(() => {
      const found = valueLabels(canvasElement);
      expect(found.map(name => name.textContent).sort()).toEqual(
        [...WAREHOUSES].sort(),
      );
      return found;
    });
    await expect(outside(names, surface)).toEqual([]);

    // The tooltip is headed by the group the point is: pinned in the
    // package (test/scatterOption.test.ts).
  },
};

/**
 * 漏斗：从结果行起头，当场画出；条用色板的第一档，留着结果区的边，百分比说明是
 * 相对上一段的转化率。
 *
 * 审查（2026-09-23）：漏斗贴着边、颜色是按钮的 primary 而不是图表色板、百分比
 * 没说是什么的百分比。四个仓库是四个阶段，从图型网格选中即按行来的顺序画出，
 * 状态行什么也不说；图离结果区左右各留 16px，每根条都在漏斗自己的框里，百分比
 * 那一列上面写着「转化率（相对上一段）」。
 */
export const FunnelFromTheRows: Story = {
  ...DisplayBarChart,
  play: async ({ canvasElement }) => {
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
    const panel = await visualize(canvasElement);
    await expect(chartTile(panel, 'funnel')).not.toHaveAttribute(
      'aria-disabled',
    );
    await userEvent.click(chartTile(panel, 'funnel'));

    const funnel = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="chart"][data-chart="funnel"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    await chartsDrawn(canvasElement);
    const stageBars = drawnMarks(funnel);
    await expect(stageBars).toHaveLength(4);
    await expect(
      canvasElement.querySelector('[data-slot="status-line"] [role="alert"]'),
    ).toBeNull();

    // The result's 16px gutter, on both sides.
    const block = canvasElement
      .querySelector('[data-slot="result-block"]')!
      .getBoundingClientRect();
    const box = funnel.getBoundingClientRect();
    await expect(box.left - block.left).toBeGreaterThanOrEqual(15);
    await expect(block.right - box.right).toBeGreaterThanOrEqual(15);
    await expect(outside(stageBars, box)).toEqual([]);
    // The palette's first slot, as a lone series wears it — read back off
    // the stylesheet, as the drawing was handed it.
    const first = formatRgb(
      parse(getComputedStyle(funnel).getPropertyValue('--chart-1').trim()),
    );
    for (const bar of stageBars)
      await expect(getComputedStyle(bar).fill).toBe(first);
    // A funnel, not bars: each stage narrower than the one above it, and
    // all of them centred on one line.
    const boxes = stageBars
      .map(bar => bar.getBoundingClientRect())
      .sort((a, b) => a.top - b.top);
    const centres = boxes.map(one => (one.left + one.right) / 2);
    await expect(
      centres.every(centre => Math.abs(centre - centres[0]!) < 1),
    ).toBe(true);

    await expect(
      funnel.querySelector('[data-slot="funnel-conversion-heading"]'),
    ).toHaveTextContent(zhCN['label.chart.column.conversion.previous']);
  },
};

/**
 * 一组画不成漏斗：卡片灰着，底下写明理由。
 *
 * 只要前 1 组时结果只有一个仓库，一个阶段没有可以转化的上一段。审查时它可选，
 * 选中之后只有一句「漏斗至少要有两个阶段」和一张空图，而那句话的按钮「打开分析」
 * 打开的是托盘——那里改不了图。现在这张卡片与热力图一样灰着，写的是缺什么。
 */
export const FunnelNeedsStages: Story = {
  ...DisplayBarChart,
  args: { ...DisplayBarChart.args, limit: 1 },
  play: async ({ canvasElement }) => {
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(1));
    const panel = await visualize(canvasElement);
    const funnel = chartTile(panel, 'funnel');
    await expect(funnel).toHaveAttribute('aria-disabled', 'true');
    await expect(
      funnel.querySelector('[data-slot="chart-reason"]'),
    ).toHaveTextContent(zhCN['chart.fit.needs-two-stages']);
    // And pressing it changes nothing: the bar stays, nothing is refused.
    await userEvent.click(funnel);
    await expect(funnel).toHaveAttribute('aria-checked', 'false');
    await expect(bars(canvasElement)).toHaveLength(1);
    await expect(
      screen.queryByRole('button', {
        name: zhCN['label.analysis.open-editor'],
      }),
    ).toBeNull();
  },
};

/**
 * 按日的结果画不成漏斗：一天不是流程里的一步，从昨天到今天也谈不上转化。
 */
export const FunnelNeedsCategory: Story = {
  ...DisplayDailyNewestFirst,
  play: async ({ canvasElement }) => {
    await waitFor(() => expect(bars(canvasElement).length).toBeGreaterThan(10));
    const panel = await visualize(canvasElement);
    const funnel = chartTile(panel, 'funnel');
    await expect(funnel).toHaveAttribute('aria-disabled', 'true');
    await expect(
      funnel.querySelector('[data-slot="chart-reason"]'),
    ).toHaveTextContent(zhCN['chart.fit.needs-category']);
  },
};

/**
 * 存下来画不出的漏斗，从图型网格修好：换一个画得出的图型就跑，跑出行来漏斗又可选。
 *
 * 漏斗只在画得出的地方给选（#1803）之前，一个阶段的漏斗是存得下来的。打开它
 * 什么也不跑——配置被拒——状态行说「漏斗至少要有两个阶段」，按钮「打开图表选项」
 * 打开的是图型网格；可网格上按什么都不起作用，因为没有行可重画。现在：漏斗那张
 * 卡片照它自己点名的阶段判，灰着说缺什么；按柱状图，图按草稿的形态装槽、视图
 * 变合法、当场跑，四根柱子出来，状态行不再报错；有了行，漏斗可选，选中它时存
 * 下的那一个阶段排第一，其余从行里补齐，四段都画出来。
 */
export const SavedFunnelRepaired: Story = {
  ...DisplayBarChart,
  args: {
    ...DisplayBarChart.args,
    savedFunnel: { value: 'amount', order: ['CN-EAST'] },
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    // Refused, so nothing ran: the status line says why, and no bar is drawn.
    const alert = await waitFor(() => {
      const found = canvasElement.querySelector(
        '[data-slot="status-line"] [role="alert"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    await expect(alert).toHaveTextContent(zhCN['chart.funnel.too-few-stages']);
    await expect(bars(canvasElement)).toHaveLength(0);

    await userEvent.click(
      canvas.getByRole('button', {
        name: zhCN['label.analysis.open-chart-options'],
      }),
    );
    const panel = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="view-panel"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    // The funnel is judged by the one stage it names; bars are offered.
    const funnel = chartTile(panel, 'funnel');
    await expect(funnel).toHaveAttribute('aria-disabled', 'true');
    await expect(
      funnel.querySelector('[data-slot="chart-reason"]'),
    ).toHaveTextContent(zhCN['chart.fit.needs-two-stages']);
    await expect(chartTile(panel, 'bar')).not.toHaveAttribute('aria-disabled');

    // A pick with no rows fits the draft and runs: a bar chart over the four
    // warehouses, a series for each metric the family had never measured.
    await userEvent.click(chartTile(panel, 'bar'));
    await chartsDrawn(canvasElement);
    await waitFor(() =>
      expect(axisTicks(canvasElement, 'bottom')).toHaveLength(4),
    );
    await expect(bars(canvasElement).length).toBeGreaterThan(0);
    await expect(
      canvasElement.querySelector('[data-slot="status-line"] [role="alert"]'),
    ).toBeNull();

    // With rows on screen the funnel draws: the stage it was saved with
    // first, the rest from the rows.
    const offered = chartTile(
      canvasElement.querySelector<HTMLElement>('[data-slot="view-panel"]')!,
      'funnel',
    );
    await waitFor(() => expect(offered).not.toHaveAttribute('aria-disabled'));
    await userEvent.click(offered);
    const drawn = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="chart"][data-chart="funnel"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    await chartsDrawn(canvasElement);
    await expect(drawnMarks(drawn)).toHaveLength(4);
    await expect(
      canvasElement.querySelector('[data-slot="status-line"] [role="alert"]'),
    ).toBeNull();
  },
};
