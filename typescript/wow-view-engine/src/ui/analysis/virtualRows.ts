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

import {
  useCallback,
  useLayoutEffect,
  useRef,
  useState,
  type RefObject,
} from 'react';
import { flushSync } from 'react-dom';
import {
  defaultRangeExtractor,
  elementScroll,
  observeElementOffset,
  observeElementRect,
  observeWindowOffset,
  observeWindowRect,
  useVirtualizer,
  windowScroll,
  type Range,
  type VirtualItem,
} from '@tanstack/react-virtual';

/**
 * How many groups a result holds before its table draws only the rows in
 * view (ui/analysis.md「长表」). Measured, not guessed: a thousand rows drew
 * and sorted in about 150ms in every engine, five thousand in about 550ms,
 * ten thousand in about 1.1s — a second's freeze on every header press.
 *
 * Below it every row is in the document, which a reader's find-in-page and
 * a screen reader's browse mode both want; above it, the rows around the
 * view are drawn and the table says how many there are (`aria-rowcount`).
 */
export const VIRTUAL_ROWS_AFTER = 1_000;

/** A row's height before one is measured: the default density's row. */
const ESTIMATED_ROW = 37;

/**
 * Rows drawn beyond each edge of the view: enough that a wheel's notch or
 * a held arrow key lands on rows already there.
 */
const OVERSCAN = 12;

/** What scrolls the rows: the table's own port, an ancestor, or the page. */
type Scroller = Element | Window;

/** One piece of the body as drawn: a row, or the room rows not drawn take. */
export type BodySegment =
  | { kind: 'row'; index: number; key: VirtualItem['key'] }
  | { kind: 'gap'; key: string; height: number };

/**
 * The drawn rows in index order with the room between and around them, so
 * the body is as tall as every row would make it and each drawn row sits
 * where it would. A gap between two drawn rows is the row holding the Tab
 * stop, kept drawn away from the others (`useVirtualRows`).
 */
export function bodySegments(
  items: readonly VirtualItem[],
  total: number,
  margin: number,
): BodySegment[] {
  const segments: BodySegment[] = [];
  let at = 0;
  for (const item of [...items].sort((a, b) => a.index - b.index)) {
    const start = item.start - margin;
    if (start > at)
      segments.push({
        kind: 'gap',
        key: `gap-${item.index}`,
        height: start - at,
      });
    segments.push({ kind: 'row', index: item.index, key: item.key });
    at = Math.max(at, item.end - margin);
  }
  if (total > at)
    segments.push({ kind: 'gap', key: 'gap-end', height: total - at });
  return segments;
}

/** What the table needs to draw its rows virtually. */
export interface VirtualRows {
  /** The body as drawn; `null` when every row is drawn. */
  segments: BodySegment[] | null;
  /** Hands a drawn row to the virtualizer, which measures it. */
  measure: (node: HTMLTableRowElement | null) => void;
  /**
   * Brings a row into view and focuses it, once it is drawn; only while the
   * rows are drawn virtually. `then` is given the row, to take the group's
   * stop before focus lands.
   */
  focusRow: (index: number, then: (row: HTMLTableRowElement) => void) => void;
  /** Records which row holds the stop, so it stays drawn. */
  holdStop: (index: number) => void;
}

/**
 * Draws the rows of a long result virtually — only the rows in view and a
 * few around them — through TanStack Virtual.
 *
 * The rows scroll in whatever scrolls them. In a workbench, an expanded
 * view or a filled embed that is the table's own port, which the header and
 * the totals stick to; on a board it is the panel's body, and in an embed
 * laid out at its content's height, the page. The port is the scroller when
 * it is shorter than what it holds, which the room the rows take makes it
 * wherever its height is bounded; otherwise the nearest ancestor that
 * scrolls, otherwise the window.
 *
 * The row holding the Tab stop is always drawn, wherever the view is, so a
 * wheel that scrolls it away never takes focus with it to the page. Printing
 * draws every row: a sheet of paper has no scroll position.
 *
 * `'use no memo'`: the virtualizer is one object that changes inside, which
 * the React Compiler would take for unchanged.
 */
export function useVirtualRows(
  count: number,
  port: RefObject<HTMLElement | null>,
  body: RefObject<HTMLTableSectionElement | null>,
): VirtualRows {
  'use no memo';
  const printing = usePrinting();
  const enabled = count > VIRTUAL_ROWS_AFTER && !printing;
  const [scroller, setScroller] = useState<Scroller | null>(null);
  const [margin, setMargin] = useState(0);
  const [padding, setPadding] = useState({ start: 0, end: 0 });
  const stop = useRef(0);
  const pending = useRef<{
    index: number;
    then: (row: HTMLTableRowElement) => void;
  } | null>(null);

  const onWindow = scroller !== null && !(scroller instanceof Element);
  // The hook opts out of the compiler ('use no memo'), and what it hands the
  // table is built anew each render (`bodySegments`), never the mutable
  // virtualizer itself, so nothing memoized downstream reads it stale.
  // eslint-disable-next-line react-hooks/incompatible-library -- see above
  const virtualizer = useVirtualizer<Element, HTMLTableRowElement>({
    count,
    enabled,
    // None until the table is laid out and the scroller known: the first
    // pass draws a handful of rows, never the port's whole unbounded height.
    getScrollElement: () => scroller as unknown as Element | null,
    estimateSize: () => ESTIMATED_ROW,
    overscan: OVERSCAN,
    scrollMargin: margin,
    scrollPaddingStart: padding.start,
    scrollPaddingEnd: padding.end,
    rangeExtractor: (range: Range) => withStop(range, stop.current, count),
    ...(onWindow ? WINDOW : ELEMENT),
  });

  // Where the rows scroll, and where they start inside it: read when the
  // rows are first drawn virtually and whenever the port, its header or its
  // totals change size — a panel resized, a view expanded — and set only
  // when it moved, so the render it causes is the last.
  useLayoutEffect(() => {
    if (!enabled) return;
    const settle = () => {
      const own = port.current;
      const rows = body.current;
      if (!own || !rows) return;
      const next = scrollerOf(own);
      setScroller(previous => (previous === next ? previous : next));
      const top = rows.getBoundingClientRect().top;
      const offset =
        next instanceof Element
          ? top -
            next.getBoundingClientRect().top +
            next.scrollTop -
            next.clientTop
          : top + window.scrollY;
      const rounded = Math.round(offset);
      setMargin(previous => (previous === rounded ? previous : rounded));
      // What covers the rows inside their own port: the sticky header and
      // totals, which a row brought into view must clear.
      const table = rows.closest('table');
      const start =
        next === own ? (table?.tHead?.getBoundingClientRect().height ?? 0) : 0;
      const end =
        next === own ? (table?.tFoot?.getBoundingClientRect().height ?? 0) : 0;
      setPadding(previous =>
        previous.start === start && previous.end === end
          ? previous
          : { start, end },
      );
    };
    settle();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(settle);
    const table = body.current?.closest('table');
    for (const node of [port.current, table?.tHead, table?.tFoot])
      if (node) observer.observe(node);
    return () => observer.disconnect();
  }, [enabled, count, port, body]);

  // A row asked for before it was drawn is focused the render it is.
  useLayoutEffect(() => {
    const want = pending.current;
    if (!want) return;
    const row = rowAt(body.current, want.index);
    if (!row) return;
    pending.current = null;
    want.then(row);
    row.focus({ preventScroll: true });
  });

  const focusRow = useCallback(
    (index: number, then: (row: HTMLTableRowElement) => void) => {
      stop.current = index;
      virtualizer.scrollToIndex(index, { align: 'auto' });
      const row = rowAt(body.current, index);
      if (row) {
        then(row);
        row.focus({ preventScroll: true });
      } else pending.current = { index, then };
    },
    [virtualizer, body],
  );

  const holdStop = useCallback((index: number) => {
    stop.current = index;
  }, []);

  if (!enabled)
    return { segments: null, measure: noMeasure, focusRow, holdStop };
  return {
    segments: bodySegments(
      virtualizer.getVirtualItems(),
      virtualizer.getTotalSize(),
      margin,
    ),
    measure: virtualizer.measureElement,
    focusRow,
    holdStop,
  };
}

const noMeasure = () => {};

const ELEMENT = {
  observeElementRect,
  observeElementOffset,
  scrollToFn: elementScroll,
};

// The page as the scroller: TanStack's window functions under the element
// hook's option names, one hook either way, since which it is is known only
// once the table is laid out.
const WINDOW = {
  observeElementRect: observeWindowRect,
  observeElementOffset: observeWindowOffset,
  scrollToFn: windowScroll,
} as unknown as typeof ELEMENT;

/** The range in view, and the row holding the stop wherever it is. */
function withStop(range: Range, stop: number, count: number): number[] {
  const indexes = defaultRangeExtractor(range);
  if (stop >= count || indexes.includes(stop)) return indexes;
  return [...indexes, stop].sort((a, b) => a - b);
}

function rowAt(
  body: HTMLTableSectionElement | null,
  index: number,
): HTMLTableRowElement | null {
  return (
    body?.querySelector<HTMLTableRowElement>(`tr[data-index="${index}"]`) ??
    null
  );
}

/**
 * What scrolls the rows: the port when its height is bounded — then the
 * room the rows take overflows it — else the nearest ancestor that scrolls
 * up and down, else the page.
 */
function scrollerOf(port: HTMLElement): Scroller {
  if (port.scrollHeight > port.clientHeight + 1) return port;
  for (
    let node = port.parentElement;
    node && node !== document.body && node !== document.documentElement;
    node = node.parentElement
  ) {
    const { overflowY } = getComputedStyle(node);
    if (/(auto|scroll|overlay)/.test(overflowY)) return node;
  }
  return window;
}

/**
 * Whether the page is being printed: every row is drawn from the moment
 * the browser says it will print, synchronously, so the sheet it lays out
 * holds them.
 */
function usePrinting(): boolean {
  const [printing, setPrinting] = useState(false);
  useLayoutEffect(() => {
    if (typeof window === 'undefined') return;
    const before = () => flushSync(() => setPrinting(true));
    const after = () => setPrinting(false);
    window.addEventListener('beforeprint', before);
    window.addEventListener('afterprint', after);
    return () => {
      window.removeEventListener('beforeprint', before);
      window.removeEventListener('afterprint', after);
    };
  }, []);
  return printing;
}
