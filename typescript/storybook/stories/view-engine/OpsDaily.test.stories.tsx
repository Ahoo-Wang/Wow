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
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  DailyReport as DisplayOpsDaily,
} from './OpsDaily.stories.js';
import {
  chartsDrawn,
  expectTooltipInProportion,
  leavePlot,
} from './chartDom.js';
import { DAILY_GOLDEN } from './retail/goldens.js';
import { noPanelOut, valueOf } from './retail/twins.js';
import { matchScreenshot } from './screenshot.js';

/**
 * 运营日报 in the dashboard workbench, as a lightweight twin (docs/scenarios.md 6.1): it draws without a
 * panel refusing its view, and shows what 4.1 says it shows.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/业务场景/运营日报/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread (see the README).
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/**
 * 运营日报 in the workbench: the same golden cards as the home page, and —
 * unlike the home page — 「编辑」, since this is where the team builds it.
 */
export const OpsDailyInTheWorkbench: Story = {
  ...DisplayOpsDaily,
  name: '运营日报',
  // One of the key screens with a screenshot baseline (themes.md 5.5).
  tags: ['visual'],
  play: async ({ canvasElement }) => {
    await waitFor(() => expect(valueOf('GMV')).toBe(DAILY_GOLDEN.cards.GMV), {
      timeout: 10_000,
    });
    await noPanelOut(canvasElement);
    await expect(
      within(canvasElement).getByRole('button', {
        name: zhCN['label.dashboard.edit'],
      }),
    ).toBeInTheDocument();
    await matchScreenshot(canvasElement, 'ops-daily-workbench');
  },
};

/**
 * 悬停每张卡的走势、逐时 GMV 和渠道分布：提示里的色块还是 10px 的小方块，卡片
 * 不因提示出现滚动条，图本身照旧铺满它的绘图区。从前 `styles.css` 让绘图区里
 * 所有 `svg` 铺满，提示的色块也被撑成绘图区那么大，把指标卡撑出了滚动条
 * （2026-09-25 走查）。
 */
export const TooltipsKeepTheirSize: Story = {
  ...DisplayOpsDaily,
  name: '悬停提示不撑开卡片',
  play: async ({ canvasElement }) => {
    await waitFor(() => expect(valueOf('GMV')).toBe(DAILY_GOLDEN.cards.GMV), {
      timeout: 10_000,
    });
    await chartsDrawn(canvasElement);
    const plots = [
      ...canvasElement.querySelectorAll<HTMLElement>(
        '[data-slot="chart-plot"]',
      ),
    ];
    const inCards = plots.filter(plot =>
      plot.closest('[data-slot="metric-card"]'),
    );
    // The cards' trends, and the board's own charts beside them.
    await expect(inCards.length).toBeGreaterThanOrEqual(5);
    await expect(plots.length - inCards.length).toBeGreaterThanOrEqual(2);
    for (const plot of plots) {
      const body = plot.closest<HTMLElement>('[data-slot="card-content"]')!;
      const before = [body.scrollWidth, body.scrollHeight];
      await expectTooltipInProportion(plot);
      // The tooltip adds nothing to scroll: no bar appears under it.
      await expect([body.scrollWidth, body.scrollHeight]).toEqual(before);
      await expect(body.scrollHeight).toBeLessThanOrEqual(
        body.clientHeight + 1,
      );
      leavePlot(plot);
    }
  },
};
