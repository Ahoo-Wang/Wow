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
import { zhCN } from '@ahoo-wang/fetcher-view-engine/ui';
import { parse } from 'culori';
import displayMeta, {
  BarChart as DisplayBarChart,
  DailyQuietDays as DisplayDailyQuietDays,
  DailyTrendCard as DisplayDailyTrendCard,
  FailingAggregates as DisplayFailingAggregates,
  HeatmapChart as DisplayHeatmapChart,
  PieChart as DisplayPieChart,
  TwoMetrics as DisplayTwoMetrics,
  ValueLabels as DisplayValueLabels,
} from './AnalysisWorkbench.stories.js';
import {
  axisTicks,
  axisTitles,
  chartsDrawn,
  drawnMarks,
  markLabels,
  overlaps,
  valueLabels,
} from './chartDom.js';
import { contrastRatio } from './contrast.js';

/**
 * 图表怎么读（2026-09-23 图表审查的 P2 打磨）：悬停的柱子更显眼而不是更淡，
 * 堆叠段内的数不糊、不重复，两根轴的刻度落在同一组网格线上，饼的图例贴着饼，
 * 中文纵轴标题不侧躺，数值标签要么都写要么都不写，长名字的排行横着放，热力图
 * 格子上的数用与格子对比的墨色，指标卡站在结果中间，补出的 0 不写数。每一条都
 * 量画出来的样子：等图画完（`chartsDrawn`）再量。
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/分析视图/图表读法/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes a file's own
  // description into a `parameters` of its meta, which would replace the
  // display meta's — and with it the full-screen host application.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

const rgb = (css: string) => {
  const color = parse(css);
  if (!color || !('r' in color)) throw new Error(`not a colour: ${css}`);
  return { r: color.r, g: color.g, b: color.b };
};

/** What the chart stands on: the page under the drawing. */
const WHITE = { r: 1, g: 1, b: 1 };

/** The middle of an element, where a pointer would rest on it. */
const middle = (element: Element) => {
  const box = element.getBoundingClientRect();
  return {
    clientX: box.left + box.width / 2,
    clientY: box.top + box.height / 2,
  };
};

/** Rests the pointer on a mark, as the library hears a pointer. */
function hover(mark: Element) {
  for (const type of ['mouseover', 'mousemove'])
    mark.dispatchEvent(
      new MouseEvent(type, { bubbles: true, ...middle(mark) }),
    );
}

/**
 * 悬停的柱子往墨色走一步：浅色主题下更深，对底色的对比更高；它的数也照旧是
 * 字的颜色。从前库把一层半透明的灰带盖在柱子上，读的那一根反而最淡、像被禁用
 * （审查）。
 */
export const HoverReadsAsEmphasis: Story = {
  ...DisplayBarChart,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const marks = await waitFor(() => {
      const found = drawnMarks(canvasElement);
      expect(found).toHaveLength(4);
      return found;
    });
    const read = marks[1]!;
    const before = read.getAttribute('fill')!;
    const rest = rgb(before);
    hover(read);
    // Darker on this light page: more contrast against the ground, never
    // less (the colour is written anew on hover, so it is read as a colour).
    await waitFor(() =>
      expect(
        contrastRatio(rgb(read.getAttribute('fill')!), WHITE),
      ).toBeGreaterThan(contrastRatio(rest, WHITE) + 0.5),
    );
    // Nothing is laid over it: the band behind the category is behind.
    const at = middle(read);
    await expect(document.elementsFromPoint(at.clientX, at.clientY)[0]).toBe(
      read,
    );
  },
};

/** Opens the chosen type's options on one of its pages. */
async function optionsPage(canvasElement: HTMLElement, tab: string) {
  await userEvent.click(
    await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="chart-options-open"]',
      );
      if (!found) throw new Error('没有选项按钮');
      return found;
    }),
  );
  const options = await waitFor(() => {
    const found = canvasElement.querySelector<HTMLElement>(
      '[data-slot="chart-options"]',
    );
    if (!found) throw new Error('选项没有打开');
    return found;
  });
  await userEvent.click(within(options).getByRole('tab', { name: tab }));
  return options;
}

/** Opens the visualization panel from the result's toolbar. */
async function visualize(canvasElement: HTMLElement) {
  await userEvent.click(
    within(canvasElement).getByRole('button', {
      name: zhCN['label.analysis.visualize'],
    }),
  );
  return canvasElement.querySelector<HTMLElement>('[data-slot="view-panel"]')!;
}

const tile = (panel: HTMLElement, type: string) =>
  panel.querySelector<HTMLButtonElement>(
    `[data-slot="chart-tile"][data-chart-type="${type}"]`,
  )!;

/**
 * 堆叠柱段内的数：不写 ¥0，一段的栈只写一次（栈顶的合计），段内的数没有白边、
 * 墨色是对着这一段的颜色取的（审查：浅色下黑字套白边发糊，华北只有「待出库」
 * 一段，段内与栈顶各写一遍 ¥2,450）。
 */
export const StackedPartsReadable: Story = {
  ...DisplayHeatmapChart,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const panel = await visualize(canvasElement);
    await userEvent.click(tile(panel, 'bar'));
    await chartsDrawn(canvasElement);
    const options = await optionsPage(
      canvasElement,
      zhCN['label.chart.tab.display'],
    );
    await userEvent.click(
      within(options).getByRole('checkbox', {
        name: zhCN['label.chart.stacked'],
      }),
    );
    await chartsDrawn(canvasElement);

    const texts = await waitFor(() => {
      const found = valueLabels(canvasElement).map(
        label => label.textContent ?? '',
      );
      expect(found.length).toBeGreaterThan(4);
      return found;
    });
    await expect(texts).not.toContain('¥0');
    // 华北 is one part: its number once, over the stack.
    await expect(texts.filter(text => text === '¥2,450')).toHaveLength(1);

    // Every part inside its segment: no halo, and the better of the two
    // inks against the segment's own colour.
    const parts = markLabels(canvasElement);
    await expect(parts.length).toBeGreaterThan(0);
    for (const part of parts) {
      await expect(part.getAttribute('stroke')).toBeNull();
      const at = middle(part);
      const segment = document
        .elementsFromPoint(at.clientX, at.clientY)
        .find(hit => hit.tagName === 'path')!;
      const fill = rgb(segment.getAttribute('fill')!);
      const ink = rgb(part.getAttribute('fill')!);
      const other =
        contrastRatio(ink, WHITE) > 2 ? WHITE : { r: 0.04, g: 0.04, b: 0.04 };
      await expect(contrastRatio(ink, fill)).toBeGreaterThanOrEqual(
        contrastRatio(other, fill),
      );
    }
  },
};

/** The vertical middle of each text, top to bottom. */
const middles = (texts: readonly Element[]) =>
  texts.map(text => middle(text).clientY).sort((a, b) => a - b);

/**
 * 两根轴一套网格线：金额在左、记录数在右，两边的刻度一样多、逐对齐平，记录数
 * 的刻度都是整数（从前右轴跟着左轴的线走，写出 0.5、1.5 条记录）。
 */
export const TwoAxesShareTheGrid: Story = {
  ...DisplayTwoMetrics,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const [left, right] = await waitFor(() => {
      const found = [
        axisTicks(canvasElement, 'left'),
        axisTicks(canvasElement, 'right'),
      ];
      expect(found[1].length).toBeGreaterThan(1);
      return found;
    });
    await expect(right.length).toBe(left.length);
    const leftAt = middles(left);
    const rightAt = middles(right);
    await expect(
      leftAt.every((at, index) => Math.abs(at - rightAt[index]!) < 1),
    ).toBe(true);
    await expect(
      right.every(tick => /^\d+$/.test((tick.textContent ?? '').trim())),
    ).toBe(true);
  },
};

/**
 * 饼图的图例贴着饼：宽屏上从前饼站在绘图区正中、图例在最右，隔着一掌宽
 * （审查）。量：图例的左边离饼（连同片外的占比）不超过 64px，而饼与图例一起
 * 站在图框中间。
 */
export const PieLegendBesideThePie: Story = {
  ...DisplayPieChart,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const frame = canvasElement.querySelector<HTMLElement>(
      '[data-slot="chart"]',
    )!;
    await expect(frame).toHaveAttribute('data-legend', 'right');
    const legend = frame
      .querySelector('[data-slot="chart-legend"]')!
      .getBoundingClientRect();
    const pie = [...drawnMarks(frame), ...valueLabels(frame)].map(each =>
      each.getBoundingClientRect(),
    );
    const east = Math.max(...pie.map(box => box.right));
    const west = Math.min(...pie.map(box => box.left));
    await expect(legend.left - east).toBeLessThanOrEqual(64);
    await expect(legend.left).toBeGreaterThan(east);
    // The pie and its legend stand together in the frame's middle.
    const box = frame.getBoundingClientRect();
    await expect(
      Math.abs(west - box.left - (box.right - legend.right)),
    ).toBeLessThan(box.width * 0.2);
  },
};

/**
 * 中文的纵轴标题不侧躺：「金额的总和」平放在纵轴顶端、刻度字的上方，宽大于高，
 * 不压任何刻度（审查：转了 90° 的中文每个字都躺着）。
 */
export const ChineseTitleUpright: Story = {
  ...DisplayBarChart,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const title = await waitFor(() => {
      const found = axisTitles(canvasElement).find(
        text => text.textContent === '金额的总和',
      );
      expect(found).toBeDefined();
      return found!;
    });
    const box = title.getBoundingClientRect();
    await expect(box.width).toBeGreaterThan(box.height);
    const ticks = axisTicks(canvasElement, 'left');
    const top = Math.min(
      ...ticks.map(tick => tick.getBoundingClientRect().top),
    );
    await expect(box.bottom).toBeLessThanOrEqual(top);
    for (const tick of ticks)
      await expect(overlaps(box, tick.getBoundingClientRect())).toBe(false);
  },
};

/**
 * Whether a text is drawn turned: the library writes a rotation into the
 * text's transform matrix (`matrix(a, b, c, d, …)`, `b` its sine).
 */
const turned = (text: Element) => {
  const matrix = /matrix\(([^)]*)\)/.exec(text.getAttribute('transform') ?? '');
  return Math.abs(Number(matrix?.[1]?.split(',')[1] ?? 0)) > 0.01;
};

/**
 * 数值标签要么都写、要么都不写：三十天的柱在宽处每根都写平排的数，在窄处每根
 * 都写竖排的数（或一个都不写），从不像库的 `hideOverlap` 那样隔一个藏一个——
 * 一根没写数的柱读起来就是没有值（审查）。
 */
export const ValueLabelsAllOrNone: Story = {
  ...DisplayValueLabels,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const bars = await waitFor(() => {
      const found = drawnMarks(canvasElement);
      expect(found.length).toBeGreaterThan(10);
      return found;
    });
    const labels = await waitFor(() => {
      const found = valueLabels(canvasElement).filter(
        label => (label.textContent ?? '') !== '',
      );
      expect(found).toHaveLength(bars.length);
      return found;
    });
    // Flat, where each fits over its bar: none of them turned.
    await expect(labels.some(turned)).toBe(false);
  },
};

/** The same thirty days in a column a phone wide. */
export const ValueLabelsAllOrNoneNarrow: Story = {
  ...DisplayValueLabels,
  decorators: [
    Story => (
      <div style={{ width: 360 }}>
        <Story />
      </div>
    ),
  ],
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const bars = await waitFor(() => {
      const found = drawnMarks(canvasElement);
      expect(found.length).toBeGreaterThan(10);
      return found;
    });
    const labels = valueLabels(canvasElement).filter(
      label => (label.textContent ?? '') !== '',
    );
    await expect([0, bars.length]).toContain(labels.length);
    // Written, they are all turned alike, or all flat.
    await expect(new Set(labels.map(turned)).size).toBeLessThanOrEqual(1);
    // Written, they run up from the bars: no two on each other.
    for (const [index, label] of labels.entries())
      for (const other of labels.slice(index + 1))
        await expect(
          overlaps(
            label.getBoundingClientRect(),
            other.getBoundingClientRect(),
          ),
        ).toBe(false);
  },
};

/**
 * 长名字的排行横着放：九个聚合 ID 竖着站时名字斜 45°、要歪着头读；没人说过
 * 方向时它们缺省横放，名字整行写在左边（审查）。
 */
export const LongNamesLieDown: Story = {
  ...DisplayFailingAggregates,
  play: async ({ canvasElement }) => {
    await userEvent.click(
      await within(canvasElement).findByRole('button', {
        name: zhCN['label.layout.chart'],
      }),
    );
    await chartsDrawn(canvasElement);
    const frame = canvasElement.querySelector('[data-slot="chart"]')!;
    await expect(frame).toHaveAttribute('data-orientation', 'horizontal');
    const bars = drawnMarks(canvasElement).map(bar =>
      bar.getBoundingClientRect(),
    );
    await expect(bars).toHaveLength(9);
    // Bars that run across: each wider than it is tall, one under the next.
    await expect(bars.every(bar => bar.width >= bar.height)).toBe(true);
    // The names stand flat, one line each.
    const names = axisTicks(canvasElement, 'left');
    await expect(names.length).toBeGreaterThan(0);
    await expect(
      names.every(name => name.getBoundingClientRect().height < 20),
    ).toBe(true);
  },
};

/**
 * 热力图格子上的数用与这一格对比的墨色：深格子上是底色的字、浅格子上是字的
 * 颜色，没有白边（审查：深色格子上的深色字看不清）。
 */
export const HeatmapInkStandsOff: Story = {
  ...DisplayHeatmapChart,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const labels = await waitFor(() => {
      const found = markLabels(canvasElement);
      expect(found.length).toBeGreaterThan(0);
      return found;
    });
    const inks = new Set<string>();
    for (const label of labels) {
      const at = middle(label);
      const cell = document
        .elementsFromPoint(at.clientX, at.clientY)
        .find(hit => hit.tagName === 'path')!;
      const fill = rgb(cell.getAttribute('fill')!);
      const ink = label.getAttribute('fill')!;
      inks.add(ink);
      const dark = { r: 0.04, g: 0.04, b: 0.04 };
      const best = Math.max(
        contrastRatio(dark, fill),
        contrastRatio(WHITE, fill),
      );
      await expect(contrastRatio(rgb(ink), fill)).toBeGreaterThanOrEqual(
        best - 0.01,
      );
      await expect(label.getAttribute('stroke')).toBeNull();
    }
  },
};

/**
 * 工作台里的指标卡站在结果中间：左右留白相等、上下留白相差不到一行，数字比
 * 仪表盘上的大一号（审查：挤在左上角、下面一大片空）。
 */
export const TrendCardStandsInTheMiddle: Story = {
  ...DisplayDailyTrendCard,
  play: async ({ canvasElement }) => {
    const card = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="metric-card"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    await chartsDrawn(canvasElement);
    const area = canvasElement
      .querySelector('[data-slot="analysis-result"]')!
      .getBoundingClientRect();
    const box = card.getBoundingClientRect();
    await expect(
      Math.abs(box.left - area.left - (area.right - box.right)),
    ).toBeLessThan(2);
    await expect(
      Math.abs(box.top - area.top - (area.bottom - box.bottom)),
    ).toBeLessThan(28);
    const value = card
      .querySelector('[data-slot="metric-value"]')!
      .getBoundingClientRect();
    await expect(value.height).toBeGreaterThanOrEqual(44);
  },
};

/** The chart's reading table, as rows of text. */
const readingOf = (canvas: HTMLElement) =>
  [
    ...(canvas.querySelector<HTMLTableElement>(
      '[data-slot="chart-reading"] table',
    )?.tBodies[0]?.rows ?? []),
  ].map(row => [...row.cells].map(cell => cell.textContent ?? ''));

/**
 * 补出的 0 不写数（D23，Q14）：只发往杭州、上海的运单，没单的日子画在 0 上，
 * 线照样落到那里，但开着数值标签也不在那一点上写「0」——那不是量出来的数，满屏
 * 「0」会盖住真正有值的点；读屏表与提示框说「0（这一天没有记录）」。
 */
export const FilledZerosWriteNothing: Story = {
  ...DisplayDailyQuietDays,
  args: { ...DisplayDailyQuietDays.args, labels: true },
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const rows = await waitFor(() => {
      const found = readingOf(canvasElement);
      expect(found.length).toBeGreaterThan(10);
      return found;
    });
    const quiet = rows.filter(row =>
      row[1]?.includes(zhCN['label.chart.filled.DAY'].replace('{value}', '0')),
    );
    const busy = rows.length - quiet.length;
    await expect(quiet.length).toBeGreaterThan(0);
    await expect(busy).toBeGreaterThan(0);
    // A dot on every day, the quiet ones on zero; a number on the busy ones.
    await waitFor(() =>
      expect(drawnMarks(canvasElement)).toHaveLength(rows.length),
    );
    await waitFor(() =>
      expect(
        valueLabels(canvasElement).filter(
          label => (label.textContent ?? '') !== '',
        ),
      ).toHaveLength(busy),
    );
    await expect(
      valueLabels(canvasElement).some(label => label.textContent === '0'),
    ).toBe(false);
  },
};
