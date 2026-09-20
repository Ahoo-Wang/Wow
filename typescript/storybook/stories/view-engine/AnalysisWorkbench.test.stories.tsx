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
import { defaultMessages } from '@ahoo-wang/fetcher-view-engine/ui';
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
  title: 'View Engine/分析视图/Analysis 工作台/回归',
  tags: ['!dev', '!autodocs', 'test'],
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

const bars = (canvas: HTMLElement) =>
  canvas.querySelectorAll('.recharts-bar-rectangle');

/** Each slice's category and fill, in the order the pie draws them. */
const slices = (canvas: HTMLElement) =>
  [...canvas.querySelectorAll('.recharts-pie-sector path')].map(path => ({
    name: path.getAttribute('name'),
    fill: path.getAttribute('fill'),
  }));

/** One bar per warehouse: the source grouped the rows it was asked to. */
export const BarChart: Story = {
  ...DisplayBarChart,
  play: async ({ canvasElement }) => {
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
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
    await expect(readColumn(table, 'orders')).toEqual(['2', '1', '2', '1']);
    await expect(readColumn(table, '金额').map(amountOf)).toEqual([
      1920, 2450, 4880, 980,
    ]);
    // The totals row comes from its own ungrouped query over the same rows.
    await expect(readTotal(table, 'orders')).toBe('6');
    await expect(amountOf(readTotal(table, '金额'))).toBe(10230);
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
        'Other',
      ]),
    );
  },
};

export const PinnedCategoryColor: Story = {
  ...DisplayPinnedCategoryColor,
  play: async ({ canvasElement }) => {
    await waitFor(() => expect(slices(canvasElement)).toHaveLength(3));
    const [south, ...others] = slices(canvasElement);
    await expect(south).toEqual({ name: '华南', fill: '#7c3aed' });
    for (const slice of others) await expect(slice.fill).not.toBe('#7c3aed');
  },
};

/** The sentence the strip says when a result fills its limit exactly. */
const CUT_SHORT = defaultMessages['analysis.result.at-limit'].replace(
  '{limit}',
  '2',
);

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

    const strip = await canvas.findByRole('status');
    await expect(strip).toHaveTextContent(CUT_SHORT);
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
    await expect(amountOf(readTotal(table, '金额'))).toBe(10230);

    await expect(await canvas.findByRole('status')).toHaveTextContent(
      CUT_SHORT,
    );
  },
};

export const EmptyResult: Story = {
  ...DisplayEmptyResult,
  play: async ({ canvasElement }) => {
    await expect(
      await within(canvasElement).findByText(
        defaultMessages['label.analysis.empty'],
      ),
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
      name: defaultMessages['label.filter.panel'],
    });
    await expect(
      within(panel).queryByRole('group', {
        name: defaultMessages['label.filter.all-conditions'],
      }),
    ).toBeNull();

    await userEvent.click(
      within(panel).getByRole('button', {
        name: defaultMessages['label.filter.advanced'],
      }),
    );

    // Advanced draws the root as a group, which is what carries the operator.
    await waitFor(() =>
      expect(
        within(
          canvas.getByRole('region', {
            name: defaultMessages['label.filter.panel'],
          }),
        ).getByRole('group', {
          name: defaultMessages['label.filter.all-conditions'],
        }),
      ).toBeVisible(),
    );
  },
};
