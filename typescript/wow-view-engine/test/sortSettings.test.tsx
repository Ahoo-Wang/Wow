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
 * The sort control: what the button says the rows are in the order of, and
 * what the editor behind it lets someone change. Like the table header's own
 * toggle, every change here applies at once — the rows on screen were
 * projected from the config that ran, so an unapplied sort is invisible.
 */

import { useState } from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { FieldDefinition, RecordSort } from '../src/model/index.js';
import { SortSettings } from '../src/ui/SortSettings.js';
import { MessagesProvider } from '../src/ui/MessagesProvider.js';
import { defaultMessages } from '../src/ui/messages.js';
import {
  reorderSort,
  sortDragAccessibility,
  sortDrop,
  sortEntryId,
  sortEntryIndex,
} from '../src/ui/sort/drag.js';
import { formattersFor, tableController } from './fixtures/columns.js';

afterEach(cleanup);

const FIELDS: FieldDefinition[] = [
  { name: 'id', label: 'Order', kind: 'string', sortable: true },
  { name: 'amount', label: 'Amount', kind: 'number', sortable: true },
  // Not sortable, so it is never offered — the backend cannot order by it.
  { name: 'warehouse', label: 'Warehouse', kind: 'string' },
];

function open(sort: RecordSort[] = [], fields = FIELDS) {
  const table = tableController({ sort });
  render(<SortSettings table={table} fields={fields} />);
  return table;
}

/** The control's trigger, whatever the current sort has made it say. */
function trigger(): HTMLElement {
  return document.querySelector<HTMLElement>('[data-control="sort"]')!;
}

describe('what the sort button says', () => {
  it('offers nothing at all when there is neither a field nor a sort', () => {
    render(
      <SortSettings
        table={tableController()}
        fields={[{ name: 'warehouse', label: 'Warehouse', kind: 'string' }]}
      />,
    );

    expect(document.querySelector('[data-control="sort"]')).toBeNull();
  });

  /**
   * Two entries on one field is a config the kernel refuses
   * (`record.sort.duplicate`), and both of them are listed: removing one has
   * to leave the other where it is, which a key of the field alone could
   * not promise.
   */
  it('lists a field sorted twice as two entries, each its own', async () => {
    const user = userEvent.setup();
    const table = open([
      { field: 'amount', direction: 'ASC' },
      { field: 'amount', direction: 'DESC' },
    ]);

    await user.click(trigger());
    expect(document.querySelectorAll('[data-slot="sort-entry"]')).toHaveLength(
      2,
    );
    await user.click(
      screen.getAllByRole('button', { name: 'Stop sorting by Amount' })[0],
    );

    expect(table.setSort).toHaveBeenCalledWith([
      { field: 'amount', direction: 'DESC' },
    ]);
  });

  /**
   * A definition that stopped declaring a field sortable while a saved
   * config still orders by it: `validateRecord` refuses the draft and the
   * workbench reports it, so hiding the control here left the user reading
   * an error whose one cause was behind a door that no longer existed.
   */
  it('stays while there is a sort to take back, sortable or not', async () => {
    const user = userEvent.setup();
    const unsortable = [{ name: 'amount', label: 'Amount', kind: 'number' }];
    const table = tableController({
      sort: [{ field: 'amount', direction: 'DESC' }],
    });
    render(<SortSettings table={table} fields={unsortable} />);

    await user.click(trigger());
    await user.click(
      screen.getByRole('button', { name: 'Stop sorting by Amount' }),
    );

    expect(table.setSort).toHaveBeenCalledWith([]);
    // And nothing is offered to put back in its place.
    expect(
      screen
        .getByRole('button', { name: /Sort by a field/ })
        .hasAttribute('disabled'),
    ).toBe(true);
  });

  it('says so when the rows are in no particular order', () => {
    open();

    expect(trigger().textContent).toBe('Sort');
  });

  /**
   * The field and the direction in words: an arrow alone is a picture, and a
   * screen reader reads the button as "Amount" with no idea which way — or
   * that a sort is what it is looking at, since the word "Sort" is what the
   * button wears only while nothing is sorted.
   */
  it('names the field and the direction it is in', () => {
    open([{ field: 'amount', direction: 'DESC' }]);

    expect(trigger().textContent).toBe('Amount');
    expect(trigger().getAttribute('aria-label')).toBe(
      'Sort: Amount Descending',
    );
  });

  /** Several fields do not fit; the first is the one the rows are in. */
  it('names the first field and counts the rest', () => {
    open([
      { field: 'amount', direction: 'DESC' },
      { field: 'id', direction: 'ASC' },
    ]);

    expect(trigger().textContent).toBe('Amount+1');
    // The count is in the name too: what is heard holds the same three
    // pieces as what is seen (WCAG 2.5.3).
    expect(trigger().getAttribute('aria-label')).toBe(
      'Sort: Amount Descending +1',
    );
  });

  /**
   * Nothing sorted, and the button's own word is already the whole of it —
   * a second name over "Sort" would be the same word twice.
   */
  it('leaves the name to the word it wears while nothing is sorted', () => {
    open();

    expect(trigger().getAttribute('aria-label')).toBeNull();
  });

  it('names itself in the catalogue in force', () => {
    render(
      <MessagesProvider
        messages={{ 'label.sort.button': '排序：{field} {direction}' }}
      >
        <SortSettings
          table={tableController({
            sort: [{ field: 'amount', direction: 'DESC' }],
          })}
          fields={FIELDS}
        />
      </MessagesProvider>,
    );

    expect(trigger().getAttribute('aria-label')).toBe(
      '排序：Amount Descending',
    );
  });

  /**
   * One arrow, after the name, whatever the sort is.
   *
   * The button used to lead with a neutral `↕` *and* draw the direction
   * after the field, so a sorted table read `↕ Amount ↓` — two arrows with
   * the word they both talk about between them, measured 48px apart, the
   * first saying nothing the second did not. The neutral one is now what
   * the button wears while nothing is sorted, and the direction takes its
   * place once something is: one mark, one place, one meaning — the way a
   * table header carries it (`SortableHeader`).
   */
  it('wears one arrow, after the name, sorted or not', () => {
    open();

    const unsorted = [...trigger().querySelectorAll('svg')];
    expect(unsorted).toHaveLength(1);
    expect(unsorted[0].dataset.slot).toBe('sort-available');
    // After the word, not before it: the word comes first in the button.
    expect(trigger().firstChild!.textContent).toBe('Sort');

    cleanup();
    open([
      { field: 'amount', direction: 'DESC' },
      { field: 'id', direction: 'ASC' },
    ]);

    const sorted = [...trigger().querySelectorAll('svg')];
    expect(sorted).toHaveLength(1);
    // Neither icon slot: those tighten the button's padding on that side,
    // and with a second field sorted the count is what ends the button.
    expect(sorted[0].hasAttribute('data-icon')).toBe(false);
  });
});

describe('editing the sort', () => {
  async function opened(sort: RecordSort[] = []) {
    const user = userEvent.setup();
    const table = open(sort);
    await user.click(trigger());
    return { user, table };
  }

  it('lists each field with its place and its direction', async () => {
    await opened([
      { field: 'amount', direction: 'DESC' },
      { field: 'id', direction: 'ASC' },
    ]);

    expect(
      [...document.querySelectorAll('[data-slot="sort-entry"]')].map(
        entry => entry.textContent,
      ),
    ).toEqual(['1AmountDescending', '2OrderAscending']);
  });

  it('says there is no order rather than showing an empty list', async () => {
    await opened();

    const said = await screen.findByText(
      'These rows are in no particular order.',
    );
    expect(document.querySelector('[data-slot="sort-entry"]')).toBeNull();
    // The package's one empty-state shape, not a third way of saying it.
    expect(said.closest('[data-slot="sort-unsorted"]')).not.toBeNull();
    expect(said.getAttribute('data-slot')).toBe('empty-description');
  });

  it('turns one entry around and leaves the others alone', async () => {
    const { user, table } = await opened([
      { field: 'amount', direction: 'DESC' },
      { field: 'id', direction: 'ASC' },
    ]);

    await user.click(
      screen.getByRole('button', { name: 'Direction of Amount' }),
    );

    expect(table.setSort).toHaveBeenCalledWith([
      { field: 'amount', direction: 'ASC' },
      { field: 'id', direction: 'ASC' },
    ]);
  });

  /**
   * The worst case for `record.sort.direction-invalid` and for
   * `record.field.unknown` on a sort entry: the field is not in the
   * definition at all, so there is no label to show. The entry wears its
   * own name and keeps both of its controls.
   */
  it('lists an entry on a field the definition dropped, and removes it', async () => {
    const { user, table } = await opened([{ field: 'gone', direction: 'ASC' }]);

    expect(
      document.querySelector('[data-slot="sort-entry"]')!.textContent,
    ).toContain('gone');

    await user.click(
      screen.getByRole('button', { name: 'Stop sorting by gone' }),
    );
    expect(table.setSort).toHaveBeenCalledWith([]);
  });

  it('drops the entry the user takes out', async () => {
    const { user, table } = await opened([
      { field: 'amount', direction: 'DESC' },
      { field: 'id', direction: 'ASC' },
    ]);

    await user.click(
      screen.getByRole('button', { name: 'Stop sorting by Amount' }),
    );

    expect(table.setSort).toHaveBeenCalledWith([
      { field: 'id', direction: 'ASC' },
    ]);
  });

  /**
   * A new field joins at the end, ascending: it is the tie-breaker for the
   * fields already there, and appending is the only place it can go without
   * silently changing what the rows are mainly ordered by.
   */
  it('offers the sortable fields not used yet, and appends the pick', async () => {
    const { user, table } = await opened([
      { field: 'amount', direction: 'DESC' },
    ]);

    await user.click(screen.getByRole('button', { name: /Sort by a field/ }));
    expect(screen.queryByRole('menuitem', { name: 'Warehouse' })).toBeNull();
    await user.click(await screen.findByRole('menuitem', { name: 'Order' }));

    expect(table.setSort).toHaveBeenCalledWith([
      { field: 'amount', direction: 'DESC' },
      { field: 'id', direction: 'ASC' },
    ]);
  });

  it('has nothing left to add once every field is used', async () => {
    await opened([
      { field: 'amount', direction: 'DESC' },
      { field: 'id', direction: 'ASC' },
    ]);

    expect(
      screen
        .getByRole('button', { name: /Sort by a field/ })
        .hasAttribute('disabled'),
    ).toBe(true);
  });

  /**
   * A cursor is a position in one total order and Wow bounds how many fields
   * that order is built from, so `validateRecord` refuses a longer sort:
   * `apply` never runs, the rows keep the order they had, and the view sits
   * in an error the control invited the user into. It stops at the ceiling.
   */
  it('stops offering fields at the ceiling the kernel refuses past', async () => {
    const user = userEvent.setup();
    const table = tableController({
      sort: [{ field: 'amount', direction: 'DESC' }],
      maxSortFields: 1,
    });
    render(<SortSettings table={table} fields={FIELDS} />);
    await user.click(trigger());

    expect(
      screen
        .getByRole('button', { name: /Sort by a field/ })
        .hasAttribute('disabled'),
    ).toBe(true);
    expect(
      await screen.findByText('These rows can be ordered by at most 1 fields.'),
    ).toBeTruthy();
  });

  /**
   * A stored config is untrusted: `validateShape` asks a sort entry for a
   * `field` and nothing else. `validateRecord` reports the direction now, but
   * the draft is still rendered while it is being fixed, and indexing a
   * wording table with `up` used to take the whole workbench down.
   */
  it('renders a direction it cannot read rather than throwing', async () => {
    const user = userEvent.setup();
    const table = tableController({
      sort: [{ field: 'amount' }] as unknown as RecordSort[],
    });
    render(<SortSettings table={table} fields={FIELDS} />);

    expect(trigger().textContent).toBe('Amount');
    expect(trigger().getAttribute('aria-label')).toBe('Sort: Amount Ascending');

    await user.click(trigger());
    await user.click(
      screen.getByRole('button', { name: 'Direction of Amount' }),
    );
    expect(table.setSort).toHaveBeenCalledWith([
      { field: 'amount', direction: 'DESC' },
    ]);
  });

  it('gives the focus back to the trigger when it closes', async () => {
    const { user } = await opened([{ field: 'amount', direction: 'DESC' }]);

    await user.keyboard('{Escape}');

    await waitFor(() => expect(document.activeElement).toBe(trigger()));
  });
});

/**
 * Which field comes first is the whole of what this list says, so the order
 * is something to take hold of. Both inputs commit the same thing — the
 * whole order through `setSort`, one `edit` and one `apply`, exactly as a
 * flipped direction or a removed entry does.
 */
describe('putting the sort in order', () => {
  /** The editor over a sort that really changes, so a move reads back. */
  function Editing({
    initial,
    committed,
  }: {
    initial: RecordSort[];
    committed(sort: RecordSort[]): void;
  }) {
    const [sort, setSort] = useState(initial);
    return (
      <SortSettings
        table={tableController({
          sort,
          setSort: next => {
            committed(next);
            setSort(next);
          },
        })}
        fields={FIELDS}
      />
    );
  }

  async function editing(initial: RecordSort[]) {
    const user = userEvent.setup();
    const committed = vi.fn();
    render(<Editing initial={initial} committed={committed} />);
    await user.click(trigger());
    return { user, committed };
  }

  /** The numbers are drawn from the list, so a move renumbers everything. */
  it('moves an entry a place up and renumbers what it passed', async () => {
    const { user, committed } = await editing([
      { field: 'amount', direction: 'DESC' },
      { field: 'id', direction: 'ASC' },
    ]);

    (await screen.findByRole('button', { name: 'Reorder Order' })).focus();
    await user.keyboard('{ArrowUp}');

    expect(committed).toHaveBeenCalledWith([
      { field: 'id', direction: 'ASC' },
      { field: 'amount', direction: 'DESC' },
    ]);
    expect(
      [...document.querySelectorAll('[data-slot="sort-entry"]')].map(
        entry => entry.textContent,
      ),
    ).toEqual(['1OrderAscending', '2AmountDescending']);
  });

  /**
   * Where the entry landed is said once, by the editor, for the arrow keys
   * and for a drop alike — the library says the pick-up and the cancel, and
   * a second voice for the landing would read the same move out twice.
   */
  it('says where the entry landed, counted over the whole sort', async () => {
    const { user } = await editing([
      { field: 'amount', direction: 'DESC' },
      { field: 'id', direction: 'ASC' },
    ]);

    (await screen.findByRole('button', { name: 'Reorder Amount' })).focus();
    await user.keyboard('{ArrowDown}');

    expect(
      document.querySelector('[data-slot="sort-announcement"]')!.textContent,
    ).toBe('Amount moved to position 2 of 2');
  });

  /** An end is an end: nothing moves, and nothing is written or said. */
  it('writes nothing when the key points past the end', async () => {
    const { user, committed } = await editing([
      { field: 'amount', direction: 'DESC' },
      { field: 'id', direction: 'ASC' },
    ]);

    (await screen.findByRole('button', { name: 'Reorder Amount' })).focus();
    await user.keyboard('{ArrowUp}');

    expect(committed).not.toHaveBeenCalled();
    expect(
      document.querySelector('[data-slot="sort-announcement"]')!.textContent,
    ).toBe('');
  });

  /**
   * One entry is first and last at once. A handle that can only put it back
   * where it is claims something it cannot do — and costs a tab stop to say
   * it.
   */
  it('refuses the handle while there is only one entry', async () => {
    await editing([{ field: 'amount', direction: 'DESC' }]);

    expect(
      (
        await screen.findByRole('button', { name: 'Reorder Amount' })
      ).hasAttribute('disabled'),
    ).toBe(true);
  });
});

/**
 * The drop, read off the two ids it reports: `@dnd-kit/dom` picks its target
 * by measuring boxes, and in jsdom every box is 0×0 at the origin — so the
 * gesture itself is a browser story, and what it means is tested here.
 */
describe('what a drop on the sort list means', () => {
  const drop = (from: number, to: number, canceled?: boolean) =>
    sortDrop(
      { source: { id: sortEntryId(from) }, target: { id: sortEntryId(to) } },
      canceled,
    );

  it('is the move between the two places it names', () => {
    expect(drop(2, 0)).toEqual({ from: 2, to: 0 });
  });

  it('is nothing when the drag was given up, or ended where it began', () => {
    expect(drop(2, 0, true)).toBeNull();
    expect(drop(1, 1)).toBeNull();
    expect(sortDrop({ source: { id: sortEntryId(0) } }, false)).toBeNull();
    expect(sortDrop({ target: { id: sortEntryId(0) } }, false)).toBeNull();
  });

  /**
   * This list is not the only draggable thing a page holds, and an id from
   * somewhere else names no place in this sort.
   */
  it('is nothing when an id names no entry of this list', () => {
    expect(
      sortDrop(
        { source: { id: 'amount' }, target: { id: sortEntryId(0) } },
        false,
      ),
    ).toBeNull();
    expect(sortEntryIndex('sort-entry-x')).toBeNull();
    expect(sortEntryIndex('sort-entry-0')).toBe(0);
  });

  it('takes the entry out and puts it back at the place asked for', () => {
    const sort: RecordSort[] = [
      { field: 'amount', direction: 'DESC' },
      { field: 'id', direction: 'ASC' },
      { field: 'warehouse', direction: 'ASC' },
    ];

    expect(reorderSort(sort, 2, 0)).toEqual([sort[2], sort[0], sort[1]]);
    expect(reorderSort(sort, 0, 2)).toEqual([sort[1], sort[2], sort[0]]);
  });

  /** A place the list does not have is not a move, at either end. */
  it('is nothing when either place is outside the list', () => {
    const sort: RecordSort[] = [{ field: 'amount', direction: 'DESC' }];

    expect(reorderSort(sort, 0, 0)).toBeNull();
    expect(reorderSort(sort, 0, 1)).toBeNull();
    expect(reorderSort(sort, 1, 0)).toBeNull();
    expect(reorderSort(sort, 0, -1)).toBeNull();
  });
});

/**
 * The library's own sentences are built from the ids it carries — here the
 * places in a list — so every one of them is said in the catalogue's words,
 * with the place turned back into the field a reader is looking at.
 */
describe('what a sort drag says out loud', () => {
  const accessibility = sortDragAccessibility(
    formattersFor(defaultMessages),
    id => (id === sortEntryId(1) ? 'Amount' : id),
  );
  const carrying = { operation: { source: { id: sortEntryId(1) } } };

  it('names the field in the reader’s own words', () => {
    expect(accessibility.announcements.dragstart(carrying)).toBe(
      'Amount picked up',
    );
  });

  /** A completed drop is announced by the editor, so it says nothing here. */
  it('speaks only when a drag is given up', () => {
    expect(accessibility.announcements.dragend(carrying)).toBeUndefined();
    expect(
      accessibility.announcements.dragend({ ...carrying, canceled: true }),
    ).toBe('Move cancelled; Amount stayed where it was');
  });

  it('carries the instructions a reader is given on the handle', () => {
    expect(accessibility.screenReaderInstructions.draggable).toBe(
      defaultMessages['label.reorder.instructions'],
    );
  });
});
