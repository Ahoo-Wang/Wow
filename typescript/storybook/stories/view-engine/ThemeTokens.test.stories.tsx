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
import {
  AnalysisChart,
  ViewSurface,
  zhCN,
} from '@ahoo-wang/wow-view-engine/ui';
import '@ahoo-wang/wow-view-engine/themes.css';
import { converter, formatRgb, parse } from 'culori';
import displayMeta, {
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';
import { measureFocusMark } from './contrast.js';
import { HOST_LANGUAGE } from './fixtures.js';
import { ENGINE_PRESET } from './presets.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/能力/主题与预设/令牌/回归',
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
 * `--_fve-quiet-foreground` 从前写死成前景色的一份拷贝（`oklch(0.145 … / 70%)`）：
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

/**
 * A preset for these stories only (phase 5, 5B), written the way
 * `themes.css` writes one: the mechanism is proven here with colours no
 * built-in preset will ever have.
 */
const STORY_PRESET = 'story-probe';
const STORY_PRESET_CSS = `:where([data-fve-preset='${STORY_PRESET}']) {
  --fvp-primary: rgb(160, 20, 60);
  --fvp-popover: rgb(255, 247, 214);
  --fvp-dark-primary: rgb(250, 160, 190);
  --fvp-dark-popover: rgb(48, 36, 8);
  --fvp-font-sans: Georgia, serif;
  --fvp-shadow-md: 0 0 0 3px rgb(160, 20, 60);
}`;
const PROBE_POPOVER = { light: 'rgb(255, 247, 214)', dark: 'rgb(48, 36, 8)' };
const PROBE_PRIMARY = { light: 'rgb(160, 20, 60)', dark: 'rgb(250, 160, 190)' };

/** Puts the story preset on the page for the length of one play. */
function withStoryPreset(): () => void {
  const style = document.createElement('style');
  style.dataset.storyPreset = '';
  style.textContent = STORY_PRESET_CSS;
  document.head.append(style);
  return () => style.remove();
}

/** A token as the cascade resolved it on an element, as `rgb(…)`. */
function tokenOf(element: Element, token: string): string {
  const value = getComputedStyle(element).getPropertyValue(token).trim();
  const rgb = toRgb(parse(value)!);
  const channel = (c: number) => Math.round(c * 255);
  return `rgb(${channel(rgb.r)}, ${channel(rgb.g)}, ${channel(rgb.b)})`;
}

/** Opens the view manager, a dialog portalled to `<body>`, and returns it. */
async function openManager(canvasElement: HTMLElement): Promise<HTMLElement> {
  await userEvent.click(
    await within(canvasElement).findByRole('button', {
      name: zhCN['label.manage.open'],
    }),
  );
  return within(document.body).findByRole('dialog');
}

/** The mode the surface is in, as the page decided it. */
function modeOf(canvasElement: HTMLElement): 'light' | 'dark' {
  const surface = canvasElement.querySelector('[data-slot="view-surface"]')!;
  return getComputedStyle(surface).colorScheme === 'dark' ? 'dark' : 'light';
}

/**
 * 面上钉住的预设送到弹层（阶段 5，5B）。
 *
 * 工作台以 `preset` 钉住一套只在 story 里的预设：面上的 `--primary` 换成它的值，
 * 从面上 portal 到 `<body>` 的视图管理对话框也带着同一个 `data-fve-preset`，
 * 画出它的 `--popover`——弹层不在面的子树里，靠的是照抄属性，和 `data-theme` 一样。
 */
export const PresetPinnedReachesPopups: Story = {
  ...DisplayWithData,
  args: { ...DisplayWithData.args, preset: STORY_PRESET },
  play: async ({ canvasElement }) => {
    const release = withStoryPreset();
    try {
      await within(canvasElement).findByRole('table');
      const mode = modeOf(canvasElement);
      const surface = canvasElement.querySelector(
        '[data-slot="view-surface"]',
      )!;
      await expect(surface).toHaveAttribute('data-fve-preset', STORY_PRESET);
      await expect(tokenOf(surface, '--primary')).toBe(PROBE_PRIMARY[mode]);

      const dialog = await openManager(canvasElement);
      await expect(dialog).toHaveAttribute('data-fve-preset', STORY_PRESET);
      await waitFor(() =>
        expect(getComputedStyle(dialog).backgroundColor).toBe(
          PROBE_POPOVER[mode],
        ),
      );
      await expect(tokenOf(dialog, '--primary')).toBe(PROBE_PRIMARY[mode]);
    } finally {
      await userEvent.keyboard('{Escape}');
      release();
    }
  },
};

/**
 * 挂在页面一部分上的预设也送到弹层（阶段 5，5B）。
 *
 * 宿主把 `data-fve-preset` 挂在包住视图的一个元素上，而不是 `<html>`：面上的值
 * 靠继承就有，portal 到 `<body>` 的对话框却不在那棵子树里。面往上找到最近的预设，
 * 交给弹层照抄，所以对话框同样画出这套预设；子树之外的页面不受影响。
 */
export const PresetOnPartOfThePage: Story = {
  ...DisplayWithData,
  args: { ...DisplayWithData.args },
  play: async ({ canvasElement }) => {
    const release = withStoryPreset();
    canvasElement.setAttribute('data-fve-preset', STORY_PRESET);
    try {
      await within(canvasElement).findByRole('table');
      const mode = modeOf(canvasElement);
      const surface = canvasElement.querySelector(
        '[data-slot="view-surface"]',
      )!;
      await expect(surface).not.toHaveAttribute('data-fve-preset');
      await expect(tokenOf(surface, '--primary')).toBe(PROBE_PRIMARY[mode]);

      const dialog = await openManager(canvasElement);
      await waitFor(() =>
        expect(dialog).toHaveAttribute('data-fve-preset', STORY_PRESET),
      );
      await waitFor(() =>
        expect(getComputedStyle(dialog).backgroundColor).toBe(
          PROBE_POPOVER[mode],
        ),
      );
      await expect(
        getComputedStyle(document.body).getPropertyValue('--fvp-popover'),
      ).toBe('');
    } finally {
      await userEvent.keyboard('{Escape}');
      canvasElement.removeAttribute('data-fve-preset');
      release();
    }
  },
};

/** Tokens a neutral preset must leave exactly where the stylesheet puts them. */
const NEUTRAL_PROBES = [
  '--background',
  '--foreground',
  '--card',
  '--popover',
  '--primary',
  '--primary-foreground',
  '--muted-foreground',
  '--border',
  '--input',
  '--ring',
  '--destructive',
  '--success',
  '--warning',
  '--_fve-quiet-foreground',
  '--sidebar',
  '--radius',
  '--chart-1',
  // Theme batch T1: the optional groups and the convention's pair.
  '--shadow-sm',
  '--shadow-md',
  '--shadow-lg',
  '--_fve-rise',
  '--_fve-fall',
];

/**
 * `neutral` 就是今天的样子（阶段 5，5B）。
 *
 * 不挂预设时量一遍面上的 token，再在 `<html>` 上挂 `neutral` 量一遍，两份逐个
 * 相同；外面挂着另一套预设、面上钉 `neutral` 时也一样——`neutral` 只是把变量放回
 * 未设，token 落回 `styles.css` 自己的内置值。亮暗各量一遍。
 */
export const NeutralUnchanged: Story = {
  ...DisplayWithData,
  args: { ...DisplayWithData.args },
  // Measured with no preset on the page first: the engine's own look,
  // whatever Storybook opens in.
  globals: { fvePreset: ENGINE_PRESET },
  play: async ({ canvasElement }) => {
    const html = document.documentElement;
    const release = withStoryPreset();
    const hadDark = html.classList.contains('dark');
    try {
      await within(canvasElement).findByRole('table');
      const surface = canvasElement.querySelector<HTMLElement>(
        '[data-slot="view-surface"]',
      )!;
      const read = () => [
        ...NEUTRAL_PROBES.map(
          token =>
            `${token}: ${getComputedStyle(surface).getPropertyValue(token).trim()}`,
        ),
        // The type is the host's unless a preset names a stack (T1).
        `font-family: ${getComputedStyle(surface).fontFamily}`,
      ];
      for (const dark of [false, true]) {
        html.classList.toggle('dark', dark);
        const plain = read();
        html.setAttribute('data-fve-preset', 'neutral');
        await expect(read()).toEqual(plain);
        // Another preset on the page, `neutral` pinned on the surface.
        html.setAttribute('data-fve-preset', STORY_PRESET);
        await expect(read()).not.toEqual(plain);
        surface.setAttribute('data-fve-preset', 'neutral');
        await expect(read()).toEqual(plain);
        surface.removeAttribute('data-fve-preset');
        html.removeAttribute('data-fve-preset');
      }
    } finally {
      html.removeAttribute('data-fve-preset');
      html.classList.toggle('dark', hadDark);
      release();
    }
  },
};

/** A day as the card's reading names it; the chart reads it as written. */
const DAY = (n: number) => `9月${n}日`;

/** A trend card whose last day moved from `before` to `after`. */
function ChangeCard({
  before,
  after,
  lowerIsBetter,
}: {
  before: number;
  after: number;
  lowerIsBetter: boolean;
}) {
  return (
    <AnalysisChart
      data={{
        type: 'metric',
        value: after,
        trend: [
          { x: DAY(21), value: before },
          { x: DAY(22), value: after },
        ],
        period: {
          at: DAY(22),
          unit: 'DAY',
          previous: { at: DAY(21), value: before },
          change: { delta: after - before, ratio: (after - before) / before },
        },
      }}
      spec={{
        type: 'metric',
        metric: { metric: 'orders', trend: { x: 'day' }, lowerIsBetter },
      }}
    />
  );
}

/**
 * 涨跌色约定（主题 T1，themes.md 2.6，D35 Q61）：指标卡「较上一期」的徽标。
 *
 * 两张卡，一张涨了、一张跌了，都是往好的方向（跌的那张 `lowerIsBetter`）。不挂
 * 约定、或挂 `semantic` 时按好坏着色，两张都是成功色；宿主在 `<html>` 上写
 * `data-fve-change-colors="red-up"` 后按方向、红涨绿跌：涨的那张是危险色（红），
 * 跌的那张是成功色（绿）；写 `green-up` 时涨绿跌红。每一种约定下两枚徽标都带方向
 * 箭头与正负号——颜色从来不是唯一的线索。组件不知道约定、不重新渲染，只由样式表选色。
 */
export const ChangeColorsOnAMetricCard: Story = {
  render: () => (
    <ViewSurface {...HOST_LANGUAGE} timeZone="UTC">
      <div data-card="rose">
        <ChangeCard before={10} after={12} lowerIsBetter={false} />
      </div>
      <div data-card="fell">
        <ChangeCard before={10} after={6} lowerIsBetter />
      </div>
    </ViewSurface>
  ),
  play: async ({ canvasElement }) => {
    const badge = (card: string) =>
      canvasElement.querySelector<HTMLElement>(
        `[data-card="${card}"] [data-slot="metric-change"] [data-slot="badge"]`,
      )!;
    await waitFor(() => expect(badge('fell')).not.toBeNull());
    const surface = canvasElement.querySelector('[data-slot="view-surface"]')!;
    // The badge eases its colour (`transition-all`); what is measured is
    // where it lands, so the easing is run to its end first — a pane that
    // paints no frames would otherwise hold it at the start.
    const ink = (card: string) => {
      for (const easing of badge(card).getAnimations()) easing.finish();
      return formatRgb(toRgb(parse(getComputedStyle(badge(card)).color)!));
    };
    const cue = (card: string) => ({
      arrow: !!badge(card).querySelector(
        card === 'rose' ? 'svg.lucide-trending-up' : 'svg.lucide-trending-down',
      ),
      sign: badge(card).textContent?.[0],
    });
    // Both read the same way, `formatRgb` clipping to the gamut.
    const token = (name: string) =>
      formatRgb(
        toRgb(parse(getComputedStyle(surface).getPropertyValue(name).trim())!),
      );
    const green = token('--success');
    const red = token('--destructive');
    const html = document.documentElement;
    try {
      for (const [convention, rose, fell] of [
        [undefined, green, green],
        ['red-up', red, green],
        ['green-up', green, red],
        ['semantic', green, green],
      ] as const) {
        if (convention) html.setAttribute('data-fve-change-colors', convention);
        else html.removeAttribute('data-fve-change-colors');
        await waitFor(() =>
          expect({ rose: ink('rose'), fell: ink('fell') }).toEqual({
            rose,
            fell,
          }),
        );
        await expect([cue('rose'), cue('fell')]).toEqual([
          { arrow: true, sign: '+' },
          { arrow: true, sign: '-' },
        ]);
      }
    } finally {
      html.removeAttribute('data-fve-change-colors');
    }
  },
};

/**
 * The popups a reader of this workbench opens, each found by what opens it
 * (the same triggers `PopupsOverRaisedHostLayer` presses). The tooltip goes
 * first: a menu closed by Escape hands the keyboard back to its trigger,
 * whose own tooltip then stays up for as long as it holds focus.
 */
const FONT_POPUPS: readonly {
  name: string;
  slot: string;
  trigger: string;
  /** Opened by pointing at the trigger rather than by pressing it. */
  hover?: boolean;
}[] = [
  {
    name: 'tooltip',
    slot: 'tooltip-content',
    trigger: '[data-slot="tooltip-trigger"]',
    hover: true,
  },
  {
    name: 'select',
    slot: 'select-content',
    // The page-size control, which is why the story pages its rows.
    trigger: '[data-slot="select-trigger"]',
  },
  {
    name: 'menu',
    slot: 'dropdown-menu-content',
    trigger: '[aria-haspopup="menu"]',
  },
  {
    name: 'dialog',
    slot: 'dialog-content',
    trigger: `[aria-label="${zhCN['label.manage.open']}"]`,
  },
];

/**
 * 弹层的字体就是它所在的面的字体（主题 T5 走查）。
 *
 * 弹层 portal 到 `<body>`，按继承拿的是 `<body>` 的字体，不是面的。宿主外壳
 * （`story-app`）把无衬线字体写在应用的外层上，`<body>` 自己不设字体——很多宿主
 * 都这样——于是不带字体栈的预设（`neutral`、`contrast` 等）下，选择框的列表、菜单、
 * 提示和对话框都成了衬线体（宋体／Times）。面把自己算出的字体交给弹层，弹层在
 * 预设没有字体栈时用它；`porcelain` 自带字体栈，弹层同样与面一致。对话框的标题
 * 与视图标题同一个字重（`--_fve-title-weight`，`porcelain` 下是 600）。
 */
const popupsTakeTheSurfaceFont = (preset: 'neutral' | 'porcelain'): Story => ({
  ...DisplayWithData,
  args: { ...DisplayWithData.args, paged: true, preset },
  play: async ({ canvasElement }) => {
    await within(canvasElement).findByRole('table');
    const surface = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-surface"]',
    )!;
    await expect(surface).toHaveAttribute('data-fve-preset', preset);
    const font = getComputedStyle(surface).fontFamily;
    // The premise: the page's body has a type of its own that is not the
    // surface's, so a popup that inherits from the body shows it.
    await expect(getComputedStyle(document.body).fontFamily).not.toBe(font);
    if (preset === 'neutral') {
      // The host's type, from its application frame, not from a preset.
      const frame = surface.closest<HTMLElement>('.story-app')!;
      await expect(font).toBe(getComputedStyle(frame).fontFamily);
    }
    const titleWeight = getComputedStyle(
      canvasElement.querySelector('[data-slot="view-title"]')!,
    ).fontWeight;
    if (preset === 'porcelain') await expect(titleWeight).toBe('600');

    for (const kind of FONT_POPUPS) {
      const trigger = [
        ...surface.querySelectorAll<HTMLElement>(kind.trigger),
      ].find(candidate => getComputedStyle(candidate).pointerEvents !== 'none');
      await expect(trigger, `no ${kind.name} to open`).toBeDefined();
      if (kind.hover) await userEvent.hover(trigger!);
      else await userEvent.click(trigger!);
      const popup = await waitFor(() => {
        const found = document.body.querySelector<HTMLElement>(
          `[data-slot="${kind.slot}"]`,
        );
        if (!found || found.hasAttribute('data-closed'))
          throw new Error(`no open ${kind.name}`);
        return found;
      });
      await expect(popup.closest('[data-slot="view-surface"]')).toBeNull();
      await expect(
        getComputedStyle(popup).fontFamily,
        `the ${kind.name}'s type`,
      ).toBe(font);
      if (kind.slot === 'dialog-content') {
        const title = popup.querySelector('[data-slot="dialog-title"]')!;
        await expect(getComputedStyle(title).fontFamily).toBe(font);
        await expect(
          getComputedStyle(title).fontWeight,
          'the dialog title weighs what the view title does',
        ).toBe(titleWeight);
      }
      if (kind.hover) await userEvent.unhover(trigger!);
      else await userEvent.keyboard('{Escape}');
      await waitFor(() => {
        const leaving = document.body.querySelector(
          `[data-slot="${kind.slot}"]`,
        );
        expect(
          leaving === null || leaving.hasAttribute('data-closed'),
          `the ${kind.name} is still open`,
        ).toBe(true);
      });
    }
  },
});

export const PopupsTakeTheSurfaceFontUnderNeutral: Story =
  popupsTakeTheSurfaceFont('neutral');
export const PopupsTakeTheSurfaceFontUnderPorcelain: Story =
  popupsTakeTheSurfaceFont('porcelain');
