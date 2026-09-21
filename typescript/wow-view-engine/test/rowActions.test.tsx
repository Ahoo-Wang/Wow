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

import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { RecordRow } from '../src/record/index.js';
import type { RecordTableController } from '../src/react/index.js';
import { RecordCards } from '../src/ui/RecordCards.js';
import { RecordTable } from '../src/ui/RecordTable.js';
import { RowActions } from '../src/ui/RowActions.js';

afterEach(cleanup);

/** The little a table or a card reads off its controller, and nothing more. */
function tableController(
  overrides: Partial<RecordTableController> = {},
): RecordTableController {
  return {
    columns: [
      {
        field: 'amount',
        label: 'Amount',
        kind: 'number',
        cell: 'number',
        sortable: false,
      },
    ],
    card: { title: 'amount', fields: [] },
    rows: [
      { key: 'o-1', data: { amount: 1 } },
      { key: 'o-2', data: { amount: 2 } },
    ],
    paging: { mode: 'paged', index: 1, total: 2 },
    summaries: null,
    status: 'success',
    error: null,
    loading: false,
    hasResult: true,
    sort: [],
    sortOf: () => null,
    toggleSort: () => {},
    setSort: () => {},
    maxSortFields: 8,
    layout: 'table',
    layouts: ['table', 'card'],
    setLayout: () => {},
    columnFields: ['amount'],
    setColumns: () => {},
    setColumnOrder: () => {},
    pinnedOf: () => null,
    setPinned: () => {},
    summaryOf: () => null,
    setSummary: () => {},
    pageSize: 20,
    pageSizes: [10, 20, 50, 100],
    setPageSize: () => {},
    selection: [],
    selectedRows: [],
    isSelected: () => false,
    toggle: () => {},
    toggleAll: () => {},
    clearSelection: () => {},
    goTo: () => {},
    hasNext: false,
    next: () => {},
    previous: () => {},
    refresh: () => {},
    ...overrides,
  };
}

/** What a host hangs on a row: a button that names the row it acts on. */
const rowActions = (row: RecordRow) => (
  <button type="button">{`act ${String(row.key)}`}</button>
);

describe('RowActions', () => {
  it('wraps whatever the host gives it in one addressable row', () => {
    const { container } = render(
      <RowActions>
        <button type="button">{'one'}</button>
      </RowActions>,
    );

    const slot = container.querySelector('[data-slot="row-actions"]');
    expect(slot).toBeTruthy();
    expect(slot?.className).toContain('justify-end');
    expect(within(slot as HTMLElement).getByRole('button')).toBeTruthy();
  });
});

describe('RecordTable row actions', () => {
  it('adds no column when the host offers nothing', () => {
    render(<RecordTable table={tableController()} />);

    expect(screen.queryByRole('columnheader', { name: 'Actions' })).toBeNull();
  });

  /**
   * The column is pinned because it is last: on a table wide enough to
   * scroll, actions that scroll out of sight are actions nobody finds.
   */
  it('pins a last column and renders the slot once per row', () => {
    render(<RecordTable table={tableController()} rowActions={rowActions} />);

    const head = screen.getByRole('columnheader', { name: 'Actions' });
    expect(head.className).toContain('sticky');
    expect(head.className).toContain('right-0');
    // And it wears its edge at rest (D13), not only once rows pass under it.
    expect(head.className).toContain('shadow-[inset_1px_0_0_var(--border)');

    expect(screen.getByRole('button', { name: 'act o-1' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'act o-2' })).toBeTruthy();
    // Each one sits inside the shared wrapper rather than loose in the cell.
    expect(document.querySelectorAll('[data-slot="row-actions"]')).toHaveLength(
      2,
    );
  });

  /**
   * The skeleton is drawn by column where the columns are known, so it is
   * as wide as the table it stands in for — checkbox, each data column, and
   * the action column — rather than one bar spanning all of them.
   */
  it('gives the loading rows a cell per column, actions included', () => {
    const { container } = render(
      <RecordTable
        table={tableController({ status: 'loading', rows: [] })}
        rowActions={rowActions}
      />,
    );

    // One checkbox column, one data column, one action column.
    const cells = container.querySelectorAll('tbody tr:first-child td');
    expect(cells).toHaveLength(3);
    expect(cells[0].getAttribute('colspan')).toBeNull();
  });

  /**
   * A footer one cell short of its rows leaves the pinned column hanging
   * over the summary, so the row that carries no summary still gets a cell.
   */
  it('keeps the summary row as wide as the rows above it', () => {
    render(
      <RecordTable
        table={tableController({
          summaries: {
            scope: 'page',
            cells: [{ field: 'amount', label: 'Amount', fn: 'SUM', value: 3 }],
          },
        })}
        rowActions={rowActions}
      />,
    );

    const [header] = screen.getAllByRole('rowgroup');
    const footerCells = document.querySelectorAll('tfoot td');
    expect(footerCells).toHaveLength(
      within(header).getAllByRole('columnheader').length,
    );
    expect(footerCells[footerCells.length - 1].textContent).toBe('');
  });
});

describe('RecordCards row actions', () => {
  it("gives every card a footer with the host's actions", () => {
    const { container } = render(
      <RecordCards table={tableController()} rowActions={rowActions} />,
    );

    const footers = container.querySelectorAll('[data-slot="card-footer"]');
    expect(footers).toHaveLength(2);
    expect(footers[0].className).toContain('border-t');
    expect(
      within(footers[0] as HTMLElement).getByRole('button', {
        name: 'act o-1',
      }),
    ).toBeTruthy();
    expect(footers[0].querySelector('[data-slot="row-actions"]')).toBeTruthy();
  });

  it('leaves the footer out when the host offers nothing', () => {
    const { container } = render(<RecordCards table={tableController()} />);

    expect(container.querySelector('[data-slot="card-footer"]')).toBeNull();
  });
});
