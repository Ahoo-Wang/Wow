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
 *   azure marks the view on screen and a select's chosen item violet.
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
import { expect, userEvent, waitFor, within } from 'storybook/test';
import { converter, parse } from 'culori';
import { FOCUS_CARD, FOCUS_INSET, FOCUS_ROW } from '@/ui/kit/variants';
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
 * 什么都不设：侧栏当前项仍是 `border` 的边与 `shadow-xs`，已选中的选项没有底、
 * 字重与旁边一样，描边按钮悬停时边不变、字是 `foreground`（ui/theme.md「角色：引擎自己的面」）。截图基线逐像素相同是同一件事的像素证据。
 */
export const UnsetMechanismDrawsTheRegistry: Story = {
  ...DisplayWithData,
  args: {
    ...DisplayWithData.args,
    theme: 'light',
    preset: 'neutral',
    paged: true,
  },
  play: async ({ canvasElement }) => {
    const { surface, current } = await workbench(canvasElement);
    // Its colours carry over from the last story's theme (`transition-all`).
    await settled(() => getComputedStyle(current).backgroundColor);
    const style = getComputedStyle(current);
    await expect(rgbOf(style.borderTopColor)).toBe(
      colorOf(surface, '--border'),
    );
    await expect(style.boxShadow).toContain('rgba(0, 0, 0, 0.05) 0px 1px 2px');

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
    await userEvent.keyboard('{Escape}');
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
    theme,
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
    await userEvent.keyboard('{Escape}');
  },
});

export const PorcelainMenuFollowsTheBrandInLight: Story =
  porcelainMenu('light');
export const PorcelainMenuFollowsTheBrandInDark: Story = porcelainMenu('dark');

/**
 * azure 挂在 `<html>` 上、品牌色给在面上：侧栏当前项是选中行的淡紫、主色的字，
 * 没有边也没有浮起；分页大小里已选中的那一项是同一个淡紫、600；描边按钮悬停
 * 时边是主色（字不变：主色在悬停的底上只有 4.17:1）。
 */
const azureMarks = (theme: 'light' | 'dark'): Story => ({
  ...DisplayWithData,
  globals: { fvePreset: 'azure' },
  args: {
    ...DisplayWithData.args,
    theme,
    preset: undefined,
    paged: true,
    tokens: { '--fve-brand': VIOLET },
  },
  play: async ({ canvasElement }) => {
    const { surface, current } = await workbench(canvasElement);
    await expect(document.documentElement).toHaveAttribute(
      'data-fve-preset',
      'azure',
    );
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
    await userEvent.keyboard('{Escape}');
  },
});

export const AzureMarksFollowTheBrandInLight: Story = azureMarks('light');
export const AzureMarksFollowTheBrandInDark: Story = azureMarks('dark');

/**
 * 引擎自己的三个焦点配方——表格行、卡片、看板面板的滚动体——读主题的
 * `focus-width`／`focus-offset`（D76）：contrast 下是 2px，卡片离边 2px，
 * 滚动体画在边内并留 2px 的间隔；azure 也是 2px，卡片压着自己的边（-1px）；
 * 不设时（neutral）仍是 1px 与原来的偏移。卡片与滚动体的光晕是面上的
 * `focus-halo`（contrast 透明，azure 淡蓝，neutral 是 `ring` 的一半）。
 * 选中行左边的色条（`row-selected-mark`）contrast 链到主色，neutral 没有。
 */
const FOCUS_MARKS = {
  // Width, a card's offset, a scrolling body's offset, a bar on the row.
  contrast: { wide: '2px', card: '2px', inset: '-4px', bar: true },
  azure: { wide: '2px', card: '-1px', inset: '-2px', bar: false },
  neutral: { wide: '1px', card: '-1px', inset: '-1px', bar: false },
} as const;

const focusMarks = (preset: keyof typeof FOCUS_MARKS): Story => ({
  ...DisplayWithData,
  render: () => (
    <div className="fve-root" data-fve-preset={preset} data-theme="light">
      <table>
        <tbody>
          <tr data-testid="row" tabIndex={0} className={FOCUS_ROW}>
            <td>一</td>
            <td>二</td>
          </tr>
          <tr data-slot="table-row" data-state="selected" data-testid="chosen">
            <td>三</td>
            <td>四</td>
          </tr>
        </tbody>
      </table>
      <div data-testid="card" tabIndex={0} className={FOCUS_CARD}>
        卡片
      </div>
      <div data-testid="inset" tabIndex={0} className={FOCUS_INSET}>
        滚动体
      </div>
      <div
        data-testid="halo"
        style={{ boxShadow: '0 0 0 3px var(--_fve-focus-halo)' }}
      />
    </div>
  ),
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const root = canvasElement.querySelector<HTMLElement>('.fve-root')!;
    const expected = FOCUS_MARKS[preset];
    const wide = expected.wide;
    // The surface's halo, as the browser serialises a shadow's colour: the
    // card's and the body's 3px ring are drawn in it, as a control's is.
    const halo = getComputedStyle(canvas.getByTestId('halo')).boxShadow.replace(
      / 0px 0px 0px 3px$/,
      '',
    );

    // A key first, so focus moved from here on is the keyboard's
    // (`:focus-visible`); the story shell's own controls come before these
    // in the tab order, so each mark is focused directly.
    await userEvent.tab();
    const focus = async (element: HTMLElement) => {
      element.focus();
      await expect(element.matches(':focus-visible')).toBe(true);
    };
    const row = canvas.getByTestId('row');
    await focus(row);
    const cell = row.querySelector('td')!;
    await settled(() => getComputedStyle(cell).boxShadow);
    await expect(getComputedStyle(cell).boxShadow).toContain(
      `0px ${wide} 0px 0px inset`,
    );

    const card = canvas.getByTestId('card');
    await focus(card);
    await expect(getComputedStyle(card).outlineWidth).toBe(wide);
    await expect(getComputedStyle(card).outlineOffset).toBe(expected.card);
    await expect(getComputedStyle(card).boxShadow).toContain(
      `${halo} 0px 0px 0px 3px`,
    );

    const inset = canvas.getByTestId('inset');
    await focus(inset);
    await expect(getComputedStyle(inset).outlineWidth).toBe(wide);
    await expect(getComputedStyle(inset).outlineOffset).toBe(expected.inset);
    await expect(getComputedStyle(inset).boxShadow).toContain(
      `${halo} 0px 0px 0px 3px inset`,
    );

    const first = canvas.getByTestId('chosen').querySelector('td')!;
    const bar = getComputedStyle(first).backgroundImage;
    if (expected.bar) {
      await expect(bar).toContain('linear-gradient');
      await expect(colorOf(root, '--_fve-row-selected-mark')).toBe(
        colorOf(root, '--primary'),
      );
    } else await expect(bar).toBe('none');
  },
});

export const FocusMarksReadTheThemeInContrast: Story = focusMarks('contrast');
export const FocusMarksReadTheThemeInAzure: Story = focusMarks('azure');
export const FocusMarksKeepTheRegistryInNeutral: Story = focusMarks('neutral');
