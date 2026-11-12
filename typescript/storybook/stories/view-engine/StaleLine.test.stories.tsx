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
import { expect } from 'storybook/test';
import { ViewSurface } from '@ahoo-wang/wow-view-engine/ui';
import { HOST_LANGUAGE } from './fixtures.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * 重新取数时的那条线（D94）：结果的行答的是旧问题、或者是另一页时（`data-stale`），
 * 行留着变淡，底边有一条走动的线——变淡说「这些行是旧的」，线说「正在取新的」。
 *
 * 线是带 `data-stale` 的那个元素自己的 `::after`，所以每一处标了旧行的面都有，
 * 这里量的就是这条规则本身：2px 高、贴着底边、在主色里、一直在动。读者要求减少
 * 动效时它照样动——和转圈、骨架屏一样是「还在取」的指示，停了就像卡死；同一页上
 * 别的过渡这时确实被收掉了。
 */
function Stale({ stale }: { stale: boolean }) {
  return (
    <ViewSurface {...HOST_LANGUAGE}>
      <div
        data-testid="rows"
        data-stale={stale || undefined}
        className="fve:transition-opacity fve:data-[stale]:opacity-60"
        style={{ height: 120 }}
      />
    </ViewSurface>
  );
}

const meta = {
  title: 'View Engine/能力/重新取数/回归',
  component: Stale,
  tags: ['!dev', '!autodocs', 'test'],
} satisfies Meta<typeof Stale>;

export default meta;

type Story = StoryObj<typeof meta>;

const lineOf = (canvasElement: HTMLElement) => {
  const rows = canvasElement.querySelector<HTMLElement>(
    '[data-testid="rows"]',
  )!;
  return { rows, line: getComputedStyle(rows, '::after') };
};

export const StaleRowsHaveAMovingLine: Story = {
  args: { stale: true },
  play: async ({ canvasElement }) => {
    const { rows, line } = lineOf(canvasElement);
    await expect(getComputedStyle(rows).position).toBe('relative');
    await expect(line.content).not.toBe('none');
    await expect(line.position).toBe('absolute');
    await expect(line.height).toBe('2px');
    await expect(line.bottom).toBe('0px');
    await expect(line.width).toBe(getComputedStyle(rows).width);
    await expect(line.animationName).toBe('fve-stale-line');
    await expect(line.animationIterationCount).toBe('infinite');
    await expect(line.animationDuration).toBe('1.4s');
    // Nothing a pointer can land on: the rows under it stay clickable.
    await expect(line.pointerEvents).toBe('none');
  },
};

export const FreshRowsHaveNone: Story = {
  args: { stale: false },
  play: async ({ canvasElement }) => {
    const { line } = lineOf(canvasElement);
    await expect(line.content).toBe('none');
    await expect(line.animationName).toBe('none');
  },
};

export const TheLineKeepsMovingUnderReducedMotion: Story = {
  args: { stale: true },
  play: async ({ canvasElement }) => {
    const media = globalThis.storybookMedia;
    // Storybook's own panel cannot tell the browser what its reader asked.
    if (!media) return;
    await media.emulate({ reducedMotion: 'reduce' });
    try {
      await expect(matchMedia('(prefers-reduced-motion: reduce)').matches).toBe(
        true,
      );
      const { rows, line } = lineOf(canvasElement);
      await expect(line.animationDuration).toBe('1.4s');
      // What is not a loading indicator is cut, on the same element.
      await expect(
        parseFloat(getComputedStyle(rows).transitionDuration),
      ).toBeLessThan(0.001);
    } finally {
      await media.emulate({ reducedMotion: 'no-preference' });
    }
  },
};
