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
import { expect, waitFor, within } from 'storybook/test';
import displayMeta, { Fixture as DisplayFixture } from './Home.stories.js';
import { findDataTable, readColumn } from './readTable.js';

/**
 * The home page over the fixture, on its fixed morning.
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
  title: 'View Engine/首页/回归',
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
    const bars = (name: string) =>
      panel(name).querySelectorAll('.recharts-bar-rectangle').length;
    await waitFor(() => expect(bars('本月每日新增失败')).toBe(22));
    await waitFor(() => expect(bars('按状态分布')).toBe(3));
    await waitFor(() => expect(bars('活动失败最多的处理器')).toBe(6));

    // The newest active failures, newest first.
    const table = await findDataTable(panel('最近的活动失败'));
    await waitFor(() => expect(readColumn(table, '处理器')).toHaveLength(10));
    await expect(readColumn(table, '最近更新')[0]).toBe(
      '2026年9月22日 06:00:00',
    );

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
