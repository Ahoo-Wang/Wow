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

import type * as React from 'react';
import { useEffect, useLayoutEffect, useRef } from 'react';
import type { RecordColumnView } from '../../record/index.js';
import { useViewMessages } from '../MessagesProvider.js';

/**
 * The narrowest a column may be dragged.
 *
 * A column has to stay grabbable: its own edge is the only way back, and an
 * edge dragged onto the one before it is a column nobody can find again. It
 * is a rule about a handle rather than about a config, so it lives here and
 * not in `validateRecord` — a width written by hand may be anything positive.
 */
export const MIN_COLUMN_WIDTH = 48;

/** One arrow press, and one arrow press with shift held. */
const STEP = 8;
const LEAP = 32;

export interface ColumnResizerProps {
  column: RecordColumnView;
  /** Commits a width in pixels, or `null` to let the column size itself. */
  onResize(field: string, width: number | null): void;
}

/**
 * The right edge of a column header, as something to take hold of.
 *
 * **The drag writes the DOM, not state.** A column's width is a layout the
 * browser already knows and React does not, and a `setState` per
 * `pointermove` would re-render the whole result — every row, every cell —
 * between one frame and the next. So the gesture writes the width straight
 * onto the cells of its own column, the same reasoning `usePinnedOffsets`
 * measures by, and only the *release* goes through the controller. The pins
 * follow along on their own: the offsets are published from a
 * `ResizeObserver` on the header cells, and the cell this drag is resizing is
 * one of them.
 *
 * **The cells are found through the table, not through React.** The handle
 * knows its own `<th>`, the `<th>` knows its `cellIndex`, and the table knows
 * its rows — so one column is every row's cell at that index, header, body
 * and summaries alike. A width put on the header alone is a suggestion an
 * auto-laid-out table is free to ignore, and it is ignored exactly when it
 * matters: dragging a column narrower than the text in it.
 *
 * **What it commits is a width, and what it un-commits is the key.** A
 * double-click, or Enter, writes `null`, and the controller then drops the
 * member rather than setting it to `undefined` — a config is JSON, and a
 * column back at its automatic width has no `width` in it.
 */
export function ColumnResizer({ column, onResize }: ColumnResizerProps) {
  const messages = useViewMessages();
  const handle = useRef<HTMLDivElement>(null);
  /** Ends the gesture in flight, if there is one. */
  const stop = useRef<(() => void) | null>(null);

  // The preview is inline style the gesture wrote; React owns `width` and
  // `max-width` and takes those back itself on the render that carries the
  // new width, but the clipping has no prop behind it and would otherwise
  // outlive the column it was applied to — a column reset to automatic would
  // keep truncating.
  useLayoutEffect(() => {
    const node = handle.current;
    for (const cell of columnCells(node)) unclip(cell);
    // A column with no width of its own reports what it measures: the table
    // laid it out by content, so the DOM holds the only number there is.
    if (!node || column.width !== undefined) return;
    const box = Math.round(headOf(node)?.getBoundingClientRect().width ?? 0);
    if (box > 0) node.setAttribute('aria-valuenow', String(box));
  }, [column.width]);

  // A handle taken off the screen mid-drag — a refresh that replaces the
  // columns — must not leave its listeners on the document.
  useEffect(() => () => stop.current?.(), []);

  const resize = (node: HTMLElement, width: number | null) => {
    draw(node, width);
    onResize(column.field, width);
  };

  const onPointerDown = (event: React.PointerEvent<HTMLDivElement>) => {
    if (event.button !== 0) return;
    // The press belongs to the edge and to nothing under it: left alone it
    // starts a text selection across the header it is drawn in.
    event.preventDefault();
    event.stopPropagation();
    const node = event.currentTarget;
    const { pointerId } = event;
    const from = event.clientX;
    const was = measure(node);
    let width = was;

    // Bound to the pointer where the platform tracks it, so a drag that
    // leaves the window still ends in this handle's own `pointerup`. Where
    // it does not — a synthetic pointer, which is what a test drives — the
    // document listeners below carry the gesture on their own.
    try {
      node.setPointerCapture(pointerId);
    } catch {
      /* Not a pointer this platform is tracking. */
    }

    const ended = () => {
      stop.current = null;
      const owner = node.ownerDocument;
      owner.removeEventListener('pointermove', moved);
      owner.removeEventListener('pointerup', released);
      owner.removeEventListener('pointercancel', cancelled);
      try {
        node.releasePointerCapture(pointerId);
      } catch {
        /* Never captured, or already let go. */
      }
    };
    const moved = (step: PointerEvent) => {
      if (step.pointerId !== pointerId) return;
      width = Math.max(MIN_COLUMN_WIDTH, Math.round(was + step.clientX - from));
      draw(node, width);
    };
    const released = (last: PointerEvent) => {
      if (last.pointerId !== pointerId) return;
      ended();
      onResize(column.field, width);
    };
    // A cancelled pointer is not a decision: the column goes back to the
    // width the config has, which is also the width React believes it wrote.
    const cancelled = (last: PointerEvent) => {
      if (last.pointerId !== pointerId) return;
      ended();
      draw(node, column.width ?? null);
    };

    stop.current = ended;
    const owner = node.ownerDocument;
    owner.addEventListener('pointermove', moved);
    owner.addEventListener('pointerup', released);
    owner.addEventListener('pointercancel', cancelled);
  };

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    const node = event.currentTarget;
    if (event.key === 'Enter') {
      event.preventDefault();
      resize(node, null);
      return;
    }
    const towards =
      event.key === 'ArrowLeft' ? -1 : event.key === 'ArrowRight' ? 1 : 0;
    if (towards === 0) return;
    event.preventDefault();
    const by = towards * (event.shiftKey ? LEAP : STEP);
    resize(node, Math.max(MIN_COLUMN_WIDTH, measure(node) + by));
  };

  return (
    <div
      ref={handle}
      data-slot="column-resizer"
      data-field={column.field}
      // A separator is what sits between two columns and can be moved, and
      // ARIA's own example of one is exactly this: a pane divider with a
      // value. Vertical, because the line is vertical — the attribute names
      // the separator's own orientation, not the axis it travels on.
      role="separator"
      aria-orientation="vertical"
      aria-label={messages.label('label.columns.resize', {
        field: column.label,
      })}
      aria-valuemin={MIN_COLUMN_WIDTH}
      aria-valuenow={column.width ?? MIN_COLUMN_WIDTH}
      tabIndex={0}
      // The hit area is wider than the line, and sits inside the cell so it
      // can never be clipped by a column that has one. The line is drawn at
      // rest — a hairline in `--border` on the boundary — and thickens to
      // `--ring` under the pointer or focus. It used to appear only on
      // hover, which the user rejected (2026-09-21) and this package's own
      // rule already forbade: an affordance that shows up only after it has
      // been used is not an affordance, and a touch screen never hovers.
      className="absolute top-0 right-0 z-20 h-full w-2 cursor-col-resize touch-none select-none after:absolute after:inset-y-0 after:right-0 after:w-px after:bg-border hover:after:w-0.5 hover:after:bg-ring focus-visible:outline-none focus-visible:after:w-0.5 focus-visible:after:bg-ring"
      onPointerDown={onPointerDown}
      onKeyDown={onKeyDown}
      // The gesture's own "never mind": back to whatever the content asks
      // for, which is the state a column that was never dragged is in.
      onDoubleClick={event => resize(event.currentTarget, null)}
    />
  );
}

/** The header cell this handle is drawn in. */
function headOf(node: HTMLElement | null): HTMLTableCellElement | null {
  return node?.closest('th') ?? null;
}

/**
 * Every cell of one column: the header, each row, and each summary line.
 *
 * Read off the table rather than collected from React, because what the
 * preview has to change is a box the browser laid out — and a width on the
 * header alone is a suggestion, which an auto-laid-out table takes only as
 * far as its widest cell lets it.
 */
function columnCells(node: HTMLElement | null): HTMLTableCellElement[] {
  const head = headOf(node);
  const table = head?.closest('table');
  if (!head || !table) return [];
  const at = head.cellIndex;
  return [...table.rows].flatMap(row => {
    const cell = row.cells[at];
    return cell ? [cell] : [];
  });
}

/**
 * The width to go on from, which is the width the column is *now*.
 *
 * Not `column.width`: a committed width only reaches the projection with the
 * next result, so a second arrow press within that round trip would repeat
 * the first one. The inline width a gesture has already written answers
 * first, then the box the table laid out.
 */
function measure(node: HTMLElement): number {
  const head = headOf(node);
  if (!head) return MIN_COLUMN_WIDTH;
  const inline = Number.parseFloat(head.style.width);
  const box = Math.round(head.getBoundingClientRect().width);
  return Math.max(
    MIN_COLUMN_WIDTH,
    Number.isFinite(inline) && inline > 0 ? inline : box,
  );
}

/**
 * The preview, written onto the column as it is dragged.
 *
 * The clipping goes on with it: a column narrower than its own text only
 * gets there if the text may be cut, and until the width commits there is no
 * class on these cells saying so.
 */
function draw(node: HTMLElement, width: number | null): void {
  for (const cell of columnCells(node)) {
    cell.style.width = width === null ? '' : `${width}px`;
    cell.style.minWidth = cell.style.width;
    cell.style.maxWidth = cell.style.width;
    if (width === null) unclip(cell);
    else {
      cell.style.overflow = 'hidden';
      cell.style.textOverflow = 'ellipsis';
      cell.style.whiteSpace = 'nowrap';
    }
  }
  node.setAttribute('aria-valuenow', String(width ?? measure(node)));
}

function unclip(cell: HTMLTableCellElement): void {
  cell.style.overflow = '';
  cell.style.textOverflow = '';
  cell.style.whiteSpace = '';
}
