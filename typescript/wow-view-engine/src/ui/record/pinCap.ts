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

import { useLayoutEffect, useState, type RefObject } from 'react';
import { FILLER_COLUMN } from './Filler.js';

/**
 * One column the table holds against an edge, as the cap weighs it.
 *
 * The two chrome columns are in here beside the data columns, because the
 * width that eats the middle is the width of the whole held group: on the
 * 420px column that produced this rule, the selection box and the host's
 * action column were 146 of the 232 pixels held.
 */
export interface PinnedSlot {
  /**
   * How the header cell is found: `data-column` for the two chrome columns,
   * `data-field` for a data column.
   */
  key: string;
  kind: 'select' | 'actions' | 'column';
  /**
   * True where the pin is not the cap's to take. The row key is the only
   * one: it says which record a row is, so a row scrolled sideways without
   * it is a row nobody can read (D17-4).
   */
  fixed: boolean;
}

/** Which pins the cap has taken back. Nothing here is stored in a config. */
export interface ReleasedPins {
  /** Data columns, by field. */
  fields: ReadonlySet<string>;
  select: boolean;
  actions: boolean;
}

export function isReleased(released: ReleasedPins): boolean {
  return released.select || released.actions || released.fields.size > 0;
}

/** Nothing released — what a table that fits, or one nobody measured, gets. */
export const NO_RELEASE: ReleasedPins = {
  fields: new Set(),
  select: false,
  actions: false,
};

/**
 * How much of the result area the held group may take: half, and no more.
 *
 * A pinned column is a fixed number of pixels, so the narrower the port the
 * larger its share — and nothing capped it. At 420×860 the three held
 * columns of the wide fixture measured 232px against a 286px result area:
 * 81%, leaving 54px for the other nineteen columns, all of them wider than
 * that. Scrolled anywhere, the reader was looking at half a column.
 */
const CAP = 0.5;

/** The result area as the cap reads it: what is visible, and what is in it. */
export interface PinPort {
  /** `clientWidth` — the visible width, a scrollbar not counted as room. */
  visible: number;
  /** `scrollWidth` — how wide the columns are altogether. */
  content: number;
}

/**
 * Which pins to let go so the held group fits within {@link CAP}.
 *
 * **A table that fits keeps every pin.** The middle is only ever eaten by a
 * held column while there is a middle to scroll: where the columns all fit
 * in the port, `sticky` holds nothing out of view, and letting a pin go
 * would buy the reader not one pixel while taking D13's frame off a table
 * standing still. So the cap asks first whether anything scrolls at all.
 *
 * Then greedy over `slots`, which arrive in the order they are let go —
 * outermost first — stopping the moment the group fits: the cap takes as
 * little as it can. A slot nothing measured buys no width, so letting it go
 * would be a pin lost for nothing, and it is skipped.
 *
 * Widths do not depend on pinning — `position: sticky` moves a cell, it does
 * not resize it, and neither number above moves either — so the decision is
 * a pure function of what was measured and cannot oscillate: releasing a pin
 * never changes what the next measurement reads.
 */
/**
 * How much wider than its port a table may be and still count as fitting.
 * `scrollWidth` is an integer rounded up from fractional cell widths, so a
 * table that fits to the sub-pixel can report itself a pixel too wide; a
 * pixel is nothing for a pin to give back.
 */
const FITS_WITHIN = 1;

export function capPins(
  slots: readonly PinnedSlot[],
  widths: ReadonlyMap<string, number>,
  port: PinPort,
): ReleasedPins {
  if (!(port.visible > 0) || port.content <= port.visible + FITS_WITHIN)
    return NO_RELEASE;
  const room = port.visible * CAP;
  let held = slots.reduce((sum, slot) => sum + (widths.get(slot.key) ?? 0), 0);
  if (held <= room) return NO_RELEASE;

  const fields = new Set<string>();
  let select = false;
  let actions = false;
  for (const slot of slots) {
    if (held <= room) break;
    const width = widths.get(slot.key);
    if (slot.fixed || !width) continue;
    held -= width;
    if (slot.kind === 'select') select = true;
    else if (slot.kind === 'actions') actions = true;
    else fields.add(slot.key);
  }
  return { fields, select, actions };
}

/**
 * The held group capped against the result area's own visible width.
 *
 * Measured rather than declared, for the reason `usePinnedOffsets` measures:
 * a column's width comes from its content, the two chrome columns declare
 * none at all, and the number this rule turns on is the one on screen. The
 * port is the table's own box — `clientWidth` against `scrollWidth`, so a
 * scrollbar is not counted as room to read in and a table that fits is left
 * alone — and the columns are the header cells, the same cells the offsets
 * are added up from.
 *
 * It runs in a **layout** effect, so the first measurement lands before the
 * first paint: a table that has to give up a pin has given it up by the time
 * anything is drawn, rather than flashing the wrong layout and correcting
 * it. What is watched is the port *and* the held cells, so the pins come
 * back as the port widens and go as it narrows — and a column that grows
 * under a loading web font is weighed again as well.
 */
export function usePinnedCap(
  port: RefObject<HTMLElement | null>,
  table: RefObject<HTMLTableElement | null>,
  slots: readonly PinnedSlot[],
): ReleasedPins {
  const [released, setReleased] = useState<ReleasedPins>(NO_RELEASE);
  useLayoutEffect(() => {
    const cells = headCells(table.current);
    const measure = () => {
      const next = capPins(slots, measureCells(cells), portOf(port.current));
      setReleased(current => (sameRelease(current, next) ? current : next));
    };
    measure();
    const watched = port.current ? [port.current, ...cells.values()] : [];
    if (watched.length === 0 || typeof ResizeObserver === 'undefined')
      return () => {};
    const observer = new ResizeObserver(measure);
    for (const node of watched) observer.observe(node);
    return () => observer.disconnect();
  });
  return released;
}

/** The result area's two widths; zeroes where there is nothing to measure. */
function portOf(node: HTMLElement | null): PinPort {
  return { visible: node?.clientWidth ?? 0, content: node?.scrollWidth ?? 0 };
}

/**
 * The header cells a slot can be weighed by, by the key that names them.
 *
 * Read by what each cell *is* rather than by its pinning: a column the cap
 * has already let go carries no `data-pin` any more, and it is exactly the
 * one whose width has to be weighed again for the pin to come back.
 *
 * The trailing filler is left out. It is not a column — it holds whatever
 * width the real ones did not need — so weighing it would be weighing the
 * empty half of the table, and watching it would be watching a box that
 * changes size every time one of the others does.
 */
function headCells(
  table: HTMLTableElement | null,
): ReadonlyMap<string, HTMLTableCellElement> {
  const cells = new Map<string, HTMLTableCellElement>();
  if (!table) return cells;
  for (const cell of table.querySelectorAll<HTMLTableCellElement>(
    'thead tr:first-child>th',
  )) {
    const key = cell.dataset.column ?? cell.dataset.field;
    if (key !== undefined && key !== FILLER_COLUMN) cells.set(key, cell);
  }
  return cells;
}

function measureCells(
  cells: ReadonlyMap<string, HTMLTableCellElement>,
): ReadonlyMap<string, number> {
  const widths = new Map<string, number>();
  for (const [key, cell] of cells)
    widths.set(key, cell.getBoundingClientRect().width);
  return widths;
}

function sameRelease(left: ReleasedPins, right: ReleasedPins): boolean {
  return (
    left.select === right.select &&
    left.actions === right.actions &&
    left.fields.size === right.fields.size &&
    [...left.fields].every(field => right.fields.has(field))
  );
}
