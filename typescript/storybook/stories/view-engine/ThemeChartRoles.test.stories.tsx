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

/**
 * The chart's roles (theme-architecture.md 6, S5), measured on the drawing.
 *
 * The library draws its own SVG, which no cascade reaches, so a chart reads
 * its whole look back off its element — colours, lengths and numbers the
 * browser works out — and is drawn in that. Here the drawing itself is
 * read: the stroke of a gridline, a line's width, whether a bar's end is an
 * arc, the tooltip's ground.
 *
 * - Unset, the chart is drawn as it always was: gridlines in `border`, a
 *   2px line, bars rounded at the end, the tooltip on the popups' surface.
 * - A host that sets the roles — here through the surface's `tokens`, the
 *   host's own layer — moves exactly those: a square style (`radius` 0)
 *   squares the bars with no word about bars, a thicker line, a stronger
 *   grid, a wider floor under a bar.
 *
 * The jsdom side — each role through the probe, the built-in look held to
 * `styles.css`, no size or width written in an option builder — is
 * `test/chartTheme.test.tsx` in the package.
 */

import type { StoryObj } from '@storybook/react-vite';
import { expect, waitFor } from 'storybook/test';
import type { ChartData, ChartSpec } from '@ahoo-wang/wow-view-engine';
import {
  AnalysisChart,
  type FveToken,
  ViewSurface,
} from '@ahoo-wang/wow-view-engine/ui';
import { converter, formatRgb, parse } from 'culori';
import displayMeta from './RecordWorkbench.stories.js';
import { chartsDrawn, drawnMarks, raiseTooltip } from './chartDom.js';
import { HOST_LANGUAGE } from './fixtures.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/能力/主题与预设/图表角色/回归',
  tags: ['!dev', '!autodocs', 'test'],
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

const toRgb = converter('rgb');

/** Any CSS colour as `rgb()`, as the library writes a stroke. */
const rgbOf = (value: string) => formatRgb(toRgb(parse(value)!));

const WAREHOUSES = ['华东', '华南', '华北', '西南'];

const bars: ChartData = {
  type: 'cartesian',
  chart: 'bar',
  points: WAREHOUSES.map((x, index) => ({
    x,
    values: { orders: [42, 30, 25, 12][index] },
  })),
  series: [{ key: 'orders', label: '订单数', metric: 'orders' }],
};

const barSpec: ChartSpec = {
  type: 'bar',
  cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
};

const line: ChartData = {
  ...bars,
  chart: 'area',
  points: ['9月1日', '9月2日', '9月3日', '9月4日', '9月5日'].map(
    (x, index) => ({ x, values: { orders: [12, 18, 15, 22, 19][index] } }),
  ),
};

const lineSpec: ChartSpec = {
  type: 'area',
  cartesian: { x: 'day', series: [{ metric: 'orders' }] },
};

/** A bar chart and an area chart on one surface, 640px wide each. */
function Charts({ tokens }: { tokens?: Partial<Record<FveToken, string>> }) {
  return (
    <ViewSurface {...HOST_LANGUAGE} tokens={tokens}>
      <div style={{ display: 'grid', gap: 16, width: 640 }}>
        <div data-chart="bars" style={{ height: 280 }}>
          <AnalysisChart data={bars} spec={barSpec} />
        </div>
        <div data-chart="line" style={{ height: 240 }}>
          <AnalysisChart data={line} spec={lineSpec} />
        </div>
      </div>
    </ViewSurface>
  );
}

/** One of the two charts' frames. */
const frame = (canvas: HTMLElement, which: 'bars' | 'line') =>
  canvas.querySelector<HTMLElement>(`[data-chart="${which}"]`)!;

/**
 * The drawing's rules — gridlines and axis lines: stroked, unfilled paths
 * that are straight across the plot.
 */
function rules(root: HTMLElement): SVGPathElement[] {
  return [
    ...root.querySelectorAll<SVGPathElement>(
      '[data-slot="chart-plot"] svg path[stroke]',
    ),
  ].filter(path => {
    const box = path.getBoundingClientRect();
    return (
      (path.getAttribute('fill') ?? 'none') === 'none' &&
      box.height < 2 &&
      box.width > 100
    );
  });
}

/** The series' line: the widest stroked path that is not a rule. */
function seriesLine(root: HTMLElement): SVGPathElement {
  const [found] = [
    ...root.querySelectorAll<SVGPathElement>(
      '[data-slot="chart-plot"] svg path[stroke]',
    ),
  ]
    .filter(
      path =>
        (path.getAttribute('fill') ?? 'none') === 'none' &&
        path.getBoundingClientRect().height >= 2,
    )
    .sort(
      (a, b) =>
        b.getBoundingClientRect().width - a.getBoundingClientRect().width,
    );
  if (!found) throw new Error('no line drawn');
  return found;
}

/** Whether a bar's outline turns its corners with an arc: a rounded end. */
const rounded = (bar: SVGPathElement) =>
  /[aAcCqQ]/.test(bar.getAttribute('d') ?? '');

/** A variable as the cascade resolved it on the surface, as `rgb()`. */
function tokenColor(canvas: HTMLElement, variable: string): string {
  const surface = canvas.querySelector('[data-slot="view-surface"]')!;
  const probe = document.createElement('span');
  probe.hidden = true;
  probe.style.setProperty('background-color', `var(${variable})`);
  surface.append(probe);
  const value = getComputedStyle(probe).backgroundColor;
  probe.remove();
  return rgbOf(value);
}

/**
 * 图表角色不设时就是原来的样子（S5）：网格线是 `border`、线宽 2、柱的末端是圆
 * 角、提示框站在弹层的底上（`popover`，不再是页面底色加 `shadow-xl`）。
 */
export const BuiltInLook: Story = {
  render: () => <Charts />,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const barChart = frame(canvasElement, 'bars');
    const areaChart = frame(canvasElement, 'line');
    const border = tokenColor(canvasElement, '--border');
    const grid = rules(barChart);
    await expect(grid.length).toBeGreaterThan(1);
    for (const rule of grid)
      await expect(rgbOf(rule.getAttribute('stroke')!)).toBe(border);
    await expect(seriesLine(areaChart).getAttribute('stroke-width')).toBe('2');
    const marks = drawnMarks(barChart);
    await expect(marks).toHaveLength(WAREHOUSES.length);
    for (const bar of marks) await expect(rounded(bar)).toBe(true);

    const tooltip = await raiseTooltip(
      barChart.querySelector<HTMLElement>('[data-slot="chart-plot"]')!,
    );
    await expect(rgbOf(getComputedStyle(tooltip).backgroundColor)).toBe(
      tokenColor(canvasElement, '--popover'),
    );
    await expect(getComputedStyle(tooltip).boxShadow).not.toBe('none');
  },
};

/** A host's square, heavy-lined style: what it sets, and nothing else. */
const SQUARE_STYLE: Partial<Record<FveToken, string>> = {
  // A square style: its corners, and so its bars', with no word on bars.
  '--fve-radius': '0rem',
  '--fve-chart-line-width': '3px',
  '--fve-chart-grid': 'oklch(0.62 0 0deg)',
  '--fve-chart-grid-width': '1.5px',
  '--fve-chart-area-opacity': '0.35',
};

/**
 * 宿主设了图表角色（S5）：方角风格（`radius` 为 0）的柱子自己就方了，线更粗，
 * 网格线更深更宽，面积更实——只动这几处，柱数、刻度都不变。
 */
export const HostSetsTheChartRoles: Story = {
  render: () => <Charts tokens={SQUARE_STYLE} />,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const barChart = frame(canvasElement, 'bars');
    const areaChart = frame(canvasElement, 'line');
    const strong = rgbOf('oklch(0.62 0 0deg)');
    await waitFor(() => expect(rules(barChart).length).toBeGreaterThan(1));
    for (const rule of rules(barChart)) {
      await expect(rgbOf(rule.getAttribute('stroke')!)).toBe(strong);
      await expect(rule.getAttribute('stroke-width')).toBe('1.5');
    }
    await expect(seriesLine(areaChart).getAttribute('stroke-width')).toBe('3');
    const area = [
      ...areaChart.querySelectorAll<SVGPathElement>(
        '[data-slot="chart-plot"] svg path[fill-opacity]',
      ),
    ].find(path => Number(path.getAttribute('fill-opacity')) < 1);
    await expect(Number(area?.getAttribute('fill-opacity'))).toBeCloseTo(
      0.35,
      2,
    );
    const marks = drawnMarks(barChart);
    await expect(marks).toHaveLength(WAREHOUSES.length);
    for (const bar of marks) await expect(rounded(bar)).toBe(false);
  },
};

/**
 * 柱宽的上下限是角色（S5）：宿主把最宽设成 24px，四根柱子都不超过它；最窄设成
 * 12px，挤在很窄的图里也不比它细。
 */
export const HostBoundsTheBars: Story = {
  render: () => (
    <ViewSurface
      {...HOST_LANGUAGE}
      tokens={{
        '--fve-chart-bar-max-width': '24px',
        '--fve-chart-bar-min-width': '12px',
      }}
    >
      <div style={{ display: 'flex', gap: 16 }}>
        <div data-chart="wide" style={{ width: 640, height: 240 }}>
          <AnalysisChart data={bars} spec={barSpec} />
        </div>
        <div data-chart="narrow" style={{ width: 120, height: 240 }}>
          <AnalysisChart data={bars} spec={barSpec} />
        </div>
      </div>
    </ViewSurface>
  ),
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const widths = (which: string) =>
      drawnMarks(
        canvasElement.querySelector<HTMLElement>(`[data-chart="${which}"]`)!,
      ).map(bar => bar.getBoundingClientRect().width);
    await expect(widths('wide')).toHaveLength(WAREHOUSES.length);
    for (const width of widths('wide'))
      await expect(width).toBeLessThanOrEqual(24.5);
    for (const width of widths('narrow'))
      await expect(width).toBeGreaterThanOrEqual(11.5);
  },
};
