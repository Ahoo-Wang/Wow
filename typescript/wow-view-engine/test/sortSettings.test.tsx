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

import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import type { FieldDefinition, RecordSort } from '../src/model/index.js';
import { SortSettings } from '../src/ui/SortSettings.js';
import { tableController } from './fixtures/columns.js';

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
   * screen reader reads the button as "Amount" with no idea which way.
   */
  it('names the field and the direction it is in', () => {
    open([{ field: 'amount', direction: 'DESC' }]);

    expect(trigger().textContent).toBe('AmountDescending');
  });

  /** Several fields do not fit; the first is the one the rows are in. */
  it('names the first field and counts the rest', () => {
    open([
      { field: 'amount', direction: 'DESC' },
      { field: 'id', direction: 'ASC' },
    ]);

    expect(trigger().textContent).toBe('AmountDescending+1');
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

    expect(
      await screen.findByText('These rows are in no particular order.'),
    ).toBeTruthy();
    expect(document.querySelector('[data-slot="sort-entry"]')).toBeNull();
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

    expect(trigger().textContent).toBe('AmountAscending');

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
