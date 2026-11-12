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

import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, waitFor } from 'storybook/test';
import { AnalysisChart, ViewSurface } from '@ahoo-wang/wow-view-engine/ui';
import { AggregationDateUnit } from '@ahoo-wang/wow-client/dsl';
import { HOST_LANGUAGE } from './fixtures.js';
import '@ahoo-wang/wow-view-engine/styles.css';

const DAY = (n: number) => Date.UTC(2026, 8, n);

function Tile({ before, after }: { before: number; after: number }) {
  return (
    <AnalysisChart
      data={{
        type: 'metric',
        value: after,
        trend: [
          { x: DAY(21), value: before },
          { x: DAY(22), value: after },
        ],
        period: {
          at: DAY(22),
          unit: AggregationDateUnit.DAY,
          previous: { at: DAY(21), value: before },
          change: { delta: after - before, ratio: (after - before) / before },
        },
      }}
      spec={{
        type: 'metric',
        metric: { metric: 'orders', trend: { x: 'day' } },
      }}
    />
  );
}

/**
 * 指标卡上「变化」与「较前一日」这两段的排法（D94，2026-11 审计 F6）：一行还是两行
 * 由卡片的宽度定（窄于 16rem 两行），不由徽标的长短定。原先任它折行：变化短的
 * 那张卡上「较前一日」在徽标旁边，变化长的那张卡上掉到徽标下面，一排卡片的趋势线
 * 于是起在不同的高度。
 *
 * 一排两张卡，一张变化短（+2），一张变化长（+1,345,678 · +134.6%），同宽。
 */
function Row({ width }: { width: string }) {
  return (
    <ViewSurface {...HOST_LANGUAGE} timeZone="UTC">
      <div style={{ display: 'flex', gap: 16 }}>
        <div data-card="short" style={{ width }}>
          <Tile before={10} after={12} />
        </div>
        <div data-card="long" style={{ width }}>
          <Tile before={1_000_000} after={2_345_678} />
        </div>
      </div>
    </ViewSurface>
  );
}

const meta = {
  title: 'View Engine/能力/指标卡/回归',
  component: Row,
  tags: ['!dev', '!autodocs', 'test'],
} satisfies Meta<typeof Row>;

export default meta;

type Story = StoryObj<typeof meta>;

const rowOf = (canvasElement: HTMLElement, card: string) => {
  const change = canvasElement.querySelector<HTMLElement>(
    `[data-card="${card}"] [data-slot="metric-change"]`,
  )!;
  const badge = change.querySelector<HTMLElement>('[data-slot="badge"]')!;
  const words = change.lastElementChild as HTMLElement;
  return {
    change: change.getBoundingClientRect(),
    badge: badge.getBoundingClientRect(),
    words: words.getBoundingClientRect(),
  };
};

const ready = (canvasElement: HTMLElement) =>
  waitFor(() =>
    expect(
      canvasElement.querySelectorAll('[data-slot="metric-change"]'),
    ).toHaveLength(2),
  );

export const AWideCardSaysBothOnOneRow: Story = {
  args: { width: '20rem' },
  play: async ({ canvasElement }) => {
    await ready(canvasElement);
    const short = rowOf(canvasElement, 'short');
    const long = rowOf(canvasElement, 'long');
    for (const row of [short, long]) {
      // Beside the badge: the words start past its end, inside its height.
      await expect(row.words.left).toBeGreaterThanOrEqual(row.badge.right);
      await expect(row.words.top).toBeLessThan(row.badge.bottom);
    }
    // So what follows the row starts at one height on both cards.
    await expect(long.change.height).toBe(short.change.height);
    await expect(long.change.bottom).toBe(short.change.bottom);
  },
};

export const ANarrowCardSaysThemOnTwo: Story = {
  args: { width: '14rem' },
  play: async ({ canvasElement }) => {
    await ready(canvasElement);
    for (const card of ['short', 'long']) {
      const row = rowOf(canvasElement, card);
      // Under the badge, from the same edge: on the short change too, which
      // had room beside it.
      await expect(row.words.top).toBeGreaterThanOrEqual(row.badge.bottom);
      await expect(row.words.left).toBe(row.badge.left);
    }
  },
};
