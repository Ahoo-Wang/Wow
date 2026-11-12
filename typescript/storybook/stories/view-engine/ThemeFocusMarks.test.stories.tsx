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

import type { CSSProperties } from 'react';
import type { StoryObj } from '@storybook/react-vite';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import { converter, parse } from 'culori';
import { FOCUS_CARD, FOCUS_PANEL, FOCUS_ROW } from '@/ui/kit/frames';
import displayMeta, {
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/能力/主题与预设/角色/焦点标记',
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

/**
 * 引擎自己的三个焦点配方——表格行、卡片、看板面板（正文获焦时画在面板卡片上）
 * ——读主题的 `focus-width`／`focus-offset`（D76）：contrast 下是 2px，卡片与
 * 面板离边 2px；宿主写 -1px 时卡片与面板压着自己的边；不设时
 * （neutral）是内置的 2px、离边 2px。卡片与面板的光晕是面上的
 * `focus-halo`（contrast 与 neutral 透明，宿主给的那套淡蓝）。
 * 选中行左边的色条（`row-selected-mark`）每一套都画，是面上的主色。
 */
const FOCUS_MARKS = {
  // Width, a card's (and a panel's) offset.
  contrast: { wide: '2px', card: '2px' },
  host: { wide: '2px', card: '-1px' },
  neutral: { wide: '2px', card: '2px' },
} as const;

/** What the `host` case writes on the surface, where a preset wrote it. */
const HOST_FOCUS = {
  '--fve-focus-offset': '-1px',
  '--fve-focus-halo': 'oklch(0.541 0.1928 258.885deg / 15%)',
} as CSSProperties;

const focusMarks = (preset: keyof typeof FOCUS_MARKS): Story => ({
  ...DisplayWithData,
  render: () => (
    <div
      className="fve-root"
      data-fve-preset={preset === 'host' ? undefined : preset}
      data-theme="light"
      style={preset === 'host' ? HOST_FOCUS : undefined}
    >
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
      <div data-testid="panel" className={FOCUS_PANEL}>
        <div data-testid="body" data-panel-body tabIndex={0}>
          滚动体
        </div>
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

    // A panel's body takes the focus; the card round it wears the mark.
    await focus(canvas.getByTestId('body'));
    const panel = canvas.getByTestId('panel');
    await expect(getComputedStyle(panel).outlineWidth).toBe(wide);
    await expect(getComputedStyle(panel).outlineOffset).toBe(expected.card);
    await expect(getComputedStyle(panel).boxShadow).toContain(
      `${halo} 0px 0px 0px 3px`,
    );

    const first = canvas.getByTestId('chosen').querySelector('td')!;
    // The bar down a selected row's edge, in the surface's primary.
    await expect(getComputedStyle(first).backgroundImage).toContain(
      'linear-gradient',
    );
    await expect(colorOf(root, '--_fve-row-selected-mark')).toBe(
      colorOf(root, '--primary'),
    );
  },
});

export const FocusMarksReadTheThemeInContrast: Story = focusMarks('contrast');
export const FocusMarksReadAHostsOwn: Story = focusMarks('host');
export const FocusMarksReadTheThemeInNeutral: Story = focusMarks('neutral');
