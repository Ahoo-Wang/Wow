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
import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, waitFor } from 'storybook/test';
import { Field, FieldDescription, FieldLabel } from '@/ui/components/field';
import { Textarea } from '@/ui/components/textarea';
import { PRESETS } from './presets.js';
import {
  ContrastMatrix,
  LINE_RATIO,
  MEASURED_MODES,
  TOKEN_PAIRS,
  passes,
  readMatrix,
} from './themeContrast.js';
import { PaletteGates, clears, readPalettes } from './paletteGates.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/** The name the pasted variables are measured under, as one more preset. */
const CUSTOM_PRESET = 'custom';

/**
 * The matrix, with a field a host pastes its own `--fve-*` declarations into
 * (both halves, the dark one as `--fve-dark-*`): they become one more preset,
 * measured on the spot beside the built-in ones — the self-check D30 Q50
 * leaves to this page rather than to a script.
 */
function MatrixPage() {
  const [draft, setDraft] = useState('');
  const [applied, setApplied] = useState('');
  const custom = applied.trim();
  return (
    <div
      className="fve-tokens bg-background text-foreground"
      style={{ display: 'flex', flexDirection: 'column', gap: 16, padding: 16 }}
    >
      <Field>
        <FieldLabel htmlFor="custom-variables">量一量自己的变量</FieldLabel>
        <Textarea
          id="custom-variables"
          value={draft}
          placeholder={
            '--fve-primary: oklch(0.55 0.21 265deg);\n--fve-dark-primary: oklch(0.75 0.15 265deg);'
          }
          onChange={event => setDraft(event.target.value)}
          onBlur={() => setApplied(draft)}
        />
        <FieldDescription>
          离开输入框后，这些声明作为预设「{CUSTOM_PRESET}」与内置预设一起量。
        </FieldDescription>
      </Field>
      <ContrastMatrix
        presets={custom ? [...PRESETS, CUSTOM_PRESET] : PRESETS}
        css={
          custom
            ? `:where([data-fve-preset='${CUSTOM_PRESET}']) { ${custom} }`
            : ''
        }
      />
      <PaletteGates
        presets={custom ? [...PRESETS, CUSTOM_PRESET] : PRESETS}
        css={
          custom
            ? `:where([data-fve-preset='${CUSTOM_PRESET}']) { ${custom} }`
            : ''
        }
      />
    </div>
  );
}

const description = `**能力 · 主题与预设**

本包的预设（\`themes.css\`）在每种明暗下守不守得住对比度承诺，以及每套画出来的样子。

- **主题一览**（下一组）：每套预设一个故事，亮、暗各一条带，每条带里三种视图——记录视图（表格、钉住的单号列、状态徽章、一行选中、一个获焦的复选框）、分析视图（两组柱与图例）、仪表盘（筛选条上一个有值的筛选、带走势与涨跌的指标卡、瀑布图）。这些块也是截图基线（\`typescript/storybook/README.md\`「截图基线」）。
- **逐套预设**（再下一组）：每套一个故事，是整张运营日报嵌在宿主外壳里。
- **对比度矩阵**：每套预设 × 每种明暗 × 每一对 token，在真浏览器里量级联后的颜色：字 ≥4.5:1，控件边与焦点 ≥3:1。「跟随系统」解析成亮或暗之一，所以量这两种。在输入框里粘贴自己的 \`--fve-*\`，它们作为一套预设当场一起量。矩阵下面的「图表八色」再量色板的三道门（themes.md 5.2）：相邻色在正常视觉与三种色觉模拟下的间距、暗色每色对卡片 ≥3:1（亮色列出例外）、每色都有一种墨色 ≥4.5:1；粘贴的 \`--fve-chart-*\` 同样一起量。
- **强制颜色与打印**（「强制颜色与打印/回归」）：Windows 对比度主题下焦点、复选框与选中行仍看得见；打印时读预设的亮色一半、没有阴影、图表开花纹。
- **工具栏**：「Preset」切换 \`<html>\` 上的 \`data-fve-preset\`，明暗开关多了「system」，「Density」与「Change colors」切换密度与涨跌约定。一览与矩阵的面都钉住了预设与明暗，不受工具栏影响；其余故事都跟着工具栏走。
- **预设从哪来**：读自包导出的 \`BUILT_IN_PRESETS\`（包自己的测试守着它与 \`themes.css\` 一致），包里多一套，工具栏、一览与矩阵就都多一套。`;

const meta = {
  title: 'View Engine/能力/主题与预设',
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
} satisfies Meta;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 每套预设 × 每种明暗 × 每一对 token 的对比度，量出来。
 *
 * 字 ≥4.5:1（WCAG 1.4.3），控件边与焦点 ≥3:1（1.4.11）；不达标的一格标「不足」，
 * 这个故事就红。预设读自 `BUILT_IN_PRESETS`：包里多一套，这里就多量一套。
 */
export const Contrast: Story = {
  name: '对比度矩阵',
  render: () => <MatrixPage />,
  play: async ({ canvasElement }) => {
    const matrix = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-matrix]');
      if (!found || found.dataset.matrix === 'measuring')
        throw new Error('矩阵还没量完');
      return found;
    });
    const measured = readMatrix(matrix);
    // Every preset, both modes, every pair — and nothing measured twice.
    await expect(measured.length).toBe(
      PRESETS.length * MEASURED_MODES.length * TOKEN_PAIRS.length,
    );
    const short = measured
      .filter(m => !passes(m))
      .map(
        m =>
          `${m.preset}/${m.mode} ${m.pair} ${m.ratio.toFixed(2)}:1 < ${LINE_RATIO[m.line]}:1 ${JSON.stringify(m.colors)}`,
      );
    await expect(short, short.join('\n')).toEqual([]);
    // And every palette clears its gates (themes.md 5.2), in the browser.
    const palettes = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-palettes]');
      if (!found || found.dataset.palettes === 'measuring')
        throw new Error('色板还没量完');
      return found;
    });
    const readings = readPalettes(palettes);
    await expect(readings.length).toBe(PRESETS.length * MEASURED_MODES.length);
    const failing = readings
      .filter(reading => !clears(reading))
      .map(reading => JSON.stringify(reading));
    await expect(failing, failing.join('\n')).toEqual([]);
  },
};
