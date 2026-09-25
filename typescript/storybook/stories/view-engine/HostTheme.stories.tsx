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
import { EmbeddedDashboard, EmbeddedView } from '@ahoo-wang/wow-view-engine/ui';
import {
  HOST_LANGUAGE,
  createStoryEngine,
  savedDashboard,
  savedViews,
} from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import {
  ContrastMatrix,
  MEASURED_MODES,
  passes,
  readMatrix,
} from './themeContrast.js';
import { PaletteGates, clears, readPalettes } from './paletteGates.js';
import '@ahoo-wang/wow-view-engine/styles.css';
// The host's own stylesheet, after the package's — nothing else is needed.
import './host-theme/acme.css';

/** The name the host gave its preset in `host-theme/acme.css`. */
const HOST_PRESET = 'acme';

/**
 * A view and a board as a host embeds them, pinned to its own preset in
 * both modes, and the package's two gates run over that preset beside them.
 */
function HostThemePage() {
  return (
    <StoryEngine
      create={() =>
        createStoryEngine({ instances: [...savedViews, savedDashboard] })
      }
    >
      {engine => (
        <div
          className="fve-tokens bg-background text-foreground"
          style={{
            display: 'flex',
            flexDirection: 'column',
            gap: 24,
            padding: 16,
          }}
        >
          {MEASURED_MODES.map(mode => (
            <section
              key={mode}
              data-host-band={mode}
              aria-label={`${HOST_PRESET} · ${mode === 'light' ? '亮' : '暗'}`}
              style={{ display: 'grid', gap: 12 }}
            >
              <h2 style={{ margin: 0, fontSize: 16, fontWeight: 600 }}>
                {HOST_PRESET} · {mode === 'light' ? '亮' : '暗'}
              </h2>
              <EmbeddedDashboard
                engine={engine}
                instanceId={savedDashboard.id}
                theme={mode}
                preset={HOST_PRESET}
                headingLevel={3}
                {...HOST_LANGUAGE}
              />
              <EmbeddedView
                engine={engine}
                instanceId={savedViews[0].id}
                theme={mode}
                preset={HOST_PRESET}
                headingLevel={3}
                withTitle
                {...HOST_LANGUAGE}
              />
            </section>
          ))}
          <h2 style={{ margin: 0, fontSize: 16, fontWeight: 600 }}>
            对比度与图表八色的门
          </h2>
          <ContrastMatrix presets={[HOST_PRESET]} />
          <PaletteGates presets={[HOST_PRESET]} />
        </div>
      )}
    </StoryEngine>
  );
}

const description = `**主题 · 宿主自定义主题**

一个写在包外面的主题：宿主自己的样式表 \`host-theme/acme.css\`，放在 \`styles.css\` 之后引入，只用 README 记下的那份合同——\`--fve-*\`／\`--fve-dark-*\` 变量，写在 \`:where([data-fve-preset='acme'])\` 里。内置预设用的也是同一份合同，没有私有选择器、没有组件内部、没有为哪一套预设开的代码路径（themes.md 1.1）。

- **页面上**：一块仪表盘与一张记录视图，钉在 \`preset="acme"\` 上，亮暗各一份。
- **量它**：同一页下面是包的两道门——对比度矩阵（字 ≥4.5:1，控件边与焦点 ≥3:1）与图表八色的三道门（相邻色间距、对卡片 3:1、柱内墨色 4.5:1）。故事的交互测试断言两者全部达标。
- **宿主怎样自查自己的主题**：打开「主题/预设 → 对比度矩阵」，把自己的 \`--fve-*\` 声明粘进输入框，它们作为一套预设当场与内置预设一起量，两道门都在那一页。`;

const meta = {
  title: 'View Engine/能力/主题与预设/宿主自定义主题',
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
} satisfies Meta;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 宿主自己写的一套预设，过包的两道门。
 *
 * 这一页画出它、量它；有一对不达标、一道色板的门不过，这个故事就红。
 */
export const HostAuthored: Story = {
  name: '宿主自己的预设',
  render: () => <HostThemePage />,
  parameters: {
    // One board and one view, twice (light and dark): their landmarks repeat
    // by design, as on the theme gallery.
    a11y: { config: { rules: [{ id: 'landmark-unique', enabled: false }] } },
  },
  play: async ({ canvasElement }) => {
    // The host's preset is worn: a surface pinned to it resolves its brand.
    const surface = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        `[data-host-band="light"] .fve-root[data-fve-preset="${HOST_PRESET}"]`,
      );
      if (!found) throw new Error('宿主的面还没出来');
      return found;
    });
    await expect(
      getComputedStyle(surface).getPropertyValue('--primary').trim(),
    ).toBe('#0f766e');
    const matrix = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-matrix]');
      if (!found || found.dataset.matrix === 'measuring')
        throw new Error('矩阵还没量完');
      return found;
    });
    const short = readMatrix(matrix)
      .filter(m => !passes(m))
      .map(m => `${m.mode} ${m.pair} ${m.ratio.toFixed(2)}:1 < ${m.line}:1`);
    await expect(short, short.join('\n')).toEqual([]);
    const palettes = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-palettes]');
      if (!found || found.dataset.palettes === 'measuring')
        throw new Error('色板还没量完');
      return found;
    });
    const readings = readPalettes(palettes);
    await expect(readings.length).toBe(MEASURED_MODES.length);
    for (const reading of readings) {
      await expect(clears(reading), JSON.stringify(reading)).toBe(true);
      // This palette asks no exception: every light slot stands off the card.
      await expect(reading.underCard).toEqual([]);
    }
  },
};
