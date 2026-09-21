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

import { act, cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { RecordColumnView } from '../src/index.js';
import type { RecordTableController } from '../src/react/index.js';
import { RecordTable } from '../src/ui/index.js';

afterEach(cleanup);

/**
 * The edge of a pinned column is drawn only while rows pass under it. jsdom
 * computes no layout, so what is pinned here is the contract the browser then
 * honours: which cells carry the edge, and which attribute on the table turns
 * it on — the browser story `PinnedEdges` reads the shadow itself.
 */
describe('the pinned edges', () => {
  const LEFT_EDGE = 'group-data-[scrolled-left]/table:';
  const RIGHT_EDGE = 'group-data-[scrolled-right]/table:';

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
    expect(cellsOf(container, 'warehouse')[0].className).not.toMatch(
      /scrolled-(left|right)/,
    );
    // The action column sits behind a column the config pinned right, so the
    // boundary is that column's and the actions draw no seam of their own.
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

  it('says on the table which side rows have scrolled under', () => {
    const { container } = render(
      <RecordTable table={controller([column('id', 'left')])} />,
    );
    const area = container.querySelector<HTMLElement>(
      '[data-slot="record-table"]',
    )!;
    const table = container.querySelector('table')!;
    const port = scrollport(area, { width: 300, room: 500 });

    // At the start: nothing under the left column, rows under the right side.
    act(() => port.scrollTo(0));
    expect(table.hasAttribute('data-scrolled-left')).toBe(false);
    expect(table.hasAttribute('data-scrolled-right')).toBe(true);

    act(() => port.scrollTo(40));
    expect(table.hasAttribute('data-scrolled-left')).toBe(true);
    expect(table.hasAttribute('data-scrolled-right')).toBe(true);

    act(() => port.scrollTo(200));
    expect(table.hasAttribute('data-scrolled-left')).toBe(true);
    expect(table.hasAttribute('data-scrolled-right')).toBe(false);

    act(() => port.scrollTo(0));
    expect(table.hasAttribute('data-scrolled-left')).toBe(false);
  });

  it('draws no edge at all while the table fits', () => {
    const { container } = render(
      <RecordTable table={controller([column('id', 'left')])} />,
    );
    const area = container.querySelector<HTMLElement>(
      '[data-slot="record-table"]',
    )!;
    const table = container.querySelector('table')!;
    const port = scrollport(area, { width: 300, room: 300 });
    act(() => port.scrollTo(0));
    expect(table.hasAttribute('data-scrolled-left')).toBe(false);
    expect(table.hasAttribute('data-scrolled-right')).toBe(false);
  });

  it('reads the box around it when the surface scrolls instead', () => {
    const { container } = render(
      <div style={{ overflowX: 'auto' }}>
        <RecordTable
          table={controller([column('id', 'left')])}
          scrolls={false}
        />
      </div>,
    );
    const outer = container.firstElementChild as HTMLElement;
    const table = container.querySelector('table')!;
    const port = scrollport(outer, { width: 300, room: 500 });
    act(() => port.scrollTo(40));
    expect(table.hasAttribute('data-scrolled-left')).toBe(true);
    expect(table.hasAttribute('data-scrolled-right')).toBe(true);
  });

  it('stops listening once the table is gone', () => {
    const { container, unmount } = render(
      <RecordTable table={controller([column('id', 'left')])} />,
    );
    const area = container.querySelector<HTMLElement>(
      '[data-slot="record-table"]',
    )!;
    const table = container.querySelector('table')!;
    const port = scrollport(area, { width: 300, room: 500 });
    unmount();
    act(() => port.scrollTo(40));
    expect(table.hasAttribute('data-scrolled-left')).toBe(false);
  });
});

/**
 * Stands in for the layout jsdom does not compute: a scrollport of a given
 * width whose content is `room` wide, scrolled by dispatching the event the
 * hook listens for.
 */
function scrollport(
  node: HTMLElement,
  { width, room }: { width: number; room: number },
) {
  let scrollLeft = 0;
  Object.defineProperties(node, {
    clientWidth: { configurable: true, get: () => width },
    scrollWidth: { configurable: true, get: () => room },
    scrollLeft: {
      configurable: true,
      get: () => scrollLeft,
      set: (value: number) => {
        scrollLeft = value;
      },
    },
  });
  return {
    scrollTo(left: number) {
      node.scrollLeft = left;
      node.dispatchEvent(new Event('scroll'));
    },
  };
}

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
