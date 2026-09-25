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
import { expect, userEvent, waitFor, within } from 'storybook/test';
import type { ViewEngine } from '@ahoo-wang/wow-view-engine';
import {
  BUILT_IN_PRESETS,
  EmbeddedDashboard,
  EmbeddedView,
  zhCN,
  type BuiltInPreset,
} from '@ahoo-wang/wow-view-engine/ui';
import { HOST_LANGUAGE } from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import {
  GALLERY_BOARD,
  GALLERY_CHANNELS,
  GALLERY_FILTERS,
  GALLERY_ORDERS,
  createGalleryEngine,
} from './retail/gallery.js';
import { RETAIL_DATA_NOTE } from './retail/scene.js';
import { chartsDrawn } from './chartDom.js';
import { matchScreenshot } from './screenshot.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/** The two modes a band pins; `system` resolves to one of them. */
const BAND_MODES = ['light', 'dark'] as const;

type BandMode = (typeof BAND_MODES)[number];

const MODE_NAMES: Record<BandMode, string> = { light: '亮', dark: '暗' };

/** The three views a band shows, each one surface and one picture. */
const BLOCKS = ['record', 'analysis', 'dashboard'] as const;

type Block = (typeof BLOCKS)[number];

/**
 * One preset in one mode: the three views, each pinning both the preset
 * and the mode the way a host pins one embed (`preset`, `theme`), so the
 * bands stand side by side whatever the toolbar says.
 */
function Band({
  engine,
  preset,
  mode,
}: {
  engine: ViewEngine;
  preset: string;
  mode: BandMode;
}) {
  const pinned = { theme: mode, preset, ...HOST_LANGUAGE } as const;
  return (
    <section
      data-gallery-band
      data-preset={preset}
      data-mode={mode}
      aria-label={`${preset} · ${MODE_NAMES[mode]}`}
      className="gallery-band"
    >
      <h2 className="gallery-band-title">
        {preset} · {MODE_NAMES[mode]}
      </h2>
      <div data-gallery-block="record">
        <EmbeddedView
          engine={engine}
          instanceId={GALLERY_ORDERS}
          interaction="interactive"
          // The export is what makes the rows selectable in an embed.
          withExport
          withTitle
          headingLevel={3}
          {...pinned}
        />
      </div>
      <div data-gallery-block="analysis">
        <EmbeddedView
          engine={engine}
          instanceId={GALLERY_CHANNELS}
          withTitle
          headingLevel={3}
          {...pinned}
        />
      </div>
      <div data-gallery-block="dashboard">
        <EmbeddedDashboard
          engine={engine}
          instanceId={GALLERY_BOARD}
          initialFilters={GALLERY_FILTERS}
          withTitle
          headingLevel={3}
          {...pinned}
        />
      </div>
    </section>
  );
}

/**
 * 主题一览里的一套预设：亮、暗两条带，每条三种视图（themes.md 4.5，T5）。
 */
function GalleryPage({ preset }: { preset: string }) {
  return (
    <StoryEngine create={createGalleryEngine}>
      {engine => (
        <div className="fve-tokens bg-background text-foreground gallery-page">
          {BAND_MODES.map(mode => (
            <Band key={mode} engine={engine} preset={preset} mode={mode} />
          ))}
        </div>
      )}
    </StoryEngine>
  );
}

/**
 * The page's own layout: the bands one above the other, the three views of
 * a band one above the other at the width a desktop gives them, each
 * surface given the gutter a host's card would give it, since an embed
 * paints to its edge.
 */
const GALLERY_CSS = `.gallery-page { display: flex; flex-direction: column; gap: 32px; padding: 16px; }
.gallery-band { display: flex; flex-direction: column; gap: 12px; }
.gallery-band-title { margin: 0; font-size: 16px; font-weight: 600; }
.gallery-band > [data-gallery-block] > .fve-root { padding: 12px; border-radius: 12px; }`;

const description = `**能力 · 主题与预设：主题一览**（themes.md 4.5、5.5，T5）

每套预设一个故事：亮、暗两条带，每条带是同一批华东仓的单画成的三种视图，每块面都用 \`preset\` 与 \`theme\` 钉住预设与明暗，不受工具栏影响。

${RETAIL_DATA_NOTE}

- **记录视图**：近 30 天的单，单号钉在左边，订单状态与售后状态是带色的徽章；故事用键盘勾选第一行，那一行选中、它的复选框获焦。
- **分析视图**：本月至今与上月同期的 GMV，分渠道两组柱，图例在上。
- **仪表盘**：筛选条上「发货仓 = 华东」，一张带走势、涨跌徽章的 GMV 指标卡，一张净销售额按渠道累加的瀑布图。

每一块也是一张截图基线（\`typescript/storybook/baselines/\`）：CI 在 Playwright 的 Linux 容器里逐张比对，更新的办法见 \`typescript/storybook/README.md\`「截图基线」。`;

const meta = {
  title: 'View Engine/能力/主题与预设/主题一览',
  component: GalleryPage,
  tags: ['visual'],
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
    // The page shows the same three views twice, by design, so their
    // landmarks (「筛选」, 「正在显示」) repeat with the same names. A host
    // embeds a view once; every other rule stays on.
    a11y: { config: { rules: [{ id: 'landmark-unique', enabled: false }] } },
  },
  decorators: [
    Story => (
      <>
        <style>{GALLERY_CSS}</style>
        <Story />
      </>
    ),
  ],
  args: { preset: 'neutral' },
  argTypes: {
    preset: { control: 'select', options: BUILT_IN_PRESETS },
  },
} satisfies Meta<typeof GalleryPage>;

export default meta;

type Story = StoryObj<typeof meta>;

/** Until a block's view has its answer on screen, in the shape it draws. */
async function landed(block: Element, kind: Block) {
  if (kind === 'record') {
    await waitFor(
      () => expect(block.querySelectorAll('tbody tr').length).toBe(5),
      { timeout: 4_000 },
    );
    return;
  }
  // The analysis's bars; the board's sparkline and waterfall.
  await chartsDrawn(block);
  await expect(
    block.querySelectorAll('[data-slot="chart"]').length,
  ).toBeGreaterThanOrEqual(kind === 'dashboard' ? 2 : 1);
}

const galleryStory = (preset: BuiltInPreset): Story => ({
  name: preset,
  args: { preset },
  play: async ({ canvasElement }) => {
    const bands = await waitFor(() => {
      const found = [
        ...canvasElement.querySelectorAll<HTMLElement>('[data-gallery-band]'),
      ];
      expect(found).toHaveLength(BAND_MODES.length);
      return found;
    });
    for (const band of bands) {
      // Every surface on the band wears its preset and its mode.
      const surfaces = band.querySelectorAll('.fve-root');
      await expect(surfaces.length).toBe(BLOCKS.length);
      for (const surface of surfaces) {
        await expect(surface).toHaveAttribute('data-fve-preset', preset);
        await expect(surface).toHaveAttribute('data-theme', band.dataset.mode);
      }
      for (const kind of BLOCKS)
        await landed(
          band.querySelector(`[data-gallery-block="${kind}"]`)!,
          kind,
        );
    }
    // `contrast` pins the chart patterns on, through the variable a host
    // would set (its optional pattern group); no other preset does.
    await expect(
      getComputedStyle(bands[0].querySelector('.fve-root')!)
        .getPropertyValue('--fve-chart-patterns')
        .trim(),
    ).toBe(preset === 'contrast' ? 'on' : '');

    for (const band of bands) {
      const mode = band.dataset.mode!;
      const record = band.querySelector('[data-gallery-block="record"]')!;
      // The first row selected from the keyboard, its checkbox keeping the
      // focus: a selected row and a focused control in the picture.
      const box = within(record.querySelector('tbody tr')!).getByRole(
        'checkbox',
      );
      box.focus();
      await userEvent.keyboard(' ');
      await waitFor(() =>
        expect(record.querySelector('tbody tr')).toHaveAttribute(
          'data-state',
          'selected',
        ),
      );
      await expect(box).toHaveFocus();
      await expect(box.matches(':focus-visible')).toBe(true);
      for (const kind of BLOCKS)
        await matchScreenshot(
          band.querySelector(`[data-gallery-block="${kind}"] > .fve-root`)!,
          `${preset}-${mode}-${kind}`,
        );
    }

    // The dialog a band opens is in the band's preset and mode.
    const dark = bands.find(band => band.dataset.mode === 'dark')!;
    await userEvent.click(
      await within(dark).findByRole('button', {
        name: zhCN['label.export.title'],
      }),
    );
    const dialog = await within(document.body).findByRole('dialog');
    try {
      await expect(dialog).toHaveAttribute('data-fve-preset', preset);
      await expect(dialog).toHaveAttribute('data-theme', 'dark');
    } finally {
      await userEvent.keyboard('{Escape}');
    }
  },
});

/** The stylesheet's own look: no preset at all is this one. */
export const Neutral: Story = galleryStory('neutral');

/** Cool greys with a blue brand colour. */
export const Slate: Story = galleryStory('slate');

/** Chinese enterprise admin: a clear blue, white cards on grey. */
export const Azure: Story = galleryStory('azure');

/** Native desktop: system type, 12px corners, soft shadows. */
export const Porcelain: Story = galleryStory('porcelain');

/** Square corners, strong greys, no shadows. */
export const Graphite: Story = galleryStory('graphite');

/** Cool, low-chroma Nordic colours. */
export const Fjord: Story = galleryStory('fjord');

/** High contrast, the chart patterns pinned on. */
export const Contrast: Story = galleryStory('contrast');

/** `neutral` with the primary derived from the host's `--fve-brand`. */
export const Brand: Story = galleryStory('brand');
