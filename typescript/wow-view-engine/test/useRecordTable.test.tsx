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
 * The record controller: the columns and rows it hands a table, the sort,
 * paging and layout commands it writes back, which rows are selected, and
 * the page sizes a runtime's limits leave on offer.
 */

import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  type RecordViewRuntime,
  type ViewSource,
  comparePending,
} from '../src/index.js';
import { useOpenView, useRecordTable } from '../src/react/index.js';
import {
  mine,
  ordersDefinition,
  recordConfig,
  ROWS,
  testSource,
} from './fixtures.js';
import { engineWith } from './fixtures/hooks.js';

afterEach(cleanup);

describe('useRecordTable', () => {
  async function openTable(source?: ViewSource) {
    const { engine } = engineWith({ source });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return {
        opened,
        table: useRecordTable(opened.runtime as RecordViewRuntime | null),
      };
    });
    await waitFor(() => expect(result.current.table.status).toBe('success'));
    return result;
  }

  /** More rows than one page holds, so there is a next page to move to. */
  function manyPages(): ViewSource {
    return testSource({
      paged: vi.fn(() => Promise.resolve({ total: 200, list: [...ROWS] })),
    });
  }

  it('is inert without a runtime', () => {
    const { result } = renderHook(() => useRecordTable(null));

    expect(result.current).toMatchObject({
      columns: [],
      rows: [],
      paging: null,
      status: 'idle',
      pageSize: 0,
      layout: 'table',
      layouts: [],
      selectedRows: [],
    });
    expect(result.current.sortOf('id')).toBeNull();
    expect(result.current.pinnedOf('id')).toBeNull();
    expect(result.current.summaryOf('amount')).toBeNull();
    expect(result.current.isSelected('o-1')).toBe(false);
    expect(() => {
      result.current.toggleSort('id');
      result.current.setSort([{ field: 'amount', direction: 'ASC' }]);
      result.current.toggle('o-1');
      result.current.toggleAll();
      result.current.clearSelection();
      result.current.setColumns(['id']);
      result.current.setColumnOrder(['id']);
      result.current.setPinned('id', 'left');
      result.current.setSummary('amount', 'SUM');
      result.current.setLayout('card');
      result.current.setPageSize(10);
      result.current.goTo(2);
      result.current.next();
      result.current.previous();
      result.current.refresh();
    }).not.toThrow();
  });

  /**
   * A size above `maxPageSize` is refused by `validateRecord`, so offering
   * one is offering a way to break the view: the user picks 100 from a list
   * the UI drew and the config stops running. The ladder is cut to what the
   * runtime was admitted under.
   */
  it('offers only the page sizes the limits admit', async () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => testSource(),
      limits: { ...DEFAULT_RUNTIME_LIMITS, maxPageSize: 50 },
    });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return useRecordTable(opened.runtime as RecordViewRuntime | null);
    });
    await waitFor(() => expect(result.current.status).toBe('success'));

    expect(result.current.pageSizes).toEqual([10, 20, 50]);
  });

  /** The ladder is the engine's: a product hands its own in with the budgets. */
  it('offers the page sizes the limits name', async () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => testSource(),
      limits: { ...DEFAULT_RUNTIME_LIMITS, pageSizes: [15, 30, 60] },
    });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return useRecordTable(opened.runtime as RecordViewRuntime | null);
    });
    await waitFor(() => expect(result.current.status).toBe('success'));

    // The saved size (20) is folded in beside the product's own rungs.
    expect(result.current.pageSizes).toEqual([15, 20, 30, 60]);
  });

  /** Whatever it is: a select whose value is not an item of it shows nothing. */
  it('folds the size in force into the ladder', async () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({
        instances: [{ ...mine, config: recordConfig({ pageSize: 25 }) }],
      }),
      resolveSource: () => testSource(),
      limits: { ...DEFAULT_RUNTIME_LIMITS, maxPageSize: 50 },
    });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return useRecordTable(opened.runtime as RecordViewRuntime | null);
    });
    await waitFor(() => expect(result.current.status).toBe('success'));

    expect(result.current.pageSizes).toEqual([10, 20, 25, 50]);
  });

  it('offers the layouts the definition allows', async () => {
    const result = await openTable();

    // A switcher offers these and nothing else — and nothing at all below two.
    expect(result.current.table.layouts).toEqual(['table', 'card']);
  });

  it('gives the selected rows in result order', async () => {
    const result = await openTable();

    act(() => result.current.table.toggle('o-2'));
    act(() => result.current.table.toggle('o-1'));

    // Clicked in reverse, listed as the table lists them: a bulk action names
    // what it is about to touch, and the list has to read like the rows above.
    expect(result.current.table.selection).toEqual(['o-2', 'o-1']);
    expect(result.current.table.selectedRows.map(row => row.key)).toEqual([
      'o-1',
      'o-2',
    ]);

    act(() => result.current.table.clearSelection());
    expect(result.current.table.selectedRows).toEqual([]);
  });

  it('keeps the priority of a column when its direction changes', async () => {
    const result = await openTable();

    act(() => result.current.table.toggleSort('amount'));
    act(() => result.current.table.toggleSort('id'));
    act(() => result.current.table.toggleSort('amount'));

    expect(result.current.table.sort).toEqual([
      { field: 'amount', direction: 'DESC' },
      { field: 'id', direction: 'ASC' },
    ]);
  });

  /**
   * A sort edits and applies in one go — unless the draft holds an error,
   * when `apply` does not land: the header then shows the draft's sort over
   * rows that were fetched under the old one. D17-6 makes that "changed,
   * not applied", the same credential the filter editor's Apply wears.
   */
  it('leaves a sort whose apply was refused pending', async () => {
    const result = await openTable();
    const runtime = () => result.current.opened.runtime as RecordViewRuntime;
    const snapshot = () => runtime().getSnapshot();
    const report = () =>
      comparePending(snapshot().draft, snapshot().applied, snapshot().issues);

    act(() =>
      runtime().edit({
        filter: {
          op: 'and',
          children: [{ field: 'nowhere', operator: 'EQ', value: 1 }],
        },
      }),
    );
    expect(snapshot().issues.some(found => found.severity === 'error')).toBe(
      true,
    );

    act(() => result.current.table.toggleSort('amount'));

    // The draft moved, the applied config did not, and the report says so:
    // the refused condition and the sort, one each.
    expect(result.current.table.sortOf('amount')).toBe('ASC');
    expect(snapshot().applied.sort).toEqual([]);
    expect(report()).toEqual({ pending: true, count: 2, conditions: true });
  });

  /**
   * Table or cards draw the same rows, so switching is complete the moment
   * it is done: no dot, nothing to apply (D17-6 counts what the query sees).
   */
  it('does not count a layout switch as an edit waiting for apply', async () => {
    const result = await openTable();
    const runtime = () => result.current.opened.runtime as RecordViewRuntime;
    const snapshot = () => runtime().getSnapshot();

    act(() => result.current.table.setLayout('card'));

    expect(snapshot().draft.layout).toBe('card');
    expect(
      comparePending(snapshot().draft, snapshot().applied, snapshot().issues),
    ).toEqual({ pending: false, count: 0, conditions: false });
  });

  it('cycles a column through ascending, descending and off', async () => {
    const result = await openTable();

    act(() => result.current.table.toggleSort('amount'));
    expect(result.current.table.sortOf('amount')).toBe('ASC');

    act(() => result.current.table.toggleSort('amount'));
    expect(result.current.table.sortOf('amount')).toBe('DESC');

    act(() => result.current.table.toggleSort('amount'));
    expect(result.current.table.sort).toEqual([]);
  });

  it('selects rows of the current result and clears them', async () => {
    const result = await openTable();

    act(() => result.current.table.toggleAll());
    expect(result.current.table.selection).toEqual(['o-1', 'o-2']);

    act(() => result.current.table.toggle('o-1'));
    expect(result.current.table.isSelected('o-1')).toBe(false);

    act(() => result.current.table.toggleAll());
    expect(result.current.table.selection).toEqual(['o-1', 'o-2']);

    act(() => result.current.table.clearSelection());
    expect(result.current.table.selection).toEqual([]);
  });

  it('moves between pages and stops at the first', async () => {
    const result = await openTable(manyPages());

    act(() => result.current.table.previous());
    expect(result.current.table.paging).toMatchObject({ index: 1 });

    act(() => result.current.table.next());
    await waitFor(() =>
      expect(result.current.table.paging).toMatchObject({ index: 2 }),
    );

    act(() => result.current.table.previous());
    await waitFor(() =>
      expect(result.current.table.paging).toMatchObject({ index: 1 }),
    );

    act(() => result.current.table.goTo(4));
    await waitFor(() =>
      expect(result.current.table.paging).toMatchObject({ index: 4 }),
    );
  });

  /**
   * The paged source says how many rows there are, and Next asked for the
   * page after the last one anyway: an empty result that reads exactly like
   * a filter matching nothing.
   */
  it('stops at the last page rather than asking past it', async () => {
    const result = await openTable();

    expect(result.current.table.hasNext).toBe(false);
    act(() => result.current.table.next());

    expect(result.current.table.paging).toMatchObject({ index: 1 });
  });

  it('follows a cursor when the definition declares one', async () => {
    const { engine } = engineWith();
    const definition = ordersDefinition({
      record: { rowKey: 'id', paging: 'cursor', layouts: ['table'] },
    });
    const cursorEngine = new ViewEngine({
      definitions: [definition],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => testSource(),
    });
    void engine;

    const { result } = renderHook(() => {
      const opened = useOpenView(cursorEngine, 'orders-1');
      return useRecordTable(opened.runtime as RecordViewRuntime | null);
    });
    await waitFor(() => expect(result.current.status).toBe('success'));

    expect(result.current.paging).toEqual({
      mode: 'cursor',
      nextCursor: 'cursor-2',
    });
    act(() => result.current.previous());
    act(() => result.current.next());
    await waitFor(() => expect(result.current.status).toBe('success'));
  });

  /**
   * The card half of the config is what a card list renders. Without it the
   * UI fell back to the table's columns, so every card setting a user saved
   * was stored and then ignored.
   */
  it('projects the card the config saved, with its labels resolved', async () => {
    const result = await openTable();

    expect(result.current.table.card).toEqual({
      title: 'id',
      // The title and each field carry how their values show.
      titleField: {
        field: 'id',
        label: 'Order',
        kind: 'string',
        cell: 'string',
      },
      fields: [
        { field: 'amount', label: 'Amount', kind: 'number', cell: 'number' },
      ],
    });
  });

  it('changes layout, columns and page size', async () => {
    const result = await openTable();

    act(() => result.current.table.setLayout('card'));
    expect(result.current.table.layout).toBe('card');

    act(() => result.current.table.setColumns(['id', 'warehouse']));
    await waitFor(() =>
      expect(result.current.table.columns.map(column => column.field)).toEqual([
        'id',
        'warehouse',
      ]),
    );
    // The columns of the draft are all of them, the switched-off one among
    // them: `amount` keeps its place so it can be switched back on there.
    expect(result.current.table.columnFields).toEqual([
      'id',
      'amount',
      'warehouse',
    ]);
    expect(result.current.table.hiddenOf('amount')).toBe(true);

    act(() => result.current.table.setPageSize(5));
    await waitFor(() => expect(result.current.table.pageSize).toBe(5));
  });

  it('keeps the width and pinning of a column it keeps', async () => {
    const { engine } = engineWith({
      instances: [
        {
          ...mine,
          config: recordConfig({
            table: {
              columns: [
                { field: 'id', width: 120, pinned: 'left' },
                { field: 'amount' },
              ],
            },
          }),
        },
      ],
    });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return useRecordTable(opened.runtime as RecordViewRuntime | null);
    });
    await waitFor(() => expect(result.current.status).toBe('success'));

    act(() => result.current.setColumns(['id', 'warehouse']));

    await waitFor(() =>
      expect(result.current.columns[0]).toMatchObject({
        field: 'id',
        width: 120,
        pinned: 'left',
      }),
    );
  });

  it('ignores a page number on a cursor source and a next with no cursor', async () => {
    const definition = ordersDefinition({
      record: { rowKey: 'id', paging: 'cursor', layouts: ['table'] },
    });
    const source = testSource({
      cursor: vi.fn(() => Promise.resolve({ nextCursor: null, list: [] })),
    });
    const engine = new ViewEngine({
      definitions: [definition],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => source,
    });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return useRecordTable(opened.runtime as RecordViewRuntime | null);
    });
    await waitFor(() => expect(result.current.status).toBe('success'));
    expect(source.cursor).toHaveBeenCalledTimes(1);

    act(() => result.current.goTo(3));
    act(() => result.current.next());

    // A page number means nothing here, and the sequence has ended.
    expect(source.cursor).toHaveBeenCalledTimes(1);
  });

  it('reports a failed query', async () => {
    const result = await openTable(
      testSource({
        paged: vi
          .fn()
          .mockResolvedValueOnce({ total: 0, list: [] })
          .mockRejectedValue(new Error('gateway down')),
      }),
    );

    act(() => result.current.table.refresh());

    await waitFor(() => expect(result.current.table.status).toBe('error'));
    expect(result.current.table.error?.code).toBe('runtime.query.failed');
  });
});
