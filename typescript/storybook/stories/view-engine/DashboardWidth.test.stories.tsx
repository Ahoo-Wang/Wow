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
import type { ComponentType } from 'react';
import type { StoryObj } from '@storybook/react-vite';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  AllPanels as DisplayAllPanels,
  FixedWidth as DisplayFixedWidth,
} from './Dashboard.stories.js';

/**
 * A board's width in a browser (D31): what jsdom cannot lay out — the fixed
 * board held to 1200px and centred on a wide screen, a full one across it,
 * one column on a phone like any other, and the switch on the edit bar
 * changing what is on screen.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/仪表盘视图/Dashboard/宽度',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread (see Dashboard.test.stories.tsx).
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** A column as wide as a wide monitor's page. */
const WIDE = (Story: ComponentType) => (
  <div style={{ width: 1920 }}>
    <Story />
  </div>
);

/** A phone's column. */
const PHONE = (Story: ComponentType) => (
  <div style={{ width: 375 }}>
    <Story />
  </div>
);

const gridOf = (canvasElement: HTMLElement) =>
  canvasElement.querySelector<HTMLElement>('[data-slot="dashboard-grid"]')!;

/** Until the panels are on screen, on the grid rather than one column. */
async function drawn(canvasElement: HTMLElement): Promise<HTMLElement> {
  return waitFor(() => {
    const grid = gridOf(canvasElement);
    expect(grid).not.toBeNull();
    expect(grid.querySelectorAll('.react-grid-item').length).toBeGreaterThan(0);
    return grid;
  });
}

/** The grid's box, and how far it sits in from each side of its parent. */
function placement(grid: HTMLElement) {
  const box = grid.getBoundingClientRect();
  const parent = grid.parentElement!.getBoundingClientRect();
  return {
    width: box.width,
    left: box.left - parent.left,
    right: parent.right - box.right,
  };
}

/** Every panel inside the grid's own box. */
function panelsInside(grid: HTMLElement) {
  const box = grid.getBoundingClientRect();
  for (const item of grid.querySelectorAll('.react-grid-item')) {
    const panel = item.getBoundingClientRect();
    expect(panel.left).toBeGreaterThanOrEqual(box.left - 1);
    expect(panel.right).toBeLessThanOrEqual(box.right + 1);
  }
}

/**
 * On a wide monitor a fixed board is 1200px and centred — its filter bar
 * with its panels — where a full one would stretch across.
 */
export const FixedOnAWideScreen: Story = {
  ...DisplayFixedWidth,
  decorators: [WIDE],
  play: async ({ canvasElement }) => {
    const grid = await drawn(canvasElement);
    await expect(grid).toHaveAttribute('data-width', 'fixed');
    await waitFor(() => {
      const { width, left, right } = placement(grid);
      expect(width).toBe(1200);
      expect(Math.abs(left - right)).toBeLessThanOrEqual(1);
      expect(left).toBeGreaterThan(100);
    });
    const bar = within(canvasElement).getByRole('region', {
      name: zhCN['label.filters.bar'],
    });
    await expect(grid.contains(bar)).toBe(true);
    await waitFor(() => panelsInside(grid));
  },
};

/** A board saved before it could say keeps the full width it was built at. */
export const FullOnAWideScreen: Story = {
  ...DisplayAllPanels,
  decorators: [WIDE],
  play: async ({ canvasElement }) => {
    const grid = await drawn(canvasElement);
    await expect(grid).toHaveAttribute('data-width', 'full');
    const { width, left, right } = placement(grid);
    await expect(width).toBeGreaterThan(1200);
    await expect(left).toBeLessThanOrEqual(1);
    await expect(right).toBeLessThanOrEqual(1);
  },
};

/**
 * On a phone a fixed board is one column like any other: the width only
 * holds a board back, it never makes it wider than its screen.
 */
export const FixedOnAPhone: Story = {
  ...DisplayFixedWidth,
  decorators: [PHONE],
  play: async ({ canvasElement }) => {
    const grid = await drawn(canvasElement);
    await expect(grid).toHaveAttribute('data-narrow');
    await expect(grid.getBoundingClientRect().width).toBeLessThanOrEqual(375);
    await waitFor(() => panelsInside(grid));
    await expect(grid.scrollWidth).toBeLessThanOrEqual(grid.clientWidth + 1);
  },
};

/**
 * 固定宽度／全宽 on the edit bar: each press lays the board out anew at once,
 * and 撤销 takes it back — one step of building like any other.
 */
export const SwitchWhileBuilding: Story = {
  ...DisplayFixedWidth,
  decorators: [WIDE],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const grid = await drawn(canvasElement);
    await userEvent.click(
      await canvas.findByRole('button', { name: zhCN['label.dashboard.edit'] }),
    );
    const switcher = await canvas.findByRole('group', {
      name: zhCN['label.dashboard.width'],
    });
    const full = within(switcher).getByRole('button', {
      name: zhCN['label.dashboard.width-full'],
    });
    await expect(
      within(switcher).getByRole('button', {
        name: zhCN['label.dashboard.width-fixed'],
      }),
    ).toHaveAttribute('aria-pressed', 'true');

    await userEvent.click(full);
    await expect(grid).toHaveAttribute('data-width', 'full');
    await waitFor(() => expect(placement(grid).width).toBeGreaterThan(1200));
    await waitFor(() => panelsInside(grid));

    await userEvent.click(
      canvas.getByRole('button', {
        name: zhCN['label.history.undo-step'].replace(
          '{what}',
          zhCN['label.history.change-width'],
        ),
      }),
    );
    await expect(grid).toHaveAttribute('data-width', 'fixed');
    await waitFor(() => expect(placement(grid).width).toBe(1200));
  },
};
