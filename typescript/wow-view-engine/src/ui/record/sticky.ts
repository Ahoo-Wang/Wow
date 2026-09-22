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

import { cn } from 'cn';
import type { CSSProperties } from 'react';

/**
 * Everything about the table that stays put while the rest of it scrolls,
 * in one place: the two bands at the ends, and the cells of a held column.
 *
 * **Why one place.** A sticky column is sticky one cell at a time — a
 * `<col>` cannot carry position, so the same recipe has to go on the header
 * cell, the body cell, the summary cell and the skeleton's — and the recipe
 * was spelled out five times over `RecordTable`, `columns.ts`,
 * `SummaryRows`, `SkeletonRows` and `Filler`. Five copies of `sticky z-10
 * bg-inherit` plus an inset hairline is five chances for one layer to drift
 * from the others, and a header that holds while its cells slide away is
 * worse than no pinning at all (`docs/design/ui/record.md`). So every cell
 * of this table — held or not, in whichever layer — is built by
 * {@link stickyCell} or {@link stickyHead}, and whether it is held is the
 * pin it is handed.
 *
 * **What is said on the element.** The classes alone cannot be read back: a
 * test that asserts `sticky` asserts a spelling, and the offsets only exist
 * in a browser that lays the table out. So each held cell also says what it
 * is — `data-pin` for the edge it is held against, `data-pin-edge` for the
 * one facing the scrolling middle, `data-pin-index` on the header cell whose
 * width names the offset — and `usePinnedOffsets`, `usePinnedCap`, the jsdom
 * suites and the browser stories all read those rather than the class list.
 *
 * Two of those words are the *port*'s and not a cell's, and they are the
 * two the cells read back through a variant: `data-sticky` on each band,
 * and `data-overflowing` on the scroll area (`overflow.ts`, P-23), which
 * is what decides whether the boundary's edge is drawn at all. A cell says
 * what it *is*; the port says what there is to hold against.
 */

/** Which edge the table holds something against. */
export type PinSide = 'left' | 'right';

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
 * filler read as an empty column. So the edge **answers to the port's own
 * word** — `data-overflowing`, which `RecordTable` writes from
 * `useOverflowing` (`overflow.ts`) and which every one of these classes
 * reads through `in-data-[overflowing]:` from inside it. That is exactly
 * "these two ends stay put while the middle moves", true from the moment
 * there is a middle to move.
 *
 * The variant is why the edge is one of the two halves of the recipe that
 * is *not* mirrored by a `data-*` attribute of its own: `data-pin-edge`
 * says "this is the cell that would draw the boundary", and whether it is
 * drawn right now is the port's sentence rather than the cell's. A suite
 * therefore asks the cell whether it is the boundary and asks the port
 * whether there is a middle; the browser story `PinnedEdges` reads the
 * shadow itself, absent at rest and wide, present once narrowed.
 *
 * The soft half is `--pin-shadow` and not a literal black: black is a
 * shadow on a white card and nothing at all on a dark one, so the token
 * carries a value per theme (`styles.css` has the measurements).
 */
const EDGE = {
  left: 'in-data-[overflowing]:shadow-[inset_-1px_0_0_var(--border),12px_0_16px_-8px_var(--pin-shadow)]',
  right:
    'in-data-[overflowing]:shadow-[inset_1px_0_0_var(--border),-12px_0_16px_-8px_var(--pin-shadow)]',
} as const;

/**
 * The recipe itself.
 *
 * `bg-inherit`, because a held cell takes its fill from the row it is in:
 * the row carries an opaque colour of its own and the cell follows it, so
 * selection and hover do not break across the held columns. That is also
 * why every one of the row's colours has to be opaque — a wash over the
 * column a held cell stands in front of is a window onto it.
 *
 * `z-10`, which is above the cells that scroll under it and below the two
 * bands at `z-20`: a band crosses every column, so where a band and a held
 * column meet the band's cell is the one that is both.
 *
 * A plain function rather than a `cva`: every one of the three things that
 * vary here is keyed by the same `side`, so the variant table would be two
 * axes of empty strings and a list of compounds saying what a ternary says
 * in one line.
 */
function held(pin: StickyPin): string {
  return cn(
    'sticky z-10 bg-inherit',
    // Against the port's own edge, which is where the two chrome columns
    // sit: nothing is ever held outside them, so they need no offset.
    pin.offset === undefined && (pin.side === 'left' ? 'left-0' : 'right-0'),
    pin.edge && EDGE[pin.side],
  );
}

/**
 * One column the table holds against an edge, as every layer's cell needs
 * it. `columns.ts` decides which columns get one; this file decides what
 * wearing one looks like.
 */
export interface StickyPin {
  /** Which edge it is held against. */
  side: PinSide;
  /**
   * The column's place in the result, which names its measured offset. The
   * two chrome columns have none — they sit against the port's own edge.
   */
  index?: number;
  /**
   * Where the column stops: the offsets of the held columns before it, as
   * `usePinnedOffsets` measures them with the config's own arithmetic as the
   * fallback. Absent means the edge itself.
   */
  offset?: string;
  /**
   * Whether this is the held column facing the scrolling middle, and so the
   * one that draws the edge (D13). Two held columns never have anything
   * pass between them, so a line there would be a lie.
   */
  edge: boolean;
}

/** What one cell of the table wears, held or not. */
export interface StickyCellProps {
  className: string;
  style?: CSSProperties;
  'data-pin'?: PinSide;
  'data-pin-edge'?: '';
  'data-pin-index'?: number;
}

/** Anything the cell carries for reasons of its own: width, alignment, … */
export interface CellStyling {
  className?: string;
  style?: CSSProperties;
}

/**
 * One cell of a table row — a body cell, a summary cell, a skeleton's — with
 * the column's own styling and, where the column is held, the sticky recipe
 * over it.
 *
 * `pin` left out is the ordinary case and not an oversight: a column that
 * scrolls, the trailing filler that is not a column at all, a skeleton row
 * whose query has not answered yet. Saying it here rather than by omission
 * is what keeps "not held" in the same place as "held".
 */
export function stickyCell(
  pin: StickyPin | undefined,
  styling: CellStyling = {},
): StickyCellProps {
  if (pin === undefined)
    return { className: cn(styling.className), style: styling.style };
  return {
    className: cn(styling.className, held(pin)),
    style: { ...styling.style, ...offsetOf(pin) },
    'data-pin': pin.side,
    ...(pin.edge ? { 'data-pin-edge': '' as const } : {}),
  };
}

/**
 * The same, for a header cell — which also publishes the offset it owns.
 *
 * The header is what the offsets are measured from: a table with no rows
 * still has a header, and column widths come from the content rather than
 * from the config, so `usePinnedOffsets` adds up these cells and writes the
 * result back as `--fve-pin-{side}-{index}`. The chrome columns take part
 * in that sum without owning a variable — they are against the edge.
 */
export function stickyHead(
  pin: StickyPin | undefined,
  styling: CellStyling = {},
): StickyCellProps {
  const cell = stickyCell(pin, styling);
  if (pin?.index === undefined) return cell;
  return { ...cell, 'data-pin-index': pin.index };
}

/**
 * What an *unheld* header cell must be so that the resize handle inside it
 * stays inside it.
 *
 * The handle is `absolute z-20`, and in a cell that is merely `relative` —
 * no stacking context of its own — that 20 competed at the row's level and
 * beat the held cells' `z-10`: scrolled sideways, a column's edge line
 * painted through the frozen header it had slid under (user, 2026-09-22).
 * `isolate` scopes it to the cell. A held cell needs none of this — `sticky`
 * with a `z-index` is already a stacking context — and two `position`
 * classes on one element is a race between stylesheet rules rather than a
 * choice, so this is the alternative to a pin and never an addition to one.
 */
export const OWN_LAYER = 'relative isolate';

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
 * The colour is on the **row** as well as on the group because a held cell
 * takes its fill from the row it is in (`bg-inherit`, see {@link held}): a
 * band painted on the `<thead>` alone leaves those cells transparent, and
 * the columns scrolling under them show through. `hover:` is said again
 * because the registry hovers every `<tr>` to `bg-muted/50`, and neither
 * band is hovered as a row — in the header only the button in it is.
 */
export const BAND = 'bg-muted';
export const BAND_ROW = 'bg-muted hover:bg-muted';

/** What a band wears, and which end of the scroll port it holds. */
export interface StickyBandProps {
  className: string;
  'data-sticky': 'top' | 'bottom';
}

/**
 * The `<thead>` or `<tfoot>` as a layer that stays while the rows move.
 *
 * `z-20`, one above the held cells, because a band crosses them: the header
 * cell of a frozen column is part of both layers and has to be drawn over
 * the rows passing beneath *and* over the columns sliding under the freeze.
 * Which end it holds is said on the element as well — jsdom lays nothing
 * out, so `data-sticky` is what a suite can read, while the browser stories
 * measure the computed `position` instead.
 */
export function stickyBand(at: 'top' | 'bottom'): StickyBandProps {
  return {
    className: cn(BAND, 'sticky z-20', at === 'top' ? 'top-0' : 'bottom-0'),
    'data-sticky': at,
  };
}

/**
 * The custom property carrying one held column's measured offset. The cells
 * read it with the config's own arithmetic as the fallback, so they are
 * placed before anything has been measured and corrected after.
 */
export function pinVar(side: PinSide, index: number): string {
  return `--fve-pin-${side}-${index}`;
}

function offsetOf(pin: StickyPin): CSSProperties | undefined {
  if (pin.offset === undefined) return undefined;
  return pin.side === 'left' ? { left: pin.offset } : { right: pin.offset };
}
