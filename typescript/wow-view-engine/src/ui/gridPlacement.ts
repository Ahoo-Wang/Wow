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

import { useMemo, useRef } from 'react';
import type {
  Compactor,
  EventCallback,
  Layout,
  LayoutItem,
} from 'react-grid-layout';
import { placePanel, type PlacedPanel } from '../dashboard/index.js';
import type { PanelLayout } from '../model/index.js';

/** What `DashboardGrid` hands the grid so a pointer places by the kernel's rules. */
export interface GridPlacement {
  compactor: Compactor;
  onDragStart: EventCallback;
  onDragStop: EventCallback;
  onResizeStart: EventCallback;
  onResizeStop: EventCallback;
}

/**
 * A pointer's drag and resize, placed by the same rules as a keyboard step.
 *
 * Left to itself the grid resolves a collision its own way: with no
 * compaction it lets a resized panel sit on top of its neighbour, and a
 * dragged one either overlaps or trades rows with what it lands on. The
 * keyboard goes through the kernel (`placePanel`), which pushes the covered
 * panels down, and two rules for one grid would show the user two answers
 * to the same move.
 *
 * So the library is told panels may overlap — it resolves nothing itself —
 * and the "compactor" it calls on every move is the kernel's placement of
 * the panel in hand against the layout as it was when the gesture began.
 * The preview under the pointer is therefore exactly the layout the drop
 * applies, and a panel pushed out of the way comes back if the dragged one
 * moves on, as it would had the drag stopped there. On the drop only the
 * panel in hand is handed to `place`; the runtime pushes the rest by the
 * same rule. Outside a gesture — on mount, on every new layout — it changes
 * nothing, because the grid shows the layout as it was admitted.
 */
export function useGridPlacement(
  place: (panelId: string, layout: PanelLayout) => void,
): GridPlacement {
  // The panel in hand and the layout the gesture started from; `null`
  // between gestures.
  const gesture = useRef<{ id: string; from: PlacedPanel[] } | null>(null);

  const compactor = useMemo<Compactor>(
    () => ({
      type: null,
      allowOverlap: true,
      compact(layout: Layout, columns: number): Layout {
        const held = gesture.current;
        const item = held && layout.find(entry => entry.i === held.id);
        const placed =
          held && item ? placePanel(held.from, held.id, item, columns) : null;
        if (!placed) return layout.map(entry => ({ ...entry }));
        const boxes = new Map(placed.map(box => [box.id, box]));
        return layout.map(entry => {
          const box = boxes.get(entry.i);
          return box ? { ...entry, ...geometry(box) } : { ...entry };
        });
      },
    }),
    [],
  );

  const start: EventCallback = (layout, _old, item) => {
    gesture.current = item
      ? {
          id: item.i,
          from: layout.map(entry => ({ id: entry.i, ...geometry(entry) })),
        }
      : null;
  };
  const stop: EventCallback = (_layout, _old, item) => {
    gesture.current = null;
    if (item) place(item.i, geometry(item));
  };

  return {
    compactor,
    onDragStart: start,
    onDragStop: stop,
    onResizeStart: start,
    onResizeStop: stop,
  };
}

function geometry({ x, y, w, h }: PanelLayout | LayoutItem): PanelLayout {
  return { x, y, w, h };
}
