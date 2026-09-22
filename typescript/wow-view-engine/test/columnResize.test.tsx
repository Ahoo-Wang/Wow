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
 * The header's own right edge, as a handle.
 *
 * What is tested here is everything about it a keyboard and a DOM can see:
 * the name and the value it carries, what each key commits, the floor it
 * stops at, and the width once it is committed — on the header, on the rows
 * and on the summaries, since a width the header alone declares is one an
 * auto-laid-out table is free to ignore. The pointer gesture itself is a
 * matter of boxes, and every box in jsdom is 0×0: it is measured in the
 * browser project instead (`stories/view-engine/RecordWorkbench.test.stories`
 * → `ColumnResize`).
 */

import {
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RecordColumnView } from '../src/record/index.js';
import { RecordTable } from '../src/ui/index.js';
import {
  ColumnResizer,
  MIN_COLUMN_WIDTH,
} from '../src/ui/record/ColumnResizer.js';
import { recordTableController } from './fixtures/ui.js';

afterEach(cleanup);

const AMOUNT: RecordColumnView = {
  field: 'amount',
  label: 'Amount',
  kind: 'number',
  cell: 'number',
  sortable: false,
};

function table(
  column: Partial<RecordColumnView> = {},
  setColumnWidth = vi.fn(),
) {
  render(
    <RecordTable
      table={recordTableController({
        columns: [{ ...AMOUNT, ...column }],
        summaries: {
          scope: 'total',
          cells: [
            {
              field: 'amount',
              label: 'Amount',
              fn: 'SUM',
              value: 3,
            },
          ],
        },
        setColumnWidth,
      })}
    />,
  );
  return setColumnWidth;
}

/** The handle of the one data column, by the name a reader hears. */
function handle(): HTMLElement {
  return screen.getByRole('separator', { name: 'Resize Amount' });
}

/** The header cell, the two rows' cells and the summary cell of that column. */
function cells(): HTMLTableCellElement[] {
  const rendered = screen.getByRole('table') as HTMLTableElement;
  const at = handle().closest('th')!.cellIndex;
  return [...rendered.rows].flatMap(row => {
    const cell = row.cells[at];
    return cell ? [cell] : [];
  });
}

describe('the resize handle', () => {
  /**
   * The line is there before anyone hovers: an affordance that appears only
   * after it has been used is not an affordance, and a touch screen never
   * hovers (the user's call, 2026-09-21). Hover and focus thicken it.
   */
  it('draws its line at rest, and thickens it under the pointer or focus', () => {
    table();
    // **Surviving class assertions**: the line is a pseudo-element's fill
    // in three pointer states, none of which is a state the handle could
    // carry and none of which jsdom renders. The three are measured in the
    // browser story; what is kept here is that the resting one is not
    // transparent, which is the regression the user's call was about.
    const node = handle();
    expect(node.className).toContain('after:bg-border');
    expect(node.className).not.toContain('after:bg-transparent');
    expect(node.className).toContain('hover:after:bg-ring');
    expect(node.className).toContain('focus-visible:after:bg-ring');
  });

  /**
   * A separator is what ARIA calls a movable boundary between two regions,
   * and one that carries a value says which value. The name is the column,
   * because a handle that says only "resize" is a handle a reader cannot
   * tell from the three beside it.
   *
   * It is **not** a tab stop (P-02 — twenty columns were twenty stops before
   * the first row); the column's own header is, and it offers the same
   * resize under Alt (`test/headerRoving.test.tsx`).
   */
  it('is a named separator carrying the width it is set to, off the Tab route', () => {
    table({ width: 160 });

    const edge = handle();
    expect(edge.getAttribute('aria-orientation')).toBe('vertical');
    expect(edge.getAttribute('aria-valuenow')).toBe('160');
    expect(edge.getAttribute('aria-valuemin')).toBe(String(MIN_COLUMN_WIDTH));
    expect(edge.tabIndex).toBe(-1);
  });

  it('is offered on every data column, sortable or not', () => {
    render(
      <RecordTable
        table={recordTableController({
          columns: [
            AMOUNT,
            { ...AMOUNT, field: 'warehouse', label: 'Warehouse' },
          ],
          columnFields: ['amount', 'warehouse'],
        })}
      />,
    );

    expect(
      screen.getAllByRole('separator').map(node => node.dataset.field),
    ).toEqual(['amount', 'warehouse']);
  });

  it('moves the width by eight, and by thirty-two with shift held', async () => {
    const user = userEvent.setup();
    const setColumnWidth = table({ width: 160 });

    handle().focus();
    await user.keyboard('{ArrowRight}');
    expect(setColumnWidth).toHaveBeenLastCalledWith('amount', 168);

    // Each press commits, and each one goes on from where the last left the
    // column: the committed width only reaches the projection with the next
    // result, so a second press that read the config back would repeat the
    // first.
    await user.keyboard('{Shift>}{ArrowRight}{/Shift}');
    expect(setColumnWidth).toHaveBeenLastCalledWith('amount', 200);

    await user.keyboard('{ArrowLeft}');
    expect(setColumnWidth).toHaveBeenLastCalledWith('amount', 192);
  });

  /** A column dragged under its own handle is a column nobody finds again. */
  it('stops at the narrowest a column may be', async () => {
    const user = userEvent.setup();
    const setColumnWidth = table({ width: MIN_COLUMN_WIDTH + 4 });

    handle().focus();
    await user.keyboard('{ArrowLeft}');

    expect(setColumnWidth).toHaveBeenLastCalledWith('amount', MIN_COLUMN_WIDTH);
  });

  /**
   * Back to automatic is `null` rather than a number, because that is what
   * the controller deletes the member for: a column that sizes itself has no
   * `width` in the config at all.
   */
  it('gives the column back to the content on Enter and on a double click', async () => {
    const user = userEvent.setup();
    const setColumnWidth = table({ width: 160 });

    handle().focus();
    await user.keyboard('{Enter}');
    expect(setColumnWidth).toHaveBeenLastCalledWith('amount', null);

    setColumnWidth.mockClear();
    await user.dblClick(handle());
    expect(setColumnWidth).toHaveBeenLastCalledWith('amount', null);
  });

  /**
   * A resize writes the new width onto the header cell, and the header cells
   * are exactly what `usePinnedOffsets` measures the frozen columns' offsets
   * from — so the pins follow a resize with nobody telling them to. What
   * jsdom can pin down is that the header being resized is one of the cells
   * being watched; the offsets themselves are a browser's arithmetic, and
   * the browser story `PinnedEdges` reads those.
   */
  it('is on a cell the pinned offsets are re-measured from', () => {
    const observed: Element[] = [];
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe(node: Element) {
          observed.push(node);
        }
        unobserve() {}
        disconnect() {}
      },
    );

    render(
      <RecordTable
        table={recordTableController({
          columns: [
            { ...AMOUNT, pinned: 'left', width: 120 },
            { ...AMOUNT, field: 'warehouse', label: 'Warehouse' },
          ],
          columnFields: ['amount', 'warehouse'],
        })}
      />,
    );

    expect(observed).toContain(
      screen
        .getByRole('separator', { name: 'Resize Amount' })
        .closest('th') as Element,
    );
    vi.unstubAllGlobals();
  });

  /**
   * The drag writes the DOM and only the release goes through the
   * controller: a `setState` per `pointermove` would re-render every row of
   * the result between one frame and the next. What jsdom can say about the
   * gesture is exactly that — which cells were written and when the commit
   * happened. Whether the column *looks* right at the end of it is a
   * question about boxes, and the browser story answers that one.
   */
  it('writes the width onto the column as it moves, and commits on release', async () => {
    const user = userEvent.setup();
    const setColumnWidth = table({ width: 120 });
    const edge = handle();

    await user.pointer([
      { keys: '[MouseLeft>]', target: edge, coords: { clientX: 200, y: 5 } },
      { target: edge, coords: { clientX: 260, y: 5 } },
    ]);

    for (const cell of cells()) expect(cell.style.width).toBe('180px');
    expect(edge.getAttribute('aria-valuenow')).toBe('180');
    expect(setColumnWidth).not.toHaveBeenCalled();

    await user.pointer({ keys: '[/MouseLeft]', target: edge });
    expect(setColumnWidth).toHaveBeenLastCalledWith('amount', 180);
  });

  /** A pointer the platform takes away has decided nothing. */
  it('puts the column back where it was when the pointer is cancelled', async () => {
    const user = userEvent.setup();
    const setColumnWidth = table({ width: 120 });
    const edge = handle();

    await user.pointer([
      { keys: '[MouseLeft>]', target: edge, coords: { clientX: 200, y: 5 } },
      { target: edge, coords: { clientX: 260, y: 5 } },
    ]);
    edge.dispatchEvent(
      new PointerEvent('pointercancel', { bubbles: true, pointerId: 1 }),
    );

    for (const cell of cells()) expect(cell.style.width).toBe('120px');
    expect(setColumnWidth).not.toHaveBeenCalled();
  });

  /**
   * And puts a column that had no width of its own back to automatic, which
   * is a cleared inline width rather than the floor: the config holds no
   * number for this column, and a cancelled gesture must not invent one.
   */
  it('gives an unsized column back to the content when the pointer is cancelled', async () => {
    const user = userEvent.setup();
    const setColumnWidth = table();
    const edge = handle();

    await user.pointer([
      { keys: '[MouseLeft>]', target: edge, coords: { clientX: 200, y: 5 } },
      { target: edge, coords: { clientX: 260, y: 5 } },
    ]);
    edge.dispatchEvent(
      new PointerEvent('pointercancel', { bubbles: true, pointerId: 1 }),
    );

    for (const cell of cells()) {
      expect(cell.style.width).toBe('');
      expect(cell.style.maxWidth).toBe('');
    }
    expect(setColumnWidth).not.toHaveBeenCalled();
  });

  /**
   * A press that is not the primary button belongs to whatever the platform
   * does with it — a context menu, a paste — and starts no gesture here. Left
   * unguarded, a right-click on the edge armed a drag that the matching
   * `pointerup` never ended, so the next pointer move anywhere in the
   * document resized the column.
   */
  it('starts nothing on a press that is not the primary button', () => {
    const setColumnWidth = table({ width: 120 });
    const edge = handle();

    fireEvent.pointerDown(edge, { button: 2, pointerId: 1, clientX: 200 });
    fireEvent.pointerMove(document, { pointerId: 1, clientX: 260 });
    fireEvent.pointerUp(document, { pointerId: 1, clientX: 260 });

    for (const cell of cells()) expect(cell.style.width).toBe('120px');
    expect(setColumnWidth).not.toHaveBeenCalled();
  });

  /**
   * The gesture belongs to the pointer that started it. A second finger — or
   * the stylus the palm is resting beside — moves, lifts and is cancelled
   * without the drag in flight hearing any of it.
   */
  it('hears only the pointer that started the drag', async () => {
    const user = userEvent.setup();
    const setColumnWidth = table({ width: 120 });
    const edge = handle();

    await user.pointer([
      { keys: '[MouseLeft>]', target: edge, coords: { clientX: 200, y: 5 } },
      { target: edge, coords: { clientX: 260, y: 5 } },
    ]);

    // Another pointer's whole life, start to finish, over the same document.
    fireEvent.pointerMove(edge, { pointerId: 2, clientX: 400 });
    fireEvent.pointerCancel(edge, { pointerId: 2 });
    fireEvent.pointerUp(edge, { pointerId: 2, clientX: 400 });

    // The preview is still the first pointer's, and nothing has committed.
    for (const cell of cells()) expect(cell.style.width).toBe('180px');
    expect(setColumnWidth).not.toHaveBeenCalled();

    // And the gesture is still live, so its own release still commits.
    await user.pointer({ keys: '[/MouseLeft]', target: edge });
    expect(setColumnWidth).toHaveBeenLastCalledWith('amount', 180);
  });

  /** The edge is a control of its own; the header under it is not pressed. */
  it('does not sort the column it is the edge of', async () => {
    const user = userEvent.setup();
    const toggleSort = vi.fn();
    render(
      <RecordTable
        table={recordTableController({
          columns: [{ ...AMOUNT, sortable: true }],
          toggleSort,
        })}
      />,
    );

    await user.click(screen.getByRole('separator'));

    expect(toggleSort).not.toHaveBeenCalled();
  });
});

describe('a column that was given a width', () => {
  /**
   * Every cell of the column carries it, not only the header. A table lays
   * out by content, so a width on the header is a suggestion the widest cell
   * in the column overrules — which is exactly the case a narrowed column is.
   */
  it('is drawn at that width in the header, the rows and the summaries', () => {
    table({ width: 120 });

    // The header, the two rows, and the two summary lines — this page and
    // every record, which a `total` scope always comes as a pair with.
    const drawn = cells();
    expect(drawn.length).toBe(5);
    for (const cell of drawn) {
      // The floor and the ceiling as well as the width: a `width` alone is
      // a suggestion an auto-laid-out table sizes past in both directions.
      expect(cell.style.width).toBe('120px');
      expect(cell.style.minWidth).toBe('120px');
      expect(cell.style.maxWidth).toBe('120px');
    }
  });

  /** What does not fit is cut, and what was cut is one hover away. */
  it('cuts its cells to it and keeps the whole value in the title', () => {
    table({ width: 120 });

    const body = within(screen.getByRole('table')).getAllByRole('cell');
    const value = body.find(cell => cell.textContent === '1')!;
    // The `title` is the clipping's own consequence and its way out: the
    // cell carries one exactly where the column was given a width, so it is
    // both what a user gets back and what says the cell is cut at all.
    expect(value.getAttribute('title')).toBe('1');
  });

  /** And a column that was given none is left to the content, as before. */
  it('leaves an unsized column alone', () => {
    table();

    for (const cell of cells()) {
      expect(cell.style.width).toBe('');
      expect(cell.style.minWidth).toBe('');
      expect(cell.style.maxWidth).toBe('');
    }
    // Nothing is cut, so no cell offers the whole of itself on a hover.
    expect(
      within(screen.getByRole('table'))
        .getAllByRole('cell')
        .some(cell => cell.hasAttribute('title')),
    ).toBe(false);
  });
});

/**
 * The handle reads the column off the table it is drawn in — the `<th>` it
 * sits in, that cell's index, and the table's rows. A host that puts one
 * somewhere else has no such column, and the guards that say so are what keep
 * a `null` out of `cellIndex` and a `NaN` out of the width.
 */
describe('a resize handle with no table around it', () => {
  it('commits the floor and writes onto nothing', async () => {
    const user = userEvent.setup();
    const onResize = vi.fn();
    render(<ColumnResizer column={AMOUNT} onResize={onResize} />);

    const edge = screen.getByRole('separator', { name: 'Resize Amount' });
    // No header to measure, so the width it goes on from is the floor.
    expect(edge.getAttribute('aria-valuenow')).toBe(String(MIN_COLUMN_WIDTH));

    edge.focus();
    await user.keyboard('{ArrowRight}');

    expect(onResize).toHaveBeenLastCalledWith('amount', MIN_COLUMN_WIDTH + 8);
    expect(edge.getAttribute('aria-valuenow')).toBe(
      String(MIN_COLUMN_WIDTH + 8),
    );
  });
});
