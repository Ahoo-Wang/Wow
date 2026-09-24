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
import displayMeta, {
  BarChart as DisplayBarChart,
  TwoMetrics as DisplayTwoMetrics,
} from './AnalysisWorkbench.stories.js';
import { dragHandleOnto } from './pointerDrag.js';
import {
  axisTexts,
  chartsDrawn,
  drawnMarks,
  overlaps,
  slicesInOrder,
  valueLabels,
} from './chartDom.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/分析视图/可视化面板/回归',
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
 * The labelled button under the tiles that opens the chosen type's options,
 * in the words the options page is headed with.
 */
const optionsOf = (type: 'bar' | 'pie') =>
  formatMessage(zhCN, 'label.chart.options', {
    name: zhCN[`label.chart.type.${type}`],
  });

const panel = () =>
  document.querySelector<HTMLElement>('[data-slot="chart-options"]');

/** 「金额的总和」: the one column the bar chart measures. */
const AMOUNT_HEADER = formatMessage(zhCN, 'label.summary.of', {
  field: '金额',
  fn: zhCN['label.summary.fn.SUM'],
});

/**
 * Each slice's path, in the order the pie draws them. A sector drawn from
 * the centre has one arc; one with a hole in it has an outer arc and an
 * inner one, which is how a donut can be told from a pie by what was drawn
 * rather than by what the config says.
 */
const arcsPerSlice = () =>
  slicesInOrder(document).map(
    path => (path.getAttribute('d')?.match(/A/g) ?? []).length,
  );

/** The series rows in the order the data page lists them. */
const seriesOrder = () =>
  [...panel()!.querySelectorAll('[data-slot="series-card"]')].map(row =>
    row.getAttribute('data-metric'),
  );

/** The legend entries in the order the chart draws them. */
const legendOrder = () =>
  [...document.querySelectorAll('[data-slot="chart-legend-item"]')].map(
    item => item.textContent,
  );

/** Every two value labels on screen, apart: none drawn over another. */
/** Every two value labels drawn over each other, by their text and box. */
const labelsOver = (root: HTMLElement) => {
  const labels = valueLabels(root).map(label => ({
    text: label.textContent,
    box: label.getBoundingClientRect(),
  }));
  return labels.flatMap((one, index) =>
    labels
      .slice(index + 1)
      .filter(other => overlaps(one.box, other.box))
      .map(other => ({
        pair: [one.text, other.text],
        boxes: [one.box, other.box].map(box =>
          [box.left, box.top, box.right, box.bottom].map(Math.round),
        ),
      })),
  );
};

/**
 * A series carried into another place with the pointer (D20 屏 J).
 *
 * Which series comes first is a setting — a stack is read from the bottom up
 * and a legend from its first entry — and the whole of what it changes is
 * the order of `cartesian.series`. jsdom can pin the move the arrow keys
 * make (`typescript/wow-view-engine/test/chartOptionsUi.test.tsx`), but not the
 * gesture: `@dnd-kit/dom` picks its drop target by *measuring*, and in jsdom
 * every box is 0×0 at the origin. Here the boxes are real, so this is the
 * one place the pointer path is exercised at all — and the proof is the
 * drawing, not the config: the legend comes back in the new order.
 */
export const SeriesOrder: Story = {
  ...DisplayTwoMetrics,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const body = within(document.body);
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(8));
    const drawn = legendOrder();
    await expect(drawn).toHaveLength(2);

    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.analysis.visualize'] }),
    );
    await userEvent.click(body.getByRole('button', { name: optionsOf('bar') }));
    await waitFor(() => expect(panel()).not.toBeNull());
    const order = seriesOrder();
    await expect(order).toEqual(['amount', 'orders']);

    // The handle leads its row, and the drop is onto the row below it.
    const rows = [...panel()!.querySelectorAll('[data-slot="series-card"]')];
    await dragHandleOnto(
      rows[0]!.querySelector('button')!,
      rows[1]! as HTMLElement,
    );

    await waitFor(() => expect(seriesOrder()).toEqual(['orders', 'amount']));
    // Only the order moved: each series kept the axis it is measured on,
    // and the chart still draws both.
    await waitFor(() => expect(legendOrder()).toEqual([...drawn].reverse()));
    await expect(drawnMarks(canvasElement)).toHaveLength(8);
  },
};

/**
 * The visualization panel's two levels, walked (D20 屏 I／J).
 *
 * Every step here is a redraw of the rows already on screen: the panel is
 * opened from the result toolbar, the chosen type's options are walked page
 * by page, and each setting reaches the drawing — the labels over the marks,
 * the reference line across them, the axis title along the axis, and the
 * hole in the middle of a pie. jsdom can pin the spec each control writes
 * (`typescript/wow-view-engine/test/chartOptionsUi.test.tsx`); only a browser can
 * say the marks changed, which is what this walk is for.
 */
export const ChartOptionsPages: Story = {
  ...DisplayTwoMetrics,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const body = within(document.body);
    // Two metrics, so there are two series to stack and a legend to read.
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(8));

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
    // A bar chart writes its values unasked, as Metabase's does where they
    // fit: turned off they go, and back on they return.
    const labelsBox = () =>
      within(panel()!).getByRole('checkbox', {
        name: zhCN['label.chart.labels'],
      });
    await expect(labelsBox()).toHaveAttribute('aria-checked', 'true');
    await waitFor(() =>
      expect(valueLabels(canvasElement).length).toBeGreaterThan(0),
    );
    await userEvent.click(labelsBox());
    await waitFor(() => expect(valueLabels(canvasElement)).toHaveLength(0));
    await userEvent.click(labelsBox());
    // A label over every bar there is room for, and none over another.
    await waitFor(() =>
      expect(valueLabels(canvasElement).length).toBeGreaterThan(0),
    );
    // Measured once the redraw the labels came with has landed.
    await chartsDrawn(canvasElement);
    await expect(labelsOver(canvasElement)).toEqual([]);

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
        canvasElement.querySelectorAll(
          '[data-slot="chart-plot"] path[stroke-dasharray]',
        ),
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
      expect(axisTexts(canvasElement).map(text => text.textContent)).toContain(
        '金额',
      ),
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
    await chartsDrawn(canvasElement);
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
    // A hole in the middle: every sector now has an inner arc as well,
    // and the hole says the whole — the amount adds up.
    await chartsDrawn(canvasElement);
    await waitFor(() =>
      expect(arcsPerSlice().every(arcs => arcs === 2)).toBe(true),
    );
    await waitFor(() =>
      expect(axisTexts(canvasElement).map(text => text.textContent)).toContain(
        zhCN['label.chart.total'],
      ),
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

/**
 * 坐标轴页的「轴标题」框空着时写出图上画的那个标题：一个量的轴是它的列标题
 * （审查：空框看不出缺省）。
 */
export const AxisTitleShowsItsDefault: Story = {
  ...DisplayBarChart,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const body = within(document.body);
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(4));
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.analysis.visualize'] }),
    );
    await userEvent.click(body.getByRole('button', { name: optionsOf('bar') }));
    await waitFor(() => expect(panel()).not.toBeNull());
    await userEvent.click(
      within(panel()!).getByRole('tab', { name: zhCN['label.chart.tab.axes'] }),
    );
    const box = within(panel()!).getAllByLabelText(
      zhCN['label.chart.axis-title'],
    )[0] as HTMLInputElement;
    await expect(box.value).toBe('');
    // The same words the axis is drawn with.
    const drawn = axisTexts(canvasElement).map(text => text.textContent);
    await expect(drawn).toContain(box.placeholder);
    await expect(box.placeholder).toBe(AMOUNT_HEADER);
  },
};

/**
 * 选项页的标题聚焦时没有焦点环：键盘从「可视化」进第一层、从「…选项」按钮进
 * 第二层，都落在那一层的标题上（`tabIndex={-1}`，只接落点），标题不画一圈框
 * （审查）。
 */
export const OptionsHeadingRingless: Story = {
  ...DisplayBarChart,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(4));
    canvas
      .getByRole('button', { name: zhCN['label.analysis.visualize'] })
      .focus();
    await userEvent.keyboard('{Enter}');
    const heading = await waitFor(() => {
      const found = document.querySelector<HTMLElement>(
        '[data-slot="chart-picker"] h2',
      );
      expect(found).toHaveFocus();
      return found!;
    });
    await expect(getComputedStyle(heading).outlineStyle).toBe('none');
    document
      .querySelector<HTMLElement>('[data-slot="chart-options-open"]')!
      .focus();
    await userEvent.keyboard('{Enter}');
    const title = await waitFor(() => {
      const found = document.querySelector<HTMLElement>(
        '[data-slot="chart-options"] h2',
      );
      expect(found).toHaveFocus();
      return found!;
    });
    // The browser would ring it — it is focused from the keyboard — and it
    // wears none: a landing, not a control.
    await expect(title.matches(':focus-visible')).toBe(true);
    await expect(getComputedStyle(title).outlineStyle).toBe('none');
  },
};

/**
 * 手机宽度（414）上的可视化面板是从底边升起的抽屉，不再是压在结果上方的一块：
 * 抽屉贴着视口底边、不高过视口的八成；选一个图型，底下的结果就地重画；按 Escape
 * 收起，键盘回到「可视化」（审查）。
 */
export const VisualizeOnAPhone: Story = {
  ...DisplayBarChart,
  decorators: [
    Story => (
      <div style={{ width: 414 }}>
        <Story />
      </div>
    ),
  ],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(4));
    const visualizeButton = canvas.getByRole('button', {
      name: zhCN['label.analysis.visualize'],
    });
    await userEvent.click(visualizeButton);
    const drawer = await within(document.body).findByRole('dialog', {
      name: zhCN['label.chart.picker'],
    });
    await expect(drawer).toHaveAttribute('data-side', 'bottom');
    // Not a block in the page: no column in the workbench holds the panel.
    await expect(
      canvasElement.querySelector('aside[data-slot="view-panel"]'),
    ).toBeNull();
    await waitFor(() => {
      const box = drawer.getBoundingClientRect();
      expect(Math.abs(box.bottom - window.innerHeight)).toBeLessThan(2);
      expect(box.height).toBeLessThanOrEqual(window.innerHeight * 0.8 + 1);
    });
    // The keyboard is on the level's heading, as it is beside the view.
    await waitFor(() =>
      expect(
        drawer.querySelector('[data-slot="chart-picker"] h2'),
      ).toHaveFocus(),
    );
    // A pick redraws the result under it.
    await userEvent.click(
      drawer.querySelector<HTMLElement>(
        '[data-slot="chart-tile"][data-chart-type="line"]',
      )!,
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="chart"]'),
      ).toHaveAttribute('data-chart', 'line'),
    );
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(document.querySelector('[data-slot="view-panel"]')).toBeNull(),
    );
    await waitFor(() => expect(visualizeButton).toHaveFocus());
  },
};
