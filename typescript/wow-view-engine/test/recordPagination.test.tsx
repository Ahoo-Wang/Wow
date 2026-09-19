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

import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RecordTableController } from '../src/react/index.js';
import { RecordPagination } from '../src/ui/RecordPagination.js';

afterEach(cleanup);

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
    paging: { mode: 'paged', index: 1, total: 42 },
    summaries: null,
    status: 'success',
    error: null,
    loading: false,
    sort: [],
    sortOf: () => null,
    toggleSort: () => {},
    layout: 'table',
    layouts: ['table', 'card'],
    setLayout: () => {},
    columnFields: ['amount'],
    setColumns: () => {},
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
    hasNext: true,
    next: () => {},
    previous: () => {},
    refresh: () => {},
    ...overrides,
  };
}

describe('RecordPagination counts', () => {
  it('says how many there are and how many are here', () => {
    render(<RecordPagination table={tableController()} />);

    expect(screen.getByText('42 in all')).toBeTruthy();
    expect(screen.getByText('2 on this page')).toBeTruthy();
  });

  /** A cursor source was never asked for a total, so it claims none. */
  it('claims no total when the source reports none', () => {
    render(
      <RecordPagination
        table={tableController({
          paging: { mode: 'paged', index: 1 },
        })}
      />,
    );

    expect(screen.queryByText(/in all/)).toBeNull();
    expect(screen.getByText('2 on this page')).toBeTruthy();
    // Without a total there are no pages to count, only the one reached.
    expect(screen.getByText('Page 1')).toBeTruthy();
  });

  /**
   * The rows on hand are the previous ones while a query is out, so the
   * counts stay with them instead of blanking and snapping back.
   */
  it('keeps the last counts while the next page is loading', () => {
    render(<RecordPagination table={tableController({ status: 'loading' })} />);

    expect(screen.getByText('42 in all')).toBeTruthy();
    expect(screen.getByText('2 on this page')).toBeTruthy();
  });

  it('renders nothing for a result that is empty and settled', () => {
    const { container } = render(
      <RecordPagination table={tableController({ rows: [] })} />,
    );

    expect(container.firstChild).toBeNull();
  });

  it('still renders on a first load, where the rows are yet to come', () => {
    render(
      <RecordPagination
        table={tableController({ rows: [], status: 'loading' })}
      />,
    );

    expect(screen.getByText('0 on this page')).toBeTruthy();
  });

  /**
   * A later page can come back empty — rows deleted since it was counted, or
   * a source that reports no total answering one page too far. Hiding the bar
   * then takes Previous with it and strands the user on an empty page with
   * nothing to press.
   */
  it('keeps the way back on an empty page that is not the first', () => {
    render(
      <RecordPagination
        table={tableController({
          rows: [],
          paging: { mode: 'paged', index: 3 },
          hasNext: false,
        })}
      />,
    );

    expect(screen.getByText('Page 3')).toBeTruthy();
    expect(
      screen.getByRole('button', { name: 'Previous page' }),
    ).toHaveProperty('disabled', false);
  });

  /** The first page has nowhere to go back to, so an empty one says nothing. */
  it('renders nothing for an empty first page', () => {
    const { container } = render(
      <RecordPagination
        table={tableController({
          rows: [],
          paging: { mode: 'paged', index: 1, total: 0 },
        })}
      />,
    );

    expect(container.firstChild).toBeNull();
  });
});

describe('RecordPagination page size', () => {
  /**
   * What may be offered is the controller's to decide — it is the one that
   * knows the runtime's budget — and the bar draws exactly that list.
   */
  it('offers the sizes the controller allows', async () => {
    const user = userEvent.setup();
    render(
      <RecordPagination
        table={tableController({
          pageSize: 25,
          pageSizes: [10, 20, 25, 50],
        })}
      />,
    );

    await user.click(screen.getByRole('combobox', { name: 'Rows per page' }));
    const options = await screen.findAllByRole('option');
    expect(options.map(option => option.textContent)).toEqual([
      '10',
      '20',
      '25',
      '50',
    ]);
  });

  it('applies the size that was picked', async () => {
    const setPageSize = vi.fn();
    const user = userEvent.setup();
    render(<RecordPagination table={tableController({ setPageSize })} />);

    await user.click(screen.getByRole('combobox', { name: 'Rows per page' }));
    await user.click(await screen.findByRole('option', { name: '50' }));
    expect(setPageSize).toHaveBeenCalledWith(50);
  });
});

describe('RecordPagination moving between pages', () => {
  it('counts the pages and holds the first one back', () => {
    render(<RecordPagination table={tableController()} />);

    expect(screen.getByText('Page 1 of 3')).toBeTruthy();
    expect(
      screen
        .getByRole('button', { name: 'Previous page' })
        .hasAttribute('disabled'),
    ).toBe(true);
  });

  it('moves either way from a page in the middle', async () => {
    const next = vi.fn();
    const previous = vi.fn();
    const user = userEvent.setup();
    render(
      <RecordPagination
        table={tableController({
          paging: { mode: 'paged', index: 2, total: 42 },
          next,
          previous,
        })}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Previous page' }));
    await user.click(screen.getByRole('button', { name: 'Next page' }));
    expect(previous).toHaveBeenCalledTimes(1);
    expect(next).toHaveBeenCalledTimes(1);
  });

  /**
   * A cursor source can only go forward, and knows of no page numbers at
   * all, so it shows neither rather than showing them dead.
   */
  it('shows a cursor source only the way forward', () => {
    render(
      <RecordPagination
        table={tableController({
          paging: { mode: 'cursor', nextCursor: 'c-2' },
        })}
      />,
    );

    expect(screen.queryByText(/Page/)).toBeNull();
    expect(screen.queryByRole('button', { name: 'Previous page' })).toBeNull();
    expect(
      screen
        .getByRole('button', { name: 'Next page' })
        .hasAttribute('disabled'),
    ).toBe(false);
  });

  it('stops at the end of a cursor source', () => {
    render(
      <RecordPagination
        table={tableController({
          paging: { mode: 'cursor', nextCursor: null },
          hasNext: false,
        })}
      />,
    );

    expect(
      screen
        .getByRole('button', { name: 'Next page' })
        .hasAttribute('disabled'),
    ).toBe(true);
  });
});
