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
import { converter, parse } from 'culori';
import displayMeta, {
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';
import { measureFocusMark } from './contrast.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/数据视图/主题/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes a file's own
  // description into a `parameters` of its meta, which would replace the
  // display meta's — and with it the full-screen host application the
  // workbench is meant to be exercised in.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** What WCAG 1.4.11 asks of a focus indicator against what it lies between. */
const NON_TEXT_CONTRAST = 3;

/**
 * Presses Tab until `reached` holds for the focused element, so
 * `:focus-visible` holds on it — a script's `focus()` need not count, a
 * keyboard always does.
 */
async function tabUntil(
  reached: (focused: Element) => boolean,
): Promise<HTMLElement> {
  for (let presses = 0; presses < 120; presses += 1) {
    const focused = document.activeElement;
    if (focused && reached(focused)) return focused as HTMLElement;
    await userEvent.tab();
  }
  throw new Error('Tab never reached the target.');
}

/**
 * Resolves once a transitioning value has stopped changing: two reads 50ms
 * apart that agree.
 */
async function settled(read: () => string): Promise<void> {
  await waitFor(async () => {
    const before = read();
    await new Promise(resolve => setTimeout(resolve, 50));
    if (read() !== before) throw new Error('The value is still moving.');
  });
}

/**
 * 行与卡片的焦点标记到 3:1（阶段 5，5A）。
 *
 * 记录行与卡片是键盘能停下的地方（行是一个 Tab 停点，卡片按行列走），它们的焦点
 * 从前只画一圈 50% 强度的 `--ring` 光晕：中性灰的一半叠在白底上，推算远不到
 * WCAG 1.4.11 要的 3:1，却没有 story 量过。这里用 Tab 走到第一行、再切到卡片走到
 * 第一张，量焦点标记压在它自己的底色与外面的底色上的层叠色——与控件那条「全强度
 * 一像素边加光晕」同一个量法（`FocusIndicators*`）。
 */
const focusMarks = (theme: 'light' | 'dark'): Story => ({
  ...DisplayWithData,
  args: { ...DisplayWithData.args, theme },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await expect(
      canvasElement.querySelector('[data-slot="view-surface"]'),
    ).toHaveAttribute('data-theme', theme);
    await waitFor(() =>
      expect(table.querySelectorAll('tbody tr').length).toBeGreaterThan(1),
    );

    const row = await tabUntil(
      focused => focused.matches('tbody tr') && table.contains(focused),
    );
    const cells = [...row.querySelectorAll<HTMLElement>(':scope > td')];
    const measured: { name: string; ratio: number; colors: object }[] = [];
    for (const [name, cell] of [
      ['row start', cells[0]],
      ['row middle', cells[Math.floor(cells.length / 2)]],
      ['row end', cells[cells.length - 1]],
    ] as const) {
      await settled(() => getComputedStyle(cell).boxShadow);
      measured.push({ name, ...measureFocusMark(cell) });
    }

    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.layout.cards'] }),
    );
    const region = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="record-cards"]',
      );
      if (!found) throw new Error('卡片区还没出来');
      return found;
    });
    const card = await tabUntil(
      focused =>
        focused.getAttribute('data-slot') === 'card' &&
        region.contains(focused),
    );
    await settled(() => getComputedStyle(card).outlineColor);
    measured.push({ name: 'card', ...measureFocusMark(card) });

    const report = measured
      .map(
        ({ name, ratio, colors }) =>
          `${name} ${ratio.toFixed(2)}:1 ${JSON.stringify(colors)}`,
      )
      .join('; ');
    await expect(
      Math.min(...measured.map(({ ratio }) => ratio)),
      `${theme} — ${report}`,
    ).toBeGreaterThanOrEqual(NON_TEXT_CONTRAST);
  },
});

export const FocusMarksInLightTheme: Story = focusMarks('light');
export const FocusMarksInDarkTheme: Story = focusMarks('dark');

const toRgb = converter('rgb');

/**
 * 汇总行的弱字跟着宿主的前景色走（阶段 5，5A）。
 *
 * `--quiet-foreground` 从前写死成前景色的一份拷贝（`oklch(0.145 … / 70%)`）：
 * 宿主设了 `--fve-foreground`，界面上每一个字都换了颜色，只有汇总行的「全部」和
 * 函数名留在原地。它现在由 `--foreground` 推导，这里在 `<html>` 上设一个前景色，
 * 量汇总行的弱字：是那个颜色的七成。
 */
export const QuietInkFollowsForeground: Story = {
  ...DisplayWithData,
  args: { ...DisplayWithData.args, theme: 'light' },
  play: async ({ canvasElement }) => {
    const table = await within(canvasElement).findByRole('table');
    const scope = await waitFor(() => {
      const found = table.querySelector<HTMLElement>(
        'tfoot [data-slot="summary-scope"]',
      );
      if (!found) throw new Error('汇总行还没出来');
      return found;
    });
    const html = document.documentElement;
    try {
      html.style.setProperty('--fve-foreground', 'rgb(120, 0, 0)');
      await waitFor(() => {
        const ink = toRgb(parse(getComputedStyle(scope).color)!);
        expect(Math.abs(Math.round(ink.r * 255))).toBe(120);
        expect(Math.abs(Math.round(ink.g * 255))).toBe(0);
        expect(Math.abs(Math.round(ink.b * 255))).toBe(0);
        expect(ink.alpha).toBeCloseTo(0.7, 2);
      });
    } finally {
      html.style.removeProperty('--fve-foreground');
    }
  },
};
