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
 * The column settings popover and the pure model under it. The controller is
 * a stub here: what is being tested is which controls a column gets, which
 * of them are refused and what each one writes — not what the runtime does
 * with the write, which `recordTableCommands.test.tsx` covers.
 */

import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import type { FieldDefinition, RecordViewConfig } from '../src/model/index.js';
import { builtinFieldKinds, validateRecord } from '../src/index.js';
import type { RecordTableController } from '../src/react/index.js';
import { ColumnSettings } from '../src/ui/ColumnSettings.js';
import {
  ACTIONS_COLUMN,
  columnSettingRows,
  movableFields,
  nextPin,
  reorderColumns,
  visibleCount,
} from '../src/ui/columns/rows.js';
import { columnDragAccessibility } from '../src/ui/columns/announce.js';
import { defaultMessages } from '../src/ui/messages.js';
import { ordersDefinition, recordConfig } from './fixtures.js';
import { formattersFor, tableController } from './fixtures/columns.js';

afterEach(cleanup);

const FIELDS: FieldDefinition[] = [
  { name: 'id', label: 'Order', kind: 'string' },
  { name: 'warehouse', label: 'Warehouse', kind: 'string' },
  { name: 'amount', label: 'Amount', kind: 'number', summary: ['SUM', 'AVG'] },
  // A field-less kind addresses an editor rather than something a row
  // holds, so it can never be a column and is never offered as one.
  { name: 'q', label: 'Search', kind: 'search' },
];

/**
 * A fourth column, for the cases that need two movable ones.
 *
 * Both ends of a table are fixed (D13) — the row key on the left, the last
 * column on the right — so three columns leave exactly one in the middle
 * and nothing that can be dragged past anything. It is added where it is
 * needed rather than to `FIELDS`, so every other case keeps the list it was
 * written against.
 */
const NOTE: FieldDefinition = { name: 'note', label: 'Note', kind: 'string' };

function open(overrides: Partial<RecordTableController> = {}, props = {}) {
  const table = tableController({
    columnFields: ['id', 'amount'],
    ...overrides,
  });
  render(
    <ColumnSettings table={table} fields={FIELDS} rowKey="id" {...props} />,
  );
  return table;
}

/**
 * What the settings' own live region says. The library puts a second one on
 * `document.body` for its pick-up and cancel, so this addresses ours.
 */
function announced(): string {
  return (
    document.querySelector('[data-slot="column-announcement"]')?.textContent ??
    ''
  );
}

/** The rows of the popover, top to bottom, by the field each one is for. */
function listed(): string[] {
  return [...document.querySelectorAll('[data-slot="column-setting"]')].map(
    row => row.getAttribute('data-field')!,
  );
}

/** The element a control names as its description. */
function describing(control: Element): HTMLElement {
  return document.getElementById(control.getAttribute('aria-describedby')!)!;
}

/** The column a piece of the panel belongs to, or null when it is loose. */
function fieldOf(element: Element): string | null {
  return (
    element
      .closest('[data-slot="column-setting"]')
      ?.getAttribute('data-field') ?? null
  );
}

describe('the column settings model', () => {
  const input = {
    fields: FIELDS,
    columns: ['amount', 'id'],
    rowKey: 'id',
    actions: false,
    summaryFields: [] as readonly string[],
    pinnedOf: () => null,
    hiddenOf: () => false,
    summaryOf: () => null,
  };

  it('lists shown columns first, then the fields that could be ones', () => {
    const rows = columnSettingRows(input);

    expect(rows.map(row => row.field)).toEqual(['amount', 'id', 'warehouse']);
    expect(rows.map(row => row.visible)).toEqual([true, true, false]);
    // A hidden column has no place in the config, so it has no order to
    // drag — and neither shown column has one either, because a table of
    // two columns is a row key and an end (D13).
    expect(movableFields(rows)).toEqual([]);
    expect(visibleCount(rows)).toBe(2);
  });

  it('holds the row key on the left and the actions on the right', () => {
    const rows = columnSettingRows({ ...input, actions: true });

    // With a row-action column the host's slot is the end (D13), so no data
    // column is held for being last: `amount` is an ordinary middle column
    // again, and the actions row is the one shown fixed on the right.
    expect(rows.map(row => row.region)).toEqual([
      'middle',
      'left',
      'middle',
      'right',
    ]);
    expect(rows[0]).toMatchObject({
      field: 'amount',
      fixed: false,
      movable: true,
    });
    expect(rows[1]).toMatchObject({ fixed: true, pinned: 'left' });
    expect(rows[3]).toMatchObject({
      field: ACTIONS_COLUMN,
      fixed: true,
      pinned: 'right',
      movable: false,
    });
  });

  /**
   * The order it produces covers every shown column with the pinned key in
   * front, because that is where the table draws it: an order that put the
   * key second would be saved and then quietly contradicted.
   */
  const movable = {
    ...input,
    fields: [...FIELDS, NOTE],
    columns: ['amount', 'id', 'warehouse', 'note'],
  };

  it('reorders the movable area and leads with the pinned key', () => {
    const rows = columnSettingRows(movable);

    expect(reorderColumns(rows, 'warehouse', 0)).toEqual([
      'id',
      'warehouse',
      'amount',
      'note',
    ]);
    expect(reorderColumns(rows, 'amount', 1)).toEqual([
      'id',
      'warehouse',
      'amount',
      'note',
    ]);
  });

  it('refuses a move that would change nothing', () => {
    const rows = columnSettingRows(movable);

    expect(reorderColumns(rows, 'amount', 0)).toBeNull();
    expect(reorderColumns(rows, 'amount', -1)).toBeNull();
    expect(reorderColumns(rows, 'amount', 2)).toBeNull();
    // Neither end is in the movable area at all.
    expect(reorderColumns(rows, 'id', 1)).toBeNull();
    expect(reorderColumns(rows, 'note', 0)).toBeNull();
    expect(reorderColumns(rows, 'gone', 0)).toBeNull();
  });

  it('cycles a pin through both sides and off again', () => {
    expect(nextPin(null)).toBe('left');
    expect(nextPin('left')).toBe('right');
    expect(nextPin('right')).toBeNull();
  });

  /**
   * A column switched off keeps its entry in the config, so it keeps its
   * place in the list — the point of the whole member (D17-8). It is listed
   * where it sits rather than at the end with the fields that were never
   * columns, it may be dragged there, and it is not counted among the
   * columns the table has to keep one of.
   */
  describe('a column switched off', () => {
    const withHidden = {
      ...input,
      fields: [...FIELDS, NOTE],
      columns: ['id', 'amount', 'warehouse', 'note'],
      hiddenOf: (field: string) => field === 'amount',
    };

    it('keeps its place, its handle and its region', () => {
      const rows = columnSettingRows(withHidden);

      expect(rows.map(row => row.field)).toEqual([
        'id',
        'amount',
        'warehouse',
        'note',
      ]);
      expect(rows.map(row => row.visible)).toEqual([true, false, true, true]);
      // It is in the config, so it has a place — which is what `placed`
      // says and `visible` no longer does.
      expect(rows.map(row => row.placed)).toEqual([true, true, true, true]);
      expect(rows[1]).toMatchObject({
        field: 'amount',
        region: 'middle',
        movable: true,
      });
      expect(movableFields(rows)).toEqual(['amount', 'warehouse']);
      // Two shown columns and the key: the guard counts what is drawn.
      expect(visibleCount(rows)).toBe(3);
    });

    /**
     * Hiding a column clears nothing, so it is listed in the area it will
     * come back to. Listed in the middle instead, it would jump sideways
     * the moment it was switched on again — and the order the panel
     * commits would have written it into the wrong area first.
     */
    it('is listed in the area its pinning asks for', () => {
      const rows = columnSettingRows({
        ...withHidden,
        pinnedOf: (field: string) => (field === 'amount' ? 'left' : null),
      });

      expect(rows[1]).toMatchObject({ field: 'amount', region: 'left' });
    });

    /** D13's last column is the last *drawn* one, not the last entry. */
    it('is never the column the table holds at its end', () => {
      const rows = columnSettingRows({
        ...input,
        columns: ['id', 'warehouse', 'amount'],
        hiddenOf: (field: string) => field === 'amount',
      });

      expect(rows.map(row => [row.field, row.fixed, row.region])).toEqual([
        ['id', true, 'left'],
        // The last column the table draws, held on the right for being it.
        ['warehouse', true, 'right'],
        ['amount', false, 'middle'],
      ]);
    });

    /**
     * The committed order covers the whole config, the switched-off column
     * among them: it is the order it will be shown in again, so leaving it
     * out would write a list that has lost the place being kept for it.
     */
    it('is part of the order a move commits', () => {
      const rows = columnSettingRows(withHidden);

      expect(reorderColumns(rows, 'warehouse', 0)).toEqual([
        'id',
        'warehouse',
        'amount',
        'note',
      ]);
    });
  });
});

describe('the column settings popover', () => {
  it('lists every column that can be one, in their areas', async () => {
    const user = userEvent.setup();
    open({}, { actions: true });

    await user.click(screen.getByRole('button', { name: /Columns/ }));

    // The host's action column is the end, so `amount` is a middle column
    // in config order and only the actions row sits on the right.
    expect(listed()).toEqual(['id', 'amount', 'warehouse', ACTIONS_COLUMN]);
    // The search handle is not something a row holds, so it is not offered.
    expect(screen.queryByRole('checkbox', { name: 'Show Search' })).toBeNull();
  });

  /**
   * Each area is a heading somebody can see, and the same words are its
   * accessible name because they are the same node.
   *
   * Three `aria-label`s and nothing on the screen was what made a column
   * pinned to the right read as having fallen to the bottom of the list:
   * the rows moved and nothing said where they had moved to. The words are
   * the three pin states, the same ones a row's own pin control cycles
   * through, so the area and the control agree.
   */
  it('heads each area with the pinning it is, seen and said alike', async () => {
    const user = userEvent.setup();
    open({}, { actions: true });

    await user.click(screen.getByRole('button', { name: /Columns/ }));

    const regions = [
      ...document.querySelectorAll<HTMLElement>('[data-slot="column-region"]'),
    ];
    const headings = [
      ...document.querySelectorAll<HTMLElement>(
        '[data-slot="column-region-heading"]',
      ),
    ];
    expect(headings.map(heading => heading.textContent)).toEqual([
      'Pinned left',
      'Not pinned',
      'Pinned right',
    ]);
    // Drawn as a heading, not as a grey line that only looks like one.
    expect(headings.map(heading => heading.tagName)).toEqual([
      'H3',
      'H3',
      'H3',
    ]);
    // And the list takes its name from that heading rather than repeating
    // the word in an attribute nobody can check against what is drawn.
    expect(
      regions.map(region => region.getAttribute('aria-labelledby')),
    ).toEqual(headings.map(heading => heading.id));
    expect(regions.some(region => region.hasAttribute('aria-label'))).toBe(
      false,
    );
    expect(regions.map(region => region.dataset.region)).toEqual([
      'left',
      'middle',
      'right',
    ]);
  });

  it('leaves the action column out when the host offers no row actions', async () => {
    const user = userEvent.setup();
    open();

    await user.click(screen.getByRole('button', { name: /Columns/ }));

    expect(listed()).toEqual(['id', 'warehouse', 'amount']);
    // The right area is still there — the last column is held in it — but
    // nothing of the host's is.
    expect(
      [
        ...document.querySelectorAll(
          '[data-region="right"] [data-slot="column-setting"]',
        ),
      ].map(row => row.getAttribute('data-field')),
    ).toEqual(['amount']);
  });

  /**
   * Unchecking is one call, and the controller takes the column's summary
   * with it (`test/recordTableCommands.test.tsx`) — the settings do not
   * write twice, which would be two draft states and two queries for one
   * click.
   */
  it('shows and hides a column from its checkbox', async () => {
    const user = userEvent.setup();
    const table = open();

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    await user.click(screen.getByRole('checkbox', { name: 'Show Warehouse' }));
    expect(table.setColumns).toHaveBeenCalledWith([
      'id',
      'amount',
      'warehouse',
    ]);

    await user.click(screen.getByRole('checkbox', { name: 'Show Amount' }));
    expect(table.setColumns).toHaveBeenLastCalledWith(['id']);
  });

  /**
   * Hiding the last column leaves a result with nothing in it and no way
   * back except the control that emptied it, so the checkbox is refused and
   * says why — on its own row, where the reader is looking, rather than in
   * a paragraph at the top of the panel that names no column at all.
   */
  it('refuses to hide the last column, and says why on that row', async () => {
    const user = userEvent.setup();
    const table = open({ columnFields: ['id'] });

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    const only = screen.getByRole('checkbox', { name: 'Show Order' });

    // Base UI's checkbox is a span in the accessibility tree, so "refused"
    // is `aria-disabled` rather than the attribute a native input carries.
    expect(only.getAttribute('aria-disabled')).toBe('true');
    expect(describing(only).textContent).toContain(
      defaultMessages['label.columns.last-visible'],
    );
    expect(fieldOf(describing(only))).toBe('id');
    expect(table.setColumns).not.toHaveBeenCalled();
  });

  /**
   * `setPinned` maps the columns the draft holds and `config.summaries` is
   * only shown under a column that is there, so both controls on a hidden
   * field would write nothing a reader could see — repeatedly, and
   * silently. They say they cannot instead, and their own row says why.
   */
  it('refuses to pin or summarise a column that is switched off', async () => {
    const user = userEvent.setup();
    const table = open({ columnFields: ['id'] });

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    const pin = screen.getByRole('button', {
      name: 'Pinning of Amount: Not pinned',
    });
    const summary = screen.getByRole('combobox', {
      name: 'Summary under Amount',
    });

    expect(pin.hasAttribute('disabled')).toBe(true);
    expect(summary.hasAttribute('disabled')).toBe(true);
    expect(describing(pin).textContent).toContain(
      defaultMessages['label.columns.hidden'],
    );
    expect(fieldOf(describing(pin))).toBe('amount');
    // The same sentence serves the handle, which is refused for the same
    // reason: a column with no place in the config has no order to drag.
    expect(
      screen
        .getByRole('button', { name: 'Reorder Amount' })
        .getAttribute('aria-describedby'),
    ).toBe(describing(pin).id);

    await user.click(pin);
    expect(table.setPinned).not.toHaveBeenCalled();
  });

  /**
   * Every rule about one column now lives on that column, so the top of the
   * panel is one sentence — the only one no row can say for itself.
   */
  it('keeps the panel top to the one rule that is not any row’s', async () => {
    const user = userEvent.setup();
    open({ columnFields: ['id'] });

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    expect(
      document.querySelector('[data-slot="popover-description"]')!.textContent,
    ).toBe(defaultMessages['label.columns.hint']);
  });

  it('offers a summary only where the field declares one', async () => {
    const user = userEvent.setup();
    const table = open();

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    expect(
      screen.queryByRole('combobox', { name: 'Summary under Order' }),
    ).toBeNull();

    await user.click(
      screen.getByRole('combobox', { name: 'Summary under Amount' }),
    );
    await user.click(await screen.findByRole('option', { name: 'Average' }));
    expect(table.setSummary).toHaveBeenCalledWith('amount', 'AVG');
  });

  it('takes a summary off again', async () => {
    const user = userEvent.setup();
    const table = open({
      summaryOf: (field: string) => (field === 'amount' ? 'SUM' : null),
    });

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    await user.click(
      screen.getByRole('combobox', { name: 'Summary under Amount' }),
    );
    await user.click(await screen.findByRole('option', { name: 'No summary' }));

    expect(table.setSummary).toHaveBeenCalledWith('amount', null);
  });

  /**
   * A stored `pinned: 'top'` used to be a key the catalogue has never heard
   * of, so the popover handed `undefined` to `messages.label` and took the
   * workbench down. `validateRecord` reports it; the row draws it as "not
   * pinned" and the toggle carries on from there.
   */
  it('draws a pinning it cannot read as none, and cycles from there', async () => {
    const user = userEvent.setup();
    // Three columns, so `amount` is in the middle: the two ends are held
    // for the user and read nothing the config stores about their pinning.
    const table = open({
      columnFields: ['id', 'amount', 'warehouse'],
      pinnedOf: (field: string) => (field === 'amount' ? 'top' : null) as never,
    });

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    await user.click(
      screen.getByRole('button', { name: 'Pinning of Amount: Not pinned' }),
    );

    expect(table.setPinned).toHaveBeenCalledWith('amount', 'left');
  });

  /**
   * A field that kept its place and lost its summary capabilities leaves a
   * config the kernel refuses (`record.summary.unsupported`), which blocks
   * the query and the save — and the one control that could take it back
   * was the one that stopped rendering, because the field declares nothing.
   * Its own value comes back as an option so "no summary" is reachable.
   */
  it('offers a configured summary back when the field no longer declares it', async () => {
    const user = userEvent.setup();
    const table = open({
      columnFields: ['id', 'warehouse'],
      summaryOf: (field: string) =>
        field === 'warehouse' ? ('SUM' as const) : null,
    });

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    const select = screen.getByRole('combobox', {
      name: 'Summary under Warehouse',
    });
    expect(select.textContent).toContain('Sum');

    await user.click(select);
    await user.click(await screen.findByRole('option', { name: 'No summary' }));
    expect(table.setSummary).toHaveBeenCalledWith('warehouse', null);
  });

  /**
   * The only column a table has is the column it draws last, so it is held
   * on the right (D13): the pin says so and refuses to change, and the
   * unreadable `pinned` stored on it is not a finding either, because
   * nothing reads it. Its checkbox is refused for the other reason — a
   * table keeps one column.
   */
  it('holds the only column on the right, and lets neither control move', async () => {
    const user = userEvent.setup();
    const table = open({
      columnFields: ['amount'],
      pinnedOf: (field: string) => (field === 'amount' ? 'top' : null) as never,
    });

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    expect(
      screen
        .getByRole('checkbox', { name: 'Show Amount' })
        .getAttribute('aria-disabled'),
    ).toBe('true');

    const pin = screen.getByRole('button', {
      name: 'Pinning of Amount: Pinned right',
    });
    expect(pin.hasAttribute('disabled')).toBe(true);
    await user.click(pin);
    expect(table.setPinned).not.toHaveBeenCalled();
  });

  it('cycles the pin of a column that may move', async () => {
    const user = userEvent.setup();
    const table = open({ columnFields: ['id', 'amount', 'warehouse'] });

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    await user.click(
      screen.getByRole('button', { name: 'Pinning of Amount: Not pinned' }),
    );

    expect(table.setPinned).toHaveBeenCalledWith('amount', 'left');
  });

  /**
   * The two columns the definition places show the state they are in and
   * refuse to change it: a pin toggle that silently does nothing is worse
   * than one that says it cannot.
   */
  it('shows the fixed columns’ pinning and refuses to change it', async () => {
    const user = userEvent.setup();
    open({}, { actions: true });

    await user.click(screen.getByRole('button', { name: /Columns/ }));

    for (const name of [
      'Pinning of Order: Pinned left',
      'Pinning of Actions: Pinned right',
      'Reorder Order',
      'Reorder Actions',
    ])
      expect(
        screen.getByRole('button', { name }).hasAttribute('disabled'),
      ).toBe(true);
    expect(
      screen
        .getByRole('checkbox', { name: 'Show Actions' })
        .getAttribute('aria-disabled'),
    ).toBe('true');
  });
});

/**
 * A column the definition no longer offers is listed precisely because it
 * is broken: `validateRecord` refuses the config over it, which blocks the
 * query and the save, and a row that is not in the list is a column nobody
 * can take out.
 */
/**
 * An area is the pinning: `projectRecord` lays the columns out that way,
 * because `sticky` fixes an element where it already is and a column pinned
 * right that is drawn in the middle would scroll away like any other. The
 * panel lists them the same way, so the two never say different things.
 */
describe('the area a column is listed in', () => {
  function openPinned(pinnedOf: (field: string) => 'left' | 'right' | null) {
    const table = tableController({
      columnFields: ['id', 'warehouse', 'amount'],
      pinnedOf,
    });
    render(
      <ColumnSettings table={table} fields={FIELDS} rowKey="id" actions />,
    );
    return table;
  }

  it('follows the pinning, and puts a right pin at the end', async () => {
    const user = userEvent.setup();
    openPinned(field => (field === 'amount' ? 'right' : null));

    await user.click(screen.getByRole('button', { name: /Columns/ }));

    expect(
      [...document.querySelectorAll('[data-slot="column-setting"]')].map(
        row =>
          `${row.getAttribute('data-region')}:${row.getAttribute('data-field')}`,
      ),
    ).toEqual([
      'left:id',
      'middle:warehouse',
      'right:amount',
      `right:${ACTIONS_COLUMN}`,
    ]);
  });

  /**
   * A field the config has never mentioned has no place in the table and no
   * pinning either — the middle is where it joins when it is switched on.
   * (A column that *is* in the config keeps the area it was pinned to while
   * it is switched off; see "a column switched off" above.)
   */
  it('lists a field that is not a column in the middle', async () => {
    const user = userEvent.setup();
    const table = tableController({
      columnFields: ['id', 'amount'],
      pinnedOf: (field: string) => (field === 'warehouse' ? 'right' : null),
    });
    render(<ColumnSettings table={table} fields={FIELDS} rowKey="id" />);

    await user.click(screen.getByRole('button', { name: /Columns/ }));

    expect(
      document
        .querySelector('[data-field="warehouse"]')!
        .getAttribute('data-region'),
    ).toBe('middle');
  });

  /**
   * A drag stays inside its area, and the order that is committed is the
   * order the table lays out: left, middle, right.
   */
  it('reorders inside one area and commits the whole layout', async () => {
    const user = userEvent.setup();
    const table = openPinned(field =>
      field === 'amount' ? 'right' : field === 'warehouse' ? 'left' : null,
    );

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    screen.getByRole('button', { name: 'Reorder Warehouse' }).focus();
    // The row key is fixed and keeps its slot; there is nowhere to go.
    await user.keyboard('{ArrowUp}');
    expect(table.setColumnOrder).not.toHaveBeenCalled();
  });
});

/**
 * A column switched off is still a column: it keeps its entry in the
 * config, so the panel lists it where it sits, drags it like any other row
 * and puts it back there when it is switched on again (D17-8). Before this
 * it fell to the end of the middle area with the fields that had never been
 * columns, and coming back meant dragging it into place a second time.
 */
describe('a column the user switched off', () => {
  function openHidden(hidden: string) {
    const table = tableController({
      columnFields: ['id', 'amount', 'warehouse', 'note'],
      hiddenOf: (field: string) => field === hidden,
    });
    render(
      <ColumnSettings table={table} fields={[...FIELDS, NOTE]} rowKey="id" />,
    );
    return table;
  }

  it('is listed in its own place, unticked, with a handle that works', async () => {
    const user = userEvent.setup();
    const table = openHidden('amount');

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    // In place — not at the end with `Search`, which is a field the config
    // has never named.
    expect(listed()).toEqual(['id', 'amount', 'warehouse', 'note']);
    expect(
      screen.getByRole('checkbox', { name: 'Show Amount' }).dataset.checked,
    ).toBeUndefined();

    const handle = screen.getByRole('button', { name: 'Reorder Amount' });
    expect(handle.hasAttribute('disabled')).toBe(false);
    // And it says nothing about being switched off: that sentence is about
    // the two controls that are refused, and this one is not one of them.
    // (The library puts its own keyboard instructions on every handle, so
    // what is checked is which description it is not.)
    expect(handle.getAttribute('aria-describedby')).not.toBe(
      document.querySelector('[data-field="amount"] [data-slot="column-note"]')!
        .id,
    );

    handle.focus();
    await user.keyboard('{ArrowDown}');
    expect(table.setColumnOrder).toHaveBeenCalledWith([
      'id',
      'warehouse',
      'amount',
      'note',
    ]);
  });

  /**
   * Its pin and its summary still are: a column the table does not draw is
   * held nowhere and has no cell under it, so both controls would write
   * something nobody could see.
   */
  it('still refuses its pin and its summary, and says why', async () => {
    const user = userEvent.setup();
    const table = openHidden('amount');

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    const pin = screen.getByRole('button', {
      name: 'Pinning of Amount: Not pinned',
    });
    expect(pin.hasAttribute('disabled')).toBe(true);
    expect(describing(pin).textContent).toContain(
      defaultMessages['label.columns.hidden'],
    );

    await user.click(pin);
    expect(table.setPinned).not.toHaveBeenCalled();
  });

  /**
   * Switching it back on is one call naming the columns that are shown, in
   * the order they are listed — the controller puts each one back where its
   * entry already was, so nothing has to be dragged twice.
   */
  it('goes back on where it is, not at the end', async () => {
    const user = userEvent.setup();
    const table = openHidden('amount');

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    await user.click(screen.getByRole('checkbox', { name: 'Show Amount' }));

    expect(table.setColumns).toHaveBeenCalledWith([
      'id',
      'amount',
      'warehouse',
      'note',
    ]);
  });

  /** And the one that is left off is simply left out of that list. */
  it('leaves the switched-off column out of what it writes', async () => {
    const user = userEvent.setup();
    const table = openHidden('amount');

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    await user.click(screen.getByRole('checkbox', { name: 'Show Warehouse' }));

    expect(table.setColumns).toHaveBeenCalledWith(['id', 'note']);
  });
});

describe('a column the definition dropped', () => {
  function openDropped(columnFields: string[]) {
    const table = tableController({ columnFields });
    render(<ColumnSettings table={table} fields={FIELDS} rowKey="id" />);
    return table;
  }

  it('lists it by its own name, with only its checkbox to press', async () => {
    const user = userEvent.setup();
    const table = openDropped(['id', 'gone', 'amount']);

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    expect(listed()).toEqual(['id', 'gone', 'warehouse', 'amount']);

    const row = document.querySelector('[data-field="gone"]')!;
    expect(row.hasAttribute('data-broken')).toBe(true);
    expect(
      within(row as HTMLElement)
        .getByRole('button', { name: 'Reorder gone' })
        .hasAttribute('disabled'),
    ).toBe(true);
    expect(
      within(row as HTMLElement)
        .getByRole('button', { name: /^Pinning of gone/ })
        .hasAttribute('disabled'),
    ).toBe(true);

    await user.click(screen.getByRole('checkbox', { name: 'Show gone' }));
    expect(table.setColumns).toHaveBeenCalledWith(['id', 'amount']);
  });

  /**
   * What sets this row apart has to be something other than its colour.
   * The grey it is drawn in used to be the whole of it — `oklch(0.556)`
   * against `oklch(0.145)`, no icon, no word, no title — which is no
   * difference at all to a reader who cannot tell those two apart, and
   * information carried by colour alone is WCAG 1.4.1. So the row says it,
   * in words that are drawn and not only announced, and the checkbox that
   * repairs the config points at that sentence rather than at a paragraph
   * elsewhere in the panel.
   */
  it('marks itself in words, on the row, not by its colour', async () => {
    const user = userEvent.setup();
    openDropped(['id', 'gone', 'amount']);

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    const row = document.querySelector<HTMLElement>('[data-field="gone"]')!;

    const note = within(row).getByText(
      defaultMessages['label.columns.unknown'],
    );
    // Drawn, not read out only: a sentence nobody can see would leave the
    // row looking exactly like the ones above it again.
    expect(note.className).not.toContain('sr-only');
    // And an icon beside it, so the row is marked before it is read.
    expect(note.querySelector('svg')).toBeTruthy();

    for (const control of [
      screen.getByRole('checkbox', { name: 'Show gone' }),
      within(row).getByRole('button', { name: 'Reorder gone' }),
      within(row).getByRole('button', { name: /^Pinning of gone/ }),
    ])
      expect(control.getAttribute('aria-describedby')).toBe(note.id);
  });

  /**
   * The last-column guard exists so a table is never left with nothing in
   * it; a broken column puts nothing in it either, so guarding it would
   * lock the one control that repairs the config.
   */
  it('can be switched off even when it is the only column', async () => {
    const user = userEvent.setup();
    const table = openDropped(['gone']);

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    const box = screen.getByRole('checkbox', { name: 'Show gone' });
    expect(box.getAttribute('aria-disabled')).not.toBe('true');

    await user.click(box);
    expect(table.setColumns).toHaveBeenCalledWith([]);
  });

  /** One row per column, so one press takes a field listed twice out. */
  it('shows a field listed twice once, and removes both', async () => {
    const user = userEvent.setup();
    const table = openDropped(['id', 'amount', 'amount']);

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    expect(listed()).toEqual(['id', 'warehouse', 'amount']);

    await user.click(screen.getByRole('checkbox', { name: 'Show Amount' }));
    expect(table.setColumns).toHaveBeenCalledWith(['id']);
  });
});

/**
 * A summary may name a field that is not a column and that the definition
 * does not declare either. This package's UI cannot write such a config; a
 * hand-written one or one migrated from an older release can. The kernel
 * refuses it, which blocks the query and the save, while the settings listed
 * columns only — so the one setting at fault was the one nothing on screen
 * could reach. It is a column setting (D17-9), and it wears the broken row's
 * shape with the checkbox pointed at the summary instead of the column.
 */
describe('a summary on a field that is not a column', () => {
  function openOrphan(overrides: Partial<RecordTableController> = {}) {
    const table = tableController({
      columnFields: ['id', 'amount'],
      summaryFields: ['gone'],
      summaryOf: (field: string) =>
        field === 'gone' ? ('SUM' as const) : null,
      ...overrides,
    });
    render(<ColumnSettings table={table} fields={FIELDS} rowKey="id" />);
    return table;
  }

  it('lists it as a broken row, in its own words', async () => {
    const user = userEvent.setup();
    openOrphan();

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    // Listed in the areas the table draws: the row key on the left, the
    // last column held on the right, and this one in the middle beside the
    // fields that are switched off.
    expect(listed()).toEqual(['id', 'gone', 'warehouse', 'amount']);

    const row = document.querySelector<HTMLElement>('[data-field="gone"]')!;
    expect(row.hasAttribute('data-broken')).toBe(true);
    // Not the sentence a dropped column wears: this one never was a column,
    // so "this column is not in the data any more" would name something the
    // reader cannot find in the settings either.
    const note = within(row).getByText(
      defaultMessages['label.columns.summary-unknown'],
    );
    expect(note.className).not.toContain('sr-only');
    expect(
      screen
        .getByRole('checkbox', { name: 'Keep the summary of gone' })
        .getAttribute('aria-describedby'),
    ).toBe(note.id);
  });

  it('takes the summary out and leaves every column where it is', async () => {
    const user = userEvent.setup();
    const table = openOrphan();

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    await user.click(
      screen.getByRole('checkbox', { name: 'Keep the summary of gone' }),
    );

    expect(table.setSummary).toHaveBeenCalledWith('gone', null);
    expect(table.setColumns).not.toHaveBeenCalled();
    expect(table.setColumnOrder).not.toHaveBeenCalled();
  });

  /** The finding that row exists for, and what pressing it leaves behind. */
  it('clears the finding the config was being refused over', () => {
    const codes = (config: RecordViewConfig) =>
      validateRecord(ordersDefinition(), config, builtinFieldKinds).map(
        found => found.code,
      );

    expect(
      codes(recordConfig({ summaries: [{ field: 'gone', fn: 'SUM' }] })),
    ).toEqual(['record.field.unknown']);
    // What `setSummary(field, null)` writes: that field's summaries gone,
    // and `table.columns` byte for byte what it was.
    expect(codes(recordConfig({ summaries: [] }))).toEqual([]);
  });

  /**
   * A field the definition still declares is already listed — switched off,
   * with its summary on it — so it is not one of these rows: it can be shown
   * again, which a field that is not in the data cannot.
   */
  it('leaves a summary on a declared field where it already is', async () => {
    const user = userEvent.setup();
    openOrphan({
      summaryFields: ['warehouse'],
      summaryOf: (field: string) =>
        field === 'warehouse' ? ('SUM' as const) : null,
    });

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    expect(listed()).toEqual(['id', 'warehouse', 'amount']);

    const row = document.querySelector<HTMLElement>(
      '[data-field="warehouse"]',
    )!;
    expect(row.hasAttribute('data-broken')).toBe(false);
    expect(
      screen.getByRole('checkbox', { name: 'Show Warehouse' }),
    ).toBeDefined();
  });
});

describe('moving a column with the keyboard', () => {
  /**
   * Four columns, because two of them are the ends: the row key leads and
   * `note` is held at the back, which leaves `amount` and `warehouse` as
   * the two the keyboard can move past each other.
   */
  function openFour(
    columnFields: string[] = ['id', 'amount', 'warehouse', 'note'],
    props = {},
  ) {
    const table = tableController({ columnFields });
    render(
      <ColumnSettings
        table={table}
        fields={[...FIELDS, NOTE]}
        rowKey="id"
        {...props}
      />,
    );
    return table;
  }

  async function twoMovable() {
    const user = userEvent.setup();
    const table = openFour();
    await user.click(screen.getByRole('button', { name: /Columns/ }));
    return { user, table };
  }

  it('moves the row down and says where it landed', async () => {
    const { user, table } = await twoMovable();

    const handle = screen.getByRole('button', { name: 'Reorder Amount' });
    handle.focus();
    expect(document.activeElement).toBe(handle);
    await user.keyboard('{ArrowDown}');

    expect(table.setColumnOrder).toHaveBeenCalledWith([
      'id',
      'warehouse',
      'amount',
      'note',
    ]);
    expect(announced()).toBe('Amount moved to position 3 of 4');
  });

  /**
   * Counted over what the reader is looking at. The action column is on
   * screen and is not in the config, so counting configured columns alone
   * said "of 3" to someone looking at four.
   */
  /**
   * Counted over the columns the table draws. A broken one is not drawn —
   * `projectRecord` leaves it out — so counting it told someone looking at
   * two columns that a row had landed "2 of 3".
   */
  it('leaves a broken column out of the position it announces', async () => {
    const user = userEvent.setup();
    openFour(['id', 'gone', 'amount', 'warehouse', 'note']);
    await user.click(screen.getByRole('button', { name: /Columns/ }));

    screen.getByRole('button', { name: 'Reorder Amount' }).focus();
    await user.keyboard('{ArrowDown}');

    expect(announced()).toBe('Amount moved to position 3 of 4');
  });

  it('counts the action column in the position it announces', async () => {
    const user = userEvent.setup();
    openFour(undefined, { actions: true });
    await user.click(screen.getByRole('button', { name: /Columns/ }));

    screen.getByRole('button', { name: 'Reorder Amount' }).focus();
    await user.keyboard('{ArrowDown}');

    expect(announced()).toBe('Amount moved to position 3 of 5');
  });

  it('moves the row up', async () => {
    const { user, table } = await twoMovable();

    screen.getByRole('button', { name: 'Reorder Warehouse' }).focus();
    await user.keyboard('{ArrowUp}');

    expect(table.setColumnOrder).toHaveBeenCalledWith([
      'id',
      'warehouse',
      'amount',
      'note',
    ]);
  });

  /** The top row has nowhere up to go, and neither does a stray key. */
  it('does nothing at the end of the area, or on another key', async () => {
    const { user, table } = await twoMovable();

    screen.getByRole('button', { name: 'Reorder Amount' }).focus();
    await user.keyboard('{ArrowUp}');
    await user.keyboard('{ArrowLeft}');

    expect(table.setColumnOrder).not.toHaveBeenCalled();
    expect(announced()).toBe('');
  });

  /**
   * A popover that closed onto nothing leaves the keyboard at the top of the
   * page, which is the other end of the toolbar from where the user was.
   */
  it('gives the focus back to the trigger when it closes', async () => {
    const user = userEvent.setup();
    open();
    const trigger = screen.getByRole('button', { name: /Columns/ });

    await user.click(trigger);
    expect(screen.getByRole('button', { name: 'Reorder Amount' })).toBeTruthy();

    await user.keyboard('{Escape}');
    await waitFor(() => expect(document.activeElement).toBe(trigger));
  });
});

describe('what a drag says out loud', () => {
  const accessibility = columnDragAccessibility(
    formattersFor(defaultMessages),
    field => (field === 'amount' ? 'Amount' : field),
  );

  it('names the column in the reader’s own words', () => {
    expect(
      accessibility.announcements.dragstart({
        operation: { source: { id: 'amount' } },
      }),
    ).toBe('Amount picked up');
  });

  /** A completed drop is announced by the settings, so it says nothing here. */
  it('speaks only when a drag is given up', () => {
    const dropped = { operation: { source: { id: 'amount' } } };

    expect(accessibility.announcements.dragend(dropped)).toBeUndefined();
    expect(
      accessibility.announcements.dragend({ ...dropped, canceled: true }),
    ).toBe('Move cancelled; Amount stayed where it was');
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
      defaultMessages['label.columns.instructions'],
    );
  });
});
