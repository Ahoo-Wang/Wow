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
  CellFamily as DisplayCellFamily,
} from './RecordWorkbench.stories.js';

/**
 * 内置预设，一套一个故事（阶段 5；D30、D35 的目录）。
 *
 * 每个故事都是同一张「单元格读法」的 Record 工作台——带语气的徽章、外链、
 * 复制按钮、侧栏、工具栏都在一屏——用 `preset` 钉上一套预设；明暗跟工具栏走。
 * 值写在 `@ahoo-wang/wow-view-engine/themes/<名>.css`，每一对字与底、控件边
 * 与焦点在每套 × 每种明暗下的对比度由 `test/presetContrast.test.ts` 量，
 * 自带的图表八色由 `test/paletteDistance.test.ts` 量。
 *
 * - **neutral**：默认，也就是不挂预设时的样子。
 * - **slate**：冷灰配蓝（补偿控制台的样子），灰阶逐档换成 Tailwind 的 slate。
 * - **azure**：中国企业后台风格：明快的蓝、6px 圆角、灰底白卡、中文优先的
 *   系统字体栈，自带一套八色。
 * - **porcelain**：桌面原生风格：系统字体、12px 圆角、柔和阴影、近中性的灰，
 *   焦点跟主色，自带一套八色。
 * - **graphite**：方角、强灰阶、无阴影的运维风格，焦点跟主色，八色是默认八色
 *   为灰底重调过的一版。
 *
 * - **fjord**：北欧冷色、低饱和，八色取那一族的霜与极光、提彩度重量过。
 * - **contrast**：高对比：字 ≥7:1、控件边与焦点 ≥4.5:1，默认开图表花纹。
 * - **brand**：neutral 的一切，主色与淡色从宿主给的一个 `--fve-brand` 派生；
 *   Storybook 在 `.storybook/preview.css` 里像宿主一样设了一个紫色。
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/数据视图/主题/预设',
  // Spelled out, not left to the spread: Storybook writes a file's own
  // description into a `parameters` of its meta, which would replace the
  // display meta's — and with it the full-screen host application.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/**
 * The brand colour each preset gives the surface, light and dark, as the
 * browser resolves it — `oklch()` through `color(srgb …)` or `rgb()`, so
 * the story compares what it reads with a probe the same browser resolved.
 */
const PRIMARY = {
  neutral: { light: 'oklch(0.205 0 0)', dark: 'oklch(0.922 0 0)' },
  slate: {
    light: 'oklch(0.546 0.245 262.881)',
    dark: 'oklch(0.707 0.165 254.624)',
  },
  azure: {
    light: 'oklch(0.541 0.1928 258.885)',
    dark: 'oklch(0.6726 0.176 255.302)',
  },
  porcelain: {
    light: 'oklch(0.522 0.1771 255.83)',
    dark: 'oklch(0.7333 0.1438 249.651)',
  },
  graphite: {
    light: 'oklch(0.509 0.2355 262.193)',
    dark: 'oklch(0.7365 0.136 261.082)',
  },
  fjord: {
    light: 'oklch(0.5181 0.0729 255.86)',
    dark: 'oklch(0.7746 0.0622 217.469)',
  },
  contrast: {
    light: 'oklch(0.42 0.23 264)',
    dark: 'oklch(0.74 0.15 253)',
  },
  // Derived from the page's `--fve-brand` (`.storybook/preview.css`); the
  // probe resolves the same relative colour the preset writes.
  brand: {
    light: 'oklch(from #7c3aed clamp(0.4, l, 0.5) c h)',
    dark: 'oklch(from #7c3aed clamp(0.68, l, 0.8) min(c, 0.18) h)',
  },
} as const;

/** A colour as this browser computes it, for comparing with a token. */
function computed(color: string): string {
  const probe = document.createElement('span');
  probe.style.color = color;
  document.body.append(probe);
  const value = getComputedStyle(probe).color;
  probe.remove();
  return value;
}

const presetStory = (preset: keyof typeof PRIMARY): Story => ({
  ...DisplayCellFamily,
  args: { ...DisplayCellFamily.args, preset },
  play: async ({ canvasElement }) => {
    await within(canvasElement).findByRole('table');
    const surface = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-surface"]',
    )!;
    await expect(surface).toHaveAttribute('data-fve-preset', preset);
    const mode =
      getComputedStyle(surface).colorScheme === 'dark' ? 'dark' : 'light';
    // A link in a cell is written in `primary`, so it wears the preset.
    const link = await waitFor(() => {
      const found = surface.querySelector('[data-slot="cell-link"]');
      if (!found) throw new Error('外链还没出来');
      return found;
    });
    await waitFor(() =>
      expect(getComputedStyle(link).color).toBe(
        computed(PRIMARY[preset][mode]),
      ),
    );
  },
});

/** The stylesheet's own look: every variable left to `styles.css`. */
export const Neutral: Story = presetStory('neutral');

/** Cool slate greys with the blue brand colour of the compensation console. */
export const Slate: Story = presetStory('slate');

/** A Chinese enterprise admin look: a clear blue, white cards on grey. */
export const Azure: Story = presetStory('azure');

/** A native desktop look: system type, larger corners, soft shadows. */
export const Porcelain: Story = presetStory('porcelain');

/** Square corners, a strong grey scale, no shadows: an operations console. */
export const Graphite: Story = presetStory('graphite');

/** Cool, low-chroma Nordic colours for tools read all day. */
export const Fjord: Story = presetStory('fjord');

/** High contrast: text at 7:1, edges at 4.5:1, chart patterns on. */
export const Contrast: Story = presetStory('contrast');

/** Neutral, with the primary and tints derived from one `--fve-brand`. */
export const Brand: Story = presetStory('brand');
