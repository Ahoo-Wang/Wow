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
 * The header row as one tab stop (P-02).
 *
 * What a keyboard and a DOM can see about it: how many stops the row costs,
 * where the arrows go, that the column's width is still reachable — now
 * under Alt, from the header rather than from an edge nobody could Tab to —
 * and that the sort button kept everything it already had. The widths
 * themselves are the resizer's own suite; here only the field and the number
 * handed to the controller matter, because every box in jsdom is 0×0.
 */

import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RecordColumnView } from '../src/record/index.js';
import { RecordTable } from '../src/ui/index.js';
import { MIN_COLUMN_WIDTH } from '../src/ui/record/ColumnResizer.js';
import { SortableHeader } from '../src/ui/record/SortableHeader.js';
import { recordTableController } from './fixtures/ui.js';

afterEach(cleanup);

const COLUMNS: RecordColumnView[] = [
  {
    field: 'id',
    label: 'Order',
    kind: 'string',
    cell: 'text',
    sortable: true,
  },
  {
    field: 'amount',
    label: 'Amount',
    kind: 'number',
    cell: 'number',
    sortable: false,
  },
  {
    field: 'warehouse',
    label: 'Warehouse',
    kind: 'string',
    cell: 'text',
    sortable: true,
  },
];

function table(overrides: Parameters<typeof recordTableController>[0] = {}) {
  const setColumnWidth = vi.fn();
  const toggleSort = vi.fn();
  render(
    <RecordTable
      table={recordTableController({
        columns: COLUMNS,
        columnFields: COLUMNS.map(column => column.field),
        setColumnWidth,
        toggleSort,
        ...overrides,
      })}
    />,
  );
  return { setColumnWidth, toggleSort };
}

/**
 * The same headers with no controller to write a width to — an embedded
 * table whose host offers no resize, which is what `onResize` being optional
 * is for. Drawn straight rather than through `RecordTable`, which always has
 * a controller behind it.
 */
function headersWithoutResize() {
  render(
    <table>
      <thead>
        <tr>
          {COLUMNS.map(column => (
            <SortableHeader
              key={column.field}
              column={column}
              sort={[]}
              onToggle={() => {}}
            />
          ))}
        </tr>
      </thead>
    </table>,
  );
}

/** The header row's own items, in the order a reader walks them. */
function headers(): HTMLElement[] {
  const row = screen.getAllByRole('row')[0];
  return [...row.querySelectorAll<HTMLElement>('[data-header-stop]')];
}

/** Everything in the header row a Tab would land on. */
function stops(): HTMLElement[] {
  const row = screen.getAllByRole('row')[0];
  return [
    ...row.querySelectorAll<HTMLElement>(
      'button:not([disabled]), [tabindex="0"]',
    ),
  ].filter(node => node.getAttribute('tabindex') !== '-1');
}

describe('the header row as one tab stop', () => {
  /**
   * The point of the whole change: a column used to cost two stops — its
   * sort button and its resize handle — so the row grew with the table and
   * a reader paid for every column on the way to the first row. What is
   * left is the select-all box, which is an action of its own, and the one
   * stop the group carries.
   */
  it('costs one stop for every column there is, plus the select-all box', () => {
    table();

    const landings = stops();
    expect(landings).toHaveLength(2);
    expect(landings[0].getAttribute('role')).toBe('checkbox');
    expect(landings[1]).toBe(headers()[0]);

    // Every column is still in the group, sortable or not: the arrows have
    // to reach the ones that cannot be sorted, or their widths are lost.
    expect(headers()).toHaveLength(3);
    expect(headers().map(node => node.getAttribute('tabindex'))).toEqual([
      '0',
      '-1',
      '-1',
    ]);
  });

  /** None of the twelve edges of a wide table stands on the Tab route. */
  it('keeps every resize handle off the Tab route', () => {
    table();

    const handles = screen.getAllByRole('separator');
    expect(handles).toHaveLength(3);
    for (const handle of handles) expect(handle.tabIndex).toBe(-1);
  });

  it('walks the columns with the arrows and jumps to the ends', async () => {
    const user = userEvent.setup();
    table();
    const [first, second, third] = headers();

    first.focus();
    await user.keyboard('{ArrowRight}');
    expect(document.activeElement).toBe(second);
    expect(second.getAttribute('tabindex')).toBe('0');
    expect(first.getAttribute('tabindex')).toBe('-1');

    await user.keyboard('{ArrowRight}');
    expect(document.activeElement).toBe(third);

    await user.keyboard('{ArrowLeft}');
    expect(document.activeElement).toBe(second);

    await user.keyboard('{End}');
    expect(document.activeElement).toBe(third);
    await user.keyboard('{Home}');
    expect(document.activeElement).toBe(first);
  });

  /**
   * Past the last column lies the first row. A bar of controls wraps
   * (`Toolbar` does); the top of a table is not a closed set, and a row that
   * sent a reader back to column one would be a row with no way out.
   */
  it('stops at the two ends rather than wrapping round', async () => {
    const user = userEvent.setup();
    table();
    const [first, , third] = headers();

    first.focus();
    await user.keyboard('{ArrowLeft}');
    expect(document.activeElement).toBe(first);

    third.focus();
    await user.keyboard('{ArrowRight}');
    expect(document.activeElement).toBe(third);
  });

  /** Coming back to the row lands where the reader left it, not at column one. */
  it('leaves the stop on the column last stood on', async () => {
    const user = userEvent.setup();
    table();

    headers()[2].focus();
    await user.keyboard('{ArrowLeft}');

    expect(headers().map(node => node.getAttribute('tabindex'))).toEqual([
      '-1',
      '0',
      '-1',
    ]);
  });
});

describe('a column widened from its own header', () => {
  /**
   * The handle's contract, reached under Alt: one press is eight pixels,
   * Shift makes it thirty-two, and it stops at the floor a column may not go
   * under. The header commits through the same function the handle does, so
   * these are the same numbers `columnResize` asserts on the edge itself.
   */
  it('moves the width by eight, and by thirty-two with shift held', async () => {
    const user = userEvent.setup();
    const { setColumnWidth } = table({
      columns: COLUMNS.map(column =>
        column.field === 'id' ? { ...column, width: 160 } : column,
      ),
    });

    headers()[0].focus();
    await user.keyboard('{Alt>}{ArrowRight}{/Alt}');
    expect(setColumnWidth).toHaveBeenLastCalledWith('id', 168);

    await user.keyboard('{Alt>}{Shift>}{ArrowRight}{/Shift}{/Alt}');
    expect(setColumnWidth).toHaveBeenLastCalledWith('id', 200);

    await user.keyboard('{Alt>}{ArrowLeft}{/Alt}');
    expect(setColumnWidth).toHaveBeenLastCalledWith('id', 192);
  });

  /** A column that cannot be sorted is a cell, and it resizes all the same. */
  it('answers on a column with no button in it', async () => {
    const user = userEvent.setup();
    const { setColumnWidth } = table();

    headers()[1].focus();
    expect(headers()[1].tagName).toBe('TH');
    await user.keyboard('{Alt>}{ArrowRight}{/Alt}');

    expect(setColumnWidth).toHaveBeenLastCalledWith(
      'amount',
      MIN_COLUMN_WIDTH + 8,
    );
  });

  /** Back to automatic, which is `null` — the member leaves the config. */
  it('gives the column back to the content on Alt+Enter', async () => {
    const user = userEvent.setup();
    const { setColumnWidth, toggleSort } = table();

    headers()[0].focus();
    await user.keyboard('{Alt>}{Enter}{/Alt}');

    expect(setColumnWidth).toHaveBeenLastCalledWith('id', null);
    // The press was about the width, so the button under it did not sort.
    expect(toggleSort).not.toHaveBeenCalled();
  });

  /** A reader is told the shortcut, rather than only the documentation. */
  it('says which keys widen the column it is standing on', () => {
    table();

    for (const header of headers())
      expect(header.getAttribute('aria-keyshortcuts')).toBe(
        'Alt+ArrowLeft Alt+ArrowRight',
      );
  });

  /**
   * An embedded table whose host has no controller to write a width to has
   * no handle either — so there is nothing for Alt to say, and the group
   * does not claim a shortcut it cannot honour.
   */
  it('claims no shortcut where there is no width to write', () => {
    headersWithoutResize();

    expect(screen.queryAllByRole('separator')).toHaveLength(0);
    for (const header of headers())
      expect(header.getAttribute('aria-keyshortcuts')).toBeNull();
  });
});

describe('the sort button inside the group', () => {
  /** The name says what the next press does, and the column it does it to. */
  it('keeps its own name and its own keys', async () => {
    const user = userEvent.setup();
    const { toggleSort } = table();

    const button = screen.getByRole('button', {
      name: 'Sort by Order, ascending',
    });
    expect(button).toBe(headers()[0]);

    button.focus();
    await user.keyboard('{Enter}');
    expect(toggleSort).toHaveBeenLastCalledWith('id', { exclusive: true });

    // Shift is still "add this column to the sort", and the roving group
    // never sees it: a modified arrow is the only thing Shift means here.
    await user.keyboard('{Shift>}{Enter}{/Shift}');
    expect(toggleSort).toHaveBeenLastCalledWith('id', { exclusive: false });
  });

  /** The cell still says which column the table is ordered by. */
  it('leaves the cell to say the sort', () => {
    table({
      columns: COLUMNS,
      sort: [{ field: 'id', direction: 'ASC' }],
    });

    const cell = within(screen.getAllByRole('row')[0])
      .getByRole('button', { name: 'Sort by Order, descending' })
      .closest('th');
    expect(cell?.getAttribute('aria-sort')).toBe('ascending');
  });
});
