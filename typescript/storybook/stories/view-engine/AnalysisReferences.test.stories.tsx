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
  AverageAndTarget as DisplayAverageAndTarget,
  CutShort as DisplayCutShort,
  RunningTotal as DisplayRunningTotal,
  TrendAndMovingAverage as DisplayTrendAndMovingAverage,
} from './AnalysisReferences.stories.js';
import { chartsDrawn, legendNames } from './chartDom.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/分析视图/参考与算出的系列/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes a file's own
  // description into a `parameters` of its meta, which would replace the
  // display meta's — and with it the full-screen host application.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

const plotOf = (root: HTMLElement) =>
  root.querySelector<HTMLElement>('[data-slot="chart-plot"]')!;

/** Every piece of text the library drew in the plot. */
const plotTexts = (root: HTMLElement) =>
  [...plotOf(root).querySelectorAll('svg text')].map(text =>
    (text.textContent ?? '').trim(),
  );

/** The reading table's column headers. */
const readingHeaders = (root: HTMLElement) =>
  [
    ...root.querySelectorAll(
      '[data-slot="chart-reading"] thead th, [data-slot="chart-reading"] thead td',
    ),
  ].map(cell => (cell.textContent ?? '').trim());

/** The id-named description of a control, as a screen reader hears it. */
const describedBy = (element: Element) =>
  (element.getAttribute('aria-describedby') ?? '')
    .split(' ')
    .filter(Boolean)
    .map(id => document.getElementById(id)?.textContent ?? '')
    .join(' ');

/** Opens the chart's options on the display page. */
async function displayPage(canvasElement: HTMLElement) {
  const canvas = within(canvasElement);
  if (!canvasElement.querySelector('[data-slot="chart-options-open"]'))
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.analysis.visualize'] }),
    );
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
  await userEvent.click(
    within(options).getByRole('tab', {
      name: zhCN['label.chart.tab.display'],
    }),
  );
  return options;
}

/**
 * 平均线站在量到的值的平均上、写「平均 ¥…」；目标区间淡色铺在后面、写它的
 * 名字；最高点与最低点各写「最高」「最低」和数（D33 批 B 判据）。
 */
export const AverageTargetAndExtremes: Story = {
  ...DisplayAverageAndTarget,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    await waitFor(() => {
      const texts = plotTexts(canvasElement);
      expect(texts.some(text => /^平均 /.test(text))).toBe(true);
      expect(texts).toContain('目标区间');
      expect(texts.some(text => /^最高 /.test(text))).toBe(true);
      expect(texts.some(text => /^最低 /.test(text))).toBe(true);
    });
  },
};

/**
 * 趋势与 7 期移动平均画成虚线，图例里各是一道短虚线、名字写「（算出的）」，
 * 读屏表各一列；键盘 Tab 到图例里的趋势按空格，它不再画、读屏表那一列也走。
 */
export const ComputedLinesAreNamedAndSwitchable: Story = {
  ...DisplayTrendAndMovingAverage,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const trend = '趋势（算出的）';
    const average = '7 期移动平均（算出的）';
    await waitFor(() =>
      expect(legendNames(canvasElement)).toEqual([
        '金额的总和',
        trend,
        average,
      ]),
    );
    await expect(
      canvasElement.querySelectorAll('[data-slot="chart-legend-dash"]'),
    ).toHaveLength(2);
    await expect(readingHeaders(canvasElement)).toEqual(
      expect.arrayContaining([trend, average]),
    );
    const canvas = within(canvasElement);
    const series = canvas.getByRole('button', { name: '金额的总和' });
    const toggle = canvas.getByRole('button', { name: trend });
    series.focus();
    await userEvent.tab();
    await expect(toggle).toHaveFocus();
    await userEvent.keyboard(' ');
    await waitFor(() =>
      expect(toggle).toHaveAttribute('aria-pressed', 'false'),
    );
    await waitFor(() =>
      expect(readingHeaders(canvasElement)).not.toContain(trend),
    );
    await expect(readingHeaders(canvasElement)).toContain(average);
  },
};

/**
 * 柱上的累计：一条虚线坐右轴、轴名就是它，末端是 90 天的总和，读屏表的最后
 * 一格写同一个数。
 */
export const RunningTotalOverBars: Story = {
  ...DisplayRunningTotal,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const header = '累计（算出的）';
    await waitFor(() =>
      expect(readingHeaders(canvasElement)).toContain(header),
    );
    const table = canvasElement.querySelector('[data-slot="chart-reading"]')!;
    const column = readingHeaders(canvasElement).indexOf(header);
    const last = [...table.querySelectorAll('tbody tr')].at(-1)!;
    const cells = [...last.children].map(cell => cell.textContent ?? '');
    await expect(cells[column]).toMatch(/^¥\d{2,3},\d{3}/);
    // On the right axis, titled by it: the bars keep their own scale.
    await expect(
      canvasElement.querySelector('[data-slot="chart"]'),
    ).toHaveAttribute('data-chart', 'bar');
    await expect(plotTexts(canvasElement)).toContain(header);
  },
};

/**
 * 只拿回最近 30 天（前 30 组）：累计不画，图上方写原因；显示页里「累计」仍勾着、
 * 写同一句原因，可以取消；「趋势线」置灰、写同一句（Q53）。
 */
export const CutShortSaysWhy: Story = {
  ...DisplayCutShort,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const reason = '结果只显示了前 30 组，算出的线会不完整。';
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="chart-gap-note"]'),
      ).toHaveTextContent(`累计（算出的）没有画：${reason}`),
    );
    await expect(
      canvasElement.querySelector('[data-slot="chart-legend-dash"]'),
    ).toBeNull();

    const options = await displayPage(canvasElement);
    const running = within(options).getByRole('checkbox', { name: '累计' });
    await expect(running).toHaveAttribute('aria-checked', 'true');
    await expect(running).not.toHaveAttribute('data-disabled');
    await expect(describedBy(running)).toBe(reason);
    const trend = within(options).getByRole('checkbox', { name: '趋势线' });
    await expect(trend).toHaveAttribute('data-disabled');
    await expect(describedBy(trend)).toBe(reason);
  },
};

/**
 * 键盘在显示页里勾上趋势线：图例多一道「趋势（算出的）」，不发查询（改了就重画）。
 */
export const KeyboardAddsATrend: Story = {
  ...DisplayAverageAndTarget,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const options = await displayPage(canvasElement);
    const trend = within(options).getByRole('checkbox', { name: '趋势线' });
    trend.focus();
    await userEvent.keyboard(' ');
    await waitFor(() =>
      expect(legendNames(canvasElement)).toContain('趋势（算出的）'),
    );
  },
};
