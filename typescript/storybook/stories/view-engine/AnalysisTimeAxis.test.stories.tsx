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
  PatternsPinnedOn as DisplayPatternsPinnedOn,
  TenThousandBars as DisplayTenThousandBars,
  TenThousandDays as DisplayTenThousandDays,
  YearOfDays as DisplayYearOfDays,
  YearOfDaysBars as DisplayYearOfDaysBars,
} from './AnalysisTimeAxis.stories.js';
import { axisTexts, chartsDrawn, legendNames } from './chartDom.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/分析视图/长时间轴/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes a file's own
  // description into a `parameters` of its meta, which would replace the
  // display meta's — and with it the full-screen host application.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

const frameOf = (root: HTMLElement) =>
  root.querySelector<HTMLElement>('[data-slot="chart"]')!;

const plotOf = (root: HTMLElement) =>
  root.querySelector<HTMLElement>('[data-slot="chart-plot"]')!;

/**
 * One turn of a wheel over the plot's middle, held with Ctrl or not, and
 * whether anything took it from the page (`defaultPrevented`). Sent to the
 * drawing itself, as a pointer over it sends one.
 *
 * The turn is away from the reader — up the page, which is what spreading
 * two fingers on a trackpad sends — and the chart library reads its size and
 * direction off the legacy `wheelDelta`, which a browser derives from the
 * delta of a real wheel: the opposite sign, so a turn up is positive. A
 * built event has no real wheel behind it, and here the browsers part:
 * Firefox and WebKit derive `wheelDelta` as a real wheel would, Chromium
 * copies `deltaY` across with its sign unchanged. A turn *down* therefore
 * zoomed in only in Chromium — and out, as a real turn down does, in the
 * other two. Chromium takes the legacy value when it is given
 * (`wheelDeltaY`, which the other two ignore), so it is given, and every
 * browser reads the turn a real wheel would make.
 */
function wheel(root: HTMLElement, ctrlKey: boolean): boolean {
  const plot = plotOf(root);
  const box = plot.getBoundingClientRect();
  const event = new WheelEvent('wheel', {
    bubbles: true,
    cancelable: true,
    clientX: box.left + box.width * 0.9,
    clientY: box.top + box.height / 2,
    // A mouse wheel's notch, as a trackpad's pinch sends several of.
    deltaY: -360,
    wheelDeltaY: 360,
    ctrlKey,
  } as WheelEventInit);
  (plot.querySelector('svg') ?? plot).dispatchEvent(event);
  return event.defaultPrevented;
}

const pause = (ms: number) => new Promise(done => setTimeout(done, ms));

/**
 * The line a redraw is held to on CI: three times the slowest runner seen
 * (≈ 630ms), so only a slowdown of a different order turns the gate red.
 * The budget itself is 500ms (analysis-echarts.md 批 A 判据), about 186ms
 * measured on a developer's machine; the times are logged every run.
 */
const REDRAW_GUARD_MS = 1500;

/**
 * The chart drawn, for a result of ten thousand rows: shaped, planned and
 * drawn at that length for the first time, which on a busy runner is slow.
 */
const drawnLong = (root: HTMLElement) =>
  waitFor(() => expect(frameOf(root)).toHaveAttribute('data-drawn', 'true'), {
    timeout: 12_000,
  });

/**
 * How long a redraw takes, from a change of theme that hands the chart a new
 * option to its marks landing (`data-drawn`), in milliseconds. The change is
 * the host pinning patterns off, or letting go of that pin — either way what
 * the reader's system already said, so the drawing is the same and only its
 * option is new. Called twice, it leaves the page as it found it.
 */
async function redrawTime(frame: HTMLElement): Promise<number> {
  const html = document.documentElement;
  const start = performance.now();
  const landed = new Promise<number>(done => {
    // Taken away when the new drawing is on its way, put back when it has
    // landed — possibly both between two looks at it.
    let cleared = false;
    const observer = new MutationObserver(records => {
      if (records.some(record => record.oldValue !== null)) cleared = true;
      if (cleared && frame.hasAttribute('data-drawn')) {
        observer.disconnect();
        done(performance.now() - start);
      }
    });
    observer.observe(frame, {
      attributes: true,
      attributeOldValue: true,
      attributeFilter: ['data-drawn'],
    });
  });
  if (html.style.getPropertyValue('--fve-chart-patterns'))
    html.style.removeProperty('--fve-chart-patterns');
  else html.style.setProperty('--fve-chart-patterns', 'off');
  return landed;
}

/**
 * Four redraws, and how long each took. The fastest is what the drawing
 * costs; the others carry whatever else the machine was doing — a CI runner
 * shares its cores — and are logged, not held to the line.
 */
async function redrawTimes(frame: HTMLElement): Promise<number[]> {
  const times: number[] = [];
  for (let round = 0; round < 4; round += 1)
    times.push(await redrawTime(frame));
  return times;
}

/**
 * The days the axis under the plot names, left to right: its text that
 * reads as a day — the value ticks are money, the title names no day.
 */
const dayTicks = (root: HTMLElement) =>
  axisTexts(root)
    .filter(text => /\d+月\d+日/.test(text.textContent ?? ''))
    .sort(
      (a, b) => a.getBoundingClientRect().left - b.getBoundingClientRect().left,
    )
    .map(text => (text.textContent ?? '').trim());

/** The reading table's column headers. */
const readingHeaders = (root: HTMLElement) =>
  [
    ...root.querySelectorAll(
      '[data-slot="chart-reading"] thead th, [data-slot="chart-reading"] thead td',
    ),
  ].map(cell => (cell.textContent ?? '').trim());

/**
 * 一年的日数据缩到一周（D33 批 A 判据）：365 天的堆叠柱下有滑条；不按 Ctrl
 * 的滚轮照常是页面的，图不缩；按住 Ctrl 滚（触控板双指捏合就是这样发的）缩到
 * 缩放的下限——一周，七天——横轴写出每一天，每一摞柱顶写出它的合计。缩放不随
 * 视图保存（Q51），所以标题不点亮「改过没应用」；换一个结果（刷新）回到全范围。
 */
export const ZoomsAYearToAWeek: Story = {
  ...DisplayYearOfDaysBars,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const frame = frameOf(canvasElement);
    await expect(frame).toHaveAttribute('data-zoom', 'gestures');
    const before = dayTicks(canvasElement);

    // A plain wheel is the page's: nothing takes it, nothing zooms.
    await expect(wheel(canvasElement, false)).toBe(false);
    await pause(200);
    await expect(frame).not.toHaveAttribute('data-zoomed');

    // Down to the zoom's floor: a week, seven days.
    for (let turn = 0; turn < 60; turn += 1) {
      await expect(wheel(canvasElement, true)).toBe(true);
      await pause(110);
      if (turn > 10 && dayTicks(canvasElement).length === 7) break;
    }
    await expect(frame).toHaveAttribute('data-zoomed', 'true');
    await waitFor(() => expect(dayTicks(canvasElement)).toHaveLength(7));
    const week = dayTicks(canvasElement);
    await expect(week).not.toEqual(before.slice(0, 7));
    // Every day's total over its stack.
    await waitFor(() =>
      expect(
        plotOf(canvasElement).querySelectorAll('svg text[stroke]').length,
      ).toBeGreaterThanOrEqual(7),
    );

    // Transient: the view is not changed by it.
    const canvas = within(canvasElement);
    await expect(
      canvas.getByRole('heading', { level: 2, name: '每日发货金额' }),
    ).not.toHaveAttribute('data-dirty');

    // A new result opens at its whole range again.
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.toolbar.refresh'] }),
    );
    await waitFor(() => expect(frame).not.toHaveAttribute('data-zoomed'));
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(dayTicks(canvasElement)).toEqual(before));
  },
};

/**
 * 图例点一项藏起一条系列（D33 批 A 判据）：两个仓库的线，图例的每一项是一颗
 * 按钮、`aria-pressed` 说它画没画；键盘 Tab 到「华南仓」按空格，它不再画，读屏
 * 表也只剩「华东仓」一列，图例仍列着它；再按一次回来。axe 在故事结束时判这组
 * 按钮。
 */
export const LegendHidesASeries: Story = {
  ...DisplayYearOfDays,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const canvas = within(canvasElement);
    const south = canvas.getByRole('button', { name: '华南仓' });
    const east = canvas.getByRole('button', { name: '华东仓' });
    await expect(south).toHaveAttribute('aria-pressed', 'true');
    await expect(readingHeaders(canvasElement)).toContain('华南仓');

    east.focus();
    await userEvent.tab();
    await expect(south).toHaveFocus();
    await userEvent.keyboard(' ');

    await waitFor(() => expect(south).toHaveAttribute('aria-pressed', 'false'));
    await expect(east).toHaveAttribute('aria-pressed', 'true');
    await waitFor(() =>
      expect(readingHeaders(canvasElement)).not.toContain('华南仓'),
    );
    await expect(readingHeaders(canvasElement)).toContain('华东仓');
    await expect(legendNames(canvasElement)).toEqual(['华东仓', '华南仓']);

    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(south).toHaveAttribute('aria-pressed', 'true'));
    await waitFor(() =>
      expect(readingHeaders(canvasElement)).toContain('华南仓'),
    );
  },
};

/**
 * 悬停一天，提示框写这一天每个仓库的金额，每个数后面是它较前一天的变化，
 * 底下一行「较上一期」说它量的是什么（D33 Q59：不多发查询）。
 */
export const TooltipSaysTheChangeFromTheDayBefore: Story = {
  ...DisplayYearOfDays,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const plot = plotOf(canvasElement);
    const svg = plot.querySelector('svg')!;
    const box = plot.getBoundingClientRect();
    svg.dispatchEvent(
      new MouseEvent('mousemove', {
        bubbles: true,
        clientX: box.left + box.width / 2,
        clientY: box.top + box.height / 2,
      }),
    );
    const tooltip = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="chart-tooltip"]',
      );
      expect(found).toBeVisible();
      return found!;
    });
    const notes = [
      ...tooltip.querySelectorAll('[data-slot="chart-tooltip-note"]'),
    ].map(note => note.textContent ?? '');
    await expect(notes).toHaveLength(2);
    for (const note of notes) await expect(note).toMatch(/^[+-]?\d/);
    await expect(
      tooltip.querySelector('[data-slot="chart-tooltip-footnote"]'),
    ).toHaveTextContent(zhCN['label.chart.change.against']);
  },
};

/**
 * 一万天的日折线（D33 批 A 判据）：一万个点按绘图区的宽度采样。预算是从换一次
 * 主题下发新的 option 到 `data-drawn` 不超过 500ms（本机实测约 186ms，CI 上
 * 390～630ms 随 runner 起伏）；这里只守 1500ms 的回归线——`typescript-storybook-gate`
 * 是 main 的必过项，一台慢 runner 的抖动不该挡住合并，而数量级的变慢照样拦得住。
 * 横轴隔几个写一个，写到的年份变了就写出年份。
 */
export const TenThousandDaysDrawInTime: Story = {
  ...DisplayTenThousandDays,
  play: async ({ canvasElement }) => {
    await drawnLong(canvasElement);
    const frame = frameOf(canvasElement);
    await expect(frame).toHaveAttribute('data-marks', '10000');
    const times = await redrawTimes(frame);
    console.info('10k-day line redraw (ms):', times.map(Math.round));
    // A regression guard, not the budget (`REDRAW_GUARD_MS`).
    await expect(Math.min(...times)).toBeLessThan(REDRAW_GUARD_MS);
    // Sampled: one line of a few hundred points, not ten thousand.
    const line = [...plotOf(canvasElement).querySelectorAll('svg path')].find(
      path => (path.getAttribute('fill') ?? 'none') === 'none',
    );
    await expect(line).toBeDefined();
    await expect(
      (line!.getAttribute('d') ?? '').split(/[LM]/).length,
    ).toBeLessThan(3_000);
    // The years are written where they change among the ticks shown.
    const ticks = dayTicks(canvasElement);
    await expect(ticks[0]).toMatch(/^\d{4}年/);
    await expect(ticks.filter(tick => /^\d{4}年/.test(tick)).length).toBe(
      ticks.length,
    );
  },
};

/**
 * 一万天画成柱：多于一千根，一条路径画完（large 模式），不写数。
 */
export const TenThousandBarsDrawAsOnePath: Story = {
  ...DisplayTenThousandBars,
  play: async ({ canvasElement }) => {
    await drawnLong(canvasElement);
    const frame = frameOf(canvasElement);
    await expect(frame).toHaveAttribute('data-marks', '10000');
    await expect(
      plotOf(canvasElement).querySelectorAll('svg path').length,
    ).toBeLessThan(100);
    await expect(
      plotOf(canvasElement).querySelectorAll('svg text[stroke]'),
    ).toHaveLength(0);
  },
};

/**
 * 花纹（D33 Q57）：宿主用 `--fve-chart-patterns: on` 钉开，每条系列除了颜色
 * 还有自己的花纹；没钉、系统也没要「提高对比度」时没有花纹。
 */
export const PatternsFollowTheHost: Story = {
  ...DisplayPatternsPinnedOn,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    await expect(frameOf(canvasElement)).toHaveAttribute('data-patterns', 'on');
    await expect(
      plotOf(canvasElement).querySelectorAll('svg pattern').length,
    ).toBeGreaterThan(0);
  },
};

/** 没钉花纹：跟随系统，测试的浏览器没要「提高对比度」，所以没有花纹。 */
export const NoPatternsByDefault: Story = {
  ...DisplayYearOfDaysBars,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    await expect(frameOf(canvasElement)).toHaveAttribute(
      'data-patterns',
      'off',
    );
    await expect(
      plotOf(canvasElement).querySelectorAll('svg pattern'),
    ).toHaveLength(0);
  },
};
