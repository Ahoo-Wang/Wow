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
 * Whether the table is wider than the port it stands in — the one fact the
 * frozen columns' edges depend on (P-23, D13 as amended 2026-09-22).
 *
 * The edge of a held column says "the middle scrolls under here". While the
 * table fits, nothing scrolls under anything: the line then only cut the
 * row's surplus off the last column, and the filler read as an empty column
 * (user, 2026-09-22). So the port says on itself whether it overflows, and
 * the edges are drawn against that word rather than always.
 *
 * Measured after layout and again whenever the port or the table changes
 * size — a column resized, a column shown, the window narrowed. The moment
 * overflow begins is the moment the filler's width reaches zero, so a held
 * column's natural place and its pinned place coincide and nothing jumps.
 */
export function useOverflowing(
  port: RefObject<HTMLElement | null>,
  table: RefObject<HTMLElement | null>,
  /**
   * Whether the port is its own scroller (`stickyPort`): only then is what
   * lies hidden past each side its scroll position's to say.
   */
  scrolls = false,
): Overflow {
  const [overflow, setOverflow] = useState<Overflow>(FITS);

  useLayoutEffect(() => {
    const node = port.current;
    if (!node) return;
    const measure = () => {
      const next = measureOverflow(node, scrolls);
      setOverflow(previous => (sameOverflow(previous, next) ? previous : next));
    };
    measure();
    let frame: number | null = null;
    const schedule = () => {
      if (frame !== null) return;
      frame = requestAnimationFrame(() => {
        frame = null;
        measure();
      });
    };
    if (scrolls) node.addEventListener('scroll', schedule, { passive: true });
    const observer =
      typeof ResizeObserver === 'undefined'
        ? null
        : new ResizeObserver(measure);
    observer?.observe(node);
    if (table.current) observer?.observe(table.current);
    return () => {
      node.removeEventListener('scroll', schedule);
      observer?.disconnect();
      if (frame !== null) cancelAnimationFrame(frame);
    };
  }, [port, table, scrolls]);

  return overflow;
}

/**
 * Whether the table overflows its port, and which sides have columns
 * scrolled out of sight past them (second review R2-76): a held column's
 * edge fades the content passing under it only while there is content
 * under it — 「PO202609210(」 cut by the held 最近更新 read as a damaged
 * number. The edge itself answers to `overflowing` alone (D13, P-23).
 */
interface Overflow {
  overflowing: boolean;
  /** Columns scrolled out of sight before the start. */
  start: boolean;
  /** Columns not yet scrolled into sight past the end. */
  end: boolean;
}

const FITS: Overflow = { overflowing: false, start: false, end: false };

/** One reading of the port. */
function measureOverflow(node: HTMLElement, scrolls: boolean): Overflow {
  const room = node.scrollWidth - node.clientWidth;
  const overflowing = room > 1;
  if (!overflowing || !scrolls) return { ...FITS, overflowing };
  return {
    overflowing,
    start: node.scrollLeft > 1,
    end: node.scrollLeft < room - 1,
  };
}

function sameOverflow(a: Overflow, b: Overflow): boolean {
  return (
    a.overflowing === b.overflowing && a.start === b.start && a.end === b.end
  );
}
