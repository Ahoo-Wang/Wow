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
import type { Decorator, StoryObj } from '@storybook/react-vite';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
// Both read as written: the bridge from the package's source, the host theme
// from the compensation console's own stylesheet — the host the bridge is
// accepted against (phase 5 plan, 5D).
import bridge from '../../../wow-view-engine/src/shadcn-bridge.css?raw';
import consoleTheme from '../../../../compensation/dashboard/src/index.css?raw';
import displayMeta, {
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';
import { colorsSettled, measureBorderContrast } from './contrast.js';

/**
 * The compensation console's shadcn tokens: its `:root` block and its `.dark`
 * block, taken out of the stylesheet whole — the rest of it (Tailwind, its
 * base layer) is the console's page, not its theme.
 */
function blockOf(selector: string): string {
  const at = consoleTheme.search(new RegExp(`^${selector} \\{`, 'm'));
  if (at < 0) throw new Error(`The console's stylesheet has no ${selector}`);
  return consoleTheme.slice(at, consoleTheme.indexOf('\n}', at) + 2);
}
const HOST_THEME = `${blockOf(':root')}\n${blockOf('\\.dark')}`;

/**
 * The host's theme and the bridge on the page for as long as the story is:
 * a host with a shadcn theme that imports the bridge.
 */
const withHostTheme: Decorator = Story => (
  <>
    <style data-host-theme>{HOST_THEME}</style>
    <style data-shadcn-bridge>{bridge}</style>
    <Story />
  </>
);

const meta = {
  ...displayMeta,
  title: 'View Engine/主题/shadcn 桥接',
  tags: ['!dev', '!autodocs', 'test'],
  parameters: {
    ...displayMeta.parameters,
    // The words are in the host's colours now, and the console's own
    // `--muted-foreground` (0.554) on its `--background` (0.985) measures
    // 4.46:1 — a shortfall of the console's theme, which the bridge carries
    // across faithfully, as it carries every text token. Text on a bridged
    // theme is the host's to keep (Q46); what the bridge promises, and what
    // this story measures, is that the control edges and the focus mark it
    // leaves out stay ≥3:1. Every other axe rule stays on.
    a11y: { config: { rules: [{ id: 'color-contrast', enabled: false }] } },
  },
  decorators: [...displayMeta.decorators, withHostTheme],
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** What WCAG 1.4.11 asks of a control's edge and of a focus mark. */
const NON_TEXT_CONTRAST = 3;

/** The tokens the bridge carries across, as the host resolves them. */
const BRIDGED = ['--background', '--foreground', '--primary', '--card'];

/** Resolves once a transitioning value has stopped changing. */
async function settled(read: () => string): Promise<void> {
  await waitFor(async () => {
    const before = read();
    await new Promise(resolve => setTimeout(resolve, 50));
    if (read() !== before) throw new Error('The value is still moving.');
  });
}

/** Tab until the element has focus, so `:focus-visible` holds. */
async function tabTo(target: HTMLElement): Promise<void> {
  const toolbar = target.closest('[role="toolbar"]');
  for (let presses = 0; presses < 80; presses += 1) {
    if (document.activeElement === target) return;
    if (toolbar?.contains(document.activeElement)) break;
    await userEvent.tab();
  }
  for (let presses = 0; toolbar && presses < 20; presses += 1) {
    if (document.activeElement === target) return;
    await userEvent.keyboard('{ArrowRight}');
  }
  if (document.activeElement !== target)
    throw new Error('Tab never reached the target.');
}

/**
 * 补偿控制台的 shadcn 主题挂上桥接，视图穿上它（阶段 5，5D，D30 Q46）。
 *
 * 页面上放补偿控制台自己的 `:root` 与 `.dark` 两块 token，再引 `shadcn-bridge.css`：
 * 面上的底色、前景、主色、卡片是宿主的；控件边与焦点不是——宿主写的是
 * `--input: var(--border)`、`--ring: var(--primary)`，桥接不接这两个，所以全选
 * 复选框、每页条数的选择框、获焦的按钮仍量出 ≥3:1。明暗跟宿主：`.dark` 挂在
 * `<html>` 上时，桥接读到的就是宿主的暗色值。
 */
const wearsTheHostTheme = (theme: 'light' | 'dark'): Story => ({
  ...DisplayWithData,
  globals: { theme },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const surface = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-surface"]',
    )!;
    await expect(getComputedStyle(surface).colorScheme).toBe(theme);
    const html = getComputedStyle(document.documentElement);
    const own = getComputedStyle(surface);
    for (const token of BRIDGED)
      await expect(own.getPropertyValue(token).trim(), token).toBe(
        html.getPropertyValue(token).trim(),
      );
    // Not bridged: the host's own `input` and `ring` are not the view's.
    for (const token of ['--input', '--ring'])
      await expect(own.getPropertyValue(token).trim(), token).not.toBe(
        html.getPropertyValue(token).trim(),
      );
    // The bridge or a preset, never both (theme T1, themes.md 2.8): a host
    // that names a preset on `<html>` gets the preset, whichever file came
    // last — here `neutral`, the built-in values, not the host's.
    const root = document.documentElement;
    try {
      root.setAttribute('data-fve-preset', 'neutral');
      await waitFor(() =>
        expect(
          getComputedStyle(surface).getPropertyValue('--primary').trim(),
        ).not.toBe(html.getPropertyValue('--primary').trim()),
      );
    } finally {
      root.removeAttribute('data-fve-preset');
    }

    const measured: { name: string; ratio: number; colors: object }[] = [];
    const checkbox = canvas.getByRole('checkbox', {
      name: zhCN['label.record.select-all'],
    });
    await expect(checkbox).not.toBeChecked();
    // Read once the host's theme and the preset taken off again have landed.
    await colorsSettled();
    measured.push({ name: 'checkbox', ...measureBorderContrast(checkbox) });
    measured.push({
      name: 'select',
      ...measureBorderContrast(
        canvas.getByRole('combobox', {
          name: zhCN['label.pagination.page-size'],
        }),
      ),
    });
    const button = canvas.getByRole('button', {
      name: zhCN['label.toolbar.columns'],
    });
    await tabTo(button);
    await settled(() => getComputedStyle(button).borderTopColor);
    await colorsSettled();
    measured.push({ name: 'focus', ...measureBorderContrast(button) });

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

export const WearsTheHostThemeInLight: Story = wearsTheHostTheme('light');
export const WearsTheHostThemeInDark: Story = wearsTheHostTheme('dark');
