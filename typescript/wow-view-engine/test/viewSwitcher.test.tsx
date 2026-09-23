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
 * The sidebar's job done in a header's worth of space (D12 Ⅰ).
 *
 * The switcher only ever appears with the sidebar folded away, so every other
 * suite reaches it through a whole workbench — which says what a collapsed
 * workbench does, not what this control promises. Here it is driven straight,
 * over a list state stood up by hand: the two groups in the sidebar's order,
 * the tag on the views that ship with the definition, which row is checked,
 * and the two entries that are on offer only when a host hands over something
 * behind them.
 */

import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { systemInstanceId } from '../src/index.js';
import type { ViewInstanceSummary } from '../src/index.js';
import type { ViewListState } from '../src/react/index.js';
import { ViewSurface, ViewSwitcher } from '../src/ui/index.js';

afterEach(cleanup);

function summary(
  overrides: Partial<ViewInstanceSummary> & { id: string },
): ViewInstanceSummary {
  return {
    definitionId: 'orders',
    title: overrides.id,
    scope: 'personal',
    kind: 'record',
    revision: '1',
    ...overrides,
  };
}

/** Two personal views, one shared, and the one the definition declares. */
const ITEMS: ViewInstanceSummary[] = [
  summary({ id: 'orders-1', title: 'Mine' }),
  summary({ id: 'orders-2', title: 'Yours' }),
  summary({ id: 'orders-3', title: 'Ours', scope: 'shared' }),
  summary({
    id: systemInstanceId('orders', 'all'),
    title: 'All orders',
    scope: 'system',
  }),
];

function listState(items = ITEMS): ViewListState {
  return {
    items,
    all: items,
    preferences: null,
    permissions: {
      createPersonal: true,
      createShared: true,
      reorder: true,
      setDefault: true,
      instance: () => ({ save: true, rename: true, delete: true }),
    },
    defaultInstanceId: null,
    loading: false,
    error: null,
    preferencesError: null,
    reload: () => {},
  };
}

function switcher(props: Partial<Parameters<typeof ViewSwitcher>[0]> = {}) {
  render(
    <ViewSurface>
      <ViewSwitcher
        list={listState()}
        kind="record"
        currentId="orders-1"
        currentTitle="Mine"
        onOpen={() => {}}
        {...props}
      />
    </ViewSurface>,
  );
}

/** The menu, opened. */
async function openMenu(): Promise<HTMLElement> {
  const user = userEvent.setup();
  await user.click(screen.getByRole('button'));
  return screen.findByRole('menu');
}

describe('the view switcher trigger', () => {
  /**
   * The title is what it shows, so the name has to say what pressing it
   * *does* — a screen reader otherwise hears the view it is on as though
   * pressing it would open that one.
   */
  it('shows the open view and is named for what it does', () => {
    switcher();

    const trigger = screen.getByRole('button', { name: 'Switch view' });
    expect(trigger.dataset.slot).toBe('view-switcher');
    expect(within(trigger).getByText('Mine').getAttribute('data-slot')).toBe(
      'view-switcher-label',
    );
  });

  /**
   * With no view behind it the trigger used to be an icon, a chevron and a
   * gap. It names itself instead — and the visible word is the accessible
   * name, which is what lets it be asked for out loud (WCAG 2.5.3).
   */
  it('names itself where there is no view to name', () => {
    switcher({ currentId: null, currentTitle: '' });

    const trigger = screen.getByRole('button', { name: 'Choose a view' });
    expect(
      within(trigger).getByText('Choose a view').getAttribute('data-slot'),
    ).toBe('view-switcher-label');
    expect(screen.queryByRole('button', { name: 'Switch view' })).toBeNull();
  });
});

describe('the view switcher menu', () => {
  it('shows the two groups in the sidebar’s order, with their views', async () => {
    switcher();
    const menu = await openMenu();

    // Personal above shared, and the system view is a shared one.
    expect(
      within(menu)
        .getAllByRole('menuitemradio')
        .map(item => item.textContent),
    ).toEqual(['Mine', 'Yours', 'Ours', 'All orderssystem']);
    expect(menu.textContent).toContain('My views');
    expect(menu.textContent).toContain('Shared views');
  });

  it('draws each view with the sidebar’s marks: its kind, and the lock', async () => {
    switcher();
    const menu = await openMenu();

    // The folded list reads as the open one: every item leads with its
    // kind, and the view that came with the definition wears the same lock
    // it wears in the sidebar — not a badge of its own.
    const items = within(menu).getAllByRole('menuitemradio');
    for (const item of items) {
      expect(item.querySelector(':scope > svg')).not.toBeNull();
    }
    const system = items.find(item => item.textContent?.includes('All orders'));
    expect(
      system?.querySelector('[data-slot="view-system-tag"] svg'),
    ).not.toBeNull();
    expect(menu.querySelector('[data-slot="badge"]')).toBeNull();
  });

  it('leaves out a group nothing is in', async () => {
    switcher({ list: listState([ITEMS[0], ITEMS[1]]) });
    const menu = await openMenu();

    expect(menu.textContent).toContain('My views');
    expect(menu.textContent).not.toContain('Shared views');
  });

  it('checks the open view, and only that one', async () => {
    switcher();
    const menu = await openMenu();

    expect(
      within(menu)
        .getAllByRole('menuitemradio')
        .filter(item => item.getAttribute('aria-checked') === 'true')
        .map(item => item.textContent),
    ).toEqual(['Mine']);
  });

  it('checks nothing while no view is open', async () => {
    switcher({ currentId: null, currentTitle: '' });
    const menu = await openMenu();

    expect(
      within(menu)
        .getAllByRole('menuitemradio')
        .map(item => item.getAttribute('aria-checked')),
    ).toEqual(['false', 'false', 'false', 'false']);
  });

  /** Choosing goes out through the same door the sidebar knocks on. */
  it('asks for the view that was chosen', async () => {
    const user = userEvent.setup();
    const onOpen = vi.fn();
    switcher({ onOpen });
    const menu = await openMenu();

    await user.click(within(menu).getByRole('menuitemradio', { name: 'Ours' }));

    expect(onOpen).toHaveBeenCalledWith('orders-3');
  });
});

/**
 * Making a view and managing the list are not choosing from it, so they sit
 * apart from the views — and each is absent where a host wired nothing behind
 * it, which is how a user with no write permission is spared an entry whose
 * only lesson is that it leads to a dialog of read-only rows.
 */
describe('what the switcher offers besides the views', () => {
  it('offers neither entry where nothing is behind them', async () => {
    switcher();
    const menu = await openMenu();

    expect(within(menu).queryByRole('menuitem')).toBeNull();
    expect(menu.querySelector('[data-slot="dropdown-menu-separator"]')).toBe(
      null,
    );
  });

  it('adds a view, then manages the list, in that order', async () => {
    const user = userEvent.setup();
    const onCreate = vi.fn();
    const onManage = vi.fn();
    switcher({
      create: { creatable: ['record'], create: onCreate },
      onManage,
    });
    const menu = await openMenu();

    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map(item => item.textContent),
    ).toEqual(['New view', 'Manage views']);

    await user.click(within(menu).getByRole('menuitem', { name: 'New view' }));
    expect(onCreate).toHaveBeenCalledTimes(1);
  });

  it('offers only the one a host handed over', async () => {
    const user = userEvent.setup();
    const onManage = vi.fn();
    switcher({ onManage });
    const menu = await openMenu();

    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map(item => item.textContent),
    ).toEqual(['Manage views']);

    await user.click(
      within(menu).getByRole('menuitem', { name: 'Manage views' }),
    );
    expect(onManage).toHaveBeenCalledTimes(1);
  });
});
