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

import { useLayoutEffect, type CSSProperties, type RefObject } from 'react';
import type { RecordColumnView } from '../../record/index.js';
import { cn } from 'cn';
import { TEXT_UI } from '../layout.js';

/**
 * Where a pinned column sits while the middle of the table scrolls.
 *
 * The class and the style go on every cell of that column — header, body and
 * summary alike — because a sticky column is sticky one cell at a time: a
 * `<col>` cannot carry position, and a header that stays while its cells
 * leave is worse than no pinning at all.
 */
export interface ColumnPin {
  /** Which edge it is held against. */
  side: 'left' | 'right';
  /** The column's place in the result, which names its measured offset. */
  index: number;
  className: string;
  style: CSSProperties;
}

/**
 * The selection column's width as its class asks for it (`w-10`), used until
 * the header has been measured. A table lays out by content, so what the
 * class asks for is a floor rather than the answer — the scope label in that
 * cell can widen it — and a pinned column offset by the class alone lands on
 * top of the checkboxes. It is the fallback, not the number.
 */
const SELECT_WIDTH = '2.5rem';

/**
 * How much room the action column takes, until it has been measured: its
 * width is the host's own buttons, so there is nothing in the config to read
 * it from. A host that renders the table where it cannot be measured sets
 * the variable.
 */
const ACTIONS_WIDTH = 'var(--fve-record-actions-width, 6rem)';

/**
 * Marks the two columns that are not a field, so the header can be measured
 * by what each cell is rather than by counting from the ends.
 */
export const SELECT_COLUMN = 'select';
export const ACTIONS_COLUMN = 'actions';

/**
 * The custom property carrying one pinned column's measured offset. The
 * cells read it with the config's own arithmetic as the fallback, so they
 * are placed before anything has been measured and corrected after.
 */
function pinVar(side: 'left' | 'right', index: number): string {
  return `--fve-pin-${side}-${index}`;
}

/**
 * Keeps the pinned columns' offsets equal to what the header really measures.
 *
 * Column widths come from the content, not from the config: a declared
 * `width` is a suggestion an auto-laid-out table may exceed, the selection
 * and action columns declare nothing at all, and a column placed by the
 * arithmetic alone lands on top of its neighbour — the selection column
 * renders half as wide again as its class asks for, because the scope label
 * sits in it.
 *
 * It writes the numbers straight onto the table as custom properties rather
 * than through state: this is a layout the DOM already knows and React does
 * not, so reading it back into a render would only buy a second pass. The
 * header is measured rather than a body row because a table with no rows
 * still has one.
 *
 * What is watched is each of those header cells and not the table, because
 * the offsets follow the cells: a web font that finishes loading or an action
 * button that grows changes a column's width inside a table whose own box
 * never moves, and offsets published from the last layout would then hold the
 * pinned columns over their neighbours.
 */
export function usePinnedOffsets(
  table: RefObject<HTMLTableElement | null>,
): void {
  useLayoutEffect(() => {
    const node = table.current;
    if (!node) return;
    applyPins(node);
    return observeResize(pinCells(node), () => applyPins(node));
  });
}

/**
 * Each pinned column stops where the sticky ones before it end. Only they
 * count: a column that scrolls away contributes nothing to stay clear of.
 */
function applyPins(table: HTMLTableElement): void {
  const cells = pinCells(table);
  accumulate(table, cells, 'left');
  accumulate(table, [...cells].reverse(), 'right');
}

/** The header cells the offsets are added up from, in column order. */
function pinCells(table: HTMLTableElement): HTMLTableCellElement[] {
  return [
    ...table.querySelectorAll<HTMLTableCellElement>(
      'thead tr:first-child>th[data-pin]',
    ),
  ];
}

function accumulate(
  table: HTMLTableElement,
  cells: readonly HTMLTableCellElement[],
  side: 'left' | 'right',
): void {
  let offset = 0;
  for (const cell of cells) {
    if (cell.dataset.pin !== side) continue;
    const index = cell.dataset.pinIndex;
    if (index !== undefined)
      table.style.setProperty(pinVar(side, Number(index)), `${offset}px`);
    offset += cell.getBoundingClientRect().width;
  }
}

/** Resize reporting where the platform has it, and nothing where it does not. */
function observeResize(
  nodes: readonly Element[],
  changed: () => void,
): () => void {
  if (nodes.length === 0 || typeof ResizeObserver === 'undefined')
    return () => {};
  const observer = new ResizeObserver(changed);
  for (const node of nodes) observer.observe(node);
  return () => observer.disconnect();
}

/**
 * The edge of a pinned column, in the direction the rows scroll away: a
 * `--border` hairline saying where the column ends, and a soft shadow saying
 * the table continues beneath it.
 *
 * It is drawn **at rest** as well as while rows pass under it (D13). Tied to
 * the scroll position it said "something is moving under me right now", which
 * is a true sentence nobody needed: a table that has not been scrolled — or
 * one that fits — then showed no frame at all, and its two held columns read
 * as a layout that had come apart. The edge says "these two ends stay put",
 * and that is true before anything moves.
 *
 * The soft half is `--pin-shadow` and not a literal black: black is a
 * shadow on a white card and nothing at all on a dark one, so the token
 * carries a value per theme (`styles.css` has the measurements).
 */
const EDGE_LEFT =
  'shadow-[inset_-1px_0_0_var(--border),12px_0_16px_-8px_var(--pin-shadow)]';
const EDGE_RIGHT =
  'shadow-[inset_1px_0_0_var(--border),-12px_0_16px_-8px_var(--pin-shadow)]';

/**
 * The action column stays put while the rest scrolls sideways, which is the
 * only reason it can be the last one: on a wide table, actions that scroll
 * away are actions nobody finds. It carries the right edge unless a column
 * the projection pinned right sits before it — then that column is the
 * boundary with the scrolling middle, and an edge here as well would draw a
 * seam between two columns nothing passes between.
 */
export function actionCell(columns: readonly RecordColumnView[]): string {
  const edge = !heldColumns(columns, { actions: true }).some(isPinned('right'));
  return `sticky right-0 z-10 w-0 bg-inherit whitespace-nowrap${edge ? ` ${EDGE_RIGHT}` : ''}`;
}

/** The selection column, pinned along with the columns it sits beside. */
export const SELECT_CELL = 'sticky left-0 z-10 bg-inherit';

/**
 * The table's border model, and the hairlines drawn under it.
 *
 * Preflight collapses table borders, and in collapsed mode Chromium paints
 * no outer `box-shadow` on a cell at all: the edges above computed fine and
 * drew nothing, so a held column sat on the page with the shadow D13 asked
 * for existing only in `getComputedStyle`. Separate borders paint it. But a
 * `<tr>` — and a `<thead>`/`<tfoot>` — has no border of its own in that
 * model, so the hairline between rows moves from the row onto its cells;
 * the last summary row leaves it off, since the frame's pagination draws
 * that line as its own top.
 */
export const TABLE_CELLS =
  'border-separate border-spacing-0 [&_th]:border-b [&_td]:border-b [&_tfoot_tr:last-child_td]:border-b-0';

/**
 * The hovered row's colour, opaque — `--row-hover` in `styles.css`, where
 * the reason it is a mix rather than a wash is written down and where a host
 * can move it. `has-aria-expanded` is the row with a menu open, which the
 * registry washes the same way.
 */
export const ROW_HOVER = 'hover:bg-row-hover has-aria-expanded:bg-row-hover';

/**
 * A column header is metadata about the column rather than content in it, so
 * it is quieter than the values under it; the header row carries the
 * emphasis instead, as a layer with its own edge.
 */
export const HEAD_CELL = `text-muted-foreground font-medium ${TEXT_UI}`;

/** Numbers line up on their last digit, in the cells and in the header. */
export const NUMERIC_CELL = 'text-right tabular-nums';

/**
 * A column that was given a width keeps it: the cell is capped as well as
 * sized, and what does not fit is cut with an ellipsis rather than pushing
 * the column back out. Without the cap the width is only a suggestion — an
 * auto-laid-out table grows a column to its widest cell whatever the header
 * asked for, so a column dragged narrower would spring back on the next
 * render.
 */
export const CLIPPED_CELL = 'truncate';

/**
 * The width one column asks for, as every cell of it has to carry it.
 *
 * **All three properties, and on the cells.** A `width` on its own is a
 * suggestion: an auto-laid-out table sizes a column by its content and then
 * shares out whatever room is left over, so a column asked for 200px comes
 * out wider on a roomy table and wider still under a long value. The floor
 * and the ceiling are what make it a width — with `min-width` and
 * `max-width` equal, the column's minimum and maximum contributions are the
 * same number and there is nothing left for either step to decide. A `<col>`
 * cannot say any of this, any more than it can say pinning: it carries a
 * width the table treats as a hint, and nothing that clips the cell drawn
 * in it.
 */
export function columnWidth(
  column: RecordColumnView,
): CSSProperties | undefined {
  if (column.width === undefined) return undefined;
  return {
    width: column.width,
    minWidth: column.width,
    maxWidth: column.width,
  };
}

/**
 * Whether a column's values are numbers, and so read from the right. The
 * projection resolves `cell` to the kind's own renderer when the field names
 * none, so the renderer key alone answers it.
 */
export function isNumeric(column: RecordColumnView): boolean {
  return column.cell === 'number';
}

/**
 * Each column's pin, by field, for the columns the config pinned.
 *
 * The offset is the measured one the effect above writes, with the config's
 * own arithmetic as the fallback: declared widths added up left to right and
 * then right to left, so a column is placed sensibly before anything has been
 * measured and exactly afterwards. A pinned column with no declared width
 * contributes nothing to that fallback — the one after it would start at the
 * same place until the measurement lands.
 */
export function columnPins(
  columns: readonly RecordColumnView[],
  layout: { selectable: boolean; actions: boolean },
): Map<string, ColumnPin> {
  const pins = new Map<string, ColumnPin>();
  columns = heldColumns(columns, layout);
  // The boundary with the scrolling middle is the last column pinned left
  // and the first pinned right; only those two draw an edge. The selection
  // column is never one — a column pinned left always follows it — and the
  // action column is the right boundary only when no column is pinned there.
  const lastLeft = [...columns].reverse().find(isPinned('left'));
  const firstRight = columns.find(isPinned('right'));

  const left: string[] = layout.selectable ? [SELECT_WIDTH] : [];
  columns.forEach((column, index) => {
    if (column.pinned !== 'left') return;
    pins.set(column.field, pin('left', index, left, column === lastLeft));
    if (column.width !== undefined) left.push(`${column.width}px`);
  });

  const right: string[] = layout.actions ? [ACTIONS_WIDTH] : [];
  [...columns.entries()].reverse().forEach(([index, column]) => {
    if (column.pinned !== 'right') return;
    pins.set(column.field, pin('right', index, right, column === firstRight));
    if (column.width !== undefined) right.push(`${column.width}px`);
  });

  return pins;
}

/**
 * The columns as the table holds them once the host's row-action column is
 * counted. The projection holds the last data column against the right edge
 * because a frame needs its end (D13) — but it cannot see the action slot,
 * and when there is one *that* is the end: two held columns at one edge,
 * with a seam between them that nothing ever passes, is a frame with a
 * doubled side. So the end column lets go and the actions take its place;
 * a column the config itself pinned right keeps its pin.
 */
export function heldColumns(
  columns: readonly RecordColumnView[],
  layout: { actions: boolean },
): readonly RecordColumnView[] {
  if (!layout.actions) return columns;
  return columns.map(column =>
    column.end ? { ...column, pinned: undefined } : column,
  );
}

function isPinned(side: 'left' | 'right') {
  return (column: RecordColumnView) => column.pinned === side;
}

/** Whether the selection column has to stay put along with a pinned column. */
export function pinsSelect(columns: readonly RecordColumnView[]): boolean {
  return columns.some(column => column.pinned === 'left');
}

function pin(
  side: 'left' | 'right',
  index: number,
  offsets: readonly string[],
  edge: boolean,
): ColumnPin {
  const declared =
    offsets.length === 0 ? '0px' : `calc(${offsets.join(' + ')})`;
  return {
    side,
    index,
    className: cn(
      'sticky z-10 bg-inherit',
      edge && (side === 'left' ? EDGE_LEFT : EDGE_RIGHT),
    ),
    style:
      side === 'left'
        ? { left: `var(${pinVar(side, index)}, ${declared})` }
        : { right: `var(${pinVar(side, index)}, ${declared})` },
  };
}
