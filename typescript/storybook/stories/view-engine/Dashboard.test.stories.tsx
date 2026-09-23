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
import displayMeta, {
  AllPanels as DisplayAllPanels,
  EditableLayout as DisplayEditableLayout,
  EmptyDashboard as DisplayEmptyDashboard,
  GlobalFilter as DisplayGlobalFilter,
  PanelUnavailable as DisplayPanelUnavailable,
  QueryFailed as DisplayQueryFailed,
} from './Dashboard.stories.js';
import { amountOf, findDataTable, readColumn, readTotal } from './readTable.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/仪表盘视图/Dashboard/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes a file's own
  // description into a `parameters` of its meta, which would replace the
  // display meta's — and with it the full-screen host application the
  // workbench is meant to be exercised in.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

const bars = (canvas: HTMLElement) =>
  canvas.querySelectorAll('.recharts-bar-rectangle');

export const AllPanels: Story = {
  ...DisplayAllPanels,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await findDataTable(canvasElement);
    // The record panel runs its own view: pending orders, largest first.
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual([
        'SO-1003',
        'SO-1005',
        'SO-1001',
        'SO-1006',
      ]),
    );
    await expect(amountOf(readTotal(table, '金额'))).toBe(6470);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
    await expect(
      canvas.getByRole('link', { name: '出库异常处理' }),
    ).toBeVisible();
  },
};

export const GlobalFilter: Story = {
  ...DisplayGlobalFilter,
  play: async ({ canvasElement }) => {
    const table = await findDataTable(canvasElement);
    // 华南 reaches both panels through their own warehouse field.
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(['SO-1005']),
    );
    await expect(amountOf(readTotal(table, '金额'))).toBe(1760);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(1));
  },
};

export const PanelUnavailable: Story = {
  ...DisplayPanelUnavailable,
  play: async ({ canvasElement }) => {
    await expect(
      await within(canvasElement).findByText(zhCN['label.panel.unavailable']),
    ).toBeVisible();
    // The other data panel is not taken down with it.
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
  },
};

export const QueryFailed: Story = {
  ...DisplayQueryFailed,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await waitFor(() =>
      expect(canvas.getAllByText(zhCN['label.query.failed'])).toHaveLength(2),
    );
    await expect(
      canvas.getByRole('link', { name: '出库异常处理' }),
    ).toBeVisible();
  },
};

/**
 * The keyboard path, in a real browser.
 *
 * jsdom already holds the contract — `test/dashboardUi.test.tsx` presses the
 * arrows on both handles and reads `controller().panels[0].layout` back. The
 * two things it cannot hold are the two this story is for. jsdom lays nothing
 * out, so every box is 0×0 at the origin and a panel that moved is
 * indistinguishable from one that did not; and it applies no stylesheet, so
 * the corner upstream paints only while a pointer is over the panel would
 * look reachable whether or not focus shows it.
 *
 * The panel is measured against the grid rather than in pixels: applying a
 * placement re-runs the panels, and the container is re-measured as their
 * contents settle, so two pixel widths taken either side of a keypress are
 * not comparable. Where the panel starts within the grid is.
 */
export const KeyboardLayout: Story = {
  ...DisplayEditableLayout,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const grip = canvas.getByLabelText(
      zhCN['label.panel.move'].replace('{title}', '待出库明细'),
    );
    const panel = grip.closest('.react-grid-item') as HTMLElement;
    const grid = canvasElement.querySelector(
      '[data-slot="dashboard-grid"]',
    ) as HTMLElement;
    /** How far into the grid the panel starts, as a fraction of its width. */
    const from = () =>
      (panel.getBoundingClientRect().left - grid.getBoundingClientRect().left) /
      grid.getBoundingClientRect().width;

    // The first column, give or take the grid's own padding.
    await expect(from()).toBeLessThan(0.03);
    grip.focus();
    await userEvent.keyboard('{ArrowRight}');
    // The second, once the grid has finished sliding it there.
    await waitFor(() => expect(from()).toBeGreaterThan(0.06));

    const corner = within(panel).getByLabelText(zhCN['label.panel.resize']);
    corner.focus();
    // Upstream keeps the corner at `opacity: 0` until a pointer is over the
    // panel; a keyboard that can reach it must be able to see it.
    await expect(getComputedStyle(corner).opacity).toBe('1');
  },
};

export const EmptyDashboard: Story = {
  ...DisplayEmptyDashboard,
  play: async ({ canvasElement }) => {
    await expect(
      await within(canvasElement).findByText(zhCN['label.dashboard.empty']),
    ).toBeVisible();
  },
};
