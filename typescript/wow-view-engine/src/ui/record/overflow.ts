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
): boolean {
  const [overflowing, setOverflowing] = useState(false);

  useLayoutEffect(() => {
    const node = port.current;
    if (!node) return;
    const measure = () =>
      setOverflowing(node.scrollWidth > node.clientWidth + 1);
    measure();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    if (table.current) observer.observe(table.current);
    return () => observer.disconnect();
  }, [port, table]);

  return overflowing;
}
