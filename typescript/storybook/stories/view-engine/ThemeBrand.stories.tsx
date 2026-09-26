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

import { useState, type CSSProperties } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import { ViewSurface } from '@ahoo-wang/wow-view-engine/ui';
import { isPending } from '@/ui/theme/pairs';
import { converter, parse } from 'culori';
import {
  ContrastMatrix,
  MEASURED_MODES,
  type MeasuredMode,
  PAIRS,
  passes,
  readMatrix,
} from './themeContrast.js';
import '@ahoo-wang/wow-view-engine/styles.css';
import '@ahoo-wang/wow-view-engine/themes.css';

/**
 * A brand colour is an input beside any preset (theme-architecture.md 2,
 * S4), measured in a browser: the same colour worn on `azure`, `porcelain`
 * and `contrast`, each deriving its primary, its tints and — where the
 * preset bounds it — its focus ring from it, held to that preset's own
 * lines. The jsdom side is `test/brandInput.test.ts` in the package, which
 * sweeps the whole sRGB range on every preset; this is one colour, as the
 * browser's cascade and relative colour resolve it.
 */

/** The brand colour every surface here is given. */
const BRAND = '#7c3aed';

/** The presets shown wearing it. */
const SHOWN = ['azure', 'porcelain', 'contrast'] as const;

/** A preset whose focus ring is its primary, so the ring follows the brand. */
const RING_FOLLOWS = new Set<string>(['azure', 'porcelain', 'contrast']);

/** The host's variable, as a wrapper's inline style. */
const branded = (more: Record<string, string> = {}) =>
  ({ '--fve-brand': BRAND, ...more }) as CSSProperties;

const toOklch = converter('oklch');

/** A colour token as the cascade resolved it under `element`, in OKLCH. */
function oklchOf(element: Element, token: string) {
  const probe = document.createElement('span');
  probe.hidden = true;
  probe.style.setProperty('background-color', `var(${token})`);
  element.append(probe);
  const value = getComputedStyle(probe).backgroundColor;
  probe.remove();
  return toOklch(parse(value)!)!;
}

const BRAND_HUE = toOklch(parse(BRAND)!)!.h!;

/** Whether a colour carries the brand's hue, allowing for gamut mapping. */
const brandHued = (color: { h?: number; c: number }) =>
  color.c > 0.01 &&
  Math.abs((((color.h ?? 0) - BRAND_HUE + 540) % 360) - 180) < 8;

const SWATCHES = [
  ['主色', 'var(--primary)', 'var(--primary-foreground)'],
  ['选中项', 'var(--accent)', 'var(--accent-foreground)'],
  ['导航悬停', 'var(--sidebar-accent)', 'var(--sidebar-accent-foreground)'],
  ['选中行', 'var(--_fve-row-selected)', 'var(--_fve-row-selected-foreground)'],
] as const;

/** One preset in one mode: the derived colours as swatches. */
function Swatches({
  preset,
  mode,
  role,
}: {
  preset: string;
  mode: MeasuredMode;
  role: 'branded' | 'own';
}) {
  return (
    <div
      className="fve-root"
      data-theme={mode}
      data-fve-preset={preset}
      data-swatches={role}
      data-mode={mode}
      style={{
        display: 'flex',
        flexWrap: 'wrap',
        alignItems: 'center',
        gap: 8,
        padding: 12,
        borderRadius: 8,
        fontSize: 13,
      }}
    >
      <strong style={{ minWidth: 120 }}>
        {preset} · {mode === 'light' ? '亮' : '暗'}
      </strong>
      {SWATCHES.map(([label, ground, ink]) => (
        <span
          key={label}
          style={{
            padding: '4px 10px',
            borderRadius: 6,
            background: ground,
            color: ink,
          }}
        >
          {label}
        </span>
      ))}
      <span
        style={{
          padding: '3px 9px',
          borderRadius: 6,
          border: '2px solid var(--ring)',
        }}
      >
        焦点
      </span>
    </div>
  );
}

function BrandPage() {
  return (
    <div
      className="fve-tokens bg-background text-foreground"
      style={{ display: 'flex', flexDirection: 'column', gap: 16, padding: 16 }}
    >
      {/* The brand on a wrapper, as a host that themes one part of its page
          would: every surface below derives from it, wherever its preset is
          named. */}
      <div
        data-brand-wrapper
        style={{
          ...branded(),
          display: 'flex',
          flexDirection: 'column',
          gap: 8,
        }}
      >
        {SHOWN.flatMap(preset =>
          MEASURED_MODES.map(mode => (
            <Swatches
              key={`${preset}-${mode}`}
              preset={preset}
              mode={mode}
              role="branded"
            />
          )),
        )}
        <ContrastMatrix presets={SHOWN} />
      </div>
      {/* The same presets with no brand colour, to compare against. */}
      <div hidden>
        {SHOWN.flatMap(preset =>
          MEASURED_MODES.map(mode => (
            <Swatches
              key={`${preset}-${mode}`}
              preset={preset}
              mode={mode}
              role="own"
            />
          )),
        )}
      </div>
    </div>
  );
}

const description = `**能力 · 主题与预设：品牌色**（theme-architecture.md 2，S4）

品牌色不是一套预设，是任何一套都接受的输入：宿主写一个 \`--fve-brand\`，主色、\`accent\`、\`sidebar-accent\` 与选中行的淡色都取它的色相；三套的焦点环本来就是主色，也跟着变。派生式只写在 \`styles.css\` 一处，每套预设只给它在自己的底上量出来的边界（\`--fvp-brand-*\`），所以同一个颜色在 \`contrast\` 上被压得更深，守它的 7:1。

这一页把同一个紫色（\`${BRAND}\`）写在一个包裹层上，挂 \`azure\`、\`porcelain\`、\`contrast\` 三套，亮暗各一条：上面是派生出来的几块颜色，下面是这三套在这个品牌色下的对比度矩阵——每一对都要过那一套自己的线。图表第 1 色要宿主另挂属性 \`data-fve-brand-chart\` 才跟品牌色，这里没挂，所以不变。包里的 \`test/brandInput.test.ts\` 把整个 sRGB 色域在每一套、每种明暗下扫一遍。`;

const meta = {
  title: 'View Engine/能力/主题与预设/品牌色',
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
} satisfies Meta;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 同一个品牌色挂在三套预设上：每套的主色、淡色与焦点环都取它的色相，图表
 * 第 1 色不变；每一对都过那一套自己的线。
 */
export const OnEveryPreset: Story = {
  name: '同一个品牌色，三套预设',
  render: () => <BrandPage />,
  play: async ({ canvasElement }) => {
    const matrix = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-matrix]');
      if (!found || found.dataset.matrix === 'measuring')
        throw new Error('矩阵还没量完');
      return found;
    });
    const measured = readMatrix(matrix);
    await expect(measured.length).toBe(
      SHOWN.length *
        MEASURED_MODES.reduce((n, mode) => n + PAIRS[mode].length, 0),
    );
    const short = measured
      .filter(m => !passes(m) && !isPending(m.preset, m.mode, m.pair))
      .map(
        m =>
          `${m.preset}/${m.mode} ${m.pair} ${m.ratio.toFixed(2)}:1 < ${m.line}:1 ${JSON.stringify(m.colors)}`,
      );
    await expect(short, short.join('\n')).toEqual([]);

    const root = (role: string, preset: string, mode: string) =>
      canvasElement.querySelector(
        `[data-swatches="${role}"][data-fve-preset="${preset}"][data-mode="${mode}"]`,
      )!;
    for (const preset of SHOWN)
      for (const mode of MEASURED_MODES) {
        const where = `${preset}/${mode}`;
        const branded = root('branded', preset, mode);
        const own = root('own', preset, mode);
        // The primary and the three tints take the brand's hue, and each
        // is a colour of its own, not the preset's.
        for (const token of [
          '--primary',
          '--accent',
          '--sidebar-accent',
          '--_fve-row-selected',
        ]) {
          const color = oklchOf(branded, token);
          await expect(brandHued(color), `${where} ${token}`).toBe(true);
          await expect(color, `${where} ${token}`).not.toEqual(
            oklchOf(own, token),
          );
        }
        // The ring follows only where the preset bounds it.
        const ring = oklchOf(branded, '--ring');
        if (RING_FOLLOWS.has(preset))
          await expect(brandHued(ring), `${where} ring`).toBe(true);
        else
          await expect(ring, `${where} ring`).toEqual(oklchOf(own, '--ring'));
        // The first chart slot is the preset's until the host switches it.
        await expect(oklchOf(branded, '--chart-1'), `${where} chart`).toEqual(
          oklchOf(own, '--chart-1'),
        );
      }
  },
};

/** What a surface on the wrapper page shows: the colours the brand reaches. */
const WRAPPER_CHIPS = [
  ['primary', '主色', 'var(--primary)', 'var(--primary-foreground)'],
  ['accent', '选中项', 'var(--accent)', 'var(--accent-foreground)'],
  ['chart-1', '图表第 1 色', 'var(--chart-1)', 'var(--primary-foreground)'],
] as const;

type WrapperChip = (typeof WRAPPER_CHIPS)[number][0] | 'ring';

/** One surface, its derived colours drawn as chips a reader can see. */
function WrapperSurface({ title, note }: { title: string; note: string }) {
  return (
    <ViewSurface
      theme="light"
      className="bg-card text-card-foreground"
      style={{
        padding: 12,
        borderRadius: 8,
        border: '1px solid var(--border)',
      }}
    >
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
        <strong style={{ minWidth: 150 }}>{title}</strong>
        {WRAPPER_CHIPS.map(([chip, label, ground, ink]) => (
          <span
            key={chip}
            data-chip={chip}
            style={{
              padding: '4px 10px',
              borderRadius: 6,
              background: ground,
              color: ink,
            }}
          >
            {label}
          </span>
        ))}
        <span
          data-chip="ring"
          style={{
            padding: '3px 9px',
            borderRadius: 6,
            border: '2px solid var(--ring)',
          }}
        >
          焦点环
        </span>
      </div>
      <p className="text-muted-foreground" style={{ margin: 0, fontSize: 12 }}>
        {note}
      </p>
    </ViewSurface>
  );
}

/**
 * The page a host themes one part of: the brand on two wrappers, the preset
 * on `<html>`. Whether the second wrapper's charts take the brand too is the
 * reader's to switch — the attribute `data-fve-brand-chart`, set by React,
 * not by a play reaching into the DOM.
 */
function WrapperPage() {
  const [charted, setCharted] = useState(true);
  return (
    <div
      className="fve-tokens bg-background text-foreground"
      style={{ display: 'flex', flexDirection: 'column', gap: 12, padding: 16 }}
    >
      <div data-where="inside" style={branded({ '--fve-brand-chart': '1' })}>
        <WrapperSurface
          title="品牌色在包裹层上"
          note={`包裹层写了 --fve-brand: ${BRAND}：主色、选中项与焦点环取它的色相。它还写了一个变量 --fve-brand-chart，那不是开关，图表第 1 色仍是 azure 自己的。`}
        />
      </div>
      <div
        data-where="charted"
        data-fve-brand-chart={charted ? '' : undefined}
        style={branded()}
      >
        <WrapperSurface
          title="图表第 1 色也跟品牌色"
          note="开关是包裹层上的属性 data-fve-brand-chart：挂上，图表第 1 色取品牌色相；拿掉，回到 azure 自己的。"
        />
        <label
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
            marginTop: 8,
            fontSize: 13,
          }}
        >
          <input
            type="checkbox"
            checked={charted}
            onChange={event => setCharted(event.target.checked)}
          />
          挂 data-fve-brand-chart
        </label>
      </div>
      <div data-where="outside">
        <WrapperSurface
          title="没有品牌色"
          note="包裹层外的面：azure 自己的蓝。"
        />
      </div>
    </div>
  );
}

/** A chip's colour as the page painted it, in OKLCH. Reads; adds nothing. */
function paintedOf(canvas: HTMLElement, where: string, chip: WrapperChip) {
  const element = canvas.querySelector<HTMLElement>(
    `[data-where="${where}"] [data-chip="${chip}"]`,
  );
  if (!element) throw new Error(`No ${chip} chip ${where}.`);
  const style = getComputedStyle(element);
  const value = chip === 'ring' ? style.borderTopColor : style.backgroundColor;
  return toOklch(parse(value)!)!;
}

/**
 * 品牌色挂在包裹层上、预设挂在 `<html>` 上，派生照样生效（theme-architecture.md
 * 2.6）；包裹层外的面仍是预设自己的主色。图表第 1 色的开关是属性：包裹层上挂
 * `data-fve-brand-chart` 就取品牌色相，不挂就不变——写成变量 `--fve-brand-chart`
 * 什么也不开。每个面把这几样颜色画成色块，看得见，play 量的也就是画出来的色块。
 */
export const OnAWrapper: Story = {
  name: '品牌色挂在包裹层上',
  globals: { fvePreset: 'azure' },
  render: () => <WrapperPage />,
  play: async ({ canvasElement }) => {
    // The preset is on `<html>` before the story renders (preview.tsx,
    // `useBeforeRender`), so this is a fact to check, not a moment to wait for.
    await expect(document.documentElement).toHaveAttribute(
      'data-fve-preset',
      'azure',
    );
    const painted = (where: string, chip: WrapperChip) =>
      paintedOf(canvasElement, where, chip);
    // The one thing to wait for: the engine's stylesheet painting the chips.
    // Until it does, a chip has no background at all; the wait only reads.
    await waitFor(() =>
      expect(painted('outside', 'primary').c).toBeGreaterThan(0.01),
    );
    for (const chip of ['primary', 'accent', 'ring'] as const) {
      await expect(brandHued(painted('inside', chip)), chip).toBe(true);
      await expect(brandHued(painted('charted', chip)), chip).toBe(true);
    }
    // Outside the wrapper the surface wears azure's own blue.
    await expect(brandHued(painted('outside', 'primary'))).toBe(false);
    await expect(painted('outside', 'primary').l).toBeCloseTo(0.541, 2);
    // Absent, the first slot is the preset's — a variable of that name
    // switches nothing on.
    await expect(painted('inside', 'chart-1')).toEqual(
      painted('outside', 'chart-1'),
    );
    // Present on the wrapper, it takes the brand's hue at the lightness
    // azure tuned its first slot to.
    const slot = painted('charted', 'chart-1');
    await expect(brandHued(slot)).toBe(true);
    await expect(slot.l).toBeCloseTo(0.5538, 2);
    // Switched off, the slot is the preset's once more.
    const canvas = within(canvasElement);
    await userEvent.click(
      canvas.getByRole('checkbox', { name: '挂 data-fve-brand-chart' }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-where="charted"]'),
      ).not.toHaveAttribute('data-fve-brand-chart'),
    );
    await expect(painted('charted', 'chart-1')).toEqual(
      painted('outside', 'chart-1'),
    );
  },
};
