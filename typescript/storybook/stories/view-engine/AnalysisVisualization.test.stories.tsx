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
  TwoMetrics as DisplayTwoMetrics,
} from './AnalysisWorkbench.stories.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/分析视图/可视化面板/回归',
  tags: ['!dev', '!autodocs', 'test'],
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** The gear beside the chosen tile, named after the type it configures. */
const optionsOf = (type: 'bar' | 'pie') =>
  formatMessage(zhCN, 'label.chart.options-of', {
    name: zhCN[`label.chart.type.${type}`],
  });

const panel = () =>
  document.querySelector<HTMLElement>('[data-slot="chart-options"]');

/**
 * Each slice's path, in the order the pie draws them. A sector drawn from
 * the centre has one arc; one with a hole in it has an outer arc and an
 * inner one, which is how a donut can be told from a pie by what was drawn
 * rather than by what the config says.
 */
const arcsPerSlice = () =>
  [...document.querySelectorAll('.recharts-pie-sector path')].map(
    path => (path.getAttribute('d')?.match(/A/g) ?? []).length,
  );

/**
 * The visualization panel's two levels, walked (D20 屏 I／J).
 *
 * Every step here is a redraw of the rows already on screen: the panel is
 * opened from the result toolbar, the chosen type's options are walked page
 * by page, and each setting reaches the drawing — the labels over the marks,
 * the reference line across them, the axis title along the axis, and the
 * hole in the middle of a pie. jsdom can pin the spec each control writes
 * (`packages/view-engine/test/chartOptionsUi.test.tsx`); only a browser can
 * say the marks changed, which is what this walk is for.
 */
export const ChartOptionsPages: Story = {
  ...DisplayTwoMetrics,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const body = within(document.body);
    // Two metrics, so there are two series to stack and a legend to read.
    await waitFor(() =>
      expect(
        canvasElement.querySelectorAll('.recharts-bar-rectangle'),
      ).toHaveLength(8),
    );

    // Level one: the panel takes the sidebar column, where the view list was.
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.analysis.visualize'] }),
    );
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="chart-picker"]'),
      ).not.toBeNull(),
    );
    expect(document.querySelector('[data-slot="view-list"]')).toBeNull();

    // Level two: the gear beside the chosen tile, and the three pages a
    // cartesian chart has.
    await userEvent.click(body.getByRole('button', { name: optionsOf('bar') }));
    await waitFor(() => expect(panel()).not.toBeNull());
    await expect(
      within(panel()!)
        .getAllByRole('tab')
        .map(tab => tab.textContent),
    ).toEqual([
      zhCN['label.chart.tab.data'],
      zhCN['label.chart.tab.display'],
      zhCN['label.chart.tab.axes'],
    ]);

    // The data page: a slot per position, each named by its column.
    await expect(
      within(panel()!).getByLabelText(zhCN['label.chart.slot.x']),
    ).toBeVisible();
    await expect(
      panel()!.querySelectorAll('[data-slot="series-card"]'),
    ).toHaveLength(2);

    // The display page: value labels over every mark, and one stack.
    await userEvent.click(
      within(panel()!).getByRole('tab', {
        name: zhCN['label.chart.tab.display'],
      }),
    );
    await expect(
      canvasElement.querySelectorAll('.recharts-label-list'),
    ).toHaveLength(0);
    await userEvent.click(
      within(panel()!).getByRole('checkbox', {
        name: zhCN['label.chart.labels'],
      }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelectorAll('.recharts-label-list'),
      ).toHaveLength(2),
    );

    await userEvent.click(
      within(panel()!).getByRole('checkbox', {
        name: zhCN['label.chart.stacked'],
      }),
    );
    // Stacking is one choice for the whole chart, so the box reads back on.
    await waitFor(() =>
      expect(
        within(panel()!)
          .getByRole('checkbox', { name: zhCN['label.chart.stacked'] })
          .getAttribute('aria-checked'),
      ).toBe('true'),
    );

    // A reference line, drawn across the marks rather than merely stored.
    await userEvent.click(
      within(panel()!).getByRole('button', {
        name: zhCN['label.chart.add-reference-line'],
      }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelectorAll('.recharts-reference-line'),
      ).toHaveLength(1),
    );

    // The axes page: a title written along the numeric axis.
    await userEvent.click(
      within(panel()!).getByRole('tab', { name: zhCN['label.chart.tab.axes'] }),
    );
    await userEvent.type(
      within(panel()!).getAllByLabelText(zhCN['label.chart.axis-title'])[0]!,
      '金额',
    );
    await waitFor(() =>
      expect(
        [...canvasElement.querySelectorAll('.recharts-label')].map(
          label => label.textContent,
        ),
      ).toContain('金额'),
    );

    // Back to the types, and on to another family: a pie of the same rows,
    // with no query in between.
    await userEvent.click(
      within(panel()!).getByRole('button', {
        name: zhCN['label.chart.options-back'],
      }),
    );
    await userEvent.click(
      await body.findByRole('radio', { name: zhCN['label.chart.type.pie'] }),
    );
    // One slice per warehouse: a type picked here is fitted to the rows on
    // screen, and nothing has asked for a tail to be merged.
    await waitFor(() => expect(arcsPerSlice()).toHaveLength(4));
    await expect(arcsPerSlice().every(arcs => arcs === 1)).toBe(true);

    // The pie's own display page, and the one setting that changes its shape.
    await userEvent.click(body.getByRole('button', { name: optionsOf('pie') }));
    await waitFor(() => expect(panel()).not.toBeNull());
    await expect(
      within(panel()!)
        .getAllByRole('tab')
        .map(tab => tab.textContent),
    ).toEqual([zhCN['label.chart.tab.data'], zhCN['label.chart.tab.display']]);
    await userEvent.click(
      within(panel()!).getByRole('tab', {
        name: zhCN['label.chart.tab.display'],
      }),
    );
    await userEvent.click(
      within(panel()!).getByRole('checkbox', {
        name: zhCN['label.chart.donut'],
      }),
    );
    // A hole in the middle: every sector now has an inner arc as well.
    await waitFor(() =>
      expect(arcsPerSlice().every(arcs => arcs === 2)).toBe(true),
    );

    // Out the way it came in: the types, then the view list back in the
    // column the panel borrowed.
    await userEvent.click(
      within(panel()!).getByRole('button', {
        name: zhCN['label.chart.options-back'],
      }),
    );
    await userEvent.click(
      await body.findByRole('button', {
        name: zhCN['label.chart.picker-back'],
      }),
    );
    await waitFor(() =>
      expect(document.querySelector('[data-slot="view-list"]')).not.toBeNull(),
    );
    await expect(
      document.querySelector('[data-slot="chart-picker"]'),
    ).toBeNull();
  },
};
