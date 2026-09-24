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
 * 内置的三套预设，一套一个故事（阶段 5，5C；D30 Q42）。
 *
 * 每个故事都是同一张「单元格读法」的 Record 工作台——带语气的徽章、外链、
 * 复制按钮、侧栏、工具栏都在一屏——用 `preset` 钉上一套预设；明暗跟工具栏走。
 * 值写在 `@ahoo-wang/wow-view-engine/themes.css`，每一对字与底、控件边与焦点
 * 在每套 × 每种明暗下的对比度由 `test/presetContrast.test.ts` 量。
 *
 * - **neutral**：默认，也就是不挂预设时的样子。
 * - **blue**：neutral 的灰配蓝色主色（shadcn 的 `blue` 主题）；暗色主色取
 *   blue-400，因为链接与星标用主色写字。
 * - **slate**：冷灰配蓝（补偿控制台的样子），灰阶逐档换成 Tailwind 的 slate。
 *
 * 三套共用状态色与图表八色（Q47），焦点与控件边都是调到 ≥3:1 的那档灰。
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
  blue: {
    light: 'oklch(0.488 0.243 264.376)',
    dark: 'oklch(0.707 0.165 254.624)',
  },
  slate: {
    light: 'oklch(0.546 0.245 262.881)',
    dark: 'oklch(0.707 0.165 254.624)',
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

/** Neutral greys with a blue brand colour. */
export const Blue: Story = presetStory('blue');

/** Cool slate greys with the blue brand colour of the compensation console. */
export const Slate: Story = presetStory('slate');
