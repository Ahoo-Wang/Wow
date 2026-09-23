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
 * The commands the column settings and the sort control write through:
 * order, pinning, summaries and the whole sort. Each is an `edit` followed
 * by an `apply`, like the column and sort commands that were here before —
 * the table renders the result the kernel projected, so a change that is not
 * applied is a change nobody can see.
 */

import { MAX_CURSOR_SORT_FIELDS } from '@ahoo-wang/fetcher-wow';
import {
  act,
  cleanup,
  render,
  renderHook,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type DataViewDefinition,
  type RecordViewConfig,
  type FilterTree,
  type RecordViewRuntime,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { useOpenView, useRecordTable } from '../src/react/index.js';
import { ResultToolbar } from '../src/ui/ResultToolbar.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';

afterEach(cleanup);

/**
 * Orders with two summarisable fields and two sortable ones, so a command
 * that replaces one of several has something to leave alone. Everything the
 * commands write is admitted, so `apply` runs and the assertions are about
 * the command rather than about a validation error.
 */
function definition(): DataViewDefinition {
  return ordersDefinition({
    fields: ordersDefinition().fields.map(field => {
      if (field.name === 'amount')
        return { ...field, summary: ['SUM', 'AVG', 'MAX'] as const };
      if (field.name === 'warehouse')
        return { ...field, summary: ['COUNT'] as const };
      if (field.name === 'id') return { ...field, sortable: true };
      return field;
    }),
  });
}

const mine: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'Mine',
  scope: 'personal',
  revision: '1',
  config: recordConfig(),
};

/**
 * Opens the view and waits for rows. A config the definition refuses never
 * runs — `apply` is blocked while the draft holds an error — so a suite
 * about such a config passes `ready: false` and reads the draft instead.
 */
async function openTable(
  config?: Partial<RecordViewConfig>,
  overrides: Partial<DataViewDefinition> = {},
  ready = true,
  source: ViewSource = testSource(),
) {
  const engine = new ViewEngine({
    definitions: [{ ...definition(), ...overrides }],
    store: new MemoryViewStore({
      instances: [config ? { ...mine, config: recordConfig(config) } : mine],
    }),
    resolveSource: () => source,
  });
  const { result } = renderHook(() => {
    const opened = useOpenView(engine, 'orders-1');
    return {
      runtime: opened.runtime as RecordViewRuntime | null,
      table: useRecordTable(opened.runtime as RecordViewRuntime | null),
    };
  });
  if (ready)
    await waitFor(() => expect(result.current.table.status).toBe('success'));
  else await waitFor(() => expect(result.current.runtime).not.toBeNull());
  return result;
}

/** The draft as the runtime holds it, which is what a save would write. */
function draft(result: {
  current: { runtime: RecordViewRuntime | null };
}): RecordViewConfig {
  return result.current.runtime!.getSnapshot().draft;
}

describe('setColumnOrder', () => {
  it('puts the columns in the order it is given, and applies at once', async () => {
    const result = await openTable();

    act(() => result.current.table.setColumnOrder(['amount', 'id']));

    await waitFor(() =>
      expect(result.current.table.columnFields).toEqual(['amount', 'id']),
    );
    // The result on screen answers the new order rather than the old one:
    // the kernel projects columns from the config that ran. The row key
    // still leads it, because that is decided where the table reads the
    // order rather than by whoever wrote the config — the same place its
    // left pin is decided, and for the same reason.
    expect(result.current.table.columns.map(column => column.field)).toEqual([
      'id',
      'amount',
    ]);
  });

  /**
   * Each column is reused rather than rebuilt from its name, so a width or a
   * pinning set earlier survives a reorder instead of being dropped on the
   * next save.
   */
  it('carries each column’s own settings through the move', async () => {
    const result = await openTable({
      table: {
        columns: [
          { field: 'id', pinned: true },
          { field: 'amount', width: 120 },
        ],
      },
    });

    act(() => result.current.table.setColumnOrder(['amount', 'id']));

    await waitFor(() =>
      expect(draft(result).table.columns).toEqual([
        { field: 'amount', width: 120 },
        { field: 'id', pinned: true },
      ]),
    );
  });

  /**
   * A control that knows about one area of the table names only that area.
   * Dropping everything it did not mention would empty the table from a
   * control that was asked to reorder two of its columns.
   */
  it('keeps a column it was not told about, at the end', async () => {
    const result = await openTable({
      table: {
        columns: [{ field: 'id' }, { field: 'warehouse' }, { field: 'amount' }],
      },
    });

    act(() => result.current.table.setColumnOrder(['amount', 'id']));

    await waitFor(() =>
      expect(result.current.table.columnFields).toEqual([
        'amount',
        'id',
        'warehouse',
      ]),
    );
  });

  it('ignores an unknown name and a name said twice', async () => {
    const result = await openTable();

    act(() =>
      result.current.table.setColumnOrder(['amount', 'amount', 'gone', 'id']),
    );

    await waitFor(() =>
      expect(result.current.table.columnFields).toEqual(['amount', 'id']),
    );
  });
});

describe('setPinned', () => {
  it('holds a column against the left edge and lets it go again', async () => {
    const result = await openTable();

    act(() => result.current.table.setPinned('amount', true));
    await waitFor(() =>
      expect(result.current.table.pinnedOf('amount')).toBe(true),
    );
    expect(result.current.table.pinnedOf('id')).toBe(false);

    act(() => result.current.table.setPinned('amount', false));
    await waitFor(() =>
      expect(result.current.table.pinnedOf('amount')).toBe(false),
    );
  });

  /**
   * A config is JSON, and `{ pinned: undefined }` is not the same object as
   * one without the key: it reads as a difference against the saved baseline
   * for the rest of the session, so a column pinned and unpinned again is
   * the config it started as.
   */
  it('leaves no trace of a pinning that was taken back', async () => {
    const result = await openTable();
    const before = draft(result).table.columns;

    act(() => result.current.table.setPinned('amount', true));
    await waitFor(() =>
      expect(result.current.table.pinnedOf('amount')).toBe(true),
    );
    act(() => result.current.table.setPinned('amount', false));

    await waitFor(() => expect(draft(result).table.columns).toEqual(before));
    expect(Object.keys(draft(result).table.columns[1]).includes('pinned')).toBe(
      false,
    );
  });

  /**
   * A pinning is one yes-or-no (D19), so a stored `'left'` is not one. Read
   * raw it became a key the catalogue has never heard of and took the
   * settings popover — and the workbench around it — down; read through
   * `columnPinned` the controller's declared type is true of it.
   */
  it('reports a pinning that is not a yes-or-no as no pinning', async () => {
    const result = await openTable(
      {
        table: {
          columns: [
            { field: 'id' },
            { field: 'amount', pinned: 'left' },
            { field: 'warehouse' },
          ],
        },
      } as unknown as Partial<RecordViewConfig>,
      {},
      false,
    );

    expect(result.current.table.pinnedOf('amount')).toBe(false);
    // And it is a finding rather than a silence: the view waits to be fixed.
    expect(result.current.table.status).toBe('idle');
  });

  it('does nothing to a column the draft does not hold', async () => {
    const result = await openTable();

    act(() => result.current.table.setPinned('gone', true));

    await waitFor(() =>
      expect(result.current.table.columnFields).toEqual(['id', 'amount']),
    );
    expect(result.current.table.pinnedOf('gone')).toBe(false);
  });
});

describe('setColumnWidth', () => {
  it('writes a width onto one column, and applies at once', async () => {
    const result = await openTable();

    act(() => result.current.table.setColumnWidth('amount', 180));

    await waitFor(() =>
      expect(
        result.current.table.columns.find(column => column.field === 'amount')
          ?.width,
      ).toBe(180),
    );
    // The result on screen carries it, which is the whole point of applying:
    // the table draws the columns the kernel projected from the config that
    // ran, so a width that was only edited would be a width nobody can see.
    expect(draft(result).table.columns).toEqual([
      { field: 'id' },
      { field: 'amount', width: 180 },
    ]);
  });

  /**
   * The same rule `setPinned` follows, for the same reason: a config is
   * JSON, and `{ width: undefined }` is not the object one without the key
   * is. A column sized and put back would otherwise read as a difference
   * against the saved baseline for the rest of the session.
   */
  it('deletes the member rather than setting it to undefined', async () => {
    const result = await openTable();
    const before = draft(result).table.columns;

    act(() => result.current.table.setColumnWidth('amount', 180));
    await waitFor(() => expect(draft(result).table.columns[1].width).toBe(180));
    act(() => result.current.table.setColumnWidth('amount', null));

    await waitFor(() => expect(draft(result).table.columns).toEqual(before));
    expect(Object.keys(draft(result).table.columns[1])).toEqual(['field']);
  });

  /** A width is one column's business; the pinning beside it is not. */
  it('keeps whatever else the column was configured with', async () => {
    const result = await openTable();

    act(() => result.current.table.setPinned('amount', true));
    await waitFor(() =>
      expect(result.current.table.pinnedOf('amount')).toBe(true),
    );
    act(() => result.current.table.setColumnWidth('amount', 96));

    await waitFor(() =>
      expect(draft(result).table.columns[1]).toEqual({
        field: 'amount',
        width: 96,
        pinned: true,
      }),
    );
  });

  it('does nothing to a column the draft does not hold', async () => {
    const result = await openTable();

    act(() => result.current.table.setColumnWidth('gone', 120));

    await waitFor(() =>
      expect(result.current.table.columnFields).toEqual(['id', 'amount']),
    );
    expect(draft(result).table.columns).toEqual([
      { field: 'id' },
      { field: 'amount' },
    ]);
  });

  /**
   * A width that is not a positive finite number of pixels is a finding
   * rather than a silence: `width: 0` draws a column nobody can see or take
   * hold of again, and the `NaN` a hand-written `"120px"` becomes lands as
   * an inline style the browser drops — the column then keeps its old size
   * while the config claims otherwise.
   */
  it('is refused by the kernel when the stored width is not a size', async () => {
    const result = await openTable(
      {
        table: { columns: [{ field: 'id' }, { field: 'amount', width: 0 }] },
      },
      {},
      false,
    );

    expect(result.current.table.status).toBe('idle');
    expect(
      result.current.runtime!.getSnapshot().issues.map(issue => issue.code),
    ).toContain('record.column.width-invalid');
  });
});

describe('setLayout', () => {
  /**
   * Both layouts draw the same result, so switching normally needs no query
   * — but a view whose saved layout the definition no longer allows has no
   * result at all: `apply` was refused on open and `refresh` is a no-op
   * until something has been admitted, so the switch that repairs it would
   * have left the screen as empty as it found it.
   */
  it('runs the first query when the layout was the thing refused', async () => {
    const result = await openTable(
      { layout: 'card' },
      { record: { rowKey: 'id', paging: 'paged', layouts: ['table'] } },
      false,
    );
    expect(result.current.table.status).toBe('idle');

    act(() => result.current.table.setLayout('table'));

    await waitFor(() => expect(result.current.table.status).toBe('success'));
    expect(result.current.table.rows.length).toBeGreaterThan(0);
  });

  /** A view that is already running only changes shape; the rows stay put. */
  it('does not re-query a view that already has a result', async () => {
    // At a size the card ladder holds, so nothing but the layout changes;
    // a size the switch has to move is a new query (useRecordTable.test).
    const result = await openTable({ pageSize: 24 });
    const before = result.current.runtime!.getSnapshot().result;

    act(() => result.current.table.setLayout('card'));

    await waitFor(() => expect(result.current.table.layout).toBe('card'));
    expect(result.current.runtime!.getSnapshot().result).toBe(before);
  });
});

describe('setColumns', () => {
  /**
   * A summary belongs to a column. Left behind when the column is hidden,
   * the runtime keeps asking for an aggregate with nowhere to appear — the
   * scope row stands empty and a failure warns about a summary nobody can
   * see — and the settings disable the select that would clear it, so there
   * is no way back except showing the column again.
   */
  it('takes a hidden column\u2019s summary with it', async () => {
    const result = await openTable({
      summaries: [
        { field: 'amount', fn: 'SUM' },
        { field: 'warehouse', fn: 'COUNT' },
      ],
      table: {
        columns: [{ field: 'id' }, { field: 'amount' }, { field: 'warehouse' }],
      },
    });

    act(() => result.current.table.setColumns(['id', 'warehouse']));

    await waitFor(() =>
      expect(draft(result).summaries).toEqual([
        { field: 'warehouse', fn: 'COUNT' },
      ]),
    );
    expect(result.current.table.summaryOf('amount')).toBeNull();
  });

  it('leaves the summaries of the columns that stay', async () => {
    const result = await openTable({
      summaries: [{ field: 'amount', fn: 'SUM' }],
    });

    act(() => result.current.table.setColumns(['id', 'amount', 'warehouse']));

    await waitFor(() =>
      expect(result.current.table.columnFields).toEqual([
        'id',
        'amount',
        'warehouse',
      ]),
    );
    expect(draft(result).summaries).toEqual([{ field: 'amount', fn: 'SUM' }]);
  });
});

/**
 * A column the user switches off keeps its entry — `hidden: true` — so it
 * keeps its place in the order and comes back where it was (D17-8). Before
 * this the entry was deleted, and every column ever switched back on landed
 * at the end of the table.
 */
describe('switching a column off', () => {
  const three = {
    table: {
      columns: [{ field: 'id' }, { field: 'amount' }, { field: 'warehouse' }],
    },
  };

  it('hides it in place rather than taking it out of the config', async () => {
    const result = await openTable(three);

    act(() => result.current.table.setColumns(['id', 'warehouse']));

    await waitFor(() =>
      expect(draft(result).table.columns).toEqual([
        { field: 'id' },
        { field: 'amount', hidden: true },
        { field: 'warehouse' },
      ]),
    );
    expect(result.current.table.hiddenOf('amount')).toBe(true);
    // The place is kept in the list a control reads, and the table simply
    // does not draw the column.
    expect(result.current.table.columnFields).toEqual([
      'id',
      'amount',
      'warehouse',
    ]);
    expect(result.current.table.columns.map(column => column.field)).toEqual([
      'id',
      'warehouse',
    ]);
  });

  /**
   * And back on where it is: the second slot, not the end. The whole point
   * of the member is that putting a column back is one click rather than a
   * click and a drag.
   */
  it('puts it back in its own place, with no member left behind', async () => {
    const result = await openTable(three);

    act(() => result.current.table.setColumns(['id', 'warehouse']));
    await waitFor(() =>
      expect(result.current.table.hiddenOf('amount')).toBe(true),
    );
    act(() => result.current.table.setColumns(['id', 'amount', 'warehouse']));

    // `{ hidden: undefined }` is not the same object as no member at all:
    // a view switched off and on again would read as unsaved for the rest
    // of the session, the same trap `pinned` and `width` are rebuilt for.
    await waitFor(() =>
      expect(draft(result).table.columns).toEqual([
        { field: 'id' },
        { field: 'amount' },
        { field: 'warehouse' },
      ]),
    );
  });

  /** Hiding a column clears nothing else it was configured with. */
  it('keeps the width and the pinning it comes back with', async () => {
    const result = await openTable({
      table: {
        columns: [
          { field: 'id' },
          { field: 'amount', width: 180, pinned: true },
          { field: 'warehouse' },
        ],
      },
    });

    act(() => result.current.table.setColumns(['id', 'warehouse']));

    await waitFor(() =>
      expect(draft(result).table.columns[1]).toEqual({
        field: 'amount',
        width: 180,
        pinned: true,
        hidden: true,
      }),
    );
  });

  /** A place in the order is a place a switched-off column can be moved to. */
  it('is ordered along with the columns the table draws', async () => {
    const result = await openTable(three);

    act(() => result.current.table.setColumns(['id', 'warehouse']));
    await waitFor(() =>
      expect(result.current.table.hiddenOf('amount')).toBe(true),
    );
    act(() =>
      result.current.table.setColumnOrder(['id', 'amount', 'warehouse']),
    );

    await waitFor(() =>
      expect(draft(result).table.columns.map(column => column.field)).toEqual([
        'id',
        'amount',
        'warehouse',
      ]),
    );
    expect(draft(result).table.columns[1].hidden).toBe(true);
  });

  /**
   * Except where there is nothing to come back to. A column the definition
   * no longer offers is a config `validateRecord` refuses, which blocks the
   * query and the save, and its checkbox is the one control that repairs it
   * — hiding it would leave the view blocked by the control that had just
   * run. A second entry for a column already kept goes the same way: the
   * settings draw one row, so one checkbox answers for both.
   */
  it('takes out what it cannot put back: a dropped field, a repeat', async () => {
    const result = await openTable(
      {
        table: {
          columns: [
            { field: 'id' },
            { field: 'gone' },
            { field: 'amount' },
            { field: 'amount' },
          ],
        },
      },
      {},
      false,
    );

    act(() => result.current.table.setColumns(['id', 'amount']));

    await waitFor(() => expect(result.current.table.status).toBe('success'));
    expect(draft(result).table.columns).toEqual([
      { field: 'id' },
      { field: 'amount' },
    ]);
  });

  /** A field the config has never named has no place yet, so it joins one. */
  it('appends a field the config has never mentioned', async () => {
    const result = await openTable(three);

    act(() =>
      result.current.table.setColumns([
        'id',
        'amount',
        'warehouse',
        'createdAt',
      ]),
    );

    await waitFor(() =>
      expect(result.current.table.columnFields).toEqual([
        'id',
        'amount',
        'warehouse',
        'createdAt',
      ]),
    );
  });
});

describe('setSummary', () => {
  it('summarises a column, and stops', async () => {
    const result = await openTable();

    act(() => result.current.table.setSummary('amount', 'SUM'));
    await waitFor(() =>
      expect(result.current.table.summaryOf('amount')).toBe('SUM'),
    );
    expect(draft(result).summaries).toEqual([{ field: 'amount', fn: 'SUM' }]);

    act(() => result.current.table.setSummary('amount', null));
    await waitFor(() =>
      expect(result.current.table.summaryOf('amount')).toBeNull(),
    );
    // The saved config has no `summaries` member, so neither has this one
    // again — see "leaves the view as saved once the last summary goes".
    expect('summaries' in draft(result)).toBe(false);
  });

  /**
   * `summaries` is optional, so "none" is spelled two ways, and `dirty` is
   * an equality against the saved config — which cannot tell a shape from a
   * change. Adding a summary and taking it away again used to leave the
   * view unsaved for the rest of the session, with the leave guard asking
   * about an edit that had already been undone.
   */
  it('leaves the view as saved once the last summary goes', async () => {
    const result = await openTable();
    expect(result.current.runtime!.getSnapshot().dirty).toBe(false);

    act(() => result.current.table.setSummary('amount', 'SUM'));
    await waitFor(() =>
      expect(result.current.runtime!.getSnapshot().dirty).toBe(true),
    );

    act(() => result.current.table.setSummary('amount', null));

    await waitFor(() =>
      expect(result.current.runtime!.getSnapshot().dirty).toBe(false),
    );
  });

  /** And a config that spells it `[]` gets `[]` back, for the same reason. */
  it('keeps an empty list where the saved config used one', async () => {
    const result = await openTable({ summaries: [] });

    act(() => result.current.table.setSummary('amount', 'SUM'));
    await waitFor(() =>
      expect(result.current.table.summaryOf('amount')).toBe('SUM'),
    );
    act(() => result.current.table.setSummary('amount', null));

    await waitFor(() => expect(draft(result).summaries).toEqual([]));
    expect(result.current.runtime!.getSnapshot().dirty).toBe(false);
  });

  /**
   * The settings offer a column one summary, so setting one replaces
   * whatever that column had — and leaves every other column alone.
   */
  it('replaces a column’s own summary and no other', async () => {
    const result = await openTable({
      summaries: [
        { field: 'amount', fn: 'SUM' },
        { field: 'warehouse', fn: 'COUNT' },
      ],
    });

    act(() => result.current.table.setSummary('amount', 'MAX'));

    await waitFor(() =>
      expect(draft(result).summaries).toEqual([
        { field: 'warehouse', fn: 'COUNT' },
        { field: 'amount', fn: 'MAX' },
      ]),
    );
  });
});

/**
 * The controller is the boundary. A config comes from a store, so its lists
 * may be objects, strings, or arrays with `null` in them; admission reports
 * the shape and the draft rightly stays in the error state, but the editor
 * that would let the user delete the offending entry is rendered from that
 * same draft. What the controller hands the UI is therefore always a safely
 * iterable list of well-formed entries — and dropping what cannot be read is
 * the repair, since the first change writes the sound list back.
 */
describe('toggleSort, exclusive', () => {
  /**
   * A plain click on a header means "order the rows by this": the cycled
   * column is the whole sort, whatever else was sorted, in one write.
   */
  it('makes the cycled column the whole sort', async () => {
    const result = await openTable();

    act(() => result.current.table.toggleSort('amount'));
    act(() => result.current.table.toggleSort('id'));
    expect(result.current.table.sort).toEqual([
      { field: 'amount', direction: 'ASC' },
      { field: 'id', direction: 'ASC' },
    ]);

    // The column keeps cycling from where it stood — id was ascending, so
    // it turns descending — and the others are dropped.
    act(() => result.current.table.toggleSort('id', { exclusive: true }));
    expect(result.current.table.sort).toEqual([
      { field: 'id', direction: 'DESC' },
    ]);

    // A fresh column starts ascending, alone.
    act(() => result.current.table.toggleSort('amount', { exclusive: true }));
    expect(result.current.table.sort).toEqual([
      { field: 'amount', direction: 'ASC' },
    ]);

    // And off is off.
    act(() => result.current.table.toggleSort('amount', { exclusive: true }));
    act(() => result.current.table.toggleSort('amount', { exclusive: true }));
    expect(result.current.table.sort).toEqual([]);
  });
});

describe('the shapes a store can hold', () => {
  const broken = {
    sort: 'amount',
    summaries: { field: 'amount', fn: 'SUM' },
    table: { columns: [null, { field: 'amount' }, 'id'] },
  } as unknown as Partial<RecordViewConfig>;

  it('hands the UI lists whatever the config holds', async () => {
    const result = await openTable(broken, {}, false);
    const table = result.current.table;

    expect(table.sort).toEqual([]);
    expect(table.columnFields).toEqual(['amount']);
    expect(table.sortOf('amount')).toBeNull();
    expect(table.summaryOf('amount')).toBeNull();
    expect(table.pinnedOf('amount')).toBe(false);
    // And the view waits to be fixed rather than pretending to be fine.
    expect(table.status).toBe('idle');
  });

  /**
   * The proof that the boundary is where the rule says it is: the panels do
   * no defensive reading of their own, so this renders them against a real
   * controller over a config a store could hold. Before the controller
   * normalised, `.map` over a string took the workbench down from inside
   * the toolbar, and the user never reached the entry that caused it.
   */
  it('lets the panels render against a config a store could hold', async () => {
    const result = await openTable(broken, {}, false);

    expect(() =>
      render(
        <ResultToolbar
          table={result.current.table}
          fields={definition().fields}
          runtime={result.current.runtime!}
        />,
      ),
    ).not.toThrow();
    expect(screen.getByRole('button', { name: /Columns/ })).toBeTruthy();
  });

  it('lets every command run against them without throwing', async () => {
    const result = await openTable(broken, {}, false);

    expect(() => {
      act(() => result.current.table.toggleSort('amount'));
      act(() => result.current.table.setColumns(['amount']));
      act(() => result.current.table.setColumnOrder(['amount']));
      act(() => result.current.table.setPinned('amount', true));
      act(() => result.current.table.setSummary('amount', 'SUM'));
    }).not.toThrow();
  });

  /**
   * A list that could not be read at all lists nothing, so no control can
   * take its entries out — the sort above has no sortable field left to
   * add, which was its only other way out. Whatever the user changes
   * carries the repair with it, so one press anywhere puts the config back
   * in a shape the kernel admits.
   */
  it('writes the sound lists back with the first change of any kind', async () => {
    const result = await openTable(broken, {}, false);

    act(() => result.current.table.setPinned('amount', true));

    await waitFor(() => expect(result.current.table.status).toBe('success'));
    expect(draft(result).sort).toEqual([]);
    expect(draft(result).summaries).toEqual([]);
    expect(draft(result).table.columns).toEqual([
      { field: 'amount', pinned: true },
    ]);
  });

  /** Entries that cannot be read go; the ones that can are kept as they are. */
  it('keeps the entries it can read and drops the rest', async () => {
    const result = await openTable(
      {
        sort: [
          null,
          { field: 'amount', direction: 'up' },
          { direction: 'ASC' },
        ],
        summaries: [null, { field: 'amount', fn: 'NOPE' }],
      } as unknown as Partial<RecordViewConfig>,
      {},
      false,
    );

    expect(result.current.table.sort).toEqual([
      { field: 'amount', direction: 'ASC' },
    ]);
    expect(result.current.table.summaryOf('amount')).toBeNull();
  });
});

describe('maxSortFields', () => {
  /**
   * A control that offers a sort field has to stop where the kernel starts
   * refusing, so the ceiling comes from the kernel rather than being spelled
   * a second time in the UI.
   */
  it('answers the cursor ceiling for a cursor source', async () => {
    const result = await openTable(undefined, {
      record: { rowKey: 'id', paging: 'cursor', layouts: ['table'] },
    });

    // One of Wow's slots is the row key the query ends on.
    expect(result.current.table.maxSortFields).toBe(MAX_CURSOR_SORT_FIELDS - 1);
  });

  it('is bounded only by the fields there are on a paged source', async () => {
    const result = await openTable();

    expect(result.current.table.maxSortFields).toBe(definition().fields.length);
  });
});

describe('setSort', () => {
  it('replaces the whole sort in priority order', async () => {
    const result = await openTable();

    act(() =>
      result.current.table.setSort([
        { field: 'amount', direction: 'DESC' },
        { field: 'id', direction: 'ASC' },
      ]),
    );

    await waitFor(() =>
      expect(result.current.table.sort).toEqual([
        { field: 'amount', direction: 'DESC' },
        { field: 'id', direction: 'ASC' },
      ]),
    );
    expect(result.current.table.sortOf('amount')).toBe('DESC');
  });

  it('clears the sort, which is a sort of none', async () => {
    const result = await openTable({
      sort: [{ field: 'amount', direction: 'ASC' }],
    });

    act(() => result.current.table.setSort([]));

    await waitFor(() => expect(result.current.table.sort).toEqual([]));
  });
});

/**
 * A sort is one member of the question, and pressing for it says nothing
 * about the others. So a sort runs at once only while nothing else in the
 * draft waits for Apply; with a condition still waiting, it joins it and
 * Apply runs the two together — the rule the analysis table's header keeps
 * (`sortNow`, #1807). Before this, a header press ran the whole draft and
 * applied the waiting condition on the user's behalf.
 */
describe('a sort never applies what else waits', () => {
  const inCN: FilterTree = {
    op: 'and',
    children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
  };

  async function waiting() {
    const source = testSource();
    const result = await openTable(undefined, {}, true, source);
    // A condition edited in the range and not applied.
    act(() => result.current.runtime!.edit({ filter: inCN }));
    return { result, source, asked: vi.mocked(source.paged).mock.calls.length };
  }

  function applied(result: {
    current: { runtime: RecordViewRuntime | null };
  }): RecordViewConfig {
    return result.current.runtime!.getSnapshot().applied;
  }

  it('runs a header press at once when nothing else waits', async () => {
    const source = testSource();
    const result = await openTable(undefined, {}, true, source);
    const asked = vi.mocked(source.paged).mock.calls.length;

    act(() => result.current.table.toggleSort('amount', { exclusive: true }));

    expect(applied(result).sort).toEqual([
      { field: 'amount', direction: 'ASC' },
    ]);
    await waitFor(() =>
      expect(result.current.table.ranSort).toEqual([
        { field: 'amount', direction: 'ASC' },
      ]),
    );
    expect(vi.mocked(source.paged).mock.calls.length).toBe(asked + 1);
  });

  it('holds a header press back while a condition waits, and Apply runs both', async () => {
    const { result, source, asked } = await waiting();

    act(() => result.current.table.toggleSort('amount', { exclusive: true }));

    // The sort is written — the next press cycles from it — but nothing ran.
    expect(result.current.table.sort).toEqual([
      { field: 'amount', direction: 'ASC' },
    ]);
    expect(applied(result).filter).toEqual({ op: 'and', children: [] });
    expect(applied(result).sort).toEqual([]);
    expect(vi.mocked(source.paged).mock.calls.length).toBe(asked);
    // The rows are still in the order they were fetched in.
    expect(result.current.table.ranSort).toEqual([]);

    // A second press goes on from the draft: ascending, then descending.
    act(() => result.current.table.toggleSort('amount', { exclusive: true }));
    expect(result.current.table.sort).toEqual([
      { field: 'amount', direction: 'DESC' },
    ]);
    expect(vi.mocked(source.paged).mock.calls.length).toBe(asked);

    act(() => result.current.runtime!.apply());
    await waitFor(() =>
      expect(result.current.table.ranSort).toEqual([
        { field: 'amount', direction: 'DESC' },
      ]),
    );
    const calls = vi.mocked(source.paged).mock.calls;
    expect(calls).toHaveLength(asked + 1);
    expect(calls[asked][0].filter).toMatchObject({
      field: 'warehouse',
      value: 'CN',
    });
    expect(calls[asked][0].sort?.[0]).toEqual({
      field: 'amount',
      direction: 'DESC',
    });
  });

  it('holds the sort editor back the same way', async () => {
    const { result, source, asked } = await waiting();

    act(() =>
      result.current.table.setSort([{ field: 'id', direction: 'DESC' }]),
    );

    expect(result.current.table.sort).toEqual([
      { field: 'id', direction: 'DESC' },
    ]);
    expect(applied(result).filter).toEqual({ op: 'and', children: [] });
    expect(vi.mocked(source.paged).mock.calls.length).toBe(asked);
  });

  /**
   * The sort a press held back is not "something else" to the next press:
   * that press replaces it. Once the condition is taken back, the header
   * runs again.
   */
  it('runs again once only the sort itself waits', async () => {
    const { result, source, asked } = await waiting();

    act(() => result.current.table.toggleSort('amount', { exclusive: true }));
    act(() => result.current.runtime!.edit({ filter: applied(result).filter }));
    act(() => result.current.table.toggleSort('amount', { exclusive: true }));

    expect(applied(result).sort).toEqual([
      { field: 'amount', direction: 'DESC' },
    ]);
    expect(applied(result).filter).toEqual({ op: 'and', children: [] });
    await waitFor(() =>
      expect(vi.mocked(source.paged).mock.calls.length).toBe(asked + 1),
    );
  });
});
