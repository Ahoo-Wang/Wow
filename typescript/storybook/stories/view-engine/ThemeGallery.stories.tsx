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
import { useEffect, useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import type { ViewEngine } from '@ahoo-wang/wow-view-engine';
import {
  EmbeddedDashboard,
  EmbeddedView,
  zhCN,
} from '@ahoo-wang/wow-view-engine/ui';
import { Field, FieldDescription, FieldLabel } from '@/ui/components/field';
import { Textarea } from '@/ui/components/textarea';
import { HOST_LANGUAGE } from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import {
  GALLERY_BOARD,
  GALLERY_CARDS,
  GALLERY_FILTERS,
  createGalleryEngine,
} from './retail/gallery.js';
import { RETAIL_DATA_NOTE } from './retail/scene.js';
import { MODES, PRESETS, type Mode } from './presets.js';
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

const MODE_NAMES: Record<Mode, string> = {
  light: '亮',
  dark: '暗',
  system: '跟随系统',
};

/** The reader's `prefers-color-scheme`, followed while the page is open. */
function useSystemMode(): 'light' | 'dark' {
  const [dark, setDark] = useState(
    () => matchMedia('(prefers-color-scheme: dark)').matches,
  );
  useEffect(() => {
    const query = matchMedia('(prefers-color-scheme: dark)');
    const follow = () => setDark(query.matches);
    query.addEventListener('change', follow);
    return () => query.removeEventListener('change', follow);
  }, []);
  return dark ? 'dark' : 'light';
}

/**
 * One preset in one mode: the board — its filter bar with a chip holding a
 * value, the record table and the analysis chart as panels — beside the same
 * orders as cards, whose 导出 opens a dialog in the band's preset and mode.
 * Every surface pins both, the way a host pins one embed (`theme`,
 * `preset`), so the bands stand side by side whatever the toolbar says.
 */
function Band({
  engine,
  preset,
  mode,
}: {
  engine: ViewEngine;
  preset: string;
  mode: Mode;
}) {
  const system = useSystemMode();
  const pinned = { theme: mode, preset, ...HOST_LANGUAGE } as const;
  return (
    <section
      data-gallery-band
      data-preset={preset}
      data-mode={mode}
      aria-label={`${preset} · ${MODE_NAMES[mode]}`}
      style={{ display: 'flex', flexDirection: 'column', gap: 8 }}
    >
      <h3 style={{ margin: 0, fontSize: 14, fontWeight: 600 }}>
        {preset} · {MODE_NAMES[mode]}
        {mode === 'system' && `（此刻为${MODE_NAMES[system]}）`}
      </h3>
      <div className="gallery-row">
        <EmbeddedDashboard
          engine={engine}
          instanceId={GALLERY_BOARD}
          initialFilters={GALLERY_FILTERS}
          {...pinned}
          headingLevel={4}
        />
        <EmbeddedView
          engine={engine}
          instanceId={GALLERY_CARDS}
          interaction="interactive"
          withExport
          headingLevel={4}
          withTitle
          {...pinned}
        />
      </div>
    </section>
  );
}

/**
 * 主题一览：每套预设 × 每种明暗，同一页上并排（阶段 5，5D）。
 *
 * 每一条带是一套预设在一种明暗下：运营日报的节选（筛选栏上「发货仓 = 华东」、
 * 「付款超过 48 小时仍未发货」的记录表格面板、渠道分布的分析图表面板）与华东仓
 * 待发货的单的卡片视图；卡片视图头上的「导出」打开对话框，对话框从面上照抄预设
 * 与明暗。预设读自 `BUILT_IN_PRESETS`，包里多一套，这里就多三条。每块面都钉住
 * 预设与明暗（`preset`、`theme`），所以工具栏的开关管不到这一页。
 */
function GalleryPage() {
  return (
    // A band per preset and mode: eight presets already ask well past the 32
    // queries an engine queues for one screen. No host shows two dozen boards
    // together; this page does, so it queues every one of them rather than
    // refusing the tail.
    <StoryEngine
      create={() => createGalleryEngine(PRESETS.length * MODES.length)}
    >
      {engine => (
        <div
          className="fve-tokens bg-background text-foreground"
          style={{
            display: 'flex',
            flexDirection: 'column',
            gap: 32,
            padding: 16,
          }}
        >
          {PRESETS.map(preset => (
            <div
              key={preset}
              style={{ display: 'flex', flexDirection: 'column', gap: 16 }}
            >
              <h2 style={{ margin: 0, fontSize: 18, fontWeight: 600 }}>
                {preset}
              </h2>
              {MODES.map(mode => (
                <Band key={mode} engine={engine} preset={preset} mode={mode} />
              ))}
            </div>
          ))}
        </div>
      )}
    </StoryEngine>
  );
}

/**
 * The page's own layout of a band: the board and the cards one above the
 * other — the board keeps the width it lays its filter chips and two panels
 * side by side at — each surface given the gutter a host's card would give
 * it, since an embed paints to its edge.
 */
const GALLERY_CSS = `.gallery-row { display: grid; gap: 12px; grid-template-columns: minmax(0, 1fr); }
.gallery-row > .fve-root { padding: 12px; border-radius: 12px; }`;

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

const description = `**能力 · 主题与预设：主题一览与对比度矩阵**

本包的预设（\`themes.css\`）在每种明暗下画出来的样子，以及它们守不守得住对比度承诺。一套预设放进一个宿主是什么样，见下面的「逐套预设」：每套一个故事，是整张运营日报。

${RETAIL_DATA_NOTE}

- **主题一览**：每套预设 × 亮／暗／跟随系统各一条带：运营日报的一个节选（筛选栏上「发货仓 = 华东」、「付款超过 48 小时仍未发货」的记录表格面板、近 30 天渠道分布的分析图表面板）加华东仓待发货的单的卡片视图，卡片视图头上的「导出」打开对话框。
- **对比度矩阵**：每套预设 × 每种明暗 × 每一对 token，在真浏览器里量级联后的颜色：字 ≥4.5:1，控件边与焦点 ≥3:1。「跟随系统」解析成亮或暗之一，所以量这两种。在输入框里粘贴自己的 \`--fve-*\`，它们作为一套预设当场一起量。矩阵下面的「图表八色」再量色板的三道门（themes.md 5.2）：相邻色在正常视觉与三种色觉模拟下的间距、暗色每色对卡片 ≥3:1（亮色列出例外）、每色都有一种墨色 ≥4.5:1；粘贴的 \`--fve-chart-*\` 同样一起量。
- **工具栏**：「Preset」切换 \`<html>\` 上的 \`data-fve-preset\`，明暗开关多了「system」。这两页的面都钉住了预设与明暗，不受工具栏影响；其余故事都跟着工具栏走。
- **预设从哪来**：读自包导出的 \`BUILT_IN_PRESETS\`（包自己的测试守着它与 \`themes.css\` 一致），包里多一套，工具栏、一览与矩阵就都多一套。`;

const meta = {
  title: 'View Engine/能力/主题与预设',
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [
    Story => (
      <>
        <style>{GALLERY_CSS}</style>
        <Story />
      </>
    ),
  ],
} satisfies Meta;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 每套预设 × 亮、暗、跟随系统各一条带。
 *
 * 带里的面都钉住预设与明暗；「跟随系统」那一条随读者的系统设置变，标题上写着
 * 此刻解析成了哪一种。
 */
export const Gallery: Story = {
  name: '主题一览',
  render: () => <GalleryPage />,
  parameters: {
    // The page shows one board and one view nine times over, by design, so
    // their landmarks (「筛选」, 「正在显示」) repeat with the same names. A
    // page that embeds a view once, as a host's does, has each once; every
    // other rule stays on.
    a11y: { config: { rules: [{ id: 'landmark-unique', enabled: false }] } },
  },
  play: async ({ canvasElement }) => {
    // Every preset `themes.css` declares, in every mode, is on the page.
    await waitFor(() =>
      expect(canvasElement.querySelectorAll('[data-gallery-band]').length).toBe(
        PRESETS.length * MODES.length,
      ),
    );
    // Each band's surfaces wear its preset and mode, `system` answered.
    const system = matchMedia('(prefers-color-scheme: dark)').matches
      ? 'dark'
      : 'light';
    for (const band of canvasElement.querySelectorAll<HTMLElement>(
      '[data-gallery-band]',
    )) {
      const { preset, mode } = band.dataset;
      const surfaces = band.querySelectorAll('.fve-root');
      await expect(surfaces.length).toBe(2);
      for (const surface of surfaces) {
        await expect(surface).toHaveAttribute('data-fve-preset', preset);
        await expect(surface).toHaveAttribute(
          'data-theme',
          mode === 'system' ? system : mode,
        );
      }
    }
    // `contrast` pins the chart patterns on, through the same variable a
    // host would set (its optional pattern group).
    const contrastSurface = canvasElement.querySelector(
      '[data-gallery-band][data-preset="contrast"][data-mode="light"] .fve-root',
    )!;
    await expect(
      getComputedStyle(contrastSurface)
        .getPropertyValue('--fve-chart-patterns')
        .trim(),
    ).toBe('on');
    // The dialog a band opens is in the band's preset and mode.
    const band = canvasElement.querySelector<HTMLElement>(
      `[data-gallery-band][data-preset="${PRESETS.at(-1)}"][data-mode="dark"]`,
    )!;
    await userEvent.click(
      await within(band).findByRole('button', {
        name: zhCN['label.export.title'],
      }),
    );
    const dialog = await within(document.body).findByRole('dialog');
    try {
      await expect(dialog).toHaveAttribute('data-fve-preset', PRESETS.at(-1));
      await expect(dialog).toHaveAttribute('data-theme', 'dark');
    } finally {
      await userEvent.keyboard('{Escape}');
    }
  },
};

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
