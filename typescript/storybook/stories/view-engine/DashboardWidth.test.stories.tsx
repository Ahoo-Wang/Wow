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
  title: 'View Engine/组件状态/仪表盘/宽度',
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

/**
 * The blocks the board draws, as the layer is masked with them: one row of
 * rectangles (the SVG in `--grid-blocks`), how tall a repeat of that row is,
 * and where the first repeat starts.
 */
function blocksOf(layer: HTMLElement) {
  const style = getComputedStyle(layer);
  const url = style.getPropertyValue('--grid-blocks').trim();
  const svg = decodeURIComponent(
    /^url\("data:image\/svg\+xml,(.*)"\)$/.exec(url)![1],
  );
  const rects = [...svg.matchAll(/<rect ([^>]*)\/>/g)].map(([, attributes]) => {
    const read = (name: string) =>
      Number(new RegExp(`${name}='([^']*)'`).exec(attributes)![1]);
    return {
      x: read('x'),
      y: read('y'),
      width: read('width'),
      height: read('height'),
    };
  });
  const [, pitch] = style
    .getPropertyValue('--grid-blocks-size')
    .trim()
    .split(' ')
    .map(Number.parseFloat);
  const top = Number.parseFloat(style.getPropertyValue('--grid-blocks-top'));
  return { rects, pitch, top };
}

/**
 * The blocks of one column of one row: as wide as the column, and as many
 * down the 80px row as come nearest a square (D34) — one, two or three.
 */
function blockSize(layer: HTMLElement) {
  const { rects } = blocksOf(layer);
  const column = rects.filter(rect => rect.x === rects[0].x);
  expect(rects.length).toBe(24 * column.length);
  const { width, height } = column[0];
  const ratio = (side: number) => Math.abs(Math.log(side / width));
  // No other count of blocks down an 80px row would be nearer a square.
  for (let count = 1; count <= 4; count++)
    expect(ratio(height)).toBeLessThanOrEqual(
      ratio((80 - (count - 1) * 10) / count) + 1e-9,
    );
  return { width, height, count: column.length };
}

/** How far `at` is from the nearest of `edges`, repeated every `pitch`. */
function offEdge(at: number, edges: number[], pitch: number) {
  return Math.min(
    ...edges.map(edge => {
      const rest = (((at - edge) % pitch) + pitch) % pitch;
      return Math.min(rest, pitch - rest);
    }),
  );
}

/** Every panel's four edges on the edges of the blocks the board draws. */
function panelsOnTheBlocks(grid: HTMLElement, layer: HTMLElement) {
  const box = layer.getBoundingClientRect();
  const { rects, pitch, top } = blocksOf(layer);
  const across = rects.flatMap(rect => [rect.x, rect.x + rect.width]);
  const down = rects
    .filter(rect => rect.x === rects[0].x)
    .flatMap(rect => [top + rect.y, top + rect.y + rect.height]);
  const items = [...grid.querySelectorAll('.react-grid-item')];
  expect(items.length).toBeGreaterThan(0);
  for (const item of items) {
    const panel = item.getBoundingClientRect();
    for (const x of [panel.left - box.left, panel.right - box.left])
      expect(Math.min(...across.map(edge => Math.abs(x - edge)))).toBeLessThan(
        1.5,
      );
    for (const y of [panel.top - box.top, panel.bottom - box.top])
      expect(offEdge(y, down, pitch)).toBeLessThan(1.5);
  }
}

/**
 * Building shows the cells as blocks (the user's 2026-09-24 walk-throughs:
 * 「仪表盘编辑模式下，显示网格线」, then 「参考 metabase，用正方形方块」; D34):
 * nothing while the board is read; once 编辑 is pressed a soft filled block
 * in each column of each row, the 80px row cut into the blocks nearest a
 * square — at the fixed 1200px two 40×35 — then at full width wider ones,
 * every panel's edge on a block's edge, and no panel a pixel taller or
 * shorter than it was read, at either width.
 */
export const GridBlocksWhileBuilding: Story = {
  ...DisplayFixedWidth,
  decorators: [WIDE],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const grid = await drawn(canvasElement);
    const layer = () =>
      grid.querySelector<HTMLElement>('[data-slot="dashboard-grid-blocks"]');
    await expect(layer()).toBeNull();
    const heights = () =>
      [...grid.querySelectorAll('.react-grid-item')].map(
        item => item.getBoundingClientRect().height,
      );
    // What the panels were before D34: a row 80px, a gap 10px, so a panel
    // h rows tall is 90h - 10 — the story's board holds h 4, 4 and 2.
    const read = heights();
    await expect(read).toEqual([350, 350, 170]);

    await userEvent.click(
      await canvas.findByRole('button', { name: zhCN['label.dashboard.edit'] }),
    );
    await waitFor(() => expect(layer()).not.toBeNull());
    const blocks = layer()!;
    await expect(blocks).toHaveAttribute('aria-hidden', 'true');
    const style = getComputedStyle(blocks);
    await expect(style.pointerEvents).toBe('none');
    await expect(style.maskImage).toContain('url(');
    await expect(style.backgroundColor).not.toBe('rgba(0, 0, 0, 0)');
    await expect(heights()).toEqual(read);
    // 1200px: a 40px column, two 35px blocks down each 80px row.
    await expect(blockSize(blocks)).toEqual({
      width: 40,
      height: 35,
      count: 2,
    });
    await waitFor(() => panelsOnTheBlocks(grid, blocks));

    await userEvent.click(
      within(
        await canvas.findByRole('group', {
          name: zhCN['label.dashboard.width'],
        }),
      ).getByRole('button', { name: zhCN['label.dashboard.width-full'] }),
    );
    await waitFor(() => expect(blockSize(layer()!).width).toBeGreaterThan(40));
    await waitFor(() => panelsOnTheBlocks(grid, layer()!));
    await expect(heights()).toEqual(read);
  },
};

/** A phone's one column has no cell to land on, so building draws none. */
export const NoGridBlocksInOneColumn: Story = {
  ...DisplayFixedWidth,
  decorators: [PHONE],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const grid = await drawn(canvasElement);
    await userEvent.click(
      await canvas.findByRole('button', { name: zhCN['label.dashboard.edit'] }),
    );
    await canvas.findByRole('region', {
      name: zhCN['label.dashboard.editing'],
    });
    await expect(grid).toHaveAttribute('data-narrow');
    await expect(
      grid.querySelector('[data-slot="dashboard-grid-blocks"]'),
    ).toBeNull();
  },
};
