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

/**
 * The least a scroll port is given, whatever is above it. Below this the
 * rows would be a slit; the page scrolls instead, which is what a table
 * this far down a page needs anyway.
 */
export const MIN_FIT_PX = 192;

/**
 * How tall the table's own scroll port may be so that the frame it is in
 * ends at the bottom of the viewport: the summaries and the pagination row
 * stay in view whenever there are more rows than room (P-22).
 *
 * **Why measure.** The port's cap used to be a flat `70vh`. A cap in
 * viewport units knows nothing about where the port starts: under a title
 * bar, an editor tray and a toolbar, 70vh of rows ran past the bottom of
 * the window, and the two summary rows — `sticky bottom-0` against the
 * port, not the window — went with them. The reader scrolled the page to
 * find a footer that exists to be always in view.
 *
 * **What is measured.** The port's top in the viewport, what the frame
 * draws under the port (the pagination row, the frame's own edge), and what
 * the containers the frame closes draw under the frame (`trailingChrome`):
 * the cap is the viewport's height less all three. The last was missing
 * until 2026-09-23: a workbench given the whole screen still scrolled by
 * the 16px of its own `main`'s bottom padding, because the frame was fitted
 * to the viewport's edge and the padding went past it. Measured again whenever the page's
 * height changes — a tray unfolding above the table moves its top — and
 * whenever the window is resized. Not on scroll: a workbench that fits its
 * viewport has no page to scroll, and a host page that scrolls as a whole
 * turns the port off (`scrolls={false}`) and keeps sticky against itself.
 *
 * Returns `null` before the first measurement and where nothing can be
 * measured (jsdom); the caller then keeps its static fallback.
 */
export function useViewportFit(
  port: RefObject<HTMLElement | null>,
  enabled: boolean,
): number | null {
  const [fit, setFit] = useState<number | null>(null);

  useLayoutEffect(() => {
    const node = port.current;
    if (!enabled || !node || typeof window === 'undefined') return;
    const measure = () => {
      const rect = node.getBoundingClientRect();
      const frame = node.closest<HTMLElement>('[data-slot="result-block"]');
      const below = frame
        ? Math.max(0, frame.getBoundingClientRect().bottom - rect.bottom) +
          trailingChrome(frame)
        : 0;
      const room = window.innerHeight - rect.top - below;
      if (!Number.isFinite(room) || rect.height === 0) return;
      setFit(Math.max(MIN_FIT_PX, Math.floor(room)));
    };
    measure();
    window.addEventListener('resize', measure);
    if (typeof ResizeObserver === 'undefined')
      return () => window.removeEventListener('resize', measure);
    // What moves the port's top: the page growing (a host whose page is
    // as tall as its content), and — inside a host that gives the workbench
    // a fixed height, where the page never changes size — the blocks
    // stacked above the result in its own column: an editor unfolding, the
    // applied band arriving with the first result. The column's children
    // are observed as well as the page, and re-observed when one mounts.
    const observer = new ResizeObserver(measure);
    const frame = node.closest<HTMLElement>('[data-slot="result-block"]');
    const column = frame?.parentElement ?? null;
    const watch = () => {
      observer.disconnect();
      observer.observe(document.body);
      if (column)
        for (const child of Array.from(column.children))
          observer.observe(child);
    };
    watch();
    const mounts =
      column && typeof MutationObserver !== 'undefined'
        ? new MutationObserver(() => {
            watch();
            measure();
          })
        : null;
    if (column) mounts?.observe(column, { childList: true });
    return () => {
      window.removeEventListener('resize', measure);
      observer.disconnect();
      mounts?.disconnect();
    };
  }, [port, enabled]);

  return fit;
}

/**
 * The padding and borders drawn under an element by the containers it
 * closes: each ancestor whose last child holds it ends where it ends, plus
 * its own bottom padding and border. An ancestor with anything after it
 * stops the walk — what follows is content, not chrome, and a page that
 * puts content under the workbench scrolls to it as it should.
 */
function trailingChrome(element: HTMLElement): number {
  let total = 0;
  for (
    let child: HTMLElement = element, parent = element.parentElement;
    parent && parent !== document.documentElement;
    child = parent, parent = parent.parentElement
  ) {
    if (parent.lastElementChild !== child) break;
    const style = getComputedStyle(parent);
    total +=
      (parseFloat(style.paddingBottom) || 0) +
      (parseFloat(style.borderBottomWidth) || 0);
  }
  return total;
}

/**
 * How much of the port the rows leave empty, so the summaries can be drawn
 * at its bottom rather than straight after the last row.
 *
 * The footer of a workbench stays at the bottom of the screen (`styles.css`,
 * "A workbench fills the height its host gives it"): the port takes the room
 * the parts above it left, three rows or thirty. The summary rows are the
 * table's own footer, and they are sticky to the port's bottom only while
 * the rows overflow it — with three rows they sat under the third, and the
 * two footers of one result, the totals and the pagination, came apart with
 * blank space between them (the user's 2026-09-23 review). The room between
 * is given to a row of its own that draws nothing (`RecordTable`), which
 * puts the totals where the pagination is.
 *
 * Measured, because a table cannot be told to push its footer down: height
 * a table has beyond its rows is shared out among the rows, which would
 * stretch every row instead. What is measured is the port's inner height
 * against the table's without the room row, so the room it adds is never
 * counted as rows. `0` while the rows fill the port, where nothing is empty,
 * and wherever nothing can be measured (jsdom).
 */
export function useRoomBelowRows(
  port: RefObject<HTMLElement | null>,
  table: RefObject<HTMLElement | null>,
  enabled: boolean,
): number {
  const [room, setRoom] = useState(0);

  useLayoutEffect(() => {
    const node = port.current;
    const content = table.current;
    if (!enabled || !node || !content || typeof window === 'undefined') return;
    const measure = () => {
      const filler = content.querySelector<HTMLElement>(
        '[data-slot="row-room"]',
      );
      const rows = content.offsetHeight - (filler?.offsetHeight ?? 0);
      const empty = Math.floor(node.clientHeight - rows);
      setRoom(Number.isFinite(empty) && empty > 0 ? empty : 0);
    };
    measure();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    observer.observe(content);
    return () => observer.disconnect();
  }, [port, table, enabled]);

  // Switched off, there is no room to give whatever was last measured.
  return enabled ? room : 0;
}
