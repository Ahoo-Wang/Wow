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
 * The theme's roles (theme-architecture.md 4, S3), measured in a browser.
 *
 * - Unset, every role draws what the registry drew: the header and the
 *   totals bands and a selected row are `muted`, the header is set at 500,
 *   a focused control has no outline of its own (the registry's edge and
 *   halo), the controls are 32px and 28px tall.
 * - A host's theme that gives a brand colour and sets roles —
 *   `host-theme/acme.css`, a theme written outside the package — moves each
 *   of those surfaces and nothing beside it: a selected row in the tint its
 *   brand derives, a header band of its own with a heavier weight and a
 *   divider between the columns, a 2px focus outline 2px off the control
 *   and no halo, a step taller controls, a menu linked to the primary its
 *   brand derives.
 *
 * Every story here runs axe (the preview's `a11y` parameter), so a role a
 * theme sets is held to the same accessibility rules as the rest of the
 * surface. The surfaces no other screenshot covers — a highlighted menu
 * item, a tooltip, the scrim, a 2px focus — have pictures of their own
 * (`visual`). The jsdom side is `test/themeRoles.test.ts` in the package.
 */

import type { StoryObj } from '@storybook/react-vite';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import { converter, parse } from 'culori';
import displayMeta, {
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';
import { matchScreenshot } from './screenshot.js';
// The host's own stylesheet, after the package's, as `HostTheme` loads it.
import './host-theme/acme.css';

const meta = {
  ...displayMeta,
  title: 'View Engine/能力/主题与预设/角色/回归',
  tags: ['!dev', '!autodocs', 'test'],
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

const toRgb = converter('rgb');

/** Any CSS colour as `rgb(r, g, b)` or `rgba(r, g, b, a)`, rounded. */
function rgbOf(value: string): string {
  const rgb = toRgb(parse(value)!);
  const channel = (c: number) => Math.round(c * 255);
  const alpha = rgb.alpha ?? 1;
  const channels = `${channel(rgb.r)}, ${channel(rgb.g)}, ${channel(rgb.b)}`;
  return alpha === 1
    ? `rgb(${channels})`
    : `rgba(${channels}, ${alpha.toFixed(2)})`;
}

/**
 * A colour variable as the cascade resolved it on an element, read through a
 * probe's `background-color` so the browser works out any mix.
 */
function colorOf(element: Element, variable: string): string {
  const probe = document.createElement('span');
  probe.hidden = true;
  probe.style.setProperty('background-color', `var(${variable})`);
  element.append(probe);
  const value = getComputedStyle(probe).backgroundColor;
  probe.remove();
  return rgbOf(value);
}

/** Every colour a computed `box-shadow` list names, as `rgbOf` writes it. */
const shadowColors = (value: string): string[] =>
  (value.match(/(?:rgba?|oklab|oklch|color)\([^)]*\)/g) ?? []).map(rgbOf);

/** An element's painted background colour, as `rgbOf` writes it. */
const groundOf = (element: Element) =>
  rgbOf(getComputedStyle(element).backgroundColor);

/**
 * Resolves once a transitioning value has stopped changing: two reads 50ms
 * apart that agree (the vendored controls carry `transition-all`).
 */
async function settled(read: () => string): Promise<void> {
  await waitFor(async () => {
    const before = read();
    await new Promise(resolve => setTimeout(resolve, 50));
    if (read() !== before) throw new Error('The value is still moving.');
  });
}

/**
 * Presses Tab until the element has focus, so `:focus-visible` holds — Tab
 * as far as a toolbar, then the arrows along it, as a keyboard would.
 */
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
  if (document.activeElement === target) return;
  throw new Error('Tab never reached the target.');
}

/** The parts of a record workbench each role paints, once its rows are in. */
async function parts(canvasElement: HTMLElement) {
  const canvas = within(canvasElement);
  const table = await canvas.findByRole('table');
  const surface = canvasElement.querySelector<HTMLElement>(
    '[data-slot="view-surface"]',
  )!;
  await waitFor(() =>
    expect(table.querySelectorAll('tbody tr').length).toBeGreaterThan(1),
  );
  const header = table.querySelector<HTMLElement>('thead')!;
  const heads = [
    ...header.querySelectorAll<HTMLElement>('[data-slot="table-head"]'),
  ];
  // A column with a sort button, not the select-all box's column.
  const named = heads.find(head => head.querySelector('button'))!;
  const totals = table.querySelector<HTMLElement>('tfoot');
  const columns = canvas.getByRole('button', {
    name: zhCN['label.toolbar.columns'],
  });
  return { canvas, table, surface, header, heads, named, totals, columns };
}

/** Ticks the first row and hands it back, once it says it is selected. */
async function selectFirstRow(table: HTMLElement): Promise<HTMLElement> {
  const row = table.querySelector<HTMLElement>('tbody tr')!;
  await userEvent.click(within(row).getByRole('checkbox'));
  await waitFor(() => expect(row).toHaveAttribute('data-state', 'selected'));
  // Away from the row, so the pointer's hover is not what is measured, and
  // past the registry's `transition-colors`.
  await userEvent.unhover(row);
  await settled(() => getComputedStyle(row).backgroundColor);
  return row;
}

/** The heights of every registry button drawn at one of its two steps. */
function buttonHeights(root: HTMLElement, step: 'h-8' | 'h-7'): number[] {
  return [...root.querySelectorAll<HTMLElement>(`.group\\/button.${step}`)]
    .filter(button => button.getClientRects().length > 0)
    .map(button => button.getBoundingClientRect().height);
}

/**
 * 什么都不设的角色就是 registry 原来画的样子（theme-architecture.md 4.5）。
 *
 * neutral 下：表头带、合计带与选中行都是 `muted`，表头字重 500，聚焦的按钮没有
 * 自己的轮廓（仍是 registry 的边与光晕），控件高 32px 与 28px。截图基线逐像素
 * 相同是同一件事的像素证据，这里量的是计算样式。
 */
export const UnsetRolesDrawTheRegistry: Story = {
  ...DisplayWithData,
  args: { ...DisplayWithData.args, theme: 'light', preset: 'neutral' },
  play: async ({ canvasElement }) => {
    const { table, surface, header, named, totals, columns } =
      await parts(canvasElement);
    const muted = colorOf(surface, '--muted');
    await expect(groundOf(header)).toBe(muted);
    if (totals) await expect(groundOf(totals)).toBe(muted);
    await expect(getComputedStyle(named).fontWeight).toBe('500');
    await expect(
      getComputedStyle(named.querySelector('button')!).fontWeight,
    ).toBe('500');
    // The divider is there and draws nothing: a transparent line.
    await expect(getComputedStyle(named).backgroundImage).toContain(
      'rgba(0, 0, 0, 0)',
    );

    const row = await selectFirstRow(table);
    await expect(groundOf(row)).toBe(muted);
    await expect(groundOf(table.querySelector('tbody tr:nth-child(2)')!)).toBe(
      colorOf(surface, '--background'),
    );

    for (const height of buttonHeights(canvasElement, 'h-7'))
      await expect(height).toBe(28);
    for (const height of buttonHeights(canvasElement, 'h-8'))
      await expect(height).toBe(32);

    await tabTo(columns);
    await settled(() => getComputedStyle(columns).boxShadow);
    await expect(getComputedStyle(columns).outlineStyle).toBe('none');
    // The registry's halo: `ring` at 50%.
    await expect(shadowColors(getComputedStyle(columns).boxShadow)).toContain(
      colorOf(surface, '--_fve-focus-halo'),
    );
  },
};

/** What `host-theme/acme.css` sets as colours, as the browser paints it. */
const ACME = {
  light: {
    header: 'rgb(248, 250, 252)',
    divider: 'rgb(203, 213, 225)',
  },
  dark: {
    header: 'rgb(30, 41, 59)',
    divider: 'rgb(71, 85, 105)',
  },
} as const;

/**
 * Its brand, `--fve-brand` and `--fve-dark-brand`, whose hue the derived
 * colours carry in each mode.
 */
const ACME_BRAND = { light: '#0f766e', dark: '#5eead4' } as const;

const toOklch = converter('oklch');

/**
 * How far a painted colour's hue is from the brand's, in degrees — a faint
 * tint, rounded to 8 bits a channel, drifts a few.
 */
function hueOffBrand(painted: string, theme: 'light' | 'dark'): number {
  const hue = (value: string) => toOklch(parse(value)!).h ?? 0;
  const apart = Math.abs(hue(painted) - hue(ACME_BRAND[theme])) % 360;
  return Math.min(apart, 360 - apart);
}

/**
 * 宿主主题设的角色，各自只动它那一块面（theme-architecture.md 4.2）。
 *
 * acme 给了品牌色（`--fve-brand`），设了表头带（底、600 的字重、列分隔线）、
 * 2px 的焦点轮廓离控件 2px 并去掉光晕、高一档的控件（36px 与 30px），菜单高亮
 * 链接到面上的主色与主色的字（`highlight-link`）。选中行是品牌派生的淡色，
 * 高亮是品牌派生的主色，都带品牌的色相；它没设的合计带仍是 `muted`，行仍是
 * 页面的底。
 */
const hostRoles = (theme: 'light' | 'dark'): Story => ({
  ...DisplayWithData,
  args: { ...DisplayWithData.args, theme, preset: 'acme' },
  play: async ({ canvasElement }) => {
    const { table, surface, header, heads, named, totals, columns } =
      await parts(canvasElement);
    const want = ACME[theme];
    await expect(surface).toHaveAttribute('data-fve-preset', 'acme');

    await expect(groundOf(header)).toBe(want.header);
    // A role the theme leaves alone still reads its token.
    if (totals)
      await expect(groundOf(totals)).toBe(colorOf(surface, '--muted'));
    await expect(getComputedStyle(named).fontWeight).toBe('600');
    await expect(
      getComputedStyle(named.querySelector('button')!).fontWeight,
    ).toBe('600');
    // A divider at every column's end but the last one drawn before the
    // filler, which takes the table's spare width.
    await expect(getComputedStyle(named).backgroundImage).toContain(
      want.divider,
    );
    const last = heads.filter(head => head.dataset.column !== 'filler').at(-1)!;
    await expect(getComputedStyle(last).backgroundImage).toBe('none');

    // The selected row is the tint the brand derives, not `muted`.
    const row = await selectFirstRow(table);
    const selected = colorOf(surface, '--_fve-row-selected');
    await expect(groundOf(row)).toBe(selected);
    await expect(selected).not.toBe(colorOf(surface, '--muted'));
    await expect(hueOffBrand(selected, theme)).toBeLessThan(10);
    await expect(groundOf(table.querySelector('tbody tr:nth-child(2)')!)).toBe(
      colorOf(surface, '--background'),
    );

    // A step taller, and still over the 24px a target needs (WCAG 2.5.8).
    for (const height of buttonHeights(canvasElement, 'h-7'))
      await expect(height).toBe(30);
    for (const height of buttonHeights(canvasElement, 'h-8'))
      await expect(height).toBe(36);

    await tabTo(columns);
    await settled(() => getComputedStyle(columns).outlineColor);
    const focused = getComputedStyle(columns);
    await expect(focused.outlineStyle).toBe('solid');
    await expect(focused.outlineWidth).toBe('2px');
    await expect(focused.outlineOffset).toBe('2px');
    await expect(rgbOf(focused.outlineColor)).toBe(colorOf(surface, '--ring'));
    // The halo is taken away: what is left of it is transparent.
    await expect(shadowColors(focused.boxShadow)).not.toContain(
      colorOf(surface, '--ring')
        .replace('rgb(', 'rgba(')
        .replace(')', ', 0.50)'),
    );

    // The menu highlights with the surface's primary and its ink, by a link:
    // the primary the brand derives, in either mode.
    const trigger = [
      ...canvasElement.querySelectorAll<HTMLElement>('[aria-haspopup="menu"]'),
    ].find(candidate => getComputedStyle(candidate).pointerEvents !== 'none')!;
    await userEvent.click(trigger);
    const item = await waitFor(() => {
      const found = document.body.querySelector<HTMLElement>(
        '[data-slot="dropdown-menu-content"]:not([data-closed]) [role^="menuitem"]',
      );
      if (!found) throw new Error('no open menu');
      return found;
    });
    await userEvent.keyboard('{ArrowDown}');
    const highlighted = await waitFor(() => {
      const active = document.activeElement as HTMLElement | null;
      if (!active?.matches('[role^="menuitem"]'))
        throw new Error('no item under the keyboard');
      return active;
    });
    await expect(item.closest('.fve-root')).toHaveAttribute(
      'data-fve-preset',
      'acme',
    );
    await settled(() => getComputedStyle(highlighted).backgroundColor);
    const primary = colorOf(surface, '--primary');
    await expect(hueOffBrand(primary, theme)).toBeLessThan(10);
    await expect(groundOf(highlighted)).toBe(primary);
    await expect(rgbOf(getComputedStyle(highlighted).color)).toBe(
      colorOf(surface, '--primary-foreground'),
    );
    await userEvent.keyboard('{Escape}');
  },
});

export const HostRolesInLightTheme: Story = hostRoles('light');
export const HostRolesInDarkTheme: Story = hostRoles('dark');

/** The open popup of one slot, laid out, once it is. */
async function openPopup(slot: string): Promise<HTMLElement> {
  return waitFor(() => {
    const found = document.body.querySelector<HTMLElement>(
      `[data-slot="${slot}"]`,
    );
    if (!found || found.hasAttribute('data-closed'))
      throw new Error(`no open ${slot}`);
    const box = found.getBoundingClientRect();
    if (box.width === 0 || box.height === 0)
      throw new Error(`the ${slot} has no box yet`);
    return found;
  });
}

/**
 * 截图里原来没有的三块面，各留一张基线（theme-architecture.md 4.5）：
 * 菜单的高亮项、提示框、对话框背后的遮罩——neutral，什么角色都不设，
 * 以后的重调批改它们时才有「改前」可比。
 */
export const HighlightedMenuItem: Story = {
  ...DisplayWithData,
  name: '菜单高亮（截图）',
  tags: ['visual'],
  args: { ...DisplayWithData.args, theme: 'light', preset: 'neutral' },
  play: async ({ canvasElement }) => {
    await parts(canvasElement);
    const trigger = [
      ...canvasElement.querySelectorAll<HTMLElement>('[aria-haspopup="menu"]'),
    ].find(candidate => getComputedStyle(candidate).pointerEvents !== 'none')!;
    await userEvent.click(trigger);
    const menu = await openPopup('dropdown-menu-content');
    await userEvent.keyboard('{ArrowDown}');
    await waitFor(() =>
      expect(
        (document.activeElement as HTMLElement | null)?.matches(
          '[role^="menuitem"]',
        ),
      ).toBe(true),
    );
    await matchScreenshot(menu, 'role-menu-highlight');
    await userEvent.keyboard('{Escape}');
  },
};

export const TooltipChip: Story = {
  ...DisplayWithData,
  name: '提示框（截图）',
  tags: ['visual'],
  args: { ...DisplayWithData.args, theme: 'light', preset: 'neutral' },
  play: async ({ canvasElement }) => {
    await parts(canvasElement);
    const trigger = [
      ...canvasElement.querySelectorAll<HTMLElement>(
        '[data-slot="tooltip-trigger"]',
      ),
    ].find(candidate => getComputedStyle(candidate).pointerEvents !== 'none')!;
    await userEvent.hover(trigger);
    const tooltip = await openPopup('tooltip-content');
    await matchScreenshot(tooltip, 'role-tooltip');
    await userEvent.unhover(trigger);
  },
};

export const DialogScrim: Story = {
  ...DisplayWithData,
  name: '遮罩（截图）',
  tags: ['visual'],
  args: { ...DisplayWithData.args, theme: 'light', preset: 'neutral' },
  play: async ({ canvasElement }) => {
    await parts(canvasElement);
    await userEvent.click(
      canvasElement.querySelector<HTMLElement>(
        `[aria-label="${zhCN['label.manage.open']}"]`,
      )!,
    );
    const dialog = await openPopup('dialog-content');
    const scrim = document.body.querySelector<HTMLElement>(
      '[data-slot="dialog-overlay"]',
    )!;
    await expect(groundOf(scrim)).toBe('rgba(0, 0, 0, 0.10)');
    await matchScreenshot(scrim, 'role-scrim');
    await expect(dialog).toBeVisible();
    await userEvent.keyboard('{Escape}');
  },
};

/** 2px 的焦点轮廓（acme 设的角色），连同它离控件的 2px。 */
export const FocusOutline: Story = {
  ...DisplayWithData,
  name: '2px 焦点（截图）',
  tags: ['visual'],
  args: { ...DisplayWithData.args, theme: 'light', preset: 'acme' },
  play: async ({ canvasElement }) => {
    const { columns } = await parts(canvasElement);
    await tabTo(columns);
    await settled(() => getComputedStyle(columns).outlineColor);
    await expect(getComputedStyle(columns).outlineWidth).toBe('2px');
    // Focus opens the button's tooltip, which the picture's top edge cuts:
    // waited for, so the picture never catches it on its way in.
    await openPopup('tooltip-content');
    await matchScreenshot(
      columns.closest<HTMLElement>('[data-slot="result-toolbar"]')!,
      'role-focus-2px',
    );
  },
};
