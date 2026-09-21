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

import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { RecordColumnView } from '../src/index.js';
import type { RecordTableController } from '../src/react/index.js';
import { RecordTable } from '../src/ui/index.js';

afterEach(cleanup);

/**
 * The edge of a pinned column, which is on at rest as well as while rows
 * pass under it (D13). jsdom computes no layout and applies no stylesheet,
 * so what is pinned here is the contract the browser then honours: which
 * cells carry the edge and which do not. The browser story `PinnedEdges`
 * reads the shadow itself, still and scrolled.
 */
describe('the pinned edges', () => {
  const LEFT_EDGE = 'shadow-[inset_-1px_0_0_var(--border)';
  const RIGHT_EDGE = 'shadow-[inset_1px_0_0_var(--border)';

  it('draws the edge on the boundary with the middle, and nowhere else', () => {
    const { container } = render(
      <RecordTable
        table={controller([
          column('id', 'left'),
          column('amount', 'left'),
          column('warehouse'),
          column('status', 'right'),
        ])}
        rowActions={() => <button />}
      />,
    );
    // The last column pinned left and the first pinned right face the
    // scrolling middle; the column before the last one faces another frozen
    // column, and nothing ever passes between the two.
    for (const cell of cellsOf(container, 'amount'))
      expect(cell.className).toContain(LEFT_EDGE);
    for (const cell of cellsOf(container, 'id'))
      expect(cell.className).not.toContain(LEFT_EDGE);
    for (const cell of cellsOf(container, 'status'))
      expect(cell.className).toContain(RIGHT_EDGE);
    expect(cellsOf(container, 'warehouse')[0].className).not.toContain(
      'shadow-[inset',
    );
    // The action column sits behind a column pinned right, so the boundary
    // is that column's and the actions draw no seam of their own.
    for (const cell of actionCells(container))
      expect(cell.className).not.toContain(RIGHT_EDGE);
  });

  it('gives the right edge to the actions when no column is pinned there', () => {
    const { container } = render(
      <RecordTable
        table={controller([column('id', 'left'), column('warehouse')])}
        rowActions={() => <button />}
      />,
    );
    for (const cell of actionCells(container))
      expect(cell.className).toContain(RIGHT_EDGE);
  });

  /**
   * The rule D13 replaced. Tied to the scroll position, the edge said
   * "something is moving under me right now" — true, and of no use: a table
   * nobody had scrolled yet, or one that fits and so never scrolls at all,
   * showed no frame and its held ends read as a layout that had come apart.
   * Nothing is listened to now and nothing is written on the table.
   */
  it('wears its edge before anything has scrolled', () => {
    const { container } = render(
      <RecordTable
        table={controller([column('id', 'left'), column('warehouse')])}
      />,
    );

    const table = container.querySelector('table')!;
    for (const cell of cellsOf(container, 'id'))
      expect(cell.className).toContain(LEFT_EDGE);
    // Nothing on the table says where it is scrolled to, and no cell reads
    // such a thing through a group variant: the edge is not a fact about
    // scrolling any more.
    expect(table.hasAttribute('data-scrolled-left')).toBe(false);
    expect(table.hasAttribute('data-scrolled-right')).toBe(false);
    expect(table.className).not.toContain('group/table');
    expect(container.innerHTML).not.toContain('scrolled-');
  });

  /**
   * Header, rows and summaries read the same class, so a held column is
   * framed from top to bottom rather than in the rows alone.
   */
  it('puts the same edge on the header, the rows and the summaries', () => {
    const { container } = render(
      <RecordTable
        table={controller([column('id', 'left'), column('amount', 'right')])}
      />,
    );

    expect(cellsOf(container, 'id')).toHaveLength(3);
    for (const cell of cellsOf(container, 'id'))
      expect(cell.className).toContain(LEFT_EDGE);
    for (const cell of cellsOf(container, 'amount'))
      expect(cell.className).toContain(RIGHT_EDGE);
  });
});

function column(field: string, pinned?: 'left' | 'right'): RecordColumnView {
  return {
    field,
    label: field,
    kind: 'string',
    cell: 'string',
    sortable: false,
    ...(pinned ? { pinned } : {}),
  };
}

function controller(columns: RecordColumnView[]): RecordTableController {
  const data = Object.fromEntries(columns.map(entry => [entry.field, 'x']));
  return {
    columns,
    rows: [{ key: 'o-1', data }],
    card: { title: columns[0].field, fields: [] },
    paging: { mode: 'paged', index: 1, total: 1 },
    summaries: { scope: 'page', cells: [] },
    status: 'success',
    hasResult: true,
    selection: [],
    sort: [],
    pageSize: 20,
    layout: 'table',
    columnFields: columns.map(entry => entry.field),
    isSelected: () => false,
    toggle: () => {},
    toggleAll: () => {},
    clearSelection: () => {},
    toggleSort: () => {},
    setSort: () => {},
    setPageSize: () => {},
    setColumns: () => {},
    setColumnOrder: () => {},
    setPinned: () => {},
    setSummary: () => {},
    setLayout: () => {},
    pinnedOf: () => null,
    summaryOf: () => null,
    goTo: () => {},
    next: () => {},
    previous: () => {},
    refresh: () => {},
  } as unknown as RecordTableController;
}

function cellsOf(container: HTMLElement, field: string): HTMLElement[] {
  const index = [...container.querySelectorAll('thead th')].findIndex(
    cell => cell.getAttribute('data-field') === field,
  );
  return [...container.querySelectorAll('thead tr, tbody tr, tfoot tr')].map(
    row => (row as HTMLTableRowElement).cells[index] as HTMLElement,
  );
}

function actionCells(container: HTMLElement): HTMLElement[] {
  const index = [...container.querySelectorAll('thead th')].findIndex(
    cell => cell.getAttribute('data-column') === 'actions',
  );
  return [...container.querySelectorAll('thead tr, tbody tr, tfoot tr')].map(
    row => (row as HTMLTableRowElement).cells[index] as HTMLElement,
  );
}
