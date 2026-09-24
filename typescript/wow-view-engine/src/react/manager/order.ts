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
 * Where a row goes when it moves: pure arithmetic over orders of ids, with the
 * audience groups the sidebar renders taken into account.
 *
 * A move reasons over ids alone — the order a queued move computed has nothing
 * but ids — so the audience of each row arrives as a lookup rather than being
 * carried along with the summaries.
 */

import type { ViewAudience } from '../../model/index.js';

/**
 * The order a queued move computed, while the list it was computed from is
 * still a reload behind it.
 *
 * `base` is the full rendered order it started from: once the list is no
 * longer that, the reload has landed and the rendered order is the truth
 * again. The two orders after the move are both kept, because they answer
 * different questions — `order` is what was submitted, and `visible` is the
 * list the next move reads its group and its indexes out of. Which inputs the
 * move was raised under is the caller's to tag on; this module only does the
 * arithmetic.
 */
export interface OptimisticOrder {
  /** The full rendered order, unfiltered, before this move. */
  base: readonly string[];
  /** The full order this move submitted. */
  order: readonly string[];
  /** The narrowed order as this move left it. */
  visible: readonly string[];
}

/**
 * The two orders one move produces; see {@link OptimisticOrder}. Both are
 * fresh arrays the caller owns — one of them is what it submits.
 */
export interface MovedOrder {
  order: string[];
  visible: string[];
}

export function sameOrder(
  one: readonly string[],
  other: readonly string[],
): boolean {
  return one.length === other.length && one.every((id, at) => id === other[at]);
}

/**
 * The places one audience occupies in an order of ids.
 *
 * Audience is why a move is not arithmetic on one flat list. Both lists draw
 * personal views above shared ones whatever order is stored, so the row above
 * a shared view on screen may be a personal one, and carrying a row across
 * that line would store a new order, spend a revision and move nothing
 * anybody can see. The drag is held inside its group by the library; this is
 * the same boundary written in indexes, for the arithmetic and for the arrow
 * keys on the handle.
 *
 * It walks an order of ids rather than the summaries themselves, because the
 * order a queued move computed is the one the next move has to reason over,
 * while a row's audience is the same wherever that order puts it.
 */
function placesOf(
  order: readonly string[],
  audiences: ReadonlyMap<string, ViewAudience>,
  audience: ViewAudience,
): number[] {
  const places: number[] = [];
  for (let at = 0; at < order.length; at += 1)
    if (audiences.get(order[at]) === audience) places.push(at);
  return places;
}

/**
 * Where a row sits inside its own audience group, counted over the rows the
 * user can see, or -1 when the list does not hold it.
 *
 * This is the index a move is expressed in, because it is the list a user is
 * looking at: one group of rows under one heading, and a drop that lands on
 * another row of it. A position in the flat rendered order would mean
 * something different on either side of the boundary.
 */
export function groupIndexOf(
  visible: readonly string[],
  audiences: ReadonlyMap<string, ViewAudience>,
  id: string,
): number {
  const audience = audiences.get(id);
  if (audience === undefined) return -1;
  return placesOf(visible, audiences, audience).indexOf(visible.indexOf(id));
}

/**
 * What one move submits and what it leaves on screen, or null when there is
 * nothing to move: a row the list no longer holds, a row of no known
 * audience, or one already sitting where it is being sent.
 *
 * `index` is counted inside the row's own audience group, among the rows the
 * user can see, and is clamped to that group — being carried past the last
 * row of a group lands on the last row of it rather than in the group below.
 *
 * The move itself is made in the *full* order. Submitting the visible one
 * would store a list with every other kind's id missing, and the store keeps
 * one order for the whole definition: a record workbench reordering its own
 * views would silently drop the analyses. The places the group holds in that
 * order are rewritten with the group as this move leaves it, so every id that
 * took no part — another kind's, the other audience's — stays exactly where
 * it was. A row the submitted order does not hold cannot be placed in it, and
 * storing an order without it would spend a revision to lose it.
 */
export function planMoveTo(
  visible: readonly string[],
  full: readonly string[],
  audiences: ReadonlyMap<string, ViewAudience>,
  id: string,
  index: number,
): MovedOrder | null {
  const audience = audiences.get(id);
  if (audience === undefined) return null;
  const places = placesOf(visible, audiences, audience);
  const group = places.map(place => visible[place]);
  const from = group.indexOf(id);
  if (from < 0) return null;
  const to = Math.min(Math.max(index, 0), group.length - 1);
  if (to === from) return null;
  const moved = [...group];
  moved.splice(from, 1);
  moved.splice(to, 0, id);

  // Sorted, so the group is written back into the places it already holds:
  // the ids that took no part keep the positions they had, and the ones that
  // moved trade places only with each other.
  const held = group.map(member => full.indexOf(member));
  if (held.includes(-1)) return null;
  held.sort((one, other) => one - other);
  const order = [...full];
  held.forEach((place, at) => {
    order[place] = moved[at];
  });
  const shown = [...visible];
  places.forEach((place, at) => {
    shown[place] = moved[at];
  });
  return { order, visible: shown };
}
