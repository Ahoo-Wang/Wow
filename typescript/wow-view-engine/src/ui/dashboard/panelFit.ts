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

import { useCallback, useRef, useState } from 'react';
import { defaultGridConfig } from 'react-grid-layout/core';
import { grownLayout, type PlacedPanel } from '../../dashboard/index.js';

/** The gap the grid keeps between two rows: the library's own. */
const GAP_Y = defaultGridConfig.margin[1];

/** More than any scrollbar is thick: a change in height smaller than this may be one. */
const SCROLLBAR = 24;

/**
 * How many rows a panel grows to at most while its board is read: eight
 * 80px rows are 710px, a panel a laptop's screen still shows whole under
 * the board's header, so a table longer than that scrolls inside a panel
 * the reader sees all of — its header, its cue and its paging — rather
 * than one that runs off the page. A panel its author made taller keeps
 * its height.
 */
export const GROWS_TO_ROWS = 8;

/**
 * The height, in pixels, each panel on screen needs to draw its body whole,
 * as each panel reports it (`DashboardPanel.onWhole`), and a reporter per
 * panel that keeps its identity, so reporting is an effect on the height
 * alone.
 */
export function usePanelWholes(): {
  wholes: ReadonlyMap<string, number>;
  reporter(id: string): (height: number) => void;
} {
  const [wholes, setWholes] = useState<ReadonlyMap<string, number>>(
    () => new Map(),
  );
  const reporters = useRef(new Map<string, (height: number) => void>());
  const reporter = useCallback((id: string) => {
    let report = reporters.current.get(id);
    if (!report) {
      report = (height: number) =>
        setWholes(previous => {
          const known = previous.get(id) ?? 0;
          // A few pixels less is a scrollbar that went when the panel grew,
          // not a shorter body: taking them back would shrink the panel,
          // bring the scrollbar back and grow it again, for ever.
          const settled =
            height === known ||
            (height > 0 && known - SCROLLBAR < height && height < known);
          return settled ? previous : new Map(previous).set(id, height);
        });
      reporters.current.set(id, report);
    }
    return report;
  }, []);
  return { wholes, reporter };
}

/**
 * The layout a board is read in (P1-3): a panel whose body holds more than
 * its saved height shows — a table's rows, a note's lines — grows by whole
 * rows until it holds it all or reaches `GROWS_TO_ROWS`, and whatever
 * stood under it moves down with it (`grownLayout`). A panel never
 * shrinks: the author's height is the least it gets, so a short table
 * beside a chart keeps the row they share even.
 *
 * Only while the board is read. Built, every panel is drawn at the size
 * that is saved, since that is what a drag or a resize changes.
 */
export function fittedLayout(
  boxes: readonly PlacedPanel[],
  wholes: ReadonlyMap<string, number>,
  rowHeight: number,
): PlacedPanel[] {
  const heights = new Map<string, number>();
  for (const box of boxes) {
    const whole = wholes.get(box.id) ?? 0;
    if (whole <= 0) continue;
    const rows = rowsFor(whole, rowHeight);
    if (rows > box.h)
      heights.set(box.id, Math.min(rows, Math.max(box.h, GROWS_TO_ROWS)));
  }
  return heights.size === 0 ? [...boxes] : grownLayout(boxes, heights);
}

/** The fewest grid rows a panel `height` pixels tall fits in. */
export function rowsFor(height: number, rowHeight: number): number {
  // A panel of h rows is h·rowHeight + (h − 1)·gap tall.
  return Math.max(1, Math.ceil((height + GAP_Y) / (rowHeight + GAP_Y) - 1e-6));
}
