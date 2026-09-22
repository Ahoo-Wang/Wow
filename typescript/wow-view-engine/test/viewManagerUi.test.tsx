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
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  ViewStoreError,
  type ViewAudience,
  type ViewInstance,
} from '../src/index.js';
import { defaultMessages } from '../src/ui/messages.js';
import { manageDragAccessibility, managerDrop } from '../src/ui/manage/drag.js';
import { DataWorkbench } from '../src/ui/DataWorkbench.js';
import { formattersFor } from './fixtures/columns.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';
import {
  dataColumnHeaders,
  mine,
  setup as workbenchSetup,
} from './fixtures/ui.js';
import {
  announcement,
  groupsOfRows,
  handle,
  manage,
  permitting,
  row,
  rows,
  setup,
  Sidebar,
  standalone,
} from './fixtures/manager.js';
import { landed, tracked } from './fixtures/writes.js';

afterEach(cleanup);

describe('the manage button on the view list', () => {
  it('is there only when the list was given a way in', async () => {
    const { engine } = setup();
    render(<Sidebar engine={engine} withManager={false} />);
    await screen.findByRole('button', { name: /Mine/ });

    expect(screen.queryByRole('button', { name: 'Manage views' })).toBeNull();
  });

  it('opens the manager, grouped as the list is', async () => {
    const { engine } = setup();
    await manage(engine);

    const dialog = screen.getByRole('dialog');
    expect(dialog.textContent).toContain('My views');
    expect(dialog.textContent).toContain('Shared views');
    // A system view is a shared view, tagged with where it came from.
    expect(row('All orders').textContent).toContain('system');
  });
});

describe('ViewManager rows', () => {
  /**
   * A manager is a list of these fields, and "Title" is the same answer on
   * every one of them: a reader tabbing down the list was told it as many
   * times as there are views, and never which view was under the cursor.
   */
  it('names the rename field after the view it renames', async () => {
    const { engine } = setup();
    await manage(engine);

    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Rename' }),
    );

    expect(within(row('Mine')).getByLabelText('Rename Mine')).toBeDefined();
  });

  it('renames a view in place', async () => {
    const { engine, store } = setup();
    await manage(engine);

    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Rename' }),
    );
    fireEvent.change(within(row('Mine')).getByLabelText(/^Rename /), {
      target: { value: 'Renamed' },
    });
    fireEvent.click(
      within(row('Renamed')).getByRole('button', { name: 'Save the title' }),
    );

    await landed(store);
    expect((await store.get('orders-1')).title).toBe('Renamed');
  });

  it('keeps the title when the rename is called off', async () => {
    const { engine, store } = setup();
    await manage(engine);

    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Rename' }),
    );
    fireEvent.change(within(row('Mine')).getByLabelText(/^Rename /), {
      target: { value: 'Never mind' },
    });
    fireEvent.click(
      within(row('Never mind')).getByRole('button', {
        name: 'Keep the title',
      }),
    );

    expect(row('Mine')).toBeDefined();
    expect((await store.get('orders-1')).title).toBe('Mine');
  });

  /**
   * The rename field takes the keyboard for both of its answers. Enter was
   * the one that did nothing: the ✓ beside the field was the only way to
   * commit, which is not how any other single-field edit on this screen
   * behaves — `SaveAsDialog` has taken Enter all along.
   */
  it('renames on Enter, by the same path as the button', async () => {
    const { engine, store } = setup();
    await manage(engine);

    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Rename' }),
    );
    const field = within(row('Mine')).getByLabelText(/^Rename /);
    // Whitespace and all: Enter trims what the button trims.
    fireEvent.change(field, { target: { value: '  Renamed  ' } });
    fireEvent.keyDown(field, { key: 'Enter' });

    await landed(store);
    expect((await store.get('orders-1')).title).toBe('Renamed');
  });

  /** And refuses the empty name the ✓ is disabled for. */
  it('writes nothing when Enter is pressed on an empty name', async () => {
    const { engine, store } = setup();
    const rename = vi.spyOn(store, 'rename');
    await manage(engine);

    // Held rather than looked up again: the row is about to hold a name no
    // lookup by title could find, which is the whole of what is being tested.
    const target = row('Mine');
    fireEvent.click(within(target).getByRole('button', { name: 'Rename' }));
    const field = within(target).getByLabelText(/^Rename /);
    fireEvent.change(field, { target: { value: '   ' } });
    fireEvent.keyDown(field, { key: 'Enter' });

    expect(rename).not.toHaveBeenCalled();
    // And the row is still being renamed, rather than silently dropped.
    expect(within(target).getByLabelText(/^Rename /)).toBeDefined();
  });

  /**
   * Escape drops the rename and stops there. It used to reach the manager's
   * own dismiss handler on `document` and close the whole dialog, taking the
   * edit with it — one key, two undos, only one of them asked for.
   */
  it('drops the rename on Escape and leaves the manager open', async () => {
    const { engine, store } = setup();
    await manage(engine);

    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Rename' }),
    );
    fireEvent.change(within(row('Mine')).getByLabelText(/^Rename /), {
      target: { value: 'Never mind' },
    });
    fireEvent.keyDown(within(row('Never mind')).getByLabelText(/^Rename /), {
      key: 'Escape',
    });

    expect(screen.getByRole('dialog')).toBeDefined();
    expect(rows()).toHaveLength(4);
    expect(row('Mine')).toBeDefined();
    expect((await store.get('orders-1')).title).toBe('Mine');
  });

  it('deletes after a confirmation', async () => {
    const { engine, store } = setup();
    await manage(engine);

    fireEvent.click(
      within(row('Yours')).getByRole('button', { name: 'Delete' }),
    );
    const dialog = await screen.findByRole('alertdialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));

    await landed(store);
    expect((await store.list('orders')).map(item => item.id)).not.toContain(
      'orders-2',
    );
  });

  /**
   * And leaves the keyboard somewhere. The button the confirmation was
   * opened from belonged to the row the delete just took away, so returning
   * focus to it returned it to nothing and left the user on `<body>` with
   * the manager still open around them.
   */
  it('puts focus on the manager’s heading after a delete', async () => {
    const { engine, store } = setup();
    await manage(engine);

    fireEvent.click(
      within(row('Yours')).getByRole('button', { name: 'Delete' }),
    );
    const dialog = await screen.findByRole('alertdialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));

    await landed(store);
    expect((await store.list('orders')).map(item => item.id)).not.toContain(
      'orders-2',
    );
    const heading = screen.getByText('Manage views');
    await waitFor(() => expect(document.activeElement).toBe(heading));
  });

  /** The same when the confirmation is called off rather than taken. */
  it('puts focus on the heading when a delete is called off', async () => {
    const { engine } = setup();
    await manage(engine);

    fireEvent.click(
      within(row('Yours')).getByRole('button', { name: 'Delete' }),
    );
    const dialog = await screen.findByRole('alertdialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Keep it' }));

    const heading = screen.getByText('Manage views');
    await waitFor(() => expect(document.activeElement).toBe(heading));
  });

  /**
   * The question names the view, because the dialog covers the row it is
   * about: the confirmation opens over the manager's list, and "this view"
   * then pointed at a row the reader could no longer see — one of several,
   * all of which the same button opens the same dialog from.
   */
  it('names the view it is about in the question', async () => {
    const { engine } = setup();
    await manage(engine);

    fireEvent.click(
      within(row('Yours')).getByRole('button', { name: 'Delete' }),
    );

    const dialog = await screen.findByRole('alertdialog');
    expect(within(dialog).getByRole('heading').textContent).toBe(
      'Delete “Yours”?',
    );
  });

  /**
   * The answer that deletes is a *solid* destructive button, not the
   * registry's 10% wash: the wash leaves `--destructive` reading against a
   * surface it has been lightened towards, which measured 3.97:1 at 14px —
   * under 1.4.3's 4.5. Pinned by the variant's classes rather than by
   * colour, because jsdom loads no stylesheet; the browser stories
   * `DeleteActionContrastIn*Theme` are where the ratio itself is measured.
   */
  it('confirms with a solid destructive button', async () => {
    const { engine } = setup();
    await manage(engine);

    fireEvent.click(
      within(row('Yours')).getByRole('button', { name: 'Delete' }),
    );

    const dialog = await screen.findByRole('alertdialog');
    const confirm = within(dialog).getByRole('button', { name: 'Delete' });
    // It is the package's one destructive answer (`DestructiveAction`),
    // which says so on itself; the solid fill that answer wears is asserted
    // once, where the recipe lives (`test/variants.test.tsx`), and measured
    // for real by `DeleteActionContrastIn*Theme`.
    expect(confirm.getAttribute('data-tone')).toBe('danger');
  });

  /**
   * The consequences are composed, not written out four times: the base
   * sentence always, and the two that depend on this view only when they do.
   */
  it('says only the consequences that apply', async () => {
    const { engine } = setup();
    await standalone(engine, 'orders-1');

    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Delete' }),
    );
    const mine = await screen.findByRole('alertdialog');
    // The open view, with edits: personal, so no word about other people.
    expect(mine.textContent).toContain('Only the view is removed');
    expect(mine.textContent).toContain('Unsaved changes go with it.');
    expect(mine.textContent).not.toContain('Everyone who uses it');
  });

  it("warns that a shared view is somebody else's too", async () => {
    const { engine } = setup();
    await standalone(engine, 'orders-1');

    fireEvent.click(
      within(row('Ours')).getByRole('button', { name: 'Delete' }),
    );
    const shared = await screen.findByRole('alertdialog');
    expect(shared.textContent).toContain('Everyone who uses it loses it.');
    // Not the open view, so nothing unsaved goes with it.
    expect(shared.textContent).not.toContain('Unsaved changes');
  });

  /**
   * The order is dragged, and the handle takes the arrow keys for the same
   * move — the one path a pointer cannot walk, and the only one jsdom can:
   * `@dnd-kit/dom` picks its drop target by measuring boxes against each
   * other, and every box here is 0×0 at the origin. The pointer half is a
   * browser story (`DataWorkbench.test.stories.tsx`, `ManageViews`).
   */
  it('moves a view one place from the arrow keys on its handle', async () => {
    const { engine, store } = setup();
    await manage(engine);

    expect(rows()[0]).toContain('Mine');
    fireEvent.keyDown(handle('Mine'), { key: 'ArrowDown' });

    // The whole definition's order goes to the store, not the one pair that
    // moved: the store keeps one list per definition.
    await landed(store);
    expect((await store.getPreferences('orders')).order).toEqual([
      'system:orders:all',
      'orders-2',
      'orders-1',
      'orders-3',
    ]);
    await waitFor(() => expect(rows()[0]).toContain('Yours'));
    // And it is said once, where a reader who cannot see the list will hear
    // it: counted inside the group the row is drawn in.
    expect(announcement()).toBe('Mine moved to position 2 of 2');
  });

  /**
   * The dialog draws two groups, personal above shared, and a row moves
   * within the one it is drawn in. Across the boundary there is nothing to
   * see: the order would be stored again, the revision spent, and the rows
   * would sit exactly where they were.
   */
  it('moves within the audience group the row is drawn in', async () => {
    const { engine, store } = setup();
    await manage(engine);

    // Personal first, shared after, whatever the stored order is.
    expect(rows().map(text => text.replace(/\s+/g, ' ').trim())).toEqual([
      expect.stringContaining('Mine'),
      expect.stringContaining('Yours'),
      expect.stringContaining('All orders'),
      expect.stringContaining('Ours'),
    ]);

    // Each audience is a sortable list of its own, so a row of the other one
    // is not a drop target at all.
    expect(groupsOfRows()).toEqual([
      [expect.stringContaining('Mine'), 'personal'],
      [expect.stringContaining('Yours'), 'personal'],
      [expect.stringContaining('All orders'), 'shared'],
      [expect.stringContaining('Ours'), 'shared'],
    ]);

    // The ends of a group: the key is taken, and nothing is written.
    const setPreferences = vi.spyOn(store, 'setPreferences');
    for (const [title, key] of [
      ['Mine', 'ArrowUp'],
      ['Yours', 'ArrowDown'],
      ['All orders', 'ArrowUp'],
      ['Ours', 'ArrowDown'],
    ] as const)
      fireEvent.keyDown(handle(title), { key });
    await waitFor(() => expect(rows()).toHaveLength(4));
    expect(setPreferences).not.toHaveBeenCalled();

    // The shared view above it is the system one, three rows away in the
    // stored order and the row above it on screen.
    fireEvent.keyDown(handle('Ours'), { key: 'ArrowUp' });

    await landed(store);
    expect((await store.getPreferences('orders')).order[0]).toBe('orders-3');
    await waitFor(() => expect(rows()[2]).toContain('Ours'));
    // The personal group never moved.
    expect(rows()[0]).toContain('Mine');
    expect(rows()[1]).toContain('Yours');
  });

  /**
   * Nothing on screen moves before the store has taken the order — the
   * optimistic plugin is off and the list catches up on the reload — so a
   * refused write has nothing to roll back. What it must not do is stay
   * silent about it: the line belongs to the list rather than to the row,
   * because the order and the default are one record.
   */
  it('says a refused reorder happened and leaves the stored order alone', async () => {
    const { engine, store } = setup();
    vi.spyOn(store, 'setPreferences').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'offline'),
    );
    await manage(engine);

    fireEvent.keyDown(handle('Mine'), { key: 'ArrowDown' });

    expect(await screen.findByText('The result never came back')).toBeDefined();
    expect(rows()[0]).toContain('Mine');
    expect(rows()[1]).toContain('Yours');
    await expect(store.getPreferences('orders')).resolves.toMatchObject({
      order: [],
    });
    // A move that never landed says nothing about where the row went.
    expect(announcement()).toBe('');
  });

  it('chooses and unchooses the view that opens first', async () => {
    const { engine, store } = setup();
    await manage(engine);

    fireEvent.click(
      within(row('Yours')).getByRole('button', {
        name: 'Open this one first',
      }),
    );
    await landed(store);
    expect((await store.getPreferences('orders')).defaultInstanceId).toBe(
      'orders-2',
    );
    await waitFor(() => expect(row('Yours').textContent).toContain('Default'));

    fireEvent.click(
      within(row('Yours')).getByRole('button', {
        name: 'Stop opening this one first',
      }),
    );
    await landed(store);
    expect((await store.getPreferences('orders')).defaultInstanceId).toBeNull();
  });

  /**
   * A row's two jobs are in two places: where the view sits in the list is
   * the handle it is carried by, at the head of the row where a reader looks
   * for one, and what is to become of it is one `ButtonGroup` at the end.
   * The five ghost buttons 2px apart this started as — a step that is on
   * none of the package's four — read as one run of icons doing one job.
   *
   * What each row *has* is still decided by what the store would take, which
   * is why the system row's group is one button rather than three greyed-out
   * ones.
   */
  it('keeps a row’s order handle apart from what becomes of it', async () => {
    const { engine } = setup();
    await manage(engine);

    const names = (title: string) =>
      within(
        within(row(title)).getByRole('group', {
          name: 'What to do with this view',
        }),
      )
        .getAllByRole('button')
        .map(button => button.getAttribute('aria-label'));

    expect(handle('Mine')).toBeDefined();
    expect(names('Mine')).toEqual(['Open this one first', 'Rename', 'Delete']);

    // A system view ships with the definition, so the only thing to be done
    // with it is to choose whether it opens first — and it is ordered like
    // the others, which is the user's preference rather than a write to it.
    expect(handle('All orders')).toBeDefined();
    expect(names('All orders')).toEqual(['Open this one first']);
    expect(within(row('All orders')).getAllByRole('group')).toHaveLength(1);
  });

  /**
   * A dialog that opens on the first thing it can focus opens on a write:
   * that was a row's star, and with the handle leading the row it would be a
   * handle. Neither says what the user is looking at.
   */
  it('opens on the heading rather than on a row’s first button', async () => {
    const { engine } = setup();
    await manage(engine);

    await waitFor(() =>
      expect(document.activeElement).toBe(
        screen.getByRole('heading', { name: 'Manage views' }),
      ),
    );
  });

  it('offers no write a system view could not take', async () => {
    const { engine } = setup();
    await manage(engine);

    const system = within(row('All orders'));
    expect(system.queryByRole('button', { name: 'Rename' })).toBeNull();
    expect(system.queryByRole('button', { name: 'Delete' })).toBeNull();
    // Ordering and the default are the user's own preference, so they stay.
    expect(handle('All orders')).toBeDefined();
    expect(
      system.getByRole('button', { name: 'Open this one first' }),
    ).toBeDefined();
  });

  it('leaves out the buttons a permission does not cover', async () => {
    const { engine } = setup(
      permitting({
        reorder: false,
        setDefault: false,
        instance: () => ({ save: true, rename: false, delete: true }),
      }),
    );
    await manage(engine);

    const mine = within(row('Mine'));
    expect(mine.queryByRole('button', { name: 'Reorder Mine' })).toBeNull();
    expect(
      mine.queryByRole('button', { name: 'Open this one first' }),
    ).toBeNull();
    expect(mine.queryByRole('button', { name: 'Rename' })).toBeNull();
    expect(mine.getByRole('button', { name: 'Delete' })).toBeDefined();
  });

  /**
   * The attribute was there and nothing was drawn from it, so "Open this one
   * first" left the star exactly as it was and the badge beside the title
   * was the only thing that answered.
   */
  it('fills the star of the view that opens first', async () => {
    const { engine } = setup();
    await manage(engine);
    const star = (title: string) =>
      within(row(title))
        .getByRole('button', { name: /opening this one first|Open this one/ })
        .querySelector('svg')!;

    // The picture and the word both answer, and the picture says which it
    // is on itself: a filled star is `data-default`, not a class list.
    expect(star('Yours').hasAttribute('data-default')).toBe(false);
    expect(
      within(row('Yours'))
        .getByRole('button', { name: 'Open this one first' })
        .getAttribute('aria-pressed'),
    ).toBe('false');

    fireEvent.click(
      within(row('Yours')).getByRole('button', {
        name: 'Open this one first',
      }),
    );

    await waitFor(() =>
      expect(star('Yours').hasAttribute('data-default')).toBe(true),
    );
    expect(
      within(row('Yours'))
        .getByRole('button', { name: 'Stop opening this one first' })
        .getAttribute('aria-pressed'),
    ).toBe('true');
    // And no other row claims it.
    expect(star('Mine').hasAttribute('data-default')).toBe(false);
  });
});

/**
 * The half of a drag a pointer decides and jsdom cannot reach:
 * `@dnd-kit/dom` picks its drop target by measuring boxes against each other,
 * and every box here is 0×0 at the origin. The gesture itself is a browser
 * story (`DataWorkbench.test.stories.tsx`, `ManageViews`); what the manager
 * makes of the result is this.
 */
describe('what the manager takes a drop to mean', () => {
  const audienceOf = (id: string) =>
    (
      ({
        'orders-1': 'personal',
        'orders-2': 'personal',
        'orders-3': 'shared',
      }) as Record<string, ViewAudience | undefined>
    )[id];
  const dropped = (source: string, target: string) =>
    managerDrop(
      { source: { id: source }, target: { id: target } },
      false,
      audienceOf,
    );

  it('takes a drop between two rows of one audience', () => {
    expect(dropped('orders-1', 'orders-2')).toEqual({
      source: 'orders-1',
      target: 'orders-2',
    });
  });

  it('refuses one that crosses the line between the two groups', () => {
    // Both lists draw personal views above shared ones whatever order is
    // stored, so this would spend a revision and move nothing on screen.
    expect(dropped('orders-1', 'orders-3')).toBeNull();
    expect(dropped('orders-3', 'orders-1')).toBeNull();
    // And a row no audience is known for is not one of either group.
    expect(dropped('gone', 'orders-1')).toBeNull();
  });

  it('refuses a drag given up, and one that ended where it began', () => {
    expect(
      managerDrop(
        { source: { id: 'orders-1' }, target: { id: 'orders-2' } },
        true,
        audienceOf,
      ),
    ).toBeNull();
    expect(dropped('orders-1', 'orders-1')).toBeNull();
    expect(
      managerDrop({ source: { id: 'orders-1' } }, false, audienceOf),
    ).toBeNull();
    expect(
      managerDrop({ target: { id: 'orders-1' } }, false, audienceOf),
    ).toBeNull();
  });
});

describe('what a drag of a view says out loud', () => {
  const accessibility = manageDragAccessibility(
    formattersFor(defaultMessages),
    id => (id === 'orders-1' ? 'Mine' : id),
  );

  it('names the view in the reader’s own words', () => {
    expect(
      accessibility.announcements.dragstart({
        operation: { source: { id: 'orders-1' } },
      }),
    ).toBe('Mine picked up');
  });

  /** A completed drop is announced by the dialog, so it says nothing here. */
  it('speaks only when a drag is given up', () => {
    const drop = { operation: { source: { id: 'orders-1' } } };

    expect(accessibility.announcements.dragend(drop)).toBeUndefined();
    expect(
      accessibility.announcements.dragend({ ...drop, canceled: true }),
    ).toBe('Move cancelled; Mine stayed where it was');
  });

  it('says nothing about a drag with no source', () => {
    const none = { operation: { source: null } };

    expect(accessibility.announcements.dragstart(none)).toBeUndefined();
    expect(
      accessibility.announcements.dragend({ ...none, canceled: true }),
    ).toBeUndefined();
  });

  it('carries the instructions a reader is given on the handle', () => {
    expect(accessibility.screenReaderInstructions.draggable).toBe(
      defaultMessages['label.manage.instructions'],
    );
  });
});

/**
 * Renaming, deleting, reordering and the default view happen in the sidebar's
 * manager rather than beside the save button — they are about the list, not
 * about the view on screen. What the workbench still owes is to follow: the
 * view it has open may be the one that just went.
 */
describe('managing views from the workbench', () => {
  /** One row of the manager, by the title it shows. */
  function row(title: string): HTMLElement {
    const found = Array.from(
      document.querySelectorAll('[data-slot="view-manager-row"]'),
    ).find(
      candidate =>
        candidate.textContent?.includes(title) ||
        Array.from(candidate.querySelectorAll('input')).some(field =>
          field.value.includes(title),
        ),
    );
    if (!found) throw new Error(`no row for ${title}`);
    return found as HTMLElement;
  }

  /**
   * The manager is a modal: everything behind it is inert, the sidebar
   * included. A test that looks at what the workbench did has to shut it.
   */
  function close() {
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
  }

  /** Opens the manager from the sidebar and waits for it to draw. */
  async function manage() {
    fireEvent.click(screen.getByRole('button', { name: 'Manage views' }));
    await screen.findByRole('dialog');
  }

  /** The manager's delete, through its confirmation. */
  async function remove(title: string) {
    await manage();
    fireEvent.click(within(row(title)).getByRole('button', { name: 'Delete' }));
    const confirm = await screen.findByRole('alertdialog');
    fireEvent.click(within(confirm).getByRole('button', { name: 'Delete' }));
  }

  it('moves on to the next view once the open default is deleted', async () => {
    // No explicit instance and the personal view is the default: the id the
    // workbench opened came from the list, so a delete has nothing to unpin.
    // The engine disposed the runtime with the instance; what is on screen
    // must follow, and the list must stop offering the view.
    const store = tracked(
      new MemoryViewStore({
        instances: [mine],
        preferences: {
          orders: { order: [], defaultInstanceId: 'orders-1', revision: '0' },
        },
      }),
    );
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store,
      resolveSource: () => testSource(),
    });
    render(<DataWorkbench engine={engine} definitionId="orders" />);
    // A regex, because this one *is* the default: its row carries the star,
    // and the star says so in a word rather than only in a picture.
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Mine/ }).ariaCurrent).toBe(
        'true',
      ),
    );

    await remove('Mine');
    close();

    // The system view is what is left, and it is the one open now.
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: /All orders/ }).ariaCurrent,
      ).toBe('true'),
    );
    expect(screen.queryByRole('button', { name: /Mine/ })).toBeNull();
    expect(screen.getAllByRole('row')).toHaveLength(3);
  });

  /**
   * The view the workbench was *pinned* to, rather than riding on: nothing
   * reloads the pin, so the workbench has to notice that reopening it now
   * answers "no such view" and let go of its own accord.
   */
  it('lets the pinned view go when the manager deletes it', async () => {
    const other: ViewInstance = { ...mine, id: 'orders-2', title: 'Other' };
    const store = tracked(new MemoryViewStore({ instances: [mine, other] }));
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store,
      resolveSource: () => testSource(),
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Mine' }).ariaCurrent).toBe(
        'true',
      ),
    );

    await remove('Mine');
    close();

    await waitFor(
      () => expect(screen.queryByRole('button', { name: 'Mine' })).toBeNull(),
      { timeout: 3000 },
    );
    // The pin is gone, so the list's default is what is open.
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: /All orders/ }).ariaCurrent,
      ).toBe('true'),
    );
  });

  it('shows the empty state once the last view is deleted', async () => {
    const store = tracked(new MemoryViewStore({ instances: [mine] }));
    const engine = new ViewEngine({
      definitions: [ordersDefinition({ views: [] })],
      store,
      resolveSource: () => testSource(),
    });
    render(<DataWorkbench engine={engine} definitionId="orders" />);
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    await remove('Mine');

    // The runtime goes at once — disposal notifies — and the list a moment
    // later, once it has reloaded without the deleted view.
    await waitFor(() => expect(screen.queryByRole('table')).toBeNull());
    // Said twice, in the sidebar and in the work area it left empty.
    expect(await screen.findAllByText('No view yet')).toHaveLength(2);
    expect(screen.queryByRole('button', { name: 'Mine' })).toBeNull();
  });

  it('lets go of the view once a recovered delete lands', async () => {
    const { engine, store } = workbenchSetup();
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    vi.spyOn(store, 'delete').mockRejectedValueOnce(new Error('socket closed'));

    await remove('Mine');

    // The row that raised the write is the one that says what became of it.
    // Read out of the dialog rather than off the page: the workbench behind
    // it still shows the open view's own outcome, and the manager now keeps
    // a live region for what a move says out loud.
    const outcome = (
      await within(screen.getByRole('dialog')).findByText(/never came back/)
    ).closest('[role="status"]') as HTMLElement;

    fireEvent.click(within(outcome).getByRole('button', { name: 'Try again' }));
    close();

    // The store has it now; the workbench follows: the view stops rendering
    // and the list drops the entry once its reload lands.
    await waitFor(
      () => {
        expect(screen.queryByRole('table')).toBeNull();
        expect(screen.queryByRole('button', { name: 'Mine' })).toBeNull();
      },
      { timeout: 3000 },
    );
  });

  it('keeps the view open when a delete conflict ends in a reload', async () => {
    // Another view is the default, so a pin dropped on the reload would
    // reopen that one instead: nothing here says the user left this view.
    const other: ViewInstance = { ...mine, id: 'other-1', title: 'Other' };
    const store = tracked(new MemoryViewStore({ instances: [other, mine] }));
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store,
      resolveSource: () => testSource(),
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    // The server moved on; taking their version adopts the existing
    // instance, it does not delete it.
    const moved = await store.save(
      'orders-1',
      recordConfig({ pageSize: 30 }),
      '1',
      { requestId: 'other' },
    );
    vi.spyOn(store, 'delete').mockImplementationOnce(() =>
      Promise.reject(
        new ViewStoreError('CONFLICT', 'moved', { instance: moved }),
      ),
    );

    await remove('Mine');

    const outcome = await screen.findByRole('alert');
    fireEvent.click(
      within(outcome).getByRole('button', { name: 'Reload list' }),
    );

    // The dialog is in the way of the sidebar; close it and look.
    close();
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Mine' }).ariaCurrent).toBe(
        'true',
      ),
    );
    expect(screen.getByRole('table')).toBeDefined();
  });

  it('keeps unsaved edits across a rename of the default view', async () => {
    // The default has to be the personal view: the first list entry is the
    // code-declared system view, which nobody may rename.
    const store = tracked(
      new MemoryViewStore({
        instances: [mine],
        preferences: {
          orders: { order: [], defaultInstanceId: 'orders-1', revision: '0' },
        },
      }),
    );
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store,
      resolveSource: () => testSource(),
    });
    // No explicit instance: the workbench rides on the default view, so
    // nothing is pinned and a list reload must not close the runtime.
    render(<DataWorkbench engine={engine} definitionId="orders" />);
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    fireEvent.click(screen.getByRole('button', { name: /Columns/ }));
    fireEvent.click(
      await screen.findByRole('checkbox', { name: 'Show Warehouse' }),
    );
    // The header count includes the select-all column.
    await waitFor(() => expect(dataColumnHeaders()).toHaveLength(4));
    // The column menu stays open after a checkbox pick; close it before the
    // next click, which the open menu would swallow.
    fireEvent.keyDown(document.body, { key: 'Escape' });

    await manage();
    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Rename' }),
    );
    fireEvent.change(within(row('Mine')).getByLabelText(/^Rename /), {
      target: { value: 'Renamed' },
    });
    fireEvent.click(
      within(row('Renamed')).getByRole('button', { name: 'Save the title' }),
    );

    // The rename refreshed the list; the unsaved column edit survived it.
    await landed(store);
    expect((await store.get('orders-1')).title).toBe('Renamed');
    close();
    await waitFor(() => expect(dataColumnHeaders()).toHaveLength(4));
  });
});
