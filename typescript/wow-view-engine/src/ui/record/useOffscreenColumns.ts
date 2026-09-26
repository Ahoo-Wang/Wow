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

import { useEffect, useState, type RefObject } from 'react';
import type { OffscreenColumns, OffscreenSide } from './offscreenSummaries.js';

/**
 * What a summary reading in the footer is marked with: the field of the
 * column it stands under. The observer watches these and nothing else.
 */
export const SUMMARY_READING = 'data-summary-field';

const NONE: OffscreenColumns = new Map();

/**
 * Seen as in view once this much of a reading is: all of it, less the
 * rounding a browser leaves on a box that sits exactly at the edge.
 */
const WHOLE = 0.99;

/**
 * The box that scrolls the table sideways: its own port, or — where the
 * table turns that off — whatever around it clips it, a board panel's body.
 * `null` is the viewport.
 */
export function scrollRootOf(node: Element): Element | null {
  for (let parent = node.parentElement; parent; parent = parent.parentElement) {
    const { overflowX } = getComputedStyle(parent);
    if (overflowX !== 'visible' && overflowX !== '') return parent;
  }
  return null;
}

/**
 * How much of the scroll root's two ends the held columns cover. A column
 * that slides under a pinned one is under it, not in view — and the footer
 * cells say which are held (`data-pin`), the hint's own cell among them.
 */
function heldInsets(
  footer: HTMLElement,
  root: Element | null,
): { left: number; right: number } {
  const bounds = root
    ? root.getBoundingClientRect()
    : { left: 0, right: window.innerWidth };
  const row = footer.querySelector('tr');
  let left = 0;
  let right = 0;
  for (const cell of row?.querySelectorAll('[data-pin]') ?? []) {
    const box = cell.getBoundingClientRect();
    if (cell.getAttribute('data-pin') === 'left')
      left = Math.max(left, box.right - bounds.left);
    else right = Math.max(right, bounds.right - box.left);
  }
  return { left: Math.max(0, left), right: Math.max(0, right) };
}

function sideOf(entry: IntersectionObserverEntry): OffscreenSide {
  const box = entry.boundingClientRect;
  const root = entry.rootBounds;
  if (!root) return 'right';
  return box.left + box.width / 2 < root.left + root.width / 2
    ? 'left'
    : 'right';
}

function same(a: OffscreenColumns, b: OffscreenColumns): boolean {
  if (a.size !== b.size) return false;
  for (const [field, side] of a) if (b.get(field) !== side) return false;
  return true;
}

/**
 * Which summarised columns lie outside the table's horizontal view (D51).
 *
 * One `IntersectionObserver`, rooted at the box that scrolls the table
 * sideways, watches the summary readings in the footer; it is told about
 * scrolling and resizing by the browser, so nothing here listens to either.
 * The root is stretched without end vertically — the question is only
 * sideways, and the band is held to the bottom of its port anyway — and
 * pulled in at each end by the columns held there. A field is out of view
 * when any of its readings is not whole in view.
 *
 * It is built again whenever the footer's cells may have changed — the rows,
 * the columns or what is held — over the new cells and the held columns'
 * new widths.
 */
export function useOffscreenColumns(
  footer: RefObject<HTMLElement | null>,
  rows: unknown,
  columns: unknown,
  pins: unknown,
): OffscreenColumns {
  const [offscreen, setOffscreen] = useState<OffscreenColumns>(NONE);

  useEffect(() => {
    const node = footer.current;
    if (!node || typeof IntersectionObserver === 'undefined') return;
    // A reading in a held cell is in view wherever the table is scrolled,
    // and it lies inside the very inset the held columns take off the root,
    // so it is not asked about at all.
    const readings = [...node.querySelectorAll(`[${SUMMARY_READING}]`)].filter(
      reading => !reading.closest('[data-pin]'),
    );
    if (readings.length === 0) return;
    const root = scrollRootOf(node);
    const inset = heldInsets(node, root);
    const out = new Map<Element, OffscreenSide | null>();
    const observer = new IntersectionObserver(
      entries => {
        for (const entry of entries)
          out.set(
            entry.target,
            entry.isIntersecting && entry.intersectionRatio >= WHOLE
              ? null
              : sideOf(entry),
          );
        const next = new Map<string, OffscreenSide>();
        for (const [reading, side] of out) {
          const field = reading.getAttribute(SUMMARY_READING);
          if (side && field && !next.has(field)) next.set(field, side);
        }
        setOffscreen(previous => (same(previous, next) ? previous : next));
      },
      {
        root,
        rootMargin: `100000px ${-inset.right}px 100000px ${-inset.left}px`,
        threshold: [0, WHOLE],
      },
    );
    readings.forEach(reading => observer.observe(reading));
    return () => observer.disconnect();
  }, [footer, rows, columns, pins]);

  return offscreen;
}

/**
 * Bring a column's summary into view: scroll the root sideways by as little
 * as puts the cell between the held columns, which `scrollIntoView` cannot
 * do — it knows nothing of what is pinned over the port's edges.
 */
export function revealCell(cell: HTMLElement): void {
  const footer = cell.closest('tfoot');
  const root = scrollRootOf(cell);
  if (!footer || !root) {
    cell.scrollIntoView({ inline: 'nearest', block: 'nearest' });
    return;
  }
  const inset = heldInsets(footer, root);
  const bounds = root.getBoundingClientRect();
  const box = cell.getBoundingClientRect();
  const from = bounds.left + inset.left;
  const to = bounds.right - inset.right;
  const delta =
    box.right > to && box.left > from
      ? Math.min(box.right - to, box.left - from)
      : box.left < from
        ? box.left - from
        : 0;
  if (delta !== 0) root.scrollBy({ left: delta });
}
