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
import '@ahoo-wang/wow-view-engine/themes.css';
import displayMeta, {
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';

/**
 * The density axis, measured on the laid-out record workbench (themes.md
 * 2.4, D35 Q63): the header row, a body cell's padding all round and a
 * view in the list at each of the three steps, and the one thing density
 * never takes below the line — a control stays at least 24px tall (WCAG
 * 2.5.8). The lengths themselves are read off `styles.css` by
 * `test/styleBoundary.test.tsx`; this is what they come to on the screen.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/数据视图/主题/密度/回归',
  tags: ['!dev', '!autodocs', 'test'],
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

type Density = 'compact' | 'default' | 'comfortable';

/**
 * What each step comes to in px: the header row, a body cell's padding
 * above and below and beside its value, a view in the list. A body row's
 * own height is its padding plus its tallest value, and a value's height
 * is the platform's font metrics (a row measured 41px on macOS and 42px
 * on Linux at the same step), so the row is held by its padding — the
 * part the density sets — not by its total.
 */
const EXPECTED: Record<
  Density,
  { head: number; block: number; inline: number; view: number }
> = {
  compact: { head: 32, block: 4, inline: 6, view: 24 },
  default: { head: 40, block: 8, inline: 8, view: 28 },
  comfortable: { head: 44, block: 10, inline: 12, view: 32 },
};

const densityStory = (density: Density, preset?: string): Story => ({
  ...DisplayWithData,
  args: { ...DisplayWithData.args, density, preset },
  play: async ({ canvasElement }) => {
    await within(canvasElement).findByRole('table');
    const surface = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-surface"]',
    )!;
    await expect(surface).toHaveAttribute('data-fve-density', density);
    const expected = EXPECTED[density];
    await waitFor(() => {
      const head = surface.querySelector('[data-slot="table-head"]')!;
      expect(head.getBoundingClientRect().height).toBe(expected.head);
    });
    const cell = surface.querySelector(
      'tbody [data-slot="table-cell"]:not([data-column="filler"]):not(:has([role="checkbox"]))',
    )!;
    await expect(getComputedStyle(cell).paddingLeft).toBe(
      `${expected.inline}px`,
    );
    await expect(getComputedStyle(cell).paddingRight).toBe(
      `${expected.inline}px`,
    );
    await expect(getComputedStyle(cell).paddingTop).toBe(`${expected.block}px`);
    await expect(getComputedStyle(cell).paddingBottom).toBe(
      `${expected.block}px`,
    );
    const view = surface.querySelector('[aria-current="true"]')!;
    await expect(view.getBoundingClientRect().height).toBe(expected.view);
    // Density moves rows and padding, never a control under a target's
    // floor: every button on the surface stays at least 24px tall.
    const short = [...surface.querySelectorAll('button')]
      .filter(button => button.getBoundingClientRect().height > 0)
      .filter(button => button.getBoundingClientRect().height < 24)
      .map(button => button.textContent || button.getAttribute('aria-label'));
    await expect(short).toEqual([]);
  },
});

/** The compact step: 32px headers, 6px beside a value, 24px views. */
export const Compact: Story = densityStory('compact');

/** The default step: the registry's own lengths, the pixels of before. */
export const Default: Story = densityStory('default');

/** The comfortable step: 44px headers, 12px beside a value, 32px views. */
export const Comfortable: Story = densityStory('comfortable');

/**
 * A host's density beats the preset's recommendation: `graphite`
 * recommends compact, and the surface pinned comfortable is comfortable.
 */
export const ChoiceOverRecommendation: Story = densityStory(
  'comfortable',
  'graphite',
);

/**
 * With no density anywhere, a surface sits where its preset recommends:
 * `porcelain` recommends comfortable.
 */
export const PresetRecommends: Story = {
  ...DisplayWithData,
  args: { ...DisplayWithData.args, preset: 'porcelain' },
  play: async ({ canvasElement }) => {
    await within(canvasElement).findByRole('table');
    const surface = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-surface"]',
    )!;
    await expect(surface.hasAttribute('data-fve-density')).toBe(false);
    await waitFor(() => {
      const head = surface.querySelector('[data-slot="table-head"]')!;
      expect(head.getBoundingClientRect().height).toBe(
        EXPECTED.comfortable.head,
      );
    });
  },
};
