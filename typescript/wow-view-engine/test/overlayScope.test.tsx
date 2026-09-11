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

import { useState, type ComponentProps } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '../src/components/ui/popover.js';
import { FilterSelect } from '../src/filter/FilterSelect.js';

afterEach(cleanup);
it('closes nested selects with a retained parent without discarding local drafts', async () => {
  function Draft() {
    const [draft, setDraft] = useState('');
    return (
      <>
        <input
          aria-label="draft"
          value={draft}
          onChange={e => setDraft(e.target.value)}
        />
        <FilterSelect
          label="type"
          options={[{ value: 'count', label: 'Count' }]}
          onValueChange={vi.fn()}
        />
      </>
    );
  }
  function Example({ open }: { open: boolean }) {
    return (
      <Popover open={open}>
        <PopoverTrigger>edit</PopoverTrigger>
        <PopoverContent keepMounted>
          <Draft />
        </PopoverContent>
      </Popover>
    );
  }
  const view = render(<Example open />);
  fireEvent.change(await screen.findByRole('textbox', { name: 'draft' }), {
    target: { value: '-' },
  });
  fireEvent.click(screen.getByRole('combobox', { name: 'type' }));
  expect(await screen.findByRole('listbox')).toBeTruthy();
  view.rerender(<Example open={false} />);
  await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull());
  view.rerender(<Example open />);
  expect(
    (
      (await screen.findByRole('textbox', {
        name: 'draft',
      })) as HTMLInputElement
    ).value,
  ).toBe('-');
  expect(screen.queryByRole('listbox')).toBeNull();
});

it.each(['select', 'search', 'choices', 'menu', 'popover'] as const)(
  'closes %s when the configuration hides without reopening it later',
  async kind => {
    const changed = vi.fn();
    const { OverlayScope } = await import('../src/lib/OverlayScope.js');
    const { FilterSearchSelect } =
      await import('../src/filter/FilterSearchSelect.js');
    const { FilterChoiceSelect } =
      await import('../src/filter/FilterChoiceSelect.js');
    const {
      DropdownMenu,
      DropdownMenuTrigger,
      DropdownMenuContent,
      DropdownMenuItem,
    } = await import('../src/components/ui/dropdown-menu.js');
    function Example({ visible }: { visible: boolean }) {
      const options = [{ value: 'one', label: 'One' }];
      return (
        <OverlayScope visible={visible}>
          {kind === 'select' ? (
            <FilterSelect
              label="open"
              options={options}
              onValueChange={vi.fn()}
            />
          ) : kind === 'search' ? (
            <FilterSearchSelect
              label="open"
              options={options}
              onValueChange={vi.fn()}
            />
          ) : kind === 'choices' ? (
            <FilterChoiceSelect
              label="open"
              options={options}
              values={[]}
              onValuesChange={vi.fn()}
              onOpenChange={changed}
            />
          ) : kind === 'menu' ? (
            <DropdownMenu>
              <DropdownMenuTrigger>open</DropdownMenuTrigger>
              <DropdownMenuContent>
                <DropdownMenuItem>One</DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          ) : (
            <Popover>
              <PopoverTrigger>open</PopoverTrigger>
              <PopoverContent>One</PopoverContent>
            </Popover>
          )}
        </OverlayScope>
      );
    }
    const role =
      kind === 'menu' ? 'menu' : kind === 'popover' ? 'dialog' : 'listbox';
    const view = render(<Example visible />);
    fireEvent.click(
      screen.getByRole(
        kind === 'select' || kind === 'search' || kind === 'choices'
          ? 'combobox'
          : 'button',
        { name: 'open' },
      ),
    );
    expect(await screen.findByRole(role)).toBeTruthy();
    view.rerender(<Example visible={false} />);
    await waitFor(() => expect(screen.queryByRole(role)).toBeNull());
    if (kind === 'choices') expect(changed).toHaveBeenLastCalledWith(false);
    view.rerender(<Example visible />);
    expect(screen.queryByRole(role)).toBeNull();
  },
);

it('respects canceled open changes and a controlled open prop', async () => {
  const callback = vi.fn<
    NonNullable<ComponentProps<typeof Popover>['onOpenChange']>
  >((_open, details) => details.cancel());
  const view = render(
    <Popover onOpenChange={callback}>
      <PopoverTrigger>open</PopoverTrigger>
      <PopoverContent>One</PopoverContent>
    </Popover>,
  );
  fireEvent.click(screen.getByRole('button', { name: 'open' }));
  expect(callback).toHaveBeenCalled();
  expect(screen.queryByRole('dialog')).toBeNull();
  view.rerender(
    <Popover open onOpenChange={callback}>
      <PopoverTrigger>open</PopoverTrigger>
      <PopoverContent>One</PopoverContent>
    </Popover>,
  );
  expect(await screen.findByRole('dialog')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'open' }));
  expect(screen.getByRole('dialog')).toBeTruthy();
});

it('clears a controlled date picker open state when its outer scope hides', async () => {
  const { OverlayScope } = await import('../src/lib/OverlayScope.js');
  const { FilterDatePicker } =
    await import('../src/filter/FilterDatePicker.js');
  function Example({ visible }: { visible: boolean }) {
    return (
      <OverlayScope visible={visible}>
        <FilterDatePicker label="date" onValueChange={vi.fn()} />
      </OverlayScope>
    );
  }
  const view = render(<Example visible />);
  fireEvent.click(screen.getByRole('button', { name: 'date：选择日期' }));
  expect(await screen.findByRole('dialog')).toBeTruthy();
  view.rerender(<Example visible={false} />);
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  view.rerender(<Example visible />);
  expect(screen.queryByRole('dialog')).toBeNull();
});
