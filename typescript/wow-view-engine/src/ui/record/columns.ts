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
import { NO_RELEASE, type PinnedSlot, type ReleasedPins } from './pinCap.js';

/**
 * Where a pinned column sits while the middle of the table scrolls.
 *
 * The class and the style go on every cell of that column — header, body and
 * summary alike — because a sticky column is sticky one cell at a time: a
 * `<col>` cannot carry position, and a header that stays while its cells
 * leave is worse than no pinning at all.
 */
export interface PinPlacement {
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
 * It is drawn **whenever the middle can scroll**, at rest as much as while
 * rows pass under it (D13, amended 2026-09-22 — P-23). Tied to the scroll
 * position it said "something is moving under me right now", which is a
 * true sentence nobody needed: a table that had not been scrolled showed no
 * frame at all, and its two held columns read as a layout that had come
 * apart. Tied to nothing at all it said too much the other way: on a table
 * that *fits*, the last column's edge cut the row's surplus off it and the
 * filler read as an empty column. So the edge answers to the port's own
 * word — `data-overflowing`, measured by `useOverflowing` — which is
 * exactly "these two ends stay put while the middle moves", true from the
 * moment there is a middle to move.
 *
 * The soft half is `--pin-shadow` and not a literal black: black is a
 * shadow on a white card and nothing at all on a dark one, so the token
 * carries a value per theme (`styles.css` has the measurements).
 */
const EDGE_LEFT =
  'in-data-[overflowing]:shadow-[inset_-1px_0_0_var(--border),12px_0_16px_-8px_var(--pin-shadow)]';
const EDGE_RIGHT =
  'in-data-[overflowing]:shadow-[inset_1px_0_0_var(--border),-12px_0_16px_-8px_var(--pin-shadow)]';

/**
 * The action column stays put while the rest scrolls sideways, which is the
 * only reason it can be the last one: on a wide table, actions that scroll
 * away are actions nobody finds. It is the only column ever held against
 * the right edge while the host gives one (D19), so it carries that edge
 * outright — `heldColumns` has already let the table's own last column go.
 */
export function actionCell(pins: TablePins): string {
  const base = 'w-0 bg-inherit whitespace-nowrap';
  // Let go by the cap on a narrow port: the buttons scroll with their row,
  // and the right edge goes with them — a frame's end is worth less than a
  // middle nobody can read (D17-4).
  if (!pins.actions) return base;
  return `sticky right-0 z-10 ${base} ${EDGE_RIGHT}`;
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
 * The sort button may be as wide as the cell it sits in — all of it.
 *
 * The registry's button carries `max-w-full`, and `full` is the cell's
 * *content* box; the button then pulls the cell's own `px-2` back out with
 * `-mx-2`, so its margin box is allowed to be that 1rem wider. Capped at the
 * content box it is short by exactly those 16px and the label ellipses —
 * `订单号` came out as `订…` the moment the column stopped being given
 * surplus width ({@link TABLE_FIT}). A cut name is one hover away in the
 * header's tooltip, but a hover is the way back from a column that has no
 * room for its name — not from one that has the room and is not given it,
 * and not on a touch screen. The ceiling is the cell's padding box, which
 * is where the negative margins reach and not one pixel further: a column
 * dragged narrower still clips its own name rather than spilling it over
 * its neighbour, and the last column's button still ends inside its cell —
 * a button that overhung by 2px once made every table report itself wider
 * than its port.
 */
const HEAD_BUTTON = '[&>button]:max-w-[calc(100%+1rem)]';

/**
 * What the pointer resting on a sortable header lights up.
 *
 * The registry's `ghost` button hovers to `bg-accent`, and on this surface
 * `--accent` and `--muted` are the same 3% grey — over the band below that
 * hover measured **1.00:1**, an affordance that stopped existing the moment
 * the header stopped being white. So the step is taken in the other
 * direction: the hovered cell lifts to the ground the rows are drawn on,
 * which is the same mark the sidebar's open view wears (`ui/variants.tsx`).
 * The step is exactly the one it always was, mirrored with the band —
 * `--accent` over `--background` measured 1.09:1 light and 1.31:1 dark, and
 * `--background` over `--muted` is those same two numbers — `--accent` and
 * `--muted` hold the same value, so the step is the band-against-the-rows
 * step either way round.
 *
 * It rides on the *cell*'s class rather than on the button's, so
 * `SortableHeader` still spells no colour: the cell already carries this
 * file's `HEAD_CELL`, and `> button:hover` outranks the variant's own
 * `:hover` by the child selector it is written with.
 */
const HEAD_HOVER = '[&>button]:hover:bg-background';

/**
 * A band at one end of the table: the header at the top, the summaries at
 * the bottom. One constant, because the two of them being **the same grey**
 * is the whole point (P-21) — they bracket the rows, and the data is the
 * only thing left drawn on the page's own ground. Before this the header
 * was `--background` like the rows, parted from them by one hairline and a
 * grey label, and the user's 2026-09-22 review read the first row as part
 * of the header. (`HeaderBand*` measures the two fills and compares them
 * byte for byte.)
 *
 * The colour is on the **row** as well as on the group because a pinned
 * cell takes its fill from the row it is in (`bg-inherit`, see
 * `SELECT_CELL`): a band painted on the `<thead>` alone leaves the held
 * cells transparent, and the columns scrolling under them show through.
 * `hover:` is said again because the registry hovers every `<tr>` to
 * `bg-muted/50`, and neither band is hovered as a row — in the header only
 * the button in it is ({@link HEAD_HOVER}).
 */
export const BAND = 'bg-muted';
export const BAND_ROW = 'bg-muted hover:bg-muted';

/**
 * A column header is metadata about the column rather than content in it —
 * but it is not quieter than the values under it: it is the same ink on a
 * grey of its own. `text-muted-foreground` measured **4.34:1** on that grey
 * (the same number `--quiet-foreground` exists because of, see
 * `styles.css`), under 1.4.3's 4.5 at a size that is already the smallest
 * on the surface; the registry's own `text-foreground font-medium` on
 * `<th>` measures 18.15:1 light and 14.48:1 dark. So this class no longer
 * overrides either of them — what is left is the one small type size and
 * the two rules about the button in the cell.
 */
export const HEAD_CELL = `${TEXT_UI} ${HEAD_BUTTON} ${HEAD_HOVER}`;

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

/** What the table holds against its two edges, once the cap has had its say. */
export interface TablePins {
  /** The pin each data column draws, by field; absent means it scrolls. */
  columns: ReadonlyMap<string, PinPlacement>;
  /** Whether the selection column is held against the left edge. */
  select: boolean;
  /** Whether the host's action column is held against the right edge. */
  actions: boolean;
}

/**
 * Every column the table would hold, in the order the cap lets them go.
 *
 * **Outermost first, and the right side before the left.** What is furthest
 * out is what the layout added rather than what the config chose: the host's
 * action column at one end, the selection box at the other. The left block
 * goes last because it is the row's identity — the checkbox and the key
 * beside it are "which row is this, and is it picked" — and the key itself
 * is never in the cap's hands at all.
 */
export function pinnedSlots(
  columns: readonly RecordColumnView[],
  layout: { selectable: boolean; actions: boolean },
): PinnedSlot[] {
  const held = heldColumns(columns, layout);
  const slot = (column: RecordColumnView): PinnedSlot => ({
    key: column.field,
    kind: 'column',
    fixed: column.primary === true,
  });
  const chrome = (key: string, kind: 'select' | 'actions'): PinnedSlot[] => [
    { key, kind, fixed: false },
  ];
  // The order the cap lets pins go in: the outermost first — the host's
  // action column, then the selection column, then the columns pinned left
  // from the key out — and the table's own last column after all of them.
  // D13 makes the first and last drawn columns the table's frame; the cap
  // (D17-4) takes what the layout and the config added before it takes the
  // frame, and it never takes the key. The last column is not held for good
  // like the key: a wide one on a narrow port would otherwise eat the
  // middle by itself, and a frame round nothing readable is worth less than
  // the rows. It is the whole of the right side — there is no pinning there
  // for a config to ask for (D19) — and it is not there at all beside a
  // host's action column, which has taken its place already.
  return [
    ...(layout.actions ? chrome(ACTIONS_COLUMN, 'actions') : []),
    ...(layout.selectable && held.some(isPinned('left'))
      ? chrome(SELECT_COLUMN, 'select')
      : []),
    ...held.filter(isPinned('left')).map(slot),
    ...held.filter(isPinned('right')).map(slot),
  ];
}

/**
 * Each column's pin, by field, for the columns the layout holds — and
 * whether the two chrome columns are held along with them.
 *
 * The offset is the measured one the effect above writes, with the config's
 * own arithmetic as the fallback: declared widths added up left to right and
 * then right to left, so a column is placed sensibly before anything has been
 * measured and exactly afterwards. A pinned column with no declared width
 * contributes nothing to that fallback — the one after it would start at the
 * same place until the measurement lands.
 *
 * `released` is the cap's answer (D17-4). It is a rendering decision and
 * never an edit: the config still says the column is pinned, and the pin
 * comes back the moment the port has room for it.
 */
export function tablePins(
  columns: readonly RecordColumnView[],
  layout: { selectable: boolean; actions: boolean },
  released: ReleasedPins = NO_RELEASE,
): TablePins {
  const pins = new Map<string, PinPlacement>();
  columns = heldColumns(columns, layout).map(column =>
    released.fields.has(column.field)
      ? { ...column, pinned: undefined }
      : column,
  );
  // The selection column is held along with the columns beside it, or a
  // column pinned left would be drawn over it.
  const select =
    layout.selectable && !released.select && columns.some(isPinned('left'));
  const actions = layout.actions && !released.actions;
  // The boundary with the scrolling middle is the last column pinned left;
  // only it draws an edge on that side. The selection column is never one —
  // a column pinned left always follows it.
  const lastLeft = [...columns].reverse().find(isPinned('left'));

  // A chrome column the cap let go holds nothing, so it is nothing for the
  // columns beside it to clear either.
  const left: string[] = select ? [SELECT_WIDTH] : [];
  columns.forEach((column, index) => {
    if (column.pinned !== 'left') return;
    pins.set(column.field, pin('left', index, left, column === lastLeft));
    if (column.width !== undefined) left.push(`${column.width}px`);
  });

  // One column at most on the right, held against the edge itself: it is
  // the one the table draws last (D13), it is the only pinning that side
  // has (D19), and beside a host's action column it is not held at all —
  // `heldColumns` gave the place away. So there is never anything for it to
  // clear, and it always draws the boundary with the scrolling middle.
  const end = columns.findIndex(isPinned('right'));
  if (end >= 0) pins.set(columns[end].field, pin('right', end, [], true));

  return { columns: pins, select, actions };
}

/**
 * The columns as the table holds them once the host's row-action column is
 * counted. The projection holds the last data column against the right edge
 * because a frame needs its end (D13) — but it cannot see the action slot,
 * and when there is one *that* is the end: two held columns at one edge,
 * with a seam between them that nothing ever passes, is a frame with a
 * doubled side. So the last column lets go and the actions take its place.
 *
 * Every right-hand pin is that one column (D19), so this is the whole of
 * the right side: with an action column the table holds nothing of its own
 * there, which is what lets everything downstream stop asking.
 */
function heldColumns(
  columns: readonly RecordColumnView[],
  layout: { actions: boolean },
): readonly RecordColumnView[] {
  if (!layout.actions) return columns;
  return columns.map(column =>
    column.pinned === 'right' ? { ...column, pinned: undefined } : column,
  );
}

function isPinned(side: 'left' | 'right') {
  return (column: RecordColumnView) => column.pinned === side;
}

function pin(
  side: 'left' | 'right',
  index: number,
  offsets: readonly string[],
  edge: boolean,
): PinPlacement {
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
