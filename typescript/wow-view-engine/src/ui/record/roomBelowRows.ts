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
 * How much of the port the rows leave empty, so the summaries can be drawn
 * at its bottom rather than straight after the last row.
 *
 * A workbench fills its container and its footer stays at the bottom
 * (`styles.css`, "A workbench fills its container"): the port takes the
 * room the parts above it left, three rows or thirty. The summary rows are the
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
