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
 * The mechanism the retunes asked for (ui/theme.md「角色：引擎自己的面」, D76), measured
 * in a browser: what the S8 and S9 retunes could not say with the contract
 * and reported instead of working round.
 *
 * - **Links.** A preset on `<html>` — where a host puts it — cannot write
 *   `var(--primary)`: that is worked out on `<html>`, above the surface, so
 *   a brand colour never reached a role written so. A preset links the role
 *   instead (`--fvp-<role>-link`), and the surface resolves the link. Worn
 *   with a violet brand, porcelain highlights its menu items violet and
 *   a host's own links mark the view on screen and a chosen item violet.
 * - **The view on screen** without an edge or a lift, the **chosen item**
 *   in a tint and a weight, an **outline button** whose edge turns under the
 *   pointer; a **board filter chip** as tall as the theme says is measured
 *   with the board's filters (`DashboardFilters.test.stories.tsx`).
 * - Unset, each of them is what the registry drew.
 *
 * The jsdom side is `test/themeLinks.test.ts` and `test/themeRoles.test.ts`
 * in the package; the brand sweep there holds every pair a link paints.
 */

import type { StoryObj } from '@storybook/react-vite';
import { expect, screen, userEvent, waitFor, within } from 'storybook/test';
import { converter, parse } from 'culori';
import { Button } from '@/ui/components/button';
import { Checkbox } from '@/ui/components/checkbox';
import { Input } from '@/ui/components/input';
import { MixedCheckbox } from '@/ui/kit/MixedCheckbox';
import displayMeta, {
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/能力/主题与预设/角色/机制',
  tags: ['!dev', '!autodocs', 'test'],
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** The brand colour the linked stories wear. */
const VIOLET = '#7c3aed';

const toOklch = converter('oklch');
const toRgb = converter('rgb');

const VIOLET_HUE = toOklch(parse(VIOLET)!)!.h!;

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

/** A colour variable as the cascade resolved it on an element. */
function colorOf(element: Element, variable: string): string {
  const probe = document.createElement('span');
  probe.hidden = true;
  probe.style.setProperty('background-color', `var(${variable})`);
  element.append(probe);
  const value = getComputedStyle(probe).backgroundColor;
  probe.remove();
  return rgbOf(value);
}

/** Whether a painted colour carries the brand's hue. */
function violet(value: string): boolean {
  const color = toOklch(parse(value)!)!;
  return (
    color.c > 0.01 &&
    Math.abs((((color.h ?? 0) - VIOLET_HUE + 540) % 360) - 180) < 8
  );
}

/**
 * Resolves once a transitioning value has stopped changing: the page's
 * transitions over, then two reads 50ms apart that agree (the vendored
 * controls carry `transition-all`). Two reads alone are not enough where
 * the browser paints rarely: Playwright's Linux WebKit drew about one frame
 * a second, a transition stood still at its first frame between the reads,
 * and a neutral grey passed for the brand's violet (2026-09-30).
 */
async function settled(read: () => string): Promise<void> {
  await waitFor(() => {
    const moving = document
      .getAnimations()
      .filter(animation => animation instanceof CSSTransition);
    if (moving.length > 0) throw new Error('A transition is running.');
  });
  await waitFor(async () => {
    const before = read();
    await new Promise(resolve => setTimeout(resolve, 50));
    if (read() !== before) throw new Error('The value is still moving.');
  });
}

/** The workbench's surface and its view on screen, once its rows are in. */
async function workbench(canvasElement: HTMLElement) {
  const table = await within(canvasElement).findByRole('table');
  await waitFor(() =>
    expect(table.querySelectorAll('tbody tr').length).toBeGreaterThan(1),
  );
  const surface = canvasElement.querySelector<HTMLElement>(
    '[data-slot="view-surface"]',
  )!;
  // The view list's open view (a page button says `aria-current` too).
  const current = surface.querySelector<HTMLElement>(
    '[aria-current="true"].fve\\:bg-nav-current',
  )!;
  return { surface, current };
}

/** The first menu a toolbar opens, with its first item under the keyboard. */
async function highlightedMenuItem(
  canvasElement: HTMLElement,
): Promise<HTMLElement> {
  const trigger = [
    ...canvasElement.querySelectorAll<HTMLElement>('[aria-haspopup="menu"]'),
  ].find(candidate => getComputedStyle(candidate).pointerEvents !== 'none')!;
  await userEvent.click(trigger);
  await waitFor(() => {
    if (
      !document.body.querySelector(
        '[data-slot="dropdown-menu-content"]:not([data-closed]) [role^="menuitem"]',
      )
    )
      throw new Error('no open menu');
  });
  await userEvent.keyboard('{ArrowDown}');
  const item = await waitFor(() => {
    const active = document.activeElement as HTMLElement | null;
    if (!active?.matches('[role^="menuitem"]'))
      throw new Error('no item under the keyboard');
    return active;
  });
  await settled(() => getComputedStyle(item).backgroundColor);
  return item;
}

/** The page-size select's chosen option, its list open. */
async function chosenOption(canvasElement: HTMLElement): Promise<HTMLElement> {
  const trigger = await waitFor(() => {
    const found = canvasElement.querySelector<HTMLElement>(
      '[data-slot="select-trigger"]',
    );
    if (!found) throw new Error('no select');
    return found;
  });
  await userEvent.click(trigger);
  const option = await waitFor(() => {
    const found = document.body.querySelector<HTMLElement>(
      '[data-slot="select-item"][data-selected]',
    );
    if (!found) throw new Error('no chosen option');
    return found;
  });
  // The keyboard off it, to a neighbour, so it is chosen and not
  // highlighted: the list opens on the chosen option.
  await waitFor(() => expect(document.activeElement).toBe(option));
  await userEvent.keyboard('{ArrowDown}');
  if (document.activeElement === option) await userEvent.keyboard('{ArrowUp}');
  await waitFor(() => expect(document.activeElement).not.toBe(option));
  await settled(() => getComputedStyle(option).backgroundColor);
  return option;
}

/**
 * Escape, and the popup of `role` hidden. A select's list leaves by an exit
 * transition and then stays mounted under a `hidden` positioner; axe runs
 * after the play function, and in WebKit it read the page-size list
 * mid-close — no longer labelled by its trigger — as an unnamed listbox
 * (2026-09-30 Firefox/WebKit pass). `queryByRole` skips what is hidden.
 */
async function escapeFrom(role: 'listbox' | 'menu'): Promise<void> {
  await userEvent.keyboard('{Escape}');
  await waitFor(() => expect(screen.queryByRole(role)).toBeNull());
}

/**
 * An outline button of the toolbar under the browser's own mouse: a built
 * event puts no real `:hover` on it (`pointerDrag.ts`), and `:hover` is
 * what the rule is written on.
 */
async function hoveredOutlineButton(
  canvasElement: HTMLElement,
): Promise<HTMLElement> {
  const mouse = globalThis.storybookRealMouse;
  if (!mouse) throw new Error('This story needs the runner’s own mouse.');
  const button = [
    ...canvasElement.querySelectorAll<HTMLElement>(
      '.fve\\:group\\/button.fve\\:border-border',
    ),
  ].find(
    candidate =>
      candidate.getClientRects().length > 0 &&
      getComputedStyle(candidate).pointerEvents !== 'none',
  )!;
  const box = button.getBoundingClientRect();
  await mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await waitFor(() => expect(button.matches(':hover')).toBe(true));
  await settled(() => getComputedStyle(button).borderTopColor);
  return button;
}

/** The mouse off the story, so nothing is left under it. */
async function mouseAway(): Promise<void> {
  await globalThis.storybookRealMouse?.away();
}

/**
 * 什么都不设：侧栏当前项是选中行的淡色、主色的字，没有边也没有浮起（D94）；
 * 已选中的选项没有底、字重与旁边一样，描边按钮悬停时边不变、字是
 * `foreground`（ui/theme.md「角色：引擎自己的面」）。
 */
export const UnsetMechanismDrawsItsBuiltInValues: Story = {
  ...DisplayWithData,
  args: {
    ...DisplayWithData.args,
    colorMode: 'light',
    preset: 'neutral',
    paged: true,
    listed: true,
  },
  play: async ({ canvasElement }) => {
    const { surface, current } = await workbench(canvasElement);
    // Its colours carry over from the last story's theme (`transition-all`).
    await settled(() => getComputedStyle(current).backgroundColor);
    const style = getComputedStyle(current);
    await expect(rgbOf(style.backgroundColor)).toBe(
      colorOf(surface, '--_fve-row-selected'),
    );
    await expect(rgbOf(style.color)).toBe(colorOf(surface, '--primary'));
    await expect(style.borderTopColor).toBe('rgba(0, 0, 0, 0)');
    await expect(style.boxShadow).not.toContain('rgba(0, 0, 0, 0.05)');

    const button = await hoveredOutlineButton(canvasElement);
    await expect(rgbOf(getComputedStyle(button).borderTopColor)).toBe(
      colorOf(surface, '--border'),
    );
    await expect(rgbOf(getComputedStyle(button).color)).toBe(
      colorOf(surface, '--foreground'),
    );
    await mouseAway();

    const option = await chosenOption(canvasElement);
    await expect(getComputedStyle(option).backgroundColor).toBe(
      'rgba(0, 0, 0, 0)',
    );
    await expect(getComputedStyle(option).fontWeight).toBe(
      getComputedStyle(option.parentElement!).fontWeight,
    );
    await escapeFrom('listbox');
  },
};

/**
 * porcelain 挂在 `<html>` 上、品牌色给在面上：菜单的高亮项是面上解析出的
 * 主色填色（`primary-fill`）与它的字——紫色。亮色就是按钮的主色；暗色是比
 * 暗色主色深一档的紫、白字（D76），不是浅紫配深字。此前 porcelain 只能写
 * 字面量，品牌色到不了菜单。
 */
const porcelainMenu = (theme: 'light' | 'dark'): Story => ({
  ...DisplayWithData,
  globals: { fvePreset: 'porcelain' },
  args: {
    ...DisplayWithData.args,
    colorMode: theme,
    preset: undefined,
    tokens: { '--fve-brand': VIOLET },
  },
  play: async ({ canvasElement }) => {
    const { surface } = await workbench(canvasElement);
    await expect(document.documentElement).toHaveAttribute(
      'data-fve-preset',
      'porcelain',
    );
    await expect(surface).not.toHaveAttribute('data-fve-preset');
    const item = await highlightedMenuItem(canvasElement);
    const ground = rgbOf(getComputedStyle(item).backgroundColor);
    await expect(violet(ground), ground).toBe(true);
    await expect(ground).toBe(colorOf(surface, '--_fve-primary-fill'));
    const ink = rgbOf(getComputedStyle(item).color);
    await expect(ink).toBe(colorOf(surface, '--_fve-primary-fill-foreground'));
    if (theme === 'light')
      await expect(ground).toBe(colorOf(surface, '--primary'));
    else {
      // A step deeper than the dark primary, under white words.
      await expect(ground).not.toBe(colorOf(surface, '--primary'));
      await expect(toOklch(parse(ground)!)!.l).toBeLessThan(0.55);
      await expect(ink).toBe('rgb(255, 255, 255)');
    }
    await escapeFrom('menu');
  },
});

export const PorcelainMenuFollowsTheBrandInLight: Story =
  porcelainMenu('light');
export const PorcelainMenuFollowsTheBrandInDark: Story = porcelainMenu('dark');

/**
 * 宿主自己的链接（内置预设里没有哪套用这几条，`azure` 用过，已移除，D94），品牌色
 * 给在面上：侧栏当前项是选中行的淡紫、主色的字，没有边也没有浮起；分页大小里已
 * 选中的那一项是同一个淡紫、600；描边按钮悬停时边是主色，字不变。
 */
const HOST_MARKS = {
  '--fve-brand': VIOLET,
  '--fve-nav-current-link': '100%',
  '--fve-nav-current-foreground-link': '100%',
  '--fve-nav-current-edge': 'transparent',
  '--fve-dark-nav-current-edge': 'transparent',
  '--fve-nav-current-shadow': '0 0 #0000',
  '--fve-dark-nav-current-shadow': '0 0 #0000',
  '--fve-item-selected-link': '100%',
  '--fve-item-selected-weight': '600',
  '--fve-outline-hover-edge-link': '100%',
};

const hostMarks = (theme: 'light' | 'dark'): Story => ({
  ...DisplayWithData,
  args: {
    ...DisplayWithData.args,
    colorMode: theme,
    preset: undefined,
    paged: true,
    listed: true,
    tokens: HOST_MARKS,
  },
  play: async ({ canvasElement }) => {
    const { surface, current } = await workbench(canvasElement);
    const tint = colorOf(surface, '--_fve-row-selected');
    await expect(violet(tint), tint).toBe(true);
    // Its colours carry over from the last story's theme (`transition-all`).
    await settled(
      () =>
        `${getComputedStyle(current).backgroundColor} ${getComputedStyle(current).boxShadow}`,
    );
    const style = getComputedStyle(current);
    await expect(rgbOf(style.backgroundColor)).toBe(tint);
    const ink = rgbOf(style.color);
    await expect(violet(ink), ink).toBe(true);
    await expect(ink).toBe(colorOf(surface, '--primary'));
    await expect(style.borderTopColor).toBe('rgba(0, 0, 0, 0)');
    await expect(style.boxShadow).not.toContain('rgba(0, 0, 0, 0.05)');

    const button = await hoveredOutlineButton(canvasElement);
    const edge = rgbOf(getComputedStyle(button).borderTopColor);
    await expect(violet(edge), edge).toBe(true);
    await expect(edge).toBe(colorOf(surface, '--primary'));
    await expect(rgbOf(getComputedStyle(button).color)).toBe(
      colorOf(surface, '--foreground'),
    );
    await mouseAway();

    const option = await chosenOption(canvasElement);
    await expect(rgbOf(getComputedStyle(option).backgroundColor)).toBe(tint);
    await expect(getComputedStyle(option).fontWeight).toBe('600');
    await escapeFrom('listbox');
  },
});

export const HostMarksFollowTheBrandInLight: Story = hostMarks('light');
export const HostMarksFollowTheBrandInDark: Story = hostMarks('dark');

/**
 * 暗色下控件没有自己的淡底（D94）。registry 在暗色里给输入框、勾选框与描边
 * 按钮铺一层 `input` 的 30%；引擎拿掉它，控件立在所在的面上。工具栏上的描边
 * 按钮是主题的控件填色（`control`），不是那层淡底；
 * 勾上的与半选的勾选框仍是主色的填色。
 */
const NO_FILL = 'rgba(0, 0, 0, 0)';

const darkControlsWearNoWash = (preset: 'neutral' | 'porcelain'): Story => ({
  ...DisplayWithData,
  render: () => (
    <div className="fve-root" data-fve-preset={preset} data-theme="dark">
      <Input aria-label="字段" />
      <Checkbox aria-label="勾选" />
      <Checkbox aria-label="已勾" defaultChecked />
      <MixedCheckbox aria-label="部分" indeterminate />
      <Button variant="outline">按钮</Button>
      <div role="toolbar" aria-label="工具栏">
        <Button variant="outline">工具</Button>
      </div>
    </div>
  ),
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const root = canvasElement.querySelector<HTMLElement>('.fve-root')!;
    const fillOf = (element: HTMLElement) =>
      getComputedStyle(element).backgroundColor;
    await expect(fillOf(canvas.getByRole('textbox'))).toBe(NO_FILL);
    await expect(fillOf(canvas.getByRole('checkbox', { name: '勾选' }))).toBe(
      NO_FILL,
    );
    // A ticked box and a mixed one keep their fill: only the wash at rest
    // goes.
    for (const name of ['已勾', '部分'])
      await expect(
        rgbOf(fillOf(canvas.getByRole('checkbox', { name }))),
        name,
      ).toBe(colorOf(root, '--primary'));
    await expect(fillOf(canvas.getByRole('button', { name: '按钮' }))).toBe(
      NO_FILL,
    );
    await expect(
      rgbOf(fillOf(canvas.getByRole('button', { name: '工具' }))),
    ).toBe(colorOf(root, '--_fve-control'));
  },
});

export const DarkControlsWearNoWashInNeutral: Story =
  darkControlsWearNoWash('neutral');
export const DarkControlsWearNoWashInPorcelain: Story =
  darkControlsWearNoWash('porcelain');

/**
 * 主按钮是主色的填色与它的字（`primary-fill`，D94）。亮色就是主色；暗色是主色
 * 自己的色相深一档、白字，所以宿主只改暗色的主色，按钮也跟着走；contrast
 * 说了自己的填色，暗色仍是它的主色配黑字。
 */
const ORANGE = 'oklch(0.8 0.12 30deg)';

const primaryButton = (
  preset: 'neutral' | 'contrast',
  theme: 'light' | 'dark',
  hostPrimary?: string,
): Story => ({
  ...DisplayWithData,
  render: () => (
    <div
      className="fve-root"
      data-fve-preset={preset}
      data-theme={theme}
      style={
        hostPrimary
          ? ({ '--fve-dark-primary': hostPrimary } as React.CSSProperties)
          : undefined
      }
    >
      <Button>应用</Button>
    </div>
  ),
  play: async ({ canvasElement }) => {
    const root = canvasElement.querySelector<HTMLElement>('.fve-root')!;
    const button = within(canvasElement).getByRole('button', { name: '应用' });
    const fill = rgbOf(getComputedStyle(button).backgroundColor);
    const ink = rgbOf(getComputedStyle(button).color);
    await expect(fill).toBe(colorOf(root, '--_fve-primary-fill'));
    await expect(ink).toBe(colorOf(root, '--_fve-primary-fill-foreground'));
    const primary = colorOf(root, '--primary');
    if (theme === 'light' || preset === 'contrast')
      return void (await expect(fill).toBe(primary));
    // A step deeper than the primary, in its hue, under white words.
    const [deep, light] = [fill, primary].map(color => toOklch(parse(color)!)!);
    await expect(deep.l).toBeLessThan(0.56);
    await expect(deep.l).toBeLessThan(light.l);
    await expect(Math.abs(deep.h! - light.h!)).toBeLessThan(6);
    await expect(ink).toBe('rgb(255, 255, 255)');
  },
});

export const PrimaryButtonIsThePrimaryInLight: Story = primaryButton(
  'neutral',
  'light',
);
export const PrimaryButtonIsADeeperFillInDark: Story = primaryButton(
  'neutral',
  'dark',
);
export const PrimaryButtonFollowsAHostsDarkPrimary: Story = primaryButton(
  'neutral',
  'dark',
  ORANGE,
);
export const PrimaryButtonKeepsContrastsPrimaryInDark: Story = primaryButton(
  'contrast',
  'dark',
);
