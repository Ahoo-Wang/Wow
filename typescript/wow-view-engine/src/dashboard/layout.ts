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

/**
 * Where panels may go: the one set of geometry rules the runtime places by
 * and the grid previews by.
 *
 * A panel is placed by hand in three ways — dragged, resized by its corner,
 * or stepped by a keyboard command — and each lands on the same thing, a new
 * `PanelLayout` for one panel. What happens to the panels it now covers is
 * decided here, once: they are pushed down out of its way, the way
 * react-grid-layout's vertical compactor treats the item being moved. They
 * are not pulled back up afterwards, because this grid does not compact — a
 * panel stays where its author put it until a hand moves it or another
 * panel's placement pushes it.
 */

import {
  DASHBOARD_GRID_COLUMNS,
  type DashboardPanel,
  type DashboardViewConfig,
  type PanelLayout,
} from '../model/index.js';
import { isPlainObject } from '../filter/index.js';
import { validateLayout } from './validate.js';

/** One keyboard command: a step of one grid cell, or a size one cell bigger or smaller. */
export type ArrangeStep =
  | 'left'
  | 'right'
  | 'up'
  | 'down'
  | 'wider'
  | 'narrower'
  | 'taller'
  | 'shorter';

/**
 * Whether a layout is one admission accepts: `x` and `y` non-negative
 * integers, `w` and `h` positive ones, and `x + w` inside the grid. The
 * same rule `validateDashboard` reports against, so nothing placed here can
 * come back from it as an error.
 */
export function fitsGrid(
  layout: unknown,
  columns: number = DASHBOARD_GRID_COLUMNS,
): layout is PanelLayout {
  return (
    isPlainObject(layout) &&
    validateLayout(layout as unknown as PanelLayout, [], columns).length === 0
  );
}

/**
 * The layout one step lands on, or `null` when the grid has no room for it.
 *
 * One step is one cell, which is what a drag lands on anyway — the grid
 * snaps to the column and the row. Down and taller have no far edge: a
 * dashboard grows downwards, and admission puts no ceiling on `y` or `h`.
 * The panels the result now covers are not this function's business;
 * `placePanel` moves them.
 */
export function arrangeLayout(
  layout: PanelLayout,
  step: ArrangeStep,
  columns: number = DASHBOARD_GRID_COLUMNS,
): PanelLayout | null {
  const { x, y, w, h } = layout;
  const next = ((): PanelLayout => {
    switch (step) {
      case 'left':
        return { x: x - 1, y, w, h };
      case 'right':
        return { x: x + 1, y, w, h };
      case 'up':
        return { x, y: y - 1, w, h };
      case 'down':
        return { x, y: y + 1, w, h };
      case 'wider':
        return { x, y, w: w + 1, h };
      case 'narrower':
        return { x, y, w: w - 1, h };
      case 'taller':
        return { x, y, w, h: h + 1 };
      case 'shorter':
        return { x, y, w, h: h - 1 };
    }
  })();
  return fitsGrid(next, columns) ? next : null;
}

/** A panel's geometry under its id: what `placePanel` reads and returns. */
export interface PlacedPanel extends PanelLayout {
  id: string;
}

/** Whether two boxes share at least one cell. */
export function overlaps(a: PanelLayout, b: PanelLayout): boolean {
  return (
    a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h
  );
}

/**
 * The panels after one of them is put at `layout`, or `null` when the
 * placement is not one the grid admits or names no panel.
 *
 * The panel placed wins the cells it asked for. A panel it now covers is
 * pushed straight down until it clears it, and a panel that push lands on
 * is pushed in turn, top to bottom — the cascade a vertical compactor runs
 * for the item being dragged. Nothing moves sideways and nothing moves up.
 *
 * Only what this placement displaces moves. Two panels a stored config
 * already had overlapping stay as they were: they are the author's doing,
 * admission lets them stand, and placing some third panel is no reason to
 * rearrange them.
 */
export function placePanel(
  panels: readonly PlacedPanel[],
  id: string,
  layout: PanelLayout,
  columns: number = DASHBOARD_GRID_COLUMNS,
): PlacedPanel[] | null {
  const at = panels.findIndex(panel => panel.id === id);
  if (at < 0 || !fitsGrid(layout, columns)) return null;

  const placed: PlacedPanel = {
    id,
    x: layout.x,
    y: layout.y,
    w: layout.w,
    h: layout.h,
  };
  const out = [...panels];
  out[at] = placed;
  // Everything moved so far; a panel is pushed only by one of these.
  const displaced: PlacedPanel[] = [placed];
  // Top to bottom, then left to right, so a push is always by something
  // already settled above it; the index breaks a tie and keeps it stable.
  const order = panels
    .map((_, index) => index)
    .filter(index => index !== at)
    .sort(
      (a, b) => panels[a].y - panels[b].y || panels[a].x - panels[b].x || a - b,
    );
  for (const index of order) {
    let box = panels[index];
    let hit = displaced.find(other => overlaps(other, box));
    if (!hit) continue;
    while (hit) {
      box = { ...box, y: hit.y + hit.h };
      hit = displaced.find(other => overlaps(other, box));
    }
    out[index] = box;
    displaced.push(box);
  }
  return out;
}

/**
 * A config with one panel placed and the panels it pushed moved with it, or
 * the same config when the placement changes nothing or cannot be made.
 *
 * Read as the untrusted thing a stored config is: an entry that is no panel,
 * or a panel whose layout admission would refuse, takes no part — it is not
 * pushed and pushes nothing — and is handed back untouched.
 */
export function placePanelIn(
  config: DashboardViewConfig,
  id: string,
  layout: PanelLayout,
  columns: number = DASHBOARD_GRID_COLUMNS,
): DashboardViewConfig {
  const panels: readonly unknown[] = Array.isArray(config.panels)
    ? config.panels
    : [];
  const boxes = panels.flatMap((panel): PlacedPanel[] =>
    isPlainObject(panel) &&
    typeof panel.id === 'string' &&
    fitsGrid(panel.layout, columns)
      ? [{ id: panel.id, ...geometry(panel.layout) }]
      : [],
  );
  const placed = placePanel(boxes, id, layout, columns);
  if (!placed) return config;
  const moved = new Map(placed.map(box => [box.id, box]));

  let changed = false;
  const next = panels.map(panel => {
    if (!isPlainObject(panel) || typeof panel.id !== 'string') return panel;
    const box = moved.get(panel.id);
    const current = panel.layout;
    if (!box || !fitsGrid(current, columns) || sameLayout(current, box))
      return panel;
    changed = true;
    return { ...panel, layout: geometry(box) };
  });
  return changed ? { ...config, panels: next as DashboardPanel[] } : config;
}

/** Whether two layouts are the same four numbers. */
export function sameLayout(a: PanelLayout, b: PanelLayout): boolean {
  return a.x === b.x && a.y === b.y && a.w === b.w && a.h === b.h;
}

/** The four numbers alone, whatever else the object carried. */
function geometry({ x, y, w, h }: PanelLayout): PanelLayout {
  return { x, y, w, h };
}
