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
 * A panel moved along the one-column reading of its tab, written back onto
 * the grid (D22 J): `reorderPanel` takes one step, `reorderPanelIn` the steps
 * a drop, an arrow key or the handle's menu asks for.
 *
 * Each step is a placement like any other (`layout.ts`): the candidates are
 * the two panels traded at each other's corner and every place either could
 * be put by hand, each compacted, and the one that reads in the order asked
 * for and disturbs the rest least wins.
 */

import {
  DASHBOARD_GRID_COLUMNS,
  type DashboardViewConfig,
} from '../model/index.js';
import {
  bottomOf,
  compactLayout,
  placePanel,
  readingOrder,
  sameLayout,
  tabBoxes,
  withLayouts,
  type PlacedPanel,
} from './layout.js';

/** One step along the one-column reading: before the panel read before it, or after the one read after it. */
export type OrderStep = 'up' | 'down';

/**
 * The panels after one of them trades places with its neighbour in reading
 * order — the one-column reading's 「上移」／「下移」 written back
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
 * A config with one panel moved to place `to` (from 0) along the reading
 * order of its tab, or the same config when it is already there or `to` is
 * no place on the tab. The one-column reading carries a panel by a handle
 * (D22 J): a drop names any place, an arrow key or the handle's menu one
 * place along or either end. It gets there one place at a time
 * (`reorderPanel`), each step the layout that reads that way and disturbs
 * the rest least, so a move of several places is the steps it passes
 * through — never a jump the grid could not have taken one place at a
 * time. Read as untrusted like `placePanelIn`: an entry that is no panel,
 * or whose layout admission would refuse, takes no part.
 */
export function reorderPanelIn(
  config: DashboardViewConfig,
  id: string,
  to: number,
  columns: number = DASHBOARD_GRID_COLUMNS,
): DashboardViewConfig {
  let boxes = tabBoxes(config, id, columns);
  if (!boxes || !Number.isInteger(to) || to < 0 || to >= boxes.length)
    return config;
  const from = readOf(boxes).indexOf(id);
  if (from < 0 || from === to) return config;
  const step: OrderStep = to < from ? 'up' : 'down';
  for (let at = from; at !== to; at += step === 'up' ? -1 : 1) {
    const placed = reorderPanel(boxes, id, step, columns);
    if (!placed) break;
    boxes = placed;
  }
  return withLayouts(config, boxes);
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
