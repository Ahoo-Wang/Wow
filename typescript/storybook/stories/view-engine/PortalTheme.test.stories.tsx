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

import '@ahoo-wang/fetcher-view-engine/styles.css';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import {
  PortalThemeExample,
  LivePortalThemeExample,
  type PortalKind,
} from '../../packages/view-engine/test/fixtures/portalTheme.js';

const meta = {
  title: 'View Engine/主题/弹层回归',
  component: PortalThemeExample,
  tags: ['!dev', '!autodocs', 'test'],
  args: { appearance: 'dark' },
} satisfies Meta<typeof PortalThemeExample>;
export default meta;
type Story = StoryObj<typeof meta>;
function story(
  kind: PortalKind,
  appearance: 'dark' | 'light' = 'dark',
  globalAppearance?: 'dark' | 'light',
  darkClass = false,
): Story {
  return {
    args: { kind, appearance, globalAppearance, darkClass },
    play: async ({ canvasElement }) => {
      const canvas = within(canvasElement),
        page = within(canvasElement.ownerDocument.body);
      const scope = canvas.getByTestId('theme-scope');
      const reference = canvas.getByRole('textbox', { name: '宿主输入' });
      const borderReference = canvas.getByTestId('border-reference');
      const trigger = canvas.getByRole(
        kind === 'select' || kind === 'choice' || kind === 'search'
          ? 'combobox'
          : 'button',
        { name: '打开弹层' },
      );
      if (kind === 'tooltip') await userEvent.hover(trigger);
      else await userEvent.click(trigger);
      const popup = await page.findByRole(
        kind === 'menu'
          ? 'menu'
          : kind === 'select' || kind === 'choice' || kind === 'search'
            ? 'listbox'
            : kind === 'tooltip'
              ? 'tooltip'
              : 'dialog',
      );
      await expect(scope.contains(popup)).toBe(false);
      await expect(popup.closest('[data-theme]')).toHaveAttribute(
        'data-theme',
        appearance,
      );
      await expect(getComputedStyle(popup).colorScheme).toBe(appearance);
      if (kind === 'popover' || kind === 'dialog') {
        const input = page.getByRole('textbox', { name: '弹层输入' });
        await waitFor(() =>
          expect(getComputedStyle(input).backgroundColor).toBe(
            getComputedStyle(reference).backgroundColor,
          ),
        );
        await waitFor(() =>
          expect(getComputedStyle(input).borderColor).toBe(
            getComputedStyle(reference).borderColor,
          ),
        );
      }
      if (kind === 'dialog' && appearance === 'dark') {
        await waitFor(() =>
          expect(getComputedStyle(popup).borderColor).toBe(
            getComputedStyle(borderReference).borderColor,
          ),
        );
      }
      if (kind === 'select')
        await userEvent.click(page.getByRole('option', { name: '候选项' }));
      else if (kind === 'tooltip') await userEvent.unhover(trigger);
      else await userEvent.keyboard('{Escape}');
      if (kind === 'select') {
        await expect(trigger).toHaveAttribute('aria-expanded', 'false');
        await waitFor(() => expect(popup).not.toBeVisible());
      } else await waitFor(() => expect(popup).not.toBeInTheDocument());
      scope.setAttribute('data-theme-verified', 'true');
    },
  };
}
export const Popover = story('popover');
export const Dialog = story('dialog');
export const Select = story('select');
export const Menu = story('menu');
export const Tooltip = story('tooltip');
export const Choice = story('choice');
export const Search = story('search');

export const LocalLightInDark = story('dialog', 'light', 'dark');
export const LocalDarkInLight = story('dialog', 'dark', 'light');
export const ExplicitLightOverridesClass = story(
  'dialog',
  'light',
  'dark',
  true,
);

export const ChangesWhileOpen: Story = {
  args: { kind: 'popover' },
  render: () => <LivePortalThemeExample />,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    const reference = canvas.getByRole('textbox', { name: '宿主输入' });
    await userEvent.click(canvas.getByRole('button', { name: '打开弹层' }));
    const popup = await page.findByRole('dialog');
    const input = page.getByRole('textbox', { name: '弹层输入' });
    for (const theme of ['light', 'dark']) {
      await userEvent.click(page.getByRole('button', { name: '切换外观' }));
      await waitFor(() =>
        expect(popup.closest('[data-theme]')).toHaveAttribute(
          'data-theme',
          theme,
        ),
      );
      await expect(getComputedStyle(popup).colorScheme).toBe(theme);
      await waitFor(() =>
        expect(getComputedStyle(input).backgroundColor).toBe(
          getComputedStyle(reference).backgroundColor,
        ),
      );
    }
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(popup).not.toBeInTheDocument());
    canvas
      .getByTestId('theme-scope')
      .setAttribute('data-theme-verified', 'true');
  },
};
