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
  PieChart as DisplayPieChart,
  PinnedCategoryColor as DisplayPinnedCategoryColor,
  QueryFailed as DisplayQueryFailed,
  TableWithTotals as DisplayTableWithTotals,
  TwoMetrics as DisplayTwoMetrics,
} from './AnalysisWorkbench.stories.js';
import { amountOf, readColumn, readTotal } from './readTable.js';

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
