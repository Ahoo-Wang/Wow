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
  Fixture as DisplayFixture,
} from './CompensationOverview.stories.js';
import { findDataTable, readColumn } from './readTable.js';
import { drawnMarks, valueLabels } from './chartDom.js';

/**
 * The compensation overview over the fixture, on its fixed morning.
 *
 * What can break here without a line of the page changing: the dashboard is
 * a system view of a dashboard definition whose panels reference saved views
 * of another definition and one of that definition's own system views, and
 * a rule change in View Engine can refuse any of those references, or the
 * relative dates they count by. So every panel is asserted to have drawn
 * its answer — the numbers the fixture's executions give on that morning —
 * and the page to fit its area sideways.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/真实后端/补偿控制台/运营概览/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes this file's own
  // description into a `parameters` of its meta, which would replace the
  // display meta's — and with it the full-screen host application the page
  // is meant to be exercised in.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

const PANELS = [
  '活动失败',
  '其中不可恢复',
  '今日新增',
  '本月每日新增失败',
  '按状态分布',
  '最近的活动失败',
  '活动失败最多的处理器',
];

/**
 * Each header a held header lies over, and by how many pixels, as
 * `"held over covered: px"`.
 *
 * Read off the header row because every layer of a column — header, rows,
 * summaries — is held together, so a header that is whole is a column that
 * is whole. The filler is skipped: it is not a column, and it has no width
 * once the table overflows. Half a pixel is sub-pixel rounding between two
 * neighbours, not a column under another.
 */
function covered(table: HTMLElement): string[] {
  const heads = [
    ...table.querySelectorAll<HTMLElement>('thead tr:first-child > th'),
  ].filter(cell => cell.getBoundingClientRect().width > 0);
  const found: string[] = [];
  for (const held of heads.filter(cell => cell.hasAttribute('data-pin'))) {
    const over = held.getBoundingClientRect();
    for (const other of heads) {
      if (other === held) continue;
      const under = other.getBoundingClientRect();
      const overlap =
        Math.min(over.right, under.right) - Math.max(over.left, under.left);
      if (overlap > 0.5)
        found.push(
          `${held.textContent} over ${other.textContent}: ${overlap.toFixed(1)}`,
        );
    }
  }
  return found;
}

export const Fixture: Story = {
  ...DisplayFixture,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    // The host's own heading, dated by the runtime's clock and zone.
    await expect(
      canvas.getByRole('heading', { level: 1, name: '运营概览' }),
    ).toBeVisible();
    await expect(canvas.getByText('2026年9月22日星期二')).toBeVisible();

    // Every panel resolved its reference and drew; none says it could not.
    const panel = (name: string) => canvas.getByRole('group', { name });
    await waitFor(() => {
      for (const name of PANELS) expect(panel(name)).toBeInTheDocument();
    });
    await expect(
      canvasElement.querySelector(
        '[data-slot="panel-failed"], [data-slot="panel-unavailable"]',
      ),
    ).toBeNull();

    // The three counts.
    const card = (name: string) =>
      panel(name).querySelector('[data-slot="metric-card"]')?.textContent;
    await waitFor(() => expect(card('活动失败')).toBe('145'));
    await expect(card('其中不可恢复')).toBe('37');
    await expect(card('今日新增')).toBe('4');
    // Each is a tile one grid row tall, and its number fits it: nothing to
    // scroll, where at two rows a tile was mostly empty and at one the
    // workbench's 3xl number overflowed.
    for (const name of ['活动失败', '其中不可恢复', '今日新增']) {
      const tile = panel(name).closest<HTMLElement>(
        '[data-slot="dashboard-panel"]',
      )!;
      await expect(tile.getBoundingClientRect().height).toBeLessThanOrEqual(80);
      await expect(panel(name).scrollHeight).toBeLessThanOrEqual(
        panel(name).clientHeight,
      );
    }

    // One bar per day of September so far, one per status, and the
    // processors ahead first.
    const bars = (name: string) => drawnMarks(panel(name)).length;
    await waitFor(() => expect(bars('本月每日新增失败')).toBe(22));
    await waitFor(() => expect(bars('按状态分布')).toBe(3));
    await waitFor(() => expect(bars('活动失败最多的处理器')).toBe(6));
    // Each processor's count is written past its bar's end, and the longest
    // bar's stays whole inside the drawing: 「59.6万」 on the live service
    // lost its 「万」 to the frame (audit P0-5).
    const processors = panel('活动失败最多的处理器');
    const frame = processors
      .querySelector('[data-slot="chart-plot"] svg')!
      .getBoundingClientRect();
    const counts = await waitFor(() => {
      const found = valueLabels(processors);
      expect(found.length).toBeGreaterThan(0);
      return found.map(label => label.getBoundingClientRect());
    });
    for (const box of counts) {
      await expect(box.left).toBeGreaterThanOrEqual(frame.left - 1);
      await expect(box.right).toBeLessThanOrEqual(frame.right + 1);
    }

    // The newest active failures, newest first.
    const table = await findDataTable(panel('最近的活动失败'));
    await waitFor(() => expect(readColumn(table, '处理器')).toHaveLength(10));
    await expect(readColumn(table, '最近更新')[0]).toBe(
      '2026年9月22日 06:00:00',
    );

    // No column sits under a held one where the panel is read, at rest.
    // Five columns overflow this seven-twelfths panel, and a last column
    // held on the right sat over the middle before anything had scrolled —
    // 「已重试次数」 read as 「已重试次」 — while the pin cap (D17-4) kept
    // it, one column being well under half the port. A panel holds no end
    // (`holdEnd`), so every header is read whole. The overflow is asserted
    // first because it is what gives the measurement its meaning: a table
    // that fits covers nothing whatever it pins.
    const port = table.closest<HTMLElement>('[data-slot="record-table"]')!;
    await waitFor(() =>
      expect(port.hasAttribute('data-overflowing')).toBe(true),
    );
    await expect(covered(table)).toEqual([]);

    // The page fits its area sideways: only a genuinely taller page scrolls,
    // and only down. Waited for, because the grid learns its width from its
    // container once it is on screen and lays out at a default until then.
    const area = canvasElement.querySelector<HTMLElement>('.story-app-page')!;
    await waitFor(() =>
      expect(area.scrollWidth).toBeLessThanOrEqual(area.clientWidth),
    );
    await expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(
      document.documentElement.clientWidth,
    );
  },
};

/** The panel titles on the board, top to bottom and left to right. */
function titles(canvasElement: HTMLElement): string[] {
  return [...canvasElement.querySelectorAll<HTMLElement>('.react-grid-item')]
    .map(item => ({
      box: item.getBoundingClientRect(),
      title: item.querySelector('[data-slot="panel-title"]')?.textContent ?? '',
    }))
    .sort((a, b) => a.box.top - b.box.top || a.box.left - b.box.left)
    .map(item => item.title);
}

/**
 * The overview is a report (D36): the interactive tier, read and never
 * built — no 「编辑」, no save and no save-as anywhere on it — while
 * 「铺满屏幕」 fills the screen with the board in place, and Escape puts it
 * back.
 */
export const ReadOnlyReport: Story = {
  ...DisplayFixture,
  name: '只读报告',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await waitFor(() =>
      expect(titles(canvasElement)).toEqual(expect.arrayContaining(PANELS)),
    );
    for (const name of [
      zhCN['label.dashboard.edit'],
      zhCN['label.save.save'],
      zhCN['label.save.save-as'],
    ])
      await expect(canvas.queryByRole('button', { name })).toBeNull();
    await expect(
      canvasElement.querySelector('[data-slot="dashboard-edit"]'),
    ).toBeNull();

    const expand = canvas.getByRole('button', {
      name: zhCN['label.workbench.expand-view'],
    });
    const surface = expand.closest<HTMLElement>('.fve-root')!;
    await userEvent.click(expand);
    await expect(surface).toHaveAttribute('data-view-expanded', 'true');
    // It covers the viewport, the host's bar and navigation under it.
    await waitFor(() => {
      const box = surface.getBoundingClientRect();
      expect(Math.abs(box.width - window.innerWidth)).toBeLessThan(1);
      expect(Math.abs(box.height - window.innerHeight)).toBeLessThan(1);
    });
    await userEvent.keyboard('{Escape}');
    await expect(surface).not.toHaveAttribute('data-view-expanded');
    await expect(
      canvas.getByRole('button', {
        name: zhCN['label.workbench.expand-view'],
      }),
    ).toHaveFocus();
  },
};
