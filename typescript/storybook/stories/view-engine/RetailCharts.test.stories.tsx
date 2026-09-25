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
  Boxplot as DisplayBoxplot,
  Calendar as DisplayCalendar,
  Gauge as DisplayGauge,
  Parallel as DisplayParallel,
  Radar as DisplayRadar,
  Sankey as DisplaySankey,
  Sunburst as DisplaySunburst,
  ThemeRiver as DisplayThemeRiver,
  Tree as DisplayTree,
} from './RetailCharts.stories.js';
import { chartsDrawn, hoverMark, pressMark } from './chartDom.js';

/**
 * 图型陈列的轻量孪生（D41）：每种图在零售数据上画得出、读屏表的行与画出的
 * 组一一对应、图上该说的话说了，按下一组弹出追问菜单；数字是种子定下的。
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/业务场景/图型陈列/回归',
  tags: ['!dev', '!autodocs', 'test'],
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** The drawn chart of `type`, once its marks are in. */
async function chartOf(canvasElement: HTMLElement, type: string) {
  await chartsDrawn(canvasElement);
  return waitFor(() => {
    const frame = canvasElement.querySelector<HTMLElement>(
      `[data-slot="chart"][data-chart="${type}"]`,
    );
    expect(frame).not.toBeNull();
    return frame!;
  });
}

/** The chart's reading table, row by row. */
const readingOf = (canvas: HTMLElement) =>
  [
    ...(canvas.querySelector<HTMLTableElement>(
      '[data-slot="chart-reading"] table',
    )?.tBodies[0]?.rows ?? []),
  ].map(row => [...row.cells].map(cell => cell.textContent ?? ''));

/** Opens the visualization panel from the result's toolbar. */
async function visualize(canvasElement: HTMLElement) {
  await userEvent.click(
    within(canvasElement).getByRole('button', {
      name: zhCN['label.analysis.visualize'],
    }),
  );
  return waitFor(() => {
    const panel = document.querySelector<HTMLElement>(
      '[data-slot="chart-picker"]',
    );
    expect(panel).not.toBeNull();
    return panel!;
  });
}

const tile = (root: ParentNode, type: string) =>
  root.querySelector<HTMLElement>(
    `[data-slot="chart-tile"][data-chart-type="${type}"]`,
  )!;

/**
 * 箱线图：各仓一个箱，五个数从低到高；图上方写近似值。可视化面板里箱线图
 * 在「适合这个结果」，刻度盘写着它为什么不行。
 */
export const BoxplotDrawn: Story = {
  ...DisplayBoxplot,
  name: '箱线图：各仓付款到发货',
  play: async ({ canvasElement }) => {
    const frame = await chartOf(canvasElement, 'boxplot');
    const rows = readingOf(canvasElement);
    await expect(Number(frame.getAttribute('data-marks'))).toBe(rows.length);
    await expect(rows.length).toBeGreaterThan(1);
    for (const row of rows) {
      const numbers = row.slice(1).map(cell => Number.parseFloat(cell));
      // Lowest to highest: a box reads upward.
      await expect([...numbers].sort((a, b) => a - b)).toEqual(numbers);
    }
    await expect(
      frame.querySelector('[data-slot="boxplot-notes"]')?.textContent,
    ).toContain(zhCN['label.chart.boxplot.approximate']);
    // A box: the one path painted and outlined. Hovered, its tooltip names
    // each of the five by its column; pressed, it opens the follow-up menu
    // on its warehouse.
    const box = [
      ...frame.querySelectorAll<SVGPathElement>(
        '[data-slot="chart-plot"] svg path',
      ),
    ].find(
      path =>
        path.getAttribute('stroke') !== null &&
        !['none', 'transparent', null].includes(path.getAttribute('fill')),
    )!;
    hoverMark(box);
    await waitFor(() =>
      expect(
        frame.querySelector('[data-slot="chart-tooltip"]')?.textContent,
      ).toContain('付款到发货中位数'),
    );
    pressMark(box);
    const menu = await waitFor(() => {
      const found = document.querySelector<HTMLElement>(
        '[data-slot="drill-menu"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    await expect(menu).toHaveTextContent(rows[0]![0]!);
    await userEvent.keyboard('{Escape}');
    const panel = await visualize(canvasElement);
    await expect(tile(panel, 'boxplot')).not.toHaveAttribute('aria-disabled');
    // Five metrics per group are a radar too; one number on a scale is not.
    await expect(tile(panel, 'radar')).not.toHaveAttribute('aria-disabled');
    await expect(tile(panel, 'gauge')).toHaveAttribute('aria-disabled', 'true');
  },
};

/**
 * 刻度盘：本月 GMV 与月目标；磁贴上指标卡与刻度盘各写回答什么。
 */
export const GaugeDrawn: Story = {
  ...DisplayGauge,
  name: '刻度盘：本月 GMV 达成',
  play: async ({ canvasElement }) => {
    await chartOf(canvasElement, 'gauge');
    const rows = readingOf(canvasElement);
    await expect(rows.map(row => row[0])).toEqual([
      'GMV',
      zhCN['label.chart.column.target'],
      zhCN['label.chart.column.reached'],
      zhCN['label.chart.column.scale-min'],
      zhCN['label.chart.column.scale-max'],
    ]);
    await expect(rows[1]?.[1]).toBe('¥300,000.00');
    const panel = await visualize(canvasElement);
    await expect(
      tile(panel, 'metric').querySelector('[data-slot="chart-hint"]'),
    ).toHaveTextContent(zhCN['label.chart.hint.metric']);
    await expect(
      tile(panel, 'gauge').querySelector('[data-slot="chart-hint"]'),
    ).toHaveTextContent(zhCN['label.chart.hint.gauge']);
  },
};

/** 雷达图：各渠道一个形状、三根轴，图例列出每个渠道。 */
export const RadarDrawn: Story = {
  ...DisplayRadar,
  name: '雷达图：各渠道',
  play: async ({ canvasElement }) => {
    const frame = await chartOf(canvasElement, 'radar');
    await expect(frame).toHaveAttribute('data-axes', '3');
    const rows = readingOf(canvasElement);
    await expect(Number(frame.getAttribute('data-marks'))).toBe(rows.length);
    const legend = frame.querySelector('[data-slot="chart-legend"]');
    for (const [name] of rows) await expect(legend).toHaveTextContent(name!);
  },
};

/** 平行坐标：每个省一条线，多于八条同一种颜色、没有图例。 */
export const ParallelDrawn: Story = {
  ...DisplayParallel,
  name: '平行坐标图：各省份',
  play: async ({ canvasElement }) => {
    const frame = await chartOf(canvasElement, 'parallel');
    const rows = readingOf(canvasElement);
    await expect(rows.length).toBeGreaterThan(8);
    await expect(Number(frame.getAttribute('data-marks'))).toBe(rows.length);
    await expect(frame.querySelector('[data-slot="chart-legend"]')).toBeNull();
  },
};

/**
 * 旭日图：读屏表每行是一个二级类目，行数就是画出的外圈段数，占比加起来
 * 是 100%。
 */
export const SunburstDrawn: Story = {
  ...DisplaySunburst,
  name: '旭日图：品类 → 子类',
  play: async ({ canvasElement }) => {
    const frame = await chartOf(canvasElement, 'sunburst');
    const rows = readingOf(canvasElement);
    await expect(rows.length).toBeGreaterThan(8);
    await expect(Number(frame.getAttribute('data-marks'))).toBe(rows.length);
    const shares = rows.reduce(
      (sum, row) => sum + Number.parseFloat(row[3] ?? '0'),
      0,
    );
    await expect(Math.abs(shares - 100)).toBeLessThan(1);
  },
};

/** 树图：每个子类的名字都画在树上。 */
export const TreeDrawn: Story = {
  ...DisplayTree,
  name: '树图：品类 → 子类',
  play: async ({ canvasElement }) => {
    const frame = await chartOf(canvasElement, 'tree');
    const rows = readingOf(canvasElement);
    await expect(Number(frame.getAttribute('data-marks'))).toBe(rows.length);
    const drawn = frame.querySelector('svg')?.textContent ?? '';
    for (const [, sub] of rows) await expect(drawn).toContain(sub!);
  },
};

/**
 * 桑基图：每条带是一对渠道与支付方式，最宽的在读屏表第一行；两层的带按下
 * 就是那一组，弹出追问菜单。
 */
export const SankeyDrawn: Story = {
  ...DisplaySankey,
  name: '桑基图：渠道 → 支付方式',
  play: async ({ canvasElement }) => {
    const frame = await chartOf(canvasElement, 'sankey');
    const rows = readingOf(canvasElement);
    await expect(Number(frame.getAttribute('data-marks'))).toBe(rows.length);
    const values = rows.map(row =>
      Number((row[2] ?? '').replace(/[^\d.-]/g, '')),
    );
    await expect([...values].sort((a, b) => b - a)).toEqual(values);
  },
};

/** 日历热力图：25 个月跨三个年份，一年一块；读屏表一天一行、从早到晚。 */
export const CalendarDrawn: Story = {
  ...DisplayCalendar,
  name: '日历热力图：每日 GMV',
  play: async ({ canvasElement }) => {
    const frame = await chartOf(canvasElement, 'calendar');
    await expect(frame).toHaveAttribute('data-years', '3');
    const rows = readingOf(canvasElement);
    await expect(Number(frame.getAttribute('data-marks'))).toBe(rows.length);
    await expect(rows.length).toBeGreaterThan(700);
  },
};

/** 河流图：每条河流一个渠道，图例列出每个渠道；读屏表一周一行。 */
export const ThemeRiverDrawn: Story = {
  ...DisplayThemeRiver,
  name: '河流图：各渠道每周 GMV',
  play: async ({ canvasElement }) => {
    const frame = await chartOf(canvasElement, 'themeRiver');
    const rows = readingOf(canvasElement);
    await expect(rows.length).toBeGreaterThan(50);
    const legend = frame.querySelector('[data-slot="chart-legend"]');
    await expect(legend).not.toBeNull();
    await expect(Number(frame.getAttribute('data-marks'))).toBe(
      rows[0]!.length - 1,
    );
  },
};
