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
 * `PanelLayout` for one panel. What happens to the others is decided here,
 * once, and it is what a Metabase board does (D22, batch-A walk): the panel
 * placed takes the cells it asked for, whatever it covers makes way, and
 * then every panel of its tab floats up as far as it can, so a move leaves
 * no hole behind — the vertical compaction react-grid-layout would run,
 * written here so the keyboard and the pointer land on one answer.
 *
 * Only a hand compacts. A stored board is drawn as it was saved, holes and
 * all, and opening it never moves a panel; the first placement tidies the
 * tab it happens on.
 */

import {
  DASHBOARD_GRID_COLUMNS,
  type DashboardPanel,
  type DashboardViewConfig,
  type PanelLayout,
} from '../model/index.js';
import { isPlainObject } from '../filter/index.js';
import { panelsOf, panelTab } from './panels.js';
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

/** The first row below every panel: where a board ends. */
export function bottomOf(panels: readonly PanelLayout[]): number {
  return panels.reduce(
    (bottom, panel) => Math.max(bottom, panel.y + panel.h),
    0,
  );
}

/**
 * The panels floated up: each, in reading order, rises as far as it can
 * without touching one already settled, and one that overlaps a settled
 * panel (a stored config may hold two on one cell) drops below it instead.
 * No panel passes another on the way up, so the order a reader sees down a
 * column is kept; only the holes go.
 */
export function compactLayout(panels: readonly PlacedPanel[]): PlacedPanel[] {
  return compactAround(panels, [], -1);
}

/**
 * The panels after one of them is put at `layout`, or `null` when the
 * placement is not one the grid admits or names no panel.
 *
 * The panel placed is held at the cells it asked for while the others are
 * compacted around it, in their reading order: one it covers drops below it,
 * one it has moved out from under rises into the room it left. Then it is
 * let go, and the whole tab is compacted — the panel placed rises too, until
 * something is above it. So a panel dropped past the one under it trades
 * places with it, and one dropped into empty space below the rest comes up
 * to rest under them.
 */
export function placePanel(
  panels: readonly PlacedPanel[],
  id: string,
  layout: PanelLayout,
  columns: number = DASHBOARD_GRID_COLUMNS,
): PlacedPanel[] | null {
  const at = panels.findIndex(panel => panel.id === id);
  if (at < 0 || !fitsGrid(layout, columns)) return null;
  const held: PlacedPanel = { id, ...geometry(layout) };
  const others = [...panels];
  others[at] = held;
  return compactLayout(compactAround(others, [held], at, held));
}

/**
 * Where one keyboard command puts the panel, as the layout to hand `place`,
 * or `null` when it would change nothing the reader could see.
 *
 * Sideways and in size, one command is one cell, as a drag snaps to one.
 * Up and down are what a compacted board makes of them: a panel stepped one
 * row down into the one under it floats straight back, so "down" is the
 * smallest drop that lands it somewhere else — past the panel below — and
 * the bottom panel of a column has nowhere to go down. Up is the same the
 * other way. A step off the grid is `null` as well, so a menu disables it
 * rather than offering a command that does nothing.
 */
export function arrangePanel(
  panels: readonly PlacedPanel[],
  id: string,
  step: ArrangeStep,
  columns: number = DASHBOARD_GRID_COLUMNS,
): PanelLayout | null {
  const panel = panels.find(entry => entry.id === id);
  // Where it rests now: the baseline a command has to differ from.
  const resting = panel && placePanel(panels, id, panel, columns);
  if (!panel || !resting) return null;
  const reach = step === 'up' || step === 'down' ? bottomOf(panels) + 1 : 1;
  for (let distance = 1; distance <= reach; distance += 1) {
    const target = stepped(panel, step, distance);
    const placed = placePanel(panels, id, target, columns);
    if (!placed) return null;
    if (!samePlacement(placed, resting)) return target;
  }
  return null;
}

/**
 * The first place a new panel of this size can go: free, at rest (the
 * compaction a later placement runs would not lift it), and at or below
 * `fromRow` — the first row the reader has on screen, so what they add
 * lands where they are looking (D22 A). Rows top to bottom, each left to
 * right. When nothing down to the board's end will take it, it goes under
 * everything and rises to rest there.
 */
export function freeSpot(
  panels: readonly PanelLayout[],
  size: { w: number; h: number },
  columns: number = DASHBOARD_GRID_COLUMNS,
  fromRow = 0,
): PanelLayout {
  const w = Math.min(Math.max(1, size.w), columns);
  const h = Math.max(1, size.h);
  const boxes = panels.map(panel => ({ id: '', ...geometry(panel) }));
  const bottom = bottomOf(panels);
  for (let y = Math.max(0, fromRow); y < bottom; y += 1)
    for (let x = 0; x + w <= columns; x += 1) {
      const spot = { x, y, w, h };
      if (boxes.some(box => overlaps(box, spot))) continue;
      if (settle({ id: '', ...spot }, boxes).y === y) return spot;
    }
  return geometry(settle({ id: '', x: 0, y: bottom, w, h }, boxes));
}

/**
 * A config with one panel placed and the rest of its tab compacted around
 * it, or the same config when the placement changes nothing or cannot be
 * made.
 *
 * Only the panel's own tab moves: another tab is another grid, whose
 * panels share no cell with these whatever their numbers say. Read as the
 * untrusted thing a stored config is: an entry that is no panel, or a panel
 * whose layout admission would refuse, takes no part — it is not moved and
 * moves nothing — and is handed back untouched.
 */
export function placePanelIn(
  config: DashboardViewConfig,
  id: string,
  layout: PanelLayout,
  columns: number = DASHBOARD_GRID_COLUMNS,
): DashboardViewConfig {
  const boxes = tabBoxes(config, id, columns);
  const placed = boxes && placePanel(boxes, id, layout, columns);
  return placed ? withLayouts(config, placed) : config;
}

/**
 * The boxes of the panels on the tab of the panel `id`, or `null` when the
 * config holds no such panel: only its own tab moves with it, since another
 * tab is another grid.
 */
function tabBoxes(
  config: DashboardViewConfig,
  id: string,
  columns: number,
): PlacedPanel[] | null {
  const panels: readonly unknown[] = panelsOf(config);
  const moving = panels.find(
    (panel): panel is DashboardPanel => isPlainObject(panel) && panel.id === id,
  );
  if (!moving) return null;
  const tab = panelTab(config, moving);
  return panels.flatMap((panel): PlacedPanel[] =>
    isPlainObject(panel) &&
    typeof panel.id === 'string' &&
    fitsGrid(panel.layout, columns) &&
    panelTab(config, panel) === tab
      ? [{ id: panel.id, ...geometry(panel.layout) }]
      : [],
  );
}

/**
 * A config with these boxes written into the panels they name, or the same
 * config when none of them moves. A panel no box names, or whose stored
 * layout is not one the grid admits, is left as it is.
 */
export function withLayouts(
  config: DashboardViewConfig,
  boxes: readonly PlacedPanel[],
  columns: number = DASHBOARD_GRID_COLUMNS,
): DashboardViewConfig {
  const panels: readonly unknown[] = panelsOf(config);
  const moved = new Map(boxes.map(box => [box.id, box]));
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

/**
 * The panels in reading order: rows top to bottom, each left to right — the
 * order a reader counts them in and the grid draws them in, whatever order
 * the config lists them. Stable, so two panels an author stacked on the same
 * cell keep the order they were saved in.
 */
export function readingOrder<T extends { layout: PanelLayout }>(
  panels: readonly T[],
): T[] {
  return [...panels].sort(
    (a, b) => a.layout.y - b.layout.y || a.layout.x - b.layout.x,
  );
}

/**
 * The layout read as one column, for a screen too narrow for the grid: each
 * panel the full width of a one-column grid and as tall as it was saved,
 * stacked in reading order. A reading of the layout, not a placement — it is
 * never handed to `place`, because nothing in it maps back to the wide
 * layout the config holds.
 */
export function stackedLayout(panels: readonly PlacedPanel[]): PlacedPanel[] {
  let y = 0;
  return readingOrder(panels.map(panel => ({ panel, layout: panel }))).map(
    ({ panel }) => {
      const stacked = { id: panel.id, x: 0, y, w: 1, h: panel.h };
      y += panel.h;
      return stacked;
    },
  );
}

/** One step along the one-column reading: before the panel read before it, or after the one read after it. */
export type OrderStep = 'up' | 'down';

/**
 * The panels after one of them trades places with its neighbour in reading
 * order — the one-column reading's 「上移」／「下移」 (D22 J) written back
 * onto the grid — or `null` when there is no neighbour that way, or no such
 * panel.
 *
 * The column is a reading of the layout, so a move in it has to come out as
 * a layout that reads that way: the panel one place further along, and as
 * near as can be every other panel where it was. The candidates are the two
 * at each other's corner (two panels of one size side by side swap and
 * nothing else moves), then every place either could be put by hand
 * (`placePanel`) — moving one up past the other is moving the other down
 * past it. Each is compacted, as every placement by hand is, so what comes
 * back is at rest; and compacting can carry a third panel into the room one
 * of the two left, which on a board whose columns do not line up puts it on
 * the other side of them. So among the candidates that land the panel where
 * it was asked to go, the one wins that reads least out of the order asked
 * for (pairs of panels the other way round), then moves fewest other panels,
 * then changes fewest rows, then moves them least far — on a board of plain
 * rows that is the order asked for exactly. Where no candidate lands it
 * there at all (a board whose rows interlock), the panels are stacked down
 * the first column in the order asked for: the one layout that always reads
 * right, at the cost of the board's columns.
 */
export function reorderPanel(
  panels: readonly PlacedPanel[],
  id: string,
  step: OrderStep,
  columns: number = DASHBOARD_GRID_COLUMNS,
): PlacedPanel[] | null {
  const order = readOf(panels);
  const at = order.indexOf(id);
  const to = step === 'up' ? at - 1 : at + 1;
  if (at < 0 || to < 0 || to >= order.length) return null;
  const other = order[to];
  const wanted = [...order];
  wanted[at] = other;
  wanted[to] = id;
  let best: { placed: PlacedPanel[]; cost: number[] } | null = null;
  const consider = (placed: PlacedPanel[] | null) => {
    if (!placed) return;
    const read = readOf(placed);
    if (read[to] !== id) return;
    const cost = [
      shuffled(wanted, read),
      ...disturbance(panels, placed, [id, other]),
    ];
    if (!best || before(cost, best.cost)) best = { placed, cost };
  };
  consider(traded(panels, id, other, columns));
  const bottom = bottomOf(panels);
  for (const mover of [id, other]) {
    const box = panels.find(entry => entry.id === mover)!;
    for (let y = 0; y <= bottom; y += 1)
      for (let x = 0; x + box.w <= columns; x += 1)
        consider(placePanel(panels, mover, { x, y, w: box.w, h: box.h }));
  }
  if (best) return (best as { placed: PlacedPanel[] }).placed;
  // Down the first column, one under another: nothing passes anything.
  let y = 0;
  const stacked = new Map(
    wanted.map(entry => {
      const box = panels.find(panel => panel.id === entry)!;
      const placed = { ...box, x: 0, y };
      y += box.h;
      return [entry, placed];
    }),
  );
  return panels.map(panel => stacked.get(panel.id)!);
}

/**
 * A config with one panel moved one place along the reading order of its
 * tab (`reorderPanel`), or the same config when it has nowhere to go that
 * way. Read as untrusted like `placePanelIn`: an entry that is no panel, or
 * whose layout admission would refuse, takes no part.
 */
export function reorderPanelIn(
  config: DashboardViewConfig,
  id: string,
  step: OrderStep,
  columns: number = DASHBOARD_GRID_COLUMNS,
): DashboardViewConfig {
  const boxes = tabBoxes(config, id, columns);
  const placed = boxes && reorderPanel(boxes, id, step, columns);
  return placed ? withLayouts(config, placed) : config;
}

/** Whether two layouts are the same four numbers. */
export function sameLayout(a: PanelLayout, b: PanelLayout): boolean {
  return a.x === b.x && a.y === b.y && a.w === b.w && a.h === b.h;
}

/**
 * `panels` with every box but `skip` settled in reading order against
 * `settled` and each other; `skip` keeps its place (`-1` skips nothing).
 * `held` is the panel a hand is placing, if any (see `settle`).
 */
function compactAround(
  panels: readonly PlacedPanel[],
  settled: PlacedPanel[],
  skip: number,
  held?: PlacedPanel,
): PlacedPanel[] {
  const out = [...panels];
  const order = panels
    .map((_, index) => index)
    .filter(index => index !== skip)
    .sort(
      (a, b) => panels[a].y - panels[b].y || panels[a].x - panels[b].x || a - b,
    );
  for (const index of order) {
    const box = settle(panels[index], settled, held);
    out[index] = box;
    settled.push(box);
  }
  return out;
}

/**
 * One box floated up until the row above it is taken, then dropped below
 * whatever it still overlaps.
 *
 * Except that a box the panel in hand has landed on first tries the room
 * just above that panel, as react-grid-layout's vertical compactor does for
 * the item being dragged: so a panel put down squarely on the one below it
 * trades places with it, rather than both going back where they were.
 */
function settle(
  box: PlacedPanel,
  settled: readonly PlacedPanel[],
  held?: PlacedPanel,
): PlacedPanel {
  let y = box.y;
  while (y > 0 && !settled.some(other => overlaps(other, { ...box, y: y - 1 })))
    y -= 1;
  let placed = y === box.y ? box : { ...box, y };
  if (held && overlaps(held, placed)) {
    const above = { ...placed, y: held.y - placed.h };
    if (above.y >= 0 && !settled.some(other => overlaps(other, above)))
      return settle(above, settled);
  }
  let hit = settled.find(other => overlaps(other, placed));
  while (hit) {
    placed = { ...placed, y: hit.y + hit.h };
    hit = settled.find(other => overlaps(other, placed));
  }
  return placed;
}

/** The layout `distance` cells away in the direction of `step`. */
function stepped(
  { x, y, w, h }: PanelLayout,
  step: ArrangeStep,
  distance: number,
): PanelLayout {
  switch (step) {
    case 'left':
      return { x: x - distance, y, w, h };
    case 'right':
      return { x: x + distance, y, w, h };
    case 'up':
      return { x, y: y - distance, w, h };
    case 'down':
      return { x, y: y + distance, w, h };
    case 'wider':
      return { x, y, w: w + distance, h };
    case 'narrower':
      return { x, y, w: w - distance, h };
    case 'taller':
      return { x, y, w, h: h + distance };
    case 'shorter':
      return { x, y, w, h: h - distance };
  }
}

/** The ids of these boxes in reading order. */
function readOf(panels: readonly PlacedPanel[]): string[] {
  return readingOrder(panels.map(box => ({ id: box.id, layout: box }))).map(
    entry => entry.id,
  );
}

/**
 * Two panels each put at the other's corner — pulled left where it would
 * run off the grid — and the tab compacted around them.
 */
function traded(
  panels: readonly PlacedPanel[],
  a: string,
  b: string,
  columns: number,
): PlacedPanel[] {
  const one = panels.find(box => box.id === a)!;
  const two = panels.find(box => box.id === b)!;
  const at = (box: PlacedPanel, corner: PlacedPanel): PlacedPanel => ({
    ...box,
    x: Math.min(corner.x, columns - box.w),
    y: corner.y,
  });
  return compactLayout(
    panels.map(box =>
      box.id === a ? at(one, two) : box.id === b ? at(two, one) : box,
    ),
  );
}

/**
 * How far a reading order is from the one asked for: the pairs of panels
 * it reads the other way round.
 */
function shuffled(wanted: readonly string[], read: readonly string[]): number {
  const place = new Map(read.map((entry, index) => [entry, index]));
  let pairs = 0;
  wanted.forEach((a, i) => {
    for (const b of wanted.slice(i + 1))
      if (place.get(a)! > place.get(b)!) pairs += 1;
  });
  return pairs;
}

/**
 * How much a placement disturbs the board, most telling first: the panels
 * other than the two trading places that moved at all, the panels that
 * changed row, and how far everything went.
 */
function disturbance(
  from: readonly PlacedPanel[],
  to: readonly PlacedPanel[],
  pair: readonly string[],
): number[] {
  let others = 0;
  let rows = 0;
  let distance = 0;
  from.forEach((box, index) => {
    const moved = to[index];
    if (!pair.includes(box.id) && !sameLayout(box, moved)) others += 1;
    if (box.y !== moved.y) rows += 1;
    distance += Math.abs(box.x - moved.x) + Math.abs(box.y - moved.y);
  });
  return [others, rows, distance];
}

/** Whether one cost is lower than another, compared member by member. */
function before(a: readonly number[], b: readonly number[]): boolean {
  const differs = a.findIndex((value, index) => value !== b[index]);
  return differs >= 0 && a[differs] < b[differs];
}

function samePlacement(
  a: readonly PlacedPanel[],
  b: readonly PlacedPanel[],
): boolean {
  return a.every((box, index) => sameLayout(box, b[index]));
}

function geometry({ x, y, w, h }: PanelLayout): PanelLayout {
  return { x, y, w, h };
}
