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

import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import {
  Popover,
  PopoverContent,
  PopoverTitle,
  PopoverTrigger,
} from '../src/components/ui/popover.js';
import { PortalThemeExample, portalKinds } from './fixtures/portalTheme.js';

afterEach(cleanup);

it.each(portalKinds)(
  'preserves a local dark marker in the %s portal',
  async kind => {
    const view = render(<PortalThemeExample kind={kind} />);
    const trigger = screen.getByRole(
      kind === 'select' || kind === 'choice' || kind === 'search'
        ? 'combobox'
        : 'button',
      { name: '打开弹层' },
    );
    if (kind === 'tooltip') {
      fireEvent.focus(trigger);
      fireEvent.mouseEnter(trigger);
    } else fireEvent.click(trigger);
    const popup = await screen.findByRole(
      kind === 'menu'
        ? 'menu'
        : kind === 'select' || kind === 'choice' || kind === 'search'
          ? 'listbox'
          : kind === 'tooltip'
            ? 'tooltip'
            : 'dialog',
    );
    expect(view.container.contains(popup)).toBe(false);
    await waitFor(() =>
      expect(popup.closest('[data-theme]')?.getAttribute('data-theme')).toBe(
        'dark',
      ),
    );
  },
);

it('updates a default-open portal and releases its ancestor observer after closing', async () => {
  const draw = (appearance: string) => (
    <div data-theme={appearance}>
      <Popover defaultOpen>
        <PopoverTrigger>默认打开</PopoverTrigger>
        <PopoverContent>
          <PopoverTitle>主题弹层</PopoverTitle>
        </PopoverContent>
      </Popover>
    </div>
  );
  const view = render(draw('dark'));
  const popup = await screen.findByRole('dialog');
  view.rerender(draw('light'));
  await waitFor(() =>
    expect(popup.closest('[data-theme]')).toHaveProperty(
      'dataset.theme',
      'light',
    ),
  );
  const trigger = screen.getByRole('button', { name: '默认打开' });
  const scope = trigger.closest('.fve-root');
  fireEvent.click(trigger);
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  const computed = vi.spyOn(window, 'getComputedStyle');
  view.rerender(draw('dark'));
  await act(async () => {});
  expect(
    computed.mock.calls.filter(([element]) => element === scope),
  ).toHaveLength(0);
  view.unmount();
  computed.mockRestore();
});
