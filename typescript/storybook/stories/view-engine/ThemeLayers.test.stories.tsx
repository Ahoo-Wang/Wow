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

/**
 * The theme's three layers and the host's import order, measured in a
 * browser (theme-architecture.md 3, S2; the compensation console's G16).
 *
 * - The host's `--fve-*` on `:root` beat every preset, one pinned on the
 *   surface included, and so do the values a surface is handed (`tokens`),
 *   popups too.
 * - A preset pinned inside another resolves exactly as it does alone: the
 *   reset rule empties the preset layer where a preset is named, so nothing
 *   the outer preset gave — a group the inner one leaves out — gets through.
 *   A host's own preset, written without a single `initial`, nests the same.
 * - A host's Tailwind utilities, loaded after the engine's stylesheet, do
 *   not undo the engine's responsive layout on its own surfaces.
 *
 * The jsdom side is `test/themeLayers.test.ts` in the package.
 */

import type { ReactNode } from 'react';
import type { StoryObj } from '@storybook/react-vite';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import {
  BUILT_IN_PRESETS,
  ViewSurface,
  zhCN,
} from '@ahoo-wang/wow-view-engine/ui';
import '@ahoo-wang/wow-view-engine/themes.css';
import { converter, parse } from 'culori';
import displayMeta, {
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';
import { ENGINE_PRESET } from './presets.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/能力/主题与预设/三层/回归',
  tags: ['!dev', '!autodocs', 'test'],
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

const toRgb = converter('rgb');

/**
 * A colour token as the cascade resolved it on an element, as `rgb(…)`: read
 * through a probe's `background-color`, so a derived one (a brand colour's
 * relative colour) comes back as the colour the browser worked out.
 */
function colorOf(element: Element, token: string): string {
  const probe = document.createElement('span');
  probe.hidden = true;
  probe.style.setProperty('background-color', `var(${token})`);
  element.append(probe);
  const value = getComputedStyle(probe).backgroundColor;
  probe.remove();
  const rgb = toRgb(parse(value)!);
  const channel = (c: number) => Math.round(c * 255);
  return `rgb(${channel(rgb.r)}, ${channel(rgb.g)}, ${channel(rgb.b)})`;
}

/** A style element for the length of one play; the returned call removes it. */
function withStyle(css: string): () => void {
  const style = document.createElement('style');
  style.dataset.themeLayers = '';
  style.textContent = css;
  // Last in `<head>`: after the engine's stylesheet, as a host's own is.
  document.head.append(style);
  return () => style.remove();
}

const HOST_PRIMARY = 'rgb(1, 2, 3)';

/**
 * 宿主 `:root` 上的值赢过任何预设，包括钉在面上的（D46 第 10 节第 7 条）。
 *
 * 宿主在 `<html>` 上写 `--fve-primary`，每一套内置预设都钉在各自的面上：每块面
 * 的 `--primary` 都是宿主的值。从前钉住的预设把值声明在面的根上，压过从 `:root`
 * 继承来的宿主值；现在宿主与预设写不同的变量，每个 token 先读宿主的。
 */
export const HostRootBeatsPinnedPresets: Story = {
  render: () => (
    <div data-surfaces>
      {BUILT_IN_PRESETS.map(preset => (
        <ViewSurface key={preset} preset={preset} theme="light">
          {preset}
        </ViewSurface>
      ))}
    </div>
  ),
  play: async ({ canvasElement }) => {
    const html = document.documentElement;
    const surfaces = [
      ...canvasElement.querySelectorAll('[data-slot="view-surface"]'),
    ];
    await expect(surfaces).toHaveLength(BUILT_IN_PRESETS.length);
    // Each preset sets a primary of its own, so the check is not idle.
    const own = surfaces.map(surface => colorOf(surface, '--primary'));
    await expect(own).not.toContain(HOST_PRIMARY);
    try {
      html.style.setProperty('--fve-primary', HOST_PRIMARY);
      for (const surface of surfaces)
        await expect(
          colorOf(surface, '--primary'),
          surface.getAttribute('data-fve-preset')!,
        ).toBe(HOST_PRIMARY);
    } finally {
      html.style.removeProperty('--fve-primary');
    }
  },
};

/**
 * 面的 `tokens` 写在面与它的每个弹层上，同样赢过钉住的预设（D46 第 10 节第 12 条）。
 *
 * 工作台钉 `porcelain`，并用 `tokens` 给自己一个主色：面上与 portal 到 `<body>`
 * 的视图管理对话框里，`--primary` 都是这个值——包裹层上的变量到不了弹层，`tokens`
 * 到得了。
 */
export const SurfaceTokensReachPopups: Story = {
  ...DisplayWithData,
  args: {
    ...DisplayWithData.args,
    theme: 'light',
    preset: 'porcelain',
    tokens: { '--fve-primary': HOST_PRIMARY },
  },
  play: async ({ canvasElement }) => {
    await within(canvasElement).findByRole('table');
    const surface = canvasElement.querySelector('[data-slot="view-surface"]')!;
    await expect(surface).toHaveAttribute('data-fve-preset', 'porcelain');
    await expect(colorOf(surface, '--primary')).toBe(HOST_PRIMARY);
    await userEvent.click(
      await within(canvasElement).findByRole('button', {
        name: zhCN['label.manage.open'],
      }),
    );
    const dialog = await within(document.body).findByRole('dialog');
    try {
      await expect(dialog).toHaveAttribute('data-fve-preset', 'porcelain');
      await expect(colorOf(dialog, '--primary')).toBe(HOST_PRIMARY);
    } finally {
      await userEvent.keyboard('{Escape}');
    }
  },
};

/**
 * What a surface resolves, token by token: the colours, the lengths and the
 * groups a preset may or may not give — the grouped ground, the card's edge
 * and lift, the controls' fills, the title weight, the density it
 * recommends, the chart patterns — and the type.
 */
const RESOLVED = [
  '--background',
  '--foreground',
  '--card',
  '--popover',
  '--primary',
  '--primary-foreground',
  '--secondary',
  '--muted',
  '--muted-foreground',
  '--accent',
  '--sidebar',
  '--sidebar-accent',
  '--destructive',
  '--success',
  '--warning',
  '--border',
  '--input',
  '--ring',
  '--chart-1',
  '--chart-8',
  '--radius',
  '--shadow-sm',
  '--shadow-md',
  '--shadow-lg',
  '--_fve-row-hover',
  '--_fve-quiet-foreground',
  '--_fve-canvas',
  '--_fve-card-edge',
  '--_fve-card-shadow',
  '--_fve-control',
  '--_fve-control-edge',
  '--_fve-control-thumb',
  '--_fve-title-weight',
  '--_fve-density',
  '--fvp-chart-patterns',
];

function resolved(surface: Element): Record<string, string> {
  const style = getComputedStyle(surface);
  return {
    ...Object.fromEntries(
      RESOLVED.map(token => [token, style.getPropertyValue(token).trim()]),
    ),
    fontFamily: style.fontFamily,
  };
}

/** A host's own preset as a host writes it now: only what it changes. */
const HOST_PRESET = 'story-host';
const HOST_PRESET_CSS = `:where([data-fve-preset='${HOST_PRESET}']) {
  --fvp-primary: rgb(10, 110, 90);
  --fvp-dark-primary: rgb(120, 220, 200);
  --fvp-radius: 0.25rem;
}`;

/** The same surface on a page with no preset, and inside `outer`. */
function Pair({ preset, outer }: { preset: string; outer: string }) {
  const surface = (where: string): ReactNode => (
    <ViewSurface preset={preset} theme="light" data-where={where}>
      {preset}
    </ViewSurface>
  );
  return (
    <div data-pair={preset}>
      {surface('alone')}
      <div data-fve-preset={outer}>{surface('nested')}</div>
    </div>
  );
}

const PRESETS = [...BUILT_IN_PRESETS, HOST_PRESET];

/**
 * 钉住的预设完整替换外层的预设（theme-architecture.md 3.2 的复位规则）。
 *
 * 每一套预设（加一套宿主写的、一行 `initial` 也没有的）各画两块面：一块在不挂
 * 预设的页面上，一块在挂了 `porcelain` 的包裹层里。两块解析出的每个 token 都一样
 * ——`porcelain` 给了、里层没给的可选组（控件填色、分组底、推荐密度、字体栈）一个
 * 也没漏进来。从前只写几个值的预设（已删的 `brand`）钉在 `porcelain` 里会带上它
 * 的填色筛选芯片。
 */
export const PinnedPresetReplacesTheOuterOne: Story = {
  globals: { fvePreset: ENGINE_PRESET },
  render: () => (
    <div>
      {PRESETS.map(preset => (
        <Pair key={preset} preset={preset} outer="porcelain" />
      ))}
    </div>
  ),
  play: async ({ canvasElement }) => {
    const release = withStyle(HOST_PRESET_CSS);
    try {
      await expect(document.documentElement).not.toHaveAttribute(
        'data-fve-preset',
      );
      for (const preset of PRESETS) {
        const pair = canvasElement.querySelector(`[data-pair="${preset}"]`)!;
        const alone = pair.querySelector('[data-where="alone"]')!;
        const nested = pair.querySelector('[data-where="nested"]')!;
        await expect(resolved(nested), preset).toEqual(resolved(alone));
      }
      // And the outer preset is really there: a surface under it with no
      // preset of its own wears porcelain's filled controls.
      const neutral = canvasElement.querySelector('[data-pair="neutral"]')!;
      const outer = neutral.querySelector('[data-fve-preset="porcelain"]')!;
      await expect(
        getComputedStyle(outer).getPropertyValue('--fvp-control').trim(),
      ).not.toBe('');
      await expect(
        getComputedStyle(neutral.querySelector('[data-where="nested"]')!)
          .getPropertyValue('--_fve-control')
          .trim(),
      ).toBe('');
    } finally {
      release();
    }
  },
};

/**
 * What a host's Tailwind build emits for two utilities the workbench also
 * wears, in the layer Tailwind puts them in.
 */
const HOST_UTILITIES = `@layer theme, base, components, utilities;
@layer utilities {
  .w-full { width: 100%; }
  .flex-col { flex-direction: column; }
}`;

/**
 * 宿主的 Tailwind 排在引擎样式之后，也打不乱引擎的布局（补偿控制台的 G16）。
 *
 * 宿主的工具类与引擎的同在 `utilities` 层，同权重时后来者胜：控制台把引擎样式放在
 * 自己的 `index.css` 之前，全局的 `.w-full`、`.flex-col` 就盖掉了引擎的
 * `md:w-64`、`md:flex-row`，桌面宽度下视图列表占满整行。现在引擎的每条规则都比
 * 源码多一个类的权重，这里把宿主的两条工具类排在引擎样式之后：视图列表仍是 16rem，
 * 面仍是横排；而同一页上宿主自己的元素照样吃宿主的工具类。
 */
export const HostUtilitiesAfterTheEngine: Story = {
  ...DisplayWithData,
  parameters: {
    ...displayMeta.parameters,
    viewport: {
      options: {
        desk: {
          name: '1440×900',
          styles: { width: '1440px', height: '900px' },
        },
      },
    },
  },
  globals: { viewport: { value: 'desk' } },
  play: async ({ canvasElement }) => {
    await expect(window.innerWidth).toBe(1440);
    await within(canvasElement).findByRole('table');
    const release = withStyle(HOST_UTILITIES);
    const own = document.createElement('div');
    own.className = 'w-full flex-col';
    own.style.display = 'flex';
    canvasElement.append(own);
    try {
      // The host's stylesheet is live, and its rules reach the host's markup.
      await expect(getComputedStyle(own).flexDirection).toBe('column');
      const surface = canvasElement.querySelector<HTMLElement>(
        '[data-slot="view-surface"]',
      )!;
      const sidebar = canvasElement.querySelector<HTMLElement>(
        '[data-slot="view-sidebar"]',
      )!;
      await expect(surface.classList.contains('flex-col')).toBe(true);
      await expect(sidebar.classList.contains('w-full')).toBe(true);
      await waitFor(() =>
        expect(getComputedStyle(surface).flexDirection).toBe('row'),
      );
      await expect(sidebar.getBoundingClientRect().width).toBeCloseTo(256, 0);
      await expect(
        canvasElement
          .querySelector('[data-slot="workbench-main"]')!
          .getBoundingClientRect().width,
      ).toBeGreaterThan(surface.getBoundingClientRect().width - 257);
    } finally {
      own.remove();
      release();
    }
  },
};
