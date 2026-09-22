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
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type ViewPermissions,
} from '../src/index.js';
import { RecordWorkbench } from '../src/ui/index.js';
import { ordersDefinition, testSource } from './fixtures.js';

afterEach(cleanup);

/**
 * A view made from nothing, from the work area to the list.
 *
 * The definition declares no system view and the store holds none, which is
 * the screen a host meets on its first day: before this there was nothing
 * to open and nothing to say so, and the work area was blank.
 */
function setup(permissions?: () => ViewPermissions) {
  const store = new MemoryViewStore({ instances: [], permissions });
  const engine = new ViewEngine({
    definitions: [ordersDefinition({ views: [] })],
    store,
    resolveSource: () => testSource(),
  });
  return { engine, store };
}

const readOnly = (): ViewPermissions => ({
  createPersonal: false,
  createShared: false,
  reorder: false,
  setDefault: false,
  instance: () => ({ save: false, rename: false, delete: false }),
});

/** The empty work area, which is where the first view is made from. */
function workArea(): HTMLElement {
  const found = document.querySelector<HTMLElement>('[data-slot="view-none"]');
  if (!found) throw new Error('the work area is not empty');
  return found;
}

describe('a new view, from the work area to the list', () => {
  it('says the definition has no view yet, and offers one', async () => {
    const { engine } = setup();
    render(<RecordWorkbench engine={engine} definitionId="orders" />);

    await waitFor(() => workArea());
    expect(within(workArea()).getByText('No view yet')).toBeDefined();
    expect(within(workArea()).getByText(/Make one to start/)).toBeDefined();
    expect(
      within(workArea()).getByRole('button', { name: 'New view' }),
    ).toBeDefined();

    // The sidebar says the same fact in one quiet line and nothing else
    // (user, 2026-09-22): the `+` in its heading is the only way in it
    // draws, so the state is not stated twice in two rooms.
    const sidebar = screen.getByRole('navigation', { name: 'Orders' });
    const body = sidebar.querySelector<HTMLElement>(
      '[data-slot="view-list-body"]',
    )!;
    expect(
      body.querySelector('[data-slot="view-list-empty"]')?.textContent,
    ).toBe('No view yet');
    expect(within(body).queryByRole('button')).toBeNull();
    expect(within(body).queryByText(/Make one to start/)).toBeNull();
    // And the `+` is still there, on the same command as the work area's.
    expect(
      within(
        sidebar.querySelector<HTMLElement>('[data-slot="view-list-header"]')!,
      ).getByRole('button', { name: 'New view' }),
    ).toBeDefined();
  });

  it('opens it unsaved with its editor out, then names it on the first save', async () => {
    const { engine, store } = setup();
    render(<RecordWorkbench engine={engine} definitionId="orders" />);
    await waitFor(() => workArea());

    fireEvent.click(
      within(workArea()).getByRole('button', { name: 'New view' }),
    );

    // On screen at once, marked as never saved, with the conditions open:
    // a view with nothing in it yet is a view about to be shaped.
    expect(
      await screen.findByRole('heading', { level: 2, name: 'New view' }),
    ).toBeDefined();
    expect(screen.getByText('Not saved yet')).toBeDefined();
    expect(await screen.findByRole('button', { name: /Apply/ })).toBeDefined();
    expect(document.querySelector('[data-slot="view-none"]')).toBeNull();
    // Rows come too: the default config runs on opening.
    await waitFor(() =>
      expect(screen.getAllByRole('row').length).toBeGreaterThan(1),
    );

    // Saving is creating, so the first save asks the copy's two questions.
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    const dialog = await screen.findByRole('dialog', {
      name: 'Save this view',
    });
    const title = within(dialog).getByRole('textbox', { name: 'Title' });
    expect((title as HTMLInputElement).value).toBe('New view');
    fireEvent.change(title, { target: { value: 'Big ones' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));

    // What the store took is what the screen shows, listed and open.
    expect(
      await screen.findByRole('heading', { level: 2, name: 'Big ones' }),
    ).toBeDefined();
    expect(screen.queryByText('Not saved yet')).toBeNull();
    const sidebar = screen.getByRole('navigation', { name: 'Orders' });
    expect(
      await within(sidebar).findByRole('button', { name: /Big ones/ }),
    ).toBeDefined();
    expect((await store.list('orders')).map(item => item.title)).toEqual([
      'Big ones',
    ]);
  });

  it('offers it from the switcher while the list is folded away', async () => {
    const { engine } = setup();
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        defaultSidebarOpen={false}
      />,
    );
    await waitFor(() => workArea());

    // With nothing open the switcher wears its placeholder as its name.
    fireEvent.click(screen.getByRole('button', { name: 'Choose a view' }));
    const item = await screen.findByRole('menuitem', { name: 'New view' });
    fireEvent.click(item);

    expect(
      await screen.findByRole('heading', { level: 2, name: 'New view' }),
    ).toBeDefined();
  });

  it('offers nothing to a reader who may not create', async () => {
    const { engine } = setup(readOnly);
    render(<RecordWorkbench engine={engine} definitionId="orders" />);

    await waitFor(() => workArea());
    expect(within(workArea()).getByText('No view yet')).toBeDefined();
    expect(screen.queryByRole('button', { name: 'New view' })).toBeNull();
    expect(screen.queryByText(/Make one to start/)).toBeNull();
    // The sidebar's line says there are none either way: it is the count,
    // not an offer, so nothing about it turns on the permission.
    expect(
      document.querySelector('[data-slot="view-list-empty"]')?.textContent,
    ).toBe('No view yet');
  });
});
