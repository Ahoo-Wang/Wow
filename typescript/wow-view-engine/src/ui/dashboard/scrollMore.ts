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

import { useLayoutEffect, useState, type CSSProperties } from 'react';

/** Which of a scroll port's sides have more of its content past them. */
export type ScrollMore = 'start' | 'end' | 'both';

/** How far in from an edge the content fades out. */
export const SCROLL_FADE = 32;

/** The tables a panel's body can draw, as against a chart's reading table. */
const TABLE = '[data-slot="record-table"], [data-slot="analysis-table"]';

/**
 * What is past a panel body's edges, and how tall the panel would have to
 * be to show its body whole.
 */
export interface ScrollState {
  /** The sides, across, with columns past them. */
  more?: ScrollMore;
  /** Whether there is more of the body under its bottom edge. */
  below?: boolean;
  /** The table's rows not wholly in view under the bottom edge. */
  rows?: number;
  /** The table's columns not wholly in view past the end edge. */
  columns?: number;
  /**
   * How far up from the port's bottom edge a cue may stand clear of what
   * the port holds on to there: its horizontal scrollbar and a table's
   * sticky totals band.
   */
  inset: number;
  /**
   * The height, in pixels, the port's parent — the panel's card — would
   * need to draw the body whole; `0` where the body's size is not its
   * content's (a chart, an empty state fill whatever room they are given).
   */
  whole: number;
}

/** The mask that fades the sides across; nothing when no side has more. */
export interface ScrollMoreResult extends ScrollState {
  style?: CSSProperties;
}

/**
 * What a panel's body has past its edges, the fade that says so across,
 * and what the panel needs to show it whole (P1-3).
 *
 * A panel's table wider than the panel scrolls sideways in the panel's
 * body, and on a system that hides its scrollbars until they are used —
 * macOS by default — nothing on screen said so: the console's cluster
 * panel read as six columns, its seventh 「最早下次重试」 cut at the edge
 * with no sign there was more (compensation console walkthrough, W13). The
 * content now fades out towards a side that has more past it, the way a
 * list that scrolls on a phone does, and stops fading once scrolled there;
 * the body is a Tab stop, so the arrow keys scroll it too. Down, a fade
 * would wash out the table's sticky header and totals band, so what is
 * under the bottom edge is counted instead — the rows (and the columns
 * past the end) not wholly in view, which the panel says in a cue.
 *
 * The fade is a mask on the port itself, so it covers whatever the body
 * draws — the sticky header and totals bands included — without a layer
 * over them that could take a click. It stops short of the port's own
 * scrollbar, which stays whole. Measured on scroll, when the port or a
 * table in it resizes, and when what the port holds changes (a result
 * arrives) — a chart redrawing itself is not that — at most once a frame.
 *
 * `fits` says the body's content keeps its own height even without a
 * table — a note, a list of links — so its whole height is worth asking
 * for; a table's always is.
 */
export function useScrollMore(
  /** The port, as a callback ref hands it over; `null` while there is none. */
  node: HTMLElement | null,
  fits = false,
): ScrollMoreResult {
  const [state, setState] = useState<ScrollState & { bar: number }>(NONE);

  useLayoutEffect(() => {
    if (!node) return;
    const measure = () => {
      const next = measurePort(node, fits);
      setState(previous => (sameState(previous, next) ? previous : next));
    };
    let frame: number | null = null;
    const schedule = () => {
      if (frame !== null) return;
      frame = requestAnimationFrame(() => {
        frame = null;
        measure();
      });
    };
    node.addEventListener('scroll', schedule, { passive: true });
    // The port's own size, and each table's in it: a table widens (a
    // result, a column) without the port changing size.
    const resized =
      typeof ResizeObserver === 'undefined'
        ? null
        : new ResizeObserver(schedule);
    resized?.observe(node);
    const watched = new Set<Element>();
    const watch = () => {
      // Each table, and each block the body stacks: the whole height is
      // theirs, and a note wraps to more lines without the port resizing.
      for (const child of [...node.querySelectorAll('table'), ...node.children])
        if (!watched.has(child)) {
          watched.add(child);
          resized?.observe(child);
        }
    };
    watch();
    measure();
    // What the body holds changing — a table arriving — but not a chart
    // redrawing itself, which rewrites its drawing many times a second.
    const changed =
      typeof MutationObserver === 'undefined'
        ? null
        : new MutationObserver(records => {
            if (records.every(record => inDrawing(record.target))) return;
            watch();
            schedule();
          });
    changed?.observe(node, { childList: true, subtree: true });
    return () => {
      node.removeEventListener('scroll', schedule);
      resized?.disconnect();
      changed?.disconnect();
      if (frame !== null) cancelAnimationFrame(frame);
    };
  }, [node, fits]);

  const { bar, ...shown } = state;
  return shown.more ? { ...shown, style: fadeMask(shown.more, bar) } : shown;
}

const NONE: ScrollState & { bar: number } = { bar: 0, inset: 0, whole: 0 };

function sameState(
  a: ScrollState & { bar: number },
  b: ScrollState & { bar: number },
): boolean {
  return (
    a.more === b.more &&
    a.below === b.below &&
    a.rows === b.rows &&
    a.columns === b.columns &&
    a.inset === b.inset &&
    a.whole === b.whole &&
    a.bar === b.bar
  );
}

/** One reading of the port: what is past its edges, and its whole height. */
export function measurePort(
  node: HTMLElement,
  fits = false,
): ScrollState & { bar: number } {
  const room = node.scrollWidth - node.clientWidth;
  const scrolled = node.scrollLeft;
  const start = room > 1 && scrolled > 1;
  const end = room > 1 && scrolled < room - 1;
  const more: ScrollMore | undefined =
    start && end ? 'both' : start ? 'start' : end ? 'end' : undefined;
  const bar = Math.max(0, node.offsetWidth - node.clientWidth);
  const table = node.querySelector<HTMLElement>(TABLE)?.querySelector('table');
  const below = node.scrollHeight - node.clientHeight - node.scrollTop > 1;
  const box = node.getBoundingClientRect();
  const foot = table?.querySelector<HTMLElement>(
    ':scope > [data-sticky="bottom"]',
  );
  // The bottom edge of what a reader sees of the rows: the port's, or the
  // top of a totals band held against it.
  const floor = Math.min(
    box.top + node.clientHeight,
    foot ? foot.getBoundingClientRect().top : Infinity,
  );
  const rows = table && below ? rowsUnder(table, floor) : 0;
  const columns =
    table && end ? columnsPast(table, box.left + node.clientWidth) : 0;
  return {
    bar,
    ...(more ? { more } : {}),
    ...(below ? { below } : {}),
    ...(rows > 0 ? { rows } : {}),
    ...(columns > 0 ? { columns } : {}),
    inset:
      Math.max(0, node.offsetHeight - node.clientHeight) +
      Math.max(0, box.top + node.clientHeight - floor),
    whole: table || fits ? wholeHeight(node) : 0,
  };
}

/**
 * The rows of a table not wholly above `floor`. A table drawn virtually
 * (`aria-rowcount`) holds only the rows around the view, so the rows it
 * does not draw are counted from where the last row in view stands.
 */
export function rowsUnder(table: HTMLTableElement, floor: number): number {
  const body = [...table.tBodies].flatMap(section => [...section.rows]);
  const rows = body.filter(row => row.cells.length > 0 && !isRoom(row));
  const cut = rows.filter(
    row => row.getBoundingClientRect().bottom > floor + 1,
  );
  const count = Number(table.getAttribute('aria-rowcount'));
  if (!count) return cut.length;
  const heads = table.tHead?.rows.length ?? 0;
  const feet = table.tFoot?.rows.length ?? 0;
  const seen = rows
    .filter(row => !cut.includes(row))
    .reduce(
      (last, row) => Math.max(last, Number(row.getAttribute('aria-rowindex'))),
      heads,
    );
  return Math.max(0, count - feet - seen);
}

/** A row that only holds the room of the rows a virtual table leaves out. */
function isRoom(row: HTMLTableRowElement): boolean {
  return (
    row.cells.length === 1 &&
    row.cells[0].colSpan > 1 &&
    !row.hasAttribute('aria-rowindex')
  );
}

/** The header's columns not wholly before `edge` — the filler aside. */
export function columnsPast(table: HTMLTableElement, edge: number): number {
  const head = table.tHead?.rows[table.tHead.rows.length - 1];
  if (!head) return 0;
  return [...head.cells].filter(
    cell =>
      cell.dataset.column !== 'filler' &&
      cell.getBoundingClientRect().right > edge + 1,
  ).length;
}

/**
 * The height the port's parent needs to draw the port's content whole: the
 * parent as it stands, less the port's inner height, plus the height the
 * content takes — measured off the blocks it stacks rather than
 * `scrollHeight`, which never reads less than the port, so a panel that
 * grew for a long page would never learn the next page is short.
 */
export function wholeHeight(node: HTMLElement): number {
  const parent = node.parentElement;
  if (!parent) return 0;
  const top = node.getBoundingClientRect().top - node.scrollTop;
  let bottom = top;
  for (const child of node.children) {
    const box = child.getBoundingClientRect();
    if (box.height === 0 && box.width === 0) continue;
    if (getComputedStyle(child).position === 'absolute') continue;
    bottom = Math.max(bottom, box.bottom);
  }
  const padding = parseFloat(getComputedStyle(node).paddingBottom) || 0;
  const content = bottom - top + padding;
  return Math.ceil(parent.offsetHeight - node.clientHeight + content);
}

/** Whether a node is part of a chart's drawing. */
function inDrawing(target: Node): boolean {
  const element = target instanceof Element ? target : target.parentElement;
  return element?.closest('svg, [data-slot="chart-plot"]') != null;
}

/**
 * The mask that fades the content out towards each side with more past it,
 * over `SCROLL_FADE` pixels, and leaves `bar` pixels at the end — the
 * port's vertical scrollbar — drawn whole.
 */
export function fadeMask(more: ScrollMore, bar: number): CSSProperties {
  const start = more !== 'end';
  const end = more !== 'start';
  const edge = `calc(100% - ${bar}px)`;
  const stops = [
    start ? `transparent 0, #000 ${SCROLL_FADE}px` : '#000 0',
    end
      ? `#000 calc(100% - ${bar + SCROLL_FADE}px), transparent ${edge}`
      : `#000 ${edge}`,
    ...(bar > 0 ? [`#000 ${edge}`] : []),
  ];
  const image = `linear-gradient(to right, ${stops.join(', ')})`;
  return { maskImage: image, WebkitMaskImage: image };
}
