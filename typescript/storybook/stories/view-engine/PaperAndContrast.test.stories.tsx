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
import galleryMeta, { Neutral } from './ThemeGallery.stories.js';
import { chartsDrawn } from './chartDom.js';
import { underMedia } from './media.js';

/**
 * The two media a surface is laid out for besides a screen (themes.md 4.6,
 * 5.4; T5), on the theme gallery's `neutral` page — the same three views,
 * one band pinned light and one pinned dark:
 *
 * - **Forced colours** (a Windows contrast theme): the browser repaints
 *   every colour with the reader's system colours and drops every shadow,
 *   which is how the vendored controls draw focus. The focused control
 *   still has an outline, the checkboxes still their edge, the selected row
 *   still its frame.
 * - **Paper**: the dark band prints in the preset's light half, without a
 *   shadow, its charts redrawn light and patterned, their colour kept.
 *
 * Both are Playwright's media emulation (`.storybook/media.ts`), which
 * re-evaluates the stylesheet's queries and a chart's `matchMedia` alike.
 * Chromium emulates both; a browser that cannot pretend one does not
 * assert on a layout it never got, and says so.
 */
const meta = {
  ...galleryMeta,
  title: 'View Engine/能力/主题与预设/强制颜色与打印/回归',
  tags: ['!dev', '!autodocs', 'test'],
  parameters: { ...galleryMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof galleryMeta>;

/** Chromium, which Playwright can lay out for either medium. */
const EMULATES = /Chrome\//.test(navigator.userAgent);

/** The band pinned to `mode`, with every view on it answered. */
async function band(canvas: HTMLElement, mode: 'light' | 'dark') {
  const found = await waitFor(() => {
    const element = canvas.querySelector<HTMLElement>(
      `[data-gallery-band][data-mode="${mode}"]`,
    );
    expect(
      element?.querySelectorAll('[data-gallery-block="record"] tbody tr')
        .length,
    ).toBe(5);
    return element!;
  });
  await chartsDrawn(found);
  return found;
}

const blockSurface = (on: Element, block: string) =>
  on.querySelector<HTMLElement>(`[data-gallery-block="${block}"] > .fve-root`)!;

const axisFills = (on: Element) =>
  [...on.querySelectorAll('[data-gallery-block="analysis"] svg text')]
    .map(text => text.getAttribute('fill'))
    .filter(Boolean);

export const ForcedColors: Story = {
  ...Neutral,
  name: '强制颜色',
  play: async ({ canvasElement }) => {
    const light = await band(canvasElement, 'light');
    const record = light.querySelector('[data-gallery-block="record"]')!;
    const [first, second] = record.querySelectorAll('tbody tr');
    const box = within(first as HTMLElement).getByRole('checkbox');
    box.focus();
    await userEvent.keyboard(' ');
    await waitFor(() =>
      expect(first).toHaveAttribute('data-state', 'selected'),
    );
    const ran = await underMedia(
      { forcedColors: 'active' },
      '(forced-colors: active)',
      async () => {
        // The focused checkbox: an outline, which forced colours keep, where
        // the ring it wears on screen is a shadow they drop.
        await waitFor(() => {
          const focus = getComputedStyle(box);
          expect(focus.outlineStyle).toBe('solid');
          expect(parseFloat(focus.outlineWidth)).toBeGreaterThanOrEqual(2);
        });
        // A checkbox still has its edge, checked or not.
        const unchecked = within(second as HTMLElement).getByRole('checkbox');
        for (const edge of [box, unchecked]) {
          const style = getComputedStyle(edge);
          await expect(style.borderTopStyle).toBe('solid');
          await expect(parseFloat(style.borderTopWidth)).toBeGreaterThan(0);
        }
        // The selected row is framed in the system's selection colour; the
        // one beside it is not.
        await expect(getComputedStyle(first).outlineStyle).toBe('solid');
        await expect(getComputedStyle(second).outlineStyle).toBe('none');
        // A button on the board, reached by the keyboard, as well.
        const button = within(
          light.querySelector('[data-gallery-block="dashboard"]')!,
        ).getAllByRole('button')[0];
        button.focus();
        await userEvent.keyboard('{Shift}');
        await expect(getComputedStyle(button).outlineStyle).toBe('solid');
      },
    );
    // Chromium pretends both; another browser asserts what it could lay out.
    if (EMULATES) await expect(ran).toBe(true);
  },
};

export const Print: Story = {
  ...Neutral,
  name: '打印',
  play: async ({ canvasElement }) => {
    const light = await band(canvasElement, 'light');
    const dark = await band(canvasElement, 'dark');
    const darkRecord = blockSurface(dark, 'record');
    const lightRecord = blockSurface(light, 'record');
    // On screen the two bands are two colours, and so are their charts.
    await expect(getComputedStyle(darkRecord).backgroundColor).not.toBe(
      getComputedStyle(lightRecord).backgroundColor,
    );
    const onScreen = axisFills(dark);
    await expect(onScreen).not.toEqual(axisFills(light));
    const ran = await underMedia({ media: 'print' }, 'print', async () => {
      // The dark band prints in the light half of its preset.
      await expect(getComputedStyle(darkRecord).colorScheme).toBe('light');
      await expect(getComputedStyle(darkRecord).backgroundColor).toBe(
        getComputedStyle(lightRecord).backgroundColor,
      );
      // No shadow lifts anything on paper.
      for (const token of ['--shadow-sm', '--shadow-md', '--shadow-lg'])
        await expect(
          getComputedStyle(darkRecord).getPropertyValue(token),
        ).toMatch(/\/ 0%\)|, 0\)/);
      // Its charts redraw light, patterned, and keep their colour on paper.
      await waitFor(() => expect(axisFills(dark)).toEqual(axisFills(light)));
      const analysis = blockSurface(dark, 'analysis');
      await expect(
        getComputedStyle(analysis).getPropertyValue('--fve-chart-patterns'),
      ).toBe('on');
      await waitFor(() =>
        expect(analysis.querySelector('svg pattern')).not.toBeNull(),
      );
      const chart = analysis.querySelector('[data-slot="chart"]')!;
      await expect(getComputedStyle(chart).printColorAdjust).toBe('exact');
    });
    // Chromium pretends both; another browser asserts what it could lay out.
    if (EMULATES) await expect(ran).toBe(true);
    // Back on screen, the dark band is dark again, charts included.
    await waitFor(() => expect(axisFills(dark)).toEqual(onScreen));
    await expect(getComputedStyle(darkRecord).colorScheme).toBe('dark');
  },
};
