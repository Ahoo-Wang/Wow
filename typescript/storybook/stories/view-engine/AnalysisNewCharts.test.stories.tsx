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
import { formatRgb, parse } from 'culori';
import displayMeta, {
  BarChart as DisplayBarChart,
  HeatmapChart as DisplayHeatmapChart,
} from './AnalysisWorkbench.stories.js';
import { chartsDrawn, drawnMarks, pressMark } from './chartDom.js';
import { measureMarkContrast } from './contrast.js';
import { findDataTable, readColumn } from './readTable.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/分析视图/图型/新图型',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: a file's own description would
  // replace the display meta's parameters, and with them the host page.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** WCAG 1.4.11: a mark is a graphical object and stands off its ground 3:1. */
const NON_TEXT_CONTRAST = 3;

/** 「金额的总和」: the metric the bar chart draws, carried into the waterfall. */
const AMOUNT_HEADER = formatMessage(zhCN, 'label.summary.of', {
  field: '金额',
  fn: zhCN['label.summary.fn.SUM'],
});

/** A number as a cell writes it — 「+¥1,920.00」 — read back as a number. */
const amount = (text: string | undefined) =>
  Number((text ?? '').replace(/[^\d.-]/g, ''));

const chartTile = (root: ParentNode, type: string) =>
  root.querySelector<HTMLElement>(
    `[data-slot="chart-tile"][data-chart-type="${type}"]`,
  )!;

/** Opens the visualization panel from the result's toolbar. */
async function visualize(canvasElement: HTMLElement) {
  await userEvent.click(
    within(canvasElement).getByRole('button', {
      name: zhCN['label.analysis.visualize'],
    }),
  );
  return waitFor(() => {
    const panel = document.querySelector<HTMLElement>(
      '[data-slot="view-panel"]',
    );
    expect(panel).not.toBeNull();
    return panel!;
  });
}

/** A token of the chart's own element, as the drawing was handed it. */
const tokenOf = (element: Element, name: string) =>
  formatRgb(parse(getComputedStyle(element).getPropertyValue(name).trim())!);

/** The chart's reading table, row by row. */
const readingOf = (canvas: HTMLElement) =>
  [
    ...(canvas.querySelector<HTMLTableElement>(
      '[data-slot="chart-reading"] table',
    )?.tBodies[0]?.rows ?? []),
  ].map(row => [...row.cells].map(cell => cell.textContent ?? ''));

const drillMenu = () =>
  waitFor(() => {
    const found = document.body.querySelector<HTMLElement>(
      '[data-slot="drill-menu"]',
    );
    expect(found).not.toBeNull();
    expect(found).toBeVisible();
    return found!;
  });

/**
 * 瀑布图（D33 Q55）：从柱状图经图型网格换过去，四个仓库是四步，最后一根是合计。
 *
 * 每一步是那个仓库的金额的总和（柱状图画的那个指标，换图型时带过来），从 0 逐步
 * 累加：读屏表每一行的「累计」是前面各步之和，每一步与切到表格后的那一格一一对上；增用 `--rise`（默认约定下是成功色）、合计用色板第一档，都对卡片过 3:1；
 * 每一步的数带「+」写在条外。按下一步弹出追问菜单，标题是这个仓库。亮暗两套各跑一遍。
 *
 * 宿主在 `<html>` 上写 `data-fve-change-colors="red-up"`（主题 T1，themes.md 2.6）时，
 * 升是红色：同一张图的增步画成 `--destructive`，符号「+」照旧——颜色不是唯一的线索。
 */
const waterfallSteps = (
  theme: 'light' | 'dark',
  convention?: 'red-up',
): Story => ({
  ...DisplayBarChart,
  globals: { theme },
  beforeEach: () => {
    if (!convention) return;
    const html = document.documentElement;
    html.setAttribute('data-fve-change-colors', convention);
    return () => html.removeAttribute('data-fve-change-colors');
  },
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(4));
    const panel = await visualize(canvasElement);
    await expect(chartTile(panel, 'waterfall')).not.toHaveAttribute(
      'aria-disabled',
    );
    await userEvent.click(chartTile(panel, 'waterfall'));
    const frame = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="chart"][data-chart="waterfall"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    await chartsDrawn(canvasElement);
    // Four steps and the total; the base they float on is air.
    const marks = drawnMarks(frame);
    await expect(marks).toHaveLength(5);
    const rise = tokenOf(frame, '--rise');
    await expect(rise).toBe(
      tokenOf(frame, convention === 'red-up' ? '--destructive' : '--success'),
    );
    const first = tokenOf(frame, '--chart-1');
    const fills = marks.map(mark => mark.getAttribute('fill'));
    await expect(fills.slice(0, 4)).toEqual([rise, rise, rise, rise]);
    await expect(fills[4]).toBe(first);
    for (const mark of marks) {
      const measured = measureMarkContrast(mark);
      await expect(
        measured.ratio,
        `${theme} ${JSON.stringify(measured.colors)}`,
      ).toBeGreaterThanOrEqual(NON_TEXT_CONTRAST);
    }
    // Each step floats where the one before it ended.
    const boxes = marks.map(mark => mark.getBoundingClientRect());
    for (let at = 1; at < 4; at += 1)
      await expect(
        Math.abs(boxes[at]!.bottom - boxes[at - 1]!.top),
      ).toBeLessThan(1.5);
    // The total stands on the axis, as tall as every step together.
    await expect(Math.abs(boxes[4]!.bottom - boxes[0]!.bottom)).toBeLessThan(
      1.5,
    );
    await expect(Math.abs(boxes[4]!.top - boxes[3]!.top)).toBeLessThan(1.5);
    const signed = [
      ...frame.querySelectorAll('[data-slot="chart-plot"] svg text'),
    ].filter(text => (text.textContent ?? '').startsWith('+'));
    await expect(signed).toHaveLength(4);

    const steps = readingOf(canvasElement);
    await expect(steps.map(([name]) => name).at(-1)).toBe(
      zhCN['label.chart.total'],
    );
    const changes = steps.slice(0, 4).map(([, change]) => change);
    let running = 0;
    for (const [index, change] of changes.entries()) {
      running += amount(change);
      await expect(amount(steps[index]![2])).toBeCloseTo(running, 2);
    }
    await expect(running).toBeGreaterThan(0);

    pressMark(marks[1]!);
    const menu = await drillMenu();
    await expect(menu.textContent).toContain(steps[1]![0]!);
    await userEvent.keyboard('{Escape}');

    await userEvent.click(
      within(canvasElement).getByRole('button', {
        name: zhCN['label.layout.table'],
      }),
    );
    const table = await findDataTable(canvasElement);
    await expect(
      changes.map(change => (change ?? '').replace(/^\+/, '')),
    ).toEqual(readColumn(table, AMOUNT_HEADER));
  },
});

export const WaterfallStepsInLight: Story = waterfallSteps('light');
export const WaterfallStepsInDark: Story = waterfallSteps('dark');
export const WaterfallRisesRedUp: Story = waterfallSteps('light', 'red-up');

/**
 * 矩形树图（D33 Q55）：两个维度的结果从热力图换过去，外层一块一个状态、里面一块一个
 * 仓库，每块都在绘图区里、写着自己的名字。块的数是读屏表那一行，两层都按大小排；
 * 每块对卡片过 3:1。按下一块弹出追问菜单。亮暗两套各跑一遍。
 */
const treemapTiles = (theme: 'light' | 'dark'): Story => ({
  ...DisplayHeatmapChart,
  globals: { theme },
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const panel = await visualize(canvasElement);
    await userEvent.click(chartTile(panel, 'treemap'));
    const frame = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="chart"][data-chart="treemap"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    await chartsDrawn(canvasElement);
    await expect(frame).toHaveAttribute('data-nested', 'on');
    const tiles = readingOf(canvasElement);
    await expect(Number(frame.getAttribute('data-marks'))).toBe(tiles.length);
    const plot = frame
      .querySelector('[data-slot="chart-plot"] svg')!
      .getBoundingClientRect();
    const texts = [
      ...frame.querySelectorAll('[data-slot="chart-plot"] svg text'),
    ].map(text => text.textContent ?? '');
    // Each tile writes its name on itself — the second column of the
    // reading is the tile, the first its block.
    for (const [, name] of tiles)
      await expect(texts.some(text => text.includes(name!))).toBe(true);
    for (const mark of drawnMarks(frame)) {
      const box = mark.getBoundingClientRect();
      await expect(box.left).toBeGreaterThanOrEqual(plot.left - 1);
      await expect(box.right).toBeLessThanOrEqual(plot.right + 1);
      const measured = measureMarkContrast(mark);
      await expect(
        measured.ratio,
        `${theme} ${JSON.stringify(measured.colors)}`,
      ).toBeGreaterThan(1);
    }
    // Largest first within each block.
    const byBlock = new Map<string, number[]>();
    for (const [block, , value] of tiles)
      byBlock.set(block!, [...(byBlock.get(block!) ?? []), amount(value)]);
    for (const values of byBlock.values())
      await expect(values).toEqual([...values].sort((a, b) => b - a));

    // The first tile of the first block, pressed where its name is written:
    // the menu is headed by that tile's group.
    const [, firstName] = tiles[0]!;
    const named = [
      ...frame.querySelectorAll('[data-slot="chart-plot"] svg text'),
    ].find(text => (text.textContent ?? '').includes(firstName!))!;
    pressMark(named);
    const menu = await drillMenu();
    await expect(menu.textContent).toContain(firstName!);
    await userEvent.keyboard('{Escape}');
  },
});

export const TreemapTilesInLight: Story = treemapTiles('light');
export const TreemapTilesInDark: Story = treemapTiles('dark');

/**
 * 图型网格分两组（D33 Q54），键盘走一遍：「适合这个结果」在上、表格排最后，「其他图型」
 * 在下、灰着并写缺什么。方向键只在画得出的磁贴之间走——从表格往右绕回第一张，灰的
 * 一张也不停；空格选中停下的那一张，结果区跟着重画成那一种。
 */
export const PickerGroupsByKeyboard: Story = {
  ...DisplayBarChart,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(4));
    const panel = await visualize(canvasElement);
    const suits = within(panel).getByRole('group', {
      name: zhCN['label.chart.group.suits'],
    });
    const others = within(panel).getByRole('group', {
      name: zhCN['label.chart.group.others'],
    });
    const typesIn = (group: HTMLElement) =>
      [...group.querySelectorAll('[data-slot="chart-tile"]')].map(tile =>
        tile.getAttribute('data-chart-type'),
      );
    await expect(typesIn(suits).at(-1)).toBe('table');
    await expect(typesIn(suits)).toEqual(
      expect.arrayContaining(['waterfall', 'treemap']),
    );
    for (const tile of others.querySelectorAll('[data-slot="chart-tile"]')) {
      await expect(tile).toHaveAttribute('aria-disabled', 'true');
      await expect(
        tile.querySelector('[data-slot="chart-reason"]')?.textContent,
      ).toBeTruthy();
    }
    // One radiogroup over both, the Tab stop on the chosen tile.
    await expect(
      within(panel).getByRole('radiogroup', {
        name: zhCN['label.chart.picker'],
      }),
    ).toContainElement(others);
    chartTile(panel, 'bar').focus();
    const walked: string[] = [];
    for (let step = 0; step < typesIn(suits).length; step += 1) {
      await userEvent.keyboard('{ArrowRight}');
      walked.push(
        (document.activeElement as HTMLElement).getAttribute(
          'data-chart-type',
        )!,
      );
    }
    // Round the first group and back to where it started, never into the
    // second.
    await expect(walked.at(-1)).toBe('bar');
    for (const type of walked) await expect(typesIn(suits)).toContain(type);
    // Back two, onto the treemap's neighbour, and Space picks it.
    const target = typesIn(suits).indexOf('treemap');
    for (let step = 0; step < typesIn(suits).length - target; step += 1)
      await userEvent.keyboard('{ArrowLeft}');
    await expect(document.activeElement).toBe(chartTile(panel, 'treemap'));
    await userEvent.keyboard(' ');
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="chart"]'),
      ).toHaveAttribute('data-chart', 'treemap'),
    );
  },
};

/**
 * 手机（375px）上图型网格在抽屉里：两组十一张磁贴加表格，整块网格不超过一屏半
 * （方案批 D 的判据），抽屉自己滚，瀑布图选中后底下的图重画。
 */
export const PickerOnAPhone: Story = {
  ...DisplayBarChart,
  decorators: [
    Story => (
      <div style={{ width: 375 }}>
        <Story />
      </div>
    ),
  ],
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(4));
    await userEvent.click(
      within(canvasElement).getByRole('button', {
        name: zhCN['label.analysis.visualize'],
      }),
    );
    const drawer = await within(document.body).findByRole('dialog', {
      name: zhCN['label.chart.picker'],
    });
    const picker = drawer.querySelector<HTMLElement>(
      '[data-slot="chart-picker"]',
    )!;
    await waitFor(() =>
      expect(picker.scrollHeight).toBeLessThanOrEqual(window.innerHeight * 1.5),
    );
    // No tile wider than the drawer: nothing scrolls sideways.
    const width = drawer.getBoundingClientRect().width;
    for (const tile of drawer.querySelectorAll('[data-slot="chart-tile"]'))
      await expect(tile.getBoundingClientRect().right).toBeLessThanOrEqual(
        drawer.getBoundingClientRect().left + width + 1,
      );
    await userEvent.click(chartTile(drawer, 'waterfall'));
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="chart"]'),
      ).toHaveAttribute('data-chart', 'waterfall'),
    );
    await userEvent.keyboard('{Escape}');
  },
};
